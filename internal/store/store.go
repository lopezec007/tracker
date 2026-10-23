// Package store is the SQLite persistence layer for the tracker.
//
// All timestamps are RFC 3339 UTC. They are stored with a fixed nine
// fractional digits so that they sort correctly as plain strings (received_at
// is ordered and range-filtered in SQL) and are emitted in time.RFC3339Nano
// form, which trims trailing zeros.
//
// The store is the single write path for every position source. The HTTP API
// calls UpsertEntity and InsertFixes on behalf of phones; a future APRS or
// ADS-B ingester running inside the same binary would call the same two
// methods with its own source and metadata. Nothing here is phone-specific.
package store

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	_ "modernc.org/sqlite"
)

// SourcePhone is the source of entities created by the Android app, and the
// default when a client does not say.
const SourcePhone = "phone"

// Limits on batches and history queries.
const (
	MaxBatch     = 500   // fixes per InsertFixes call
	DefaultLimit = 1000  // fixes returned by ListFixes when no limit is given
	MaxLimit     = 10000 // largest limit ListFixes accepts
	MaxName      = 64    // characters in an entity name
)

var (
	validID     = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)
	validSource = regexp.MustCompile(`^[a-z0-9_-]{1,32}$`)
)

// ValidationError is returned for client-caused failures (HTTP 400).
type ValidationError struct{ Msg string }

func (e *ValidationError) Error() string { return e.Msg }

func verr(format string, a ...any) error { return &ValidationError{Msg: fmt.Sprintf(format, a...)} }

// ErrNotFound is returned when a row does not exist.
var ErrNotFound = errors.New("not found")

// Entity is anything with a position: a phone, or later an APRS station or
// an aircraft. Latest is its most recent fix by received_at (then id), or nil
// if it has none.
type Entity struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Source    string `json:"source"`
	CreatedAt string `json:"created_at"`
	UpdatedAt string `json:"updated_at"`
	Latest    *Fix   `json:"latest"`
}

// Fix is one position report as exposed by the API. Nullable fields are
// pointers and are emitted as explicit null.
type Fix struct {
	ID         int64           `json:"id"`
	EntityID   string          `json:"entity_id"`
	ReceivedAt string          `json:"received_at"`
	DeviceTime *string         `json:"device_time"`
	Lat        float64         `json:"lat"`
	Lon        float64         `json:"lon"`
	AccuracyM  *float64        `json:"accuracy_m"`
	AltitudeM  *float64        `json:"altitude_m"`
	SpeedMps   *float64        `json:"speed_mps"`
	HeadingDeg *float64        `json:"heading_deg"`
	BatteryPct *int            `json:"battery_pct"`
	Metadata   json.RawMessage `json:"metadata"`
}

// NewFix is the input for InsertFixes. DeviceTime is an RFC 3339 string (any
// offset; it is stored in UTC) and Metadata a JSON object; nil (or the JSON
// null) means not provided.
type NewFix struct {
	DeviceTime *string
	Lat        float64
	Lon        float64
	AccuracyM  *float64
	AltitudeM  *float64
	SpeedMps   *float64
	HeadingDeg *float64
	BatteryPct *int
	Metadata   json.RawMessage
}

type Store struct {
	db *sql.DB
	// LastBackup is the path of the copy taken before migrating an existing
	// database on Open, or "" if none was needed.
	LastBackup string
}

// schemaVersion is stored in PRAGMA user_version. Bump it whenever migrate()
// gains a step; an existing database below it is backed up before migrating.
const schemaVersion = 1

// Open opens (creating if necessary) the SQLite database at path and applies
// the schema. An existing database whose schema is behind is first copied to
// "<path>.bak-<timestamp>-v<old version>" with SQLite's own backup (VACUUM
// INTO), so a migration can always be rolled back.
func Open(path string) (*Store, error) {
	dsn := fmt.Sprintf("file:%s?_pragma=foreign_keys(1)&_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)", path)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	// SQLite handles one writer at a time; serialise through a single conn to
	// avoid SQLITE_BUSY under concurrent requests.
	db.SetMaxOpenConns(1)
	s := &Store{db: db}

	var version, tables int
	if err := db.QueryRow(`PRAGMA user_version`).Scan(&version); err != nil {
		db.Close()
		return nil, err
	}
	if err := db.QueryRow(`SELECT COUNT(*) FROM sqlite_master WHERE type = 'table'`).Scan(&tables); err != nil {
		db.Close()
		return nil, err
	}
	if version < schemaVersion && tables > 0 && path != ":memory:" {
		backup := fmt.Sprintf("%s.bak-%s-v%d", path, time.Now().Format("20060102-150405"), version)
		if _, err := db.Exec(`VACUUM INTO ?`, backup); err != nil {
			db.Close()
			return nil, fmt.Errorf("backup before migration to %s: %w", backup, err)
		}
		s.LastBackup = backup
	}
	if err := s.migrate(); err != nil {
		db.Close()
		return nil, err
	}
	if version < schemaVersion {
		if _, err := db.Exec(fmt.Sprintf(`PRAGMA user_version = %d`, schemaVersion)); err != nil {
			db.Close()
			return nil, err
		}
	}
	return s, nil
}

func (s *Store) Close() error { return s.db.Close() }

const schema = `
CREATE TABLE IF NOT EXISTS entities (
	id         TEXT PRIMARY KEY,
	name       TEXT NOT NULL,
	source     TEXT NOT NULL,
	created_at TEXT NOT NULL,
	updated_at TEXT NOT NULL
);

-- The fix schema is shared by every source; what a source cannot provide is
-- NULL and its extras go in metadata (a JSON object). Nothing is ever pruned.
CREATE TABLE IF NOT EXISTS fixes (
	id          INTEGER PRIMARY KEY AUTOINCREMENT,
	entity_id   TEXT NOT NULL REFERENCES entities(id),
	received_at TEXT NOT NULL,
	device_time TEXT,
	lat         REAL NOT NULL,
	lon         REAL NOT NULL,
	accuracy_m  REAL,
	altitude_m  REAL,
	speed_mps   REAL,
	heading_deg REAL,
	battery_pct INTEGER,
	metadata    TEXT
);
CREATE INDEX IF NOT EXISTS fixes_entity_time ON fixes(entity_id, received_at, id);
`

func (s *Store) migrate() error {
	_, err := s.db.Exec(schema)
	return err
}

// ---------------------------------------------------------------- timestamps

// tsLayout is the stored form: RFC 3339 UTC with nine fractional digits, so
// that string order is time order.
const tsLayout = "2006-01-02T15:04:05.000000000Z"

func now() string { return time.Now().UTC().Format(tsLayout) }

// ParseTime parses an RFC 3339 timestamp from a client (any offset, optional
// fraction) into UTC.
func ParseTime(s string) (time.Time, error) {
	t, err := time.Parse(time.RFC3339Nano, s)
	if err != nil {
		return time.Time{}, err
	}
	return t.UTC(), nil
}

// outTS converts a stored timestamp to its wire form (time.RFC3339Nano).
func outTS(s string) string {
	t, err := time.Parse(time.RFC3339Nano, s)
	if err != nil {
		return s
	}
	return t.UTC().Format(time.RFC3339Nano)
}

// ---------------------------------------------------------------- entities

// UpsertEntity creates the entity, or renames it if it exists. An empty
// source means "phone" on creation and "leave as is" on an existing entity;
// a source that differs from an existing entity's is rejected. updated_at
// changes only when the name actually changes.
func (s *Store) UpsertEntity(id, name, source string) (*Entity, error) {
	if !validID.MatchString(id) {
		return nil, verr("id must be 1 to 64 characters of A-Z, a-z, 0-9, '.', '_' or '-'")
	}
	name = strings.TrimSpace(name)
	if n := utf8.RuneCountInString(name); n == 0 || n > MaxName {
		return nil, verr("name is required and must be at most %d characters", MaxName)
	}
	if source != "" && !validSource.MatchString(source) {
		return nil, verr("source must be 1 to 32 characters of a-z, 0-9, '_' or '-'")
	}
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var curName, curSource string
	err = tx.QueryRow(`SELECT name, source FROM entities WHERE id = ?`, id).Scan(&curName, &curSource)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		if source == "" {
			source = SourcePhone
		}
		ts := now()
		if _, err := tx.Exec(`INSERT INTO entities (id, name, source, created_at, updated_at) VALUES (?,?,?,?,?)`,
			id, name, source, ts, ts); err != nil {
			return nil, err
		}
	case err != nil:
		return nil, err
	default:
		if source != "" && source != curSource {
			return nil, verr("source cannot be changed (entity %s is %q)", id, curSource)
		}
		if name != curName {
			if _, err := tx.Exec(`UPDATE entities SET name = ?, updated_at = ? WHERE id = ?`, name, now(), id); err != nil {
				return nil, err
			}
		}
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return s.GetEntity(id)
}

const entityCols = `id, name, source, created_at, updated_at`

type scanner interface{ Scan(dest ...any) error }

func scanEntity(r scanner) (*Entity, error) {
	var e Entity
	if err := r.Scan(&e.ID, &e.Name, &e.Source, &e.CreatedAt, &e.UpdatedAt); err != nil {
		return nil, err
	}
	e.CreatedAt, e.UpdatedAt = outTS(e.CreatedAt), outTS(e.UpdatedAt)
	return &e, nil
}

// GetEntity returns the entity with its latest fix.
func (s *Store) GetEntity(id string) (*Entity, error) {
	e, err := scanEntity(s.db.QueryRow(`SELECT `+entityCols+` FROM entities WHERE id = ?`, id))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	e.Latest, err = latestFix(s.db, id)
	return e, err
}

// ListEntities returns every entity, ordered by name then id, each with its
// latest fix.
func (s *Store) ListEntities() ([]Entity, error) {
	rows, err := s.db.Query(`SELECT ` + entityCols + ` FROM entities ORDER BY name COLLATE NOCASE, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Entity{}
	for rows.Next() {
		e, err := scanEntity(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, *e)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	// One query for every entity's latest fix, joined in Go.
	latest, err := s.db.Query(`SELECT ` + fixCols + ` FROM fixes f WHERE f.id =
		(SELECT id FROM fixes WHERE entity_id = f.entity_id ORDER BY received_at DESC, id DESC LIMIT 1)`)
	if err != nil {
		return nil, err
	}
	defer latest.Close()
	byEntity := map[string]*Fix{}
	for latest.Next() {
		f, err := scanFix(latest)
		if err != nil {
			return nil, err
		}
		byEntity[f.EntityID] = f
	}
	if err := latest.Err(); err != nil {
		return nil, err
	}
	for i := range out {
		out[i].Latest = byEntity[out[i].ID]
	}
	return out, nil
}

// ---------------------------------------------------------------- fixes

const fixCols = `f.id, f.entity_id, f.received_at, f.device_time, f.lat, f.lon,
	f.accuracy_m, f.altitude_m, f.speed_mps, f.heading_deg, f.battery_pct, f.metadata`

func scanFix(r scanner) (*Fix, error) {
	var f Fix
	var meta *string
	if err := r.Scan(&f.ID, &f.EntityID, &f.ReceivedAt, &f.DeviceTime, &f.Lat, &f.Lon,
		&f.AccuracyM, &f.AltitudeM, &f.SpeedMps, &f.HeadingDeg, &f.BatteryPct, &meta); err != nil {
		return nil, err
	}
	f.ReceivedAt = outTS(f.ReceivedAt)
	if f.DeviceTime != nil {
		t := outTS(*f.DeviceTime)
		f.DeviceTime = &t
	}
	if meta != nil {
		f.Metadata = json.RawMessage(*meta)
	}
	return &f, nil
}

type querier interface {
	QueryRow(query string, args ...any) *sql.Row
}

// latestFix returns the entity's most recent fix: the greatest received_at,
// and among equals (a batch shares one receive time) the greatest id.
func latestFix(q querier, entityID string) (*Fix, error) {
	f, err := scanFix(q.QueryRow(`SELECT `+fixCols+` FROM fixes f WHERE f.entity_id = ?
		ORDER BY f.received_at DESC, f.id DESC LIMIT 1`, entityID))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return f, err
}

// fixRow is a validated NewFix in its stored form.
type fixRow struct {
	NewFix
	deviceTime *string
	metadata   *string
}

func (in *NewFix) validate() (fixRow, error) {
	r := fixRow{NewFix: *in}
	if in.DeviceTime != nil {
		t, err := ParseTime(*in.DeviceTime)
		if err != nil {
			return r, verr("device_time must be an RFC 3339 timestamp")
		}
		s := t.Format(tsLayout)
		r.deviceTime = &s
	}
	if in.Lat < -90 || in.Lat > 90 {
		return r, verr("lat must be between -90 and 90")
	}
	if in.Lon < -180 || in.Lon > 180 {
		return r, verr("lon must be between -180 and 180")
	}
	if in.AccuracyM != nil && *in.AccuracyM < 0 {
		return r, verr("accuracy_m must not be negative")
	}
	if in.SpeedMps != nil && *in.SpeedMps < 0 {
		return r, verr("speed_mps must not be negative")
	}
	if in.HeadingDeg != nil && (*in.HeadingDeg < 0 || *in.HeadingDeg > 360) {
		return r, verr("heading_deg must be between 0 and 360")
	}
	if in.BatteryPct != nil && (*in.BatteryPct < 0 || *in.BatteryPct > 100) {
		return r, verr("battery_pct must be between 0 and 100")
	}
	if m := bytes.TrimSpace(in.Metadata); len(m) > 0 && !bytes.Equal(m, []byte("null")) {
		var buf bytes.Buffer
		if m[0] != '{' || json.Compact(&buf, m) != nil {
			return r, verr("metadata must be a JSON object or null")
		}
		s := buf.String()
		r.metadata = &s
	}
	return r, nil
}

// InsertFixes records fixes for an entity in one transaction, each stamped
// with the server's receive time, and returns the number accepted and the
// entity's latest fix afterwards. Every fix is validated first; if any is
// invalid nothing is inserted. The batch is limited to MaxBatch.
func (s *Store) InsertFixes(entityID string, fixes []NewFix) (int, *Fix, error) {
	if len(fixes) == 0 {
		return 0, nil, verr("at least one fix is required")
	}
	if len(fixes) > MaxBatch {
		return 0, nil, verr("at most %d fixes per batch", MaxBatch)
	}
	rows := make([]fixRow, len(fixes))
	for i := range fixes {
		r, err := fixes[i].validate()
		if err != nil {
			return 0, nil, verr("fix %d: %v", i, err)
		}
		rows[i] = r
	}
	tx, err := s.db.Begin()
	if err != nil {
		return 0, nil, err
	}
	defer tx.Rollback()
	var n int
	if err := tx.QueryRow(`SELECT COUNT(*) FROM entities WHERE id = ?`, entityID).Scan(&n); err != nil {
		return 0, nil, err
	}
	if n == 0 {
		return 0, nil, ErrNotFound
	}
	ts := now()
	for _, r := range rows {
		if _, err := tx.Exec(`INSERT INTO fixes (entity_id, received_at, device_time, lat, lon,
			accuracy_m, altitude_m, speed_mps, heading_deg, battery_pct, metadata)
			VALUES (?,?,?,?,?,?,?,?,?,?,?)`,
			entityID, ts, r.deviceTime, r.Lat, r.Lon,
			r.AccuracyM, r.AltitudeM, r.SpeedMps, r.HeadingDeg, r.BatteryPct, r.metadata); err != nil {
			return 0, nil, err
		}
	}
	latest, err := latestFix(tx, entityID)
	if err != nil {
		return 0, nil, err
	}
	return len(rows), latest, tx.Commit()
}

// FixQuery selects a slice of an entity's history. From is inclusive and To
// exclusive on received_at; the zero time means unbounded. Limit 0 means
// DefaultLimit.
type FixQuery struct {
	From  time.Time
	To    time.Time
	Limit int
}

// ListFixes returns the entity's fixes matching q, oldest first (received_at
// then id).
func (s *Store) ListFixes(entityID string, q FixQuery) ([]Fix, error) {
	if q.Limit == 0 {
		q.Limit = DefaultLimit
	}
	if q.Limit < 1 || q.Limit > MaxLimit {
		return nil, verr("limit must be between 1 and %d", MaxLimit)
	}
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM entities WHERE id = ?`, entityID).Scan(&n); err != nil {
		return nil, err
	}
	if n == 0 {
		return nil, ErrNotFound
	}
	query := `SELECT ` + fixCols + ` FROM fixes f WHERE f.entity_id = ?`
	args := []any{entityID}
	if !q.From.IsZero() {
		query += ` AND f.received_at >= ?`
		args = append(args, q.From.UTC().Format(tsLayout))
	}
	if !q.To.IsZero() {
		query += ` AND f.received_at < ?`
		args = append(args, q.To.UTC().Format(tsLayout))
	}
	query += ` ORDER BY f.received_at, f.id LIMIT ?`
	args = append(args, q.Limit)
	rows, err := s.db.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Fix{}
	for rows.Next() {
		f, err := scanFix(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, *f)
	}
	return out, rows.Err()
}
