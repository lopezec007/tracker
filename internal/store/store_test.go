package store

import (
	"database/sql"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func openTest(t *testing.T) *Store {
	t.Helper()
	s, err := Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func str(s string) *string   { return &s }
func f64(f float64) *float64 { return &f }
func in(i int) *int          { return &i }

func mustEntity(t *testing.T, s *Store, id, name, source string) *Entity {
	t.Helper()
	e, err := s.UpsertEntity(id, name, source)
	if err != nil {
		t.Fatalf("upsert %s: %v", id, err)
	}
	return e
}

func mustInsert(t *testing.T, s *Store, id string, fixes ...NewFix) *Fix {
	t.Helper()
	n, latest, err := s.InsertFixes(id, fixes)
	if err != nil {
		t.Fatalf("insert into %s: %v", id, err)
	}
	if n != len(fixes) {
		t.Fatalf("accepted %d of %d", n, len(fixes))
	}
	return latest
}

func isValidation(err error) bool {
	var ve *ValidationError
	return errors.As(err, &ve)
}

func TestUpsertEntity(t *testing.T) {
	s := openTest(t)
	e := mustEntity(t, s, "phone-1", "  Evan ", "")
	if e.Name != "Evan" || e.Source != SourcePhone || e.Latest != nil || e.CreatedAt != e.UpdatedAt {
		t.Fatalf("created: %+v", e)
	}
	if _, err := time.Parse(time.RFC3339Nano, e.CreatedAt); err != nil {
		t.Fatalf("created_at not RFC 3339: %q", e.CreatedAt)
	}
	// Same name again: idempotent, updated_at unchanged.
	same := mustEntity(t, s, "phone-1", "Evan", "phone")
	if same.UpdatedAt != e.UpdatedAt {
		t.Fatalf("updated_at changed on a no-op PUT: %s -> %s", e.UpdatedAt, same.UpdatedAt)
	}
	time.Sleep(2 * time.Millisecond)
	// Rename: same entity, updated_at moves, created_at does not.
	re := mustEntity(t, s, "phone-1", "Evan L", "")
	if re.Name != "Evan L" || re.CreatedAt != e.CreatedAt || re.UpdatedAt == e.UpdatedAt || re.Source != SourcePhone {
		t.Fatalf("renamed: %+v (was %+v)", re, e)
	}
	// Source cannot change.
	if _, err := s.UpsertEntity("phone-1", "Evan", "aprs"); !isValidation(err) {
		t.Fatalf("source change: %v", err)
	}
	// Other sources are fine for new entities.
	a := mustEntity(t, s, "VE2ABC-9", "VE2ABC-9", "aprs")
	if a.Source != "aprs" {
		t.Fatalf("aprs entity: %+v", a)
	}
	es, err := s.ListEntities()
	if err != nil {
		t.Fatal(err)
	}
	if len(es) != 2 || es[0].ID != "phone-1" || es[1].ID != "VE2ABC-9" {
		t.Fatalf("list order by name: %+v", es)
	}
	if _, err := s.GetEntity("nope"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("missing entity: %v", err)
	}
}

func TestUpsertEntityValidation(t *testing.T) {
	s := openTest(t)
	cases := []struct {
		name              string
		id, ename, source string
	}{
		{"empty id", "", "x", ""},
		{"id with slash", "a/b", "x", ""},
		{"id with space", "a b", "x", ""},
		{"id too long", strings.Repeat("a", 65), "x", ""},
		{"empty name", "ok", "", ""},
		{"blank name", "ok", "   ", ""},
		{"name too long", "ok", strings.Repeat("n", 65), ""},
		{"source uppercase", "ok", "x", "Phone"},
		{"source with dot", "ok", "x", "ads.b"},
		{"source too long", "ok", "x", strings.Repeat("s", 33)},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if _, err := s.UpsertEntity(c.id, c.ename, c.source); !isValidation(err) {
				t.Fatalf("expected validation error, got %v", err)
			}
		})
	}
	// Boundaries that are allowed.
	mustEntity(t, s, strings.Repeat("a", 64)+"", strings.Repeat("n", 64), strings.Repeat("s", 32))
	mustEntity(t, s, "a.b_c-D9", "x", "ads-b_2")
}

func TestInsertFixesAndLatest(t *testing.T) {
	s := openTest(t)
	mustEntity(t, s, "p", "P", "")
	meta := json.RawMessage(`{ "provider" : "gps" }`)
	latest := mustInsert(t, s, "p",
		NewFix{Lat: 45.5, Lon: -73.6, DeviceTime: str("2026-10-22T14:00:00-04:00"), AccuracyM: f64(12.5), BatteryPct: in(83), Metadata: meta},
		NewFix{Lat: 45.6, Lon: -73.7, Metadata: json.RawMessage("null")},
	)
	// Both fixes share one received_at; the later id wins.
	if latest == nil || latest.Lat != 45.6 || latest.ID != 2 || latest.EntityID != "p" {
		t.Fatalf("latest: %+v", latest)
	}
	if latest.DeviceTime != nil || latest.Metadata != nil || latest.AccuracyM != nil || latest.BatteryPct != nil {
		t.Fatalf("nullable fields should be nil: %+v", latest)
	}
	fixes, err := s.ListFixes("p", FixQuery{})
	if err != nil {
		t.Fatal(err)
	}
	if len(fixes) != 2 || fixes[0].ID != 1 || fixes[1].ID != 2 || fixes[0].ReceivedAt != fixes[1].ReceivedAt {
		t.Fatalf("fixes: %+v", fixes)
	}
	first := fixes[0]
	if first.DeviceTime == nil || *first.DeviceTime != "2026-10-22T18:00:00Z" {
		t.Fatalf("device_time should be normalised to UTC: %v", first.DeviceTime)
	}
	if string(first.Metadata) != `{"provider":"gps"}` || *first.AccuracyM != 12.5 || *first.BatteryPct != 83 {
		t.Fatalf("first: %+v", first)
	}
	if _, err := time.Parse(time.RFC3339Nano, first.ReceivedAt); err != nil {
		t.Fatalf("received_at not RFC 3339: %q", first.ReceivedAt)
	}
	// A later batch becomes the latest, and the entity carries it.
	time.Sleep(2 * time.Millisecond)
	l2 := mustInsert(t, s, "p", NewFix{Lat: 1, Lon: 2})
	e, err := s.GetEntity("p")
	if err != nil {
		t.Fatal(err)
	}
	if e.Latest == nil || e.Latest.ID != l2.ID || e.Latest.ID != 3 {
		t.Fatalf("entity latest: %+v", e.Latest)
	}
	es, _ := s.ListEntities()
	if len(es) != 1 || es[0].Latest == nil || es[0].Latest.ID != 3 {
		t.Fatalf("list latest: %+v", es)
	}
	// Unknown entity.
	if _, _, err := s.InsertFixes("ghost", []NewFix{{Lat: 1, Lon: 1}}); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unknown entity: %v", err)
	}
	if _, err := s.ListFixes("ghost", FixQuery{}); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unknown entity list: %v", err)
	}
}

func TestInsertFixesValidationIsAllOrNothing(t *testing.T) {
	s := openTest(t)
	mustEntity(t, s, "p", "P", "")
	good := NewFix{Lat: 0, Lon: 0}
	cases := []struct {
		name string
		bad  NewFix
	}{
		{"lat high", NewFix{Lat: 90.1, Lon: 0}},
		{"lat low", NewFix{Lat: -90.1, Lon: 0}},
		{"lon high", NewFix{Lat: 0, Lon: 180.1}},
		{"lon low", NewFix{Lat: 0, Lon: -180.1}},
		{"accuracy negative", NewFix{Lat: 0, Lon: 0, AccuracyM: f64(-1)}},
		{"speed negative", NewFix{Lat: 0, Lon: 0, SpeedMps: f64(-0.1)}},
		{"heading high", NewFix{Lat: 0, Lon: 0, HeadingDeg: f64(360.5)}},
		{"heading low", NewFix{Lat: 0, Lon: 0, HeadingDeg: f64(-1)}},
		{"battery high", NewFix{Lat: 0, Lon: 0, BatteryPct: in(101)}},
		{"battery low", NewFix{Lat: 0, Lon: 0, BatteryPct: in(-1)}},
		{"bad device time", NewFix{Lat: 0, Lon: 0, DeviceTime: str("yesterday")}},
		{"metadata array", NewFix{Lat: 0, Lon: 0, Metadata: json.RawMessage(`[1]`)}},
		{"metadata string", NewFix{Lat: 0, Lon: 0, Metadata: json.RawMessage(`"x"`)}},
		{"metadata broken", NewFix{Lat: 0, Lon: 0, Metadata: json.RawMessage(`{"a":`)}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			n, latest, err := s.InsertFixes("p", []NewFix{good, c.bad})
			if !isValidation(err) || n != 0 || latest != nil {
				t.Fatalf("expected validation error, got n=%d latest=%v err=%v", n, latest, err)
			}
			if !strings.HasPrefix(err.Error(), "fix 1:") {
				t.Fatalf("error should name the fix: %v", err)
			}
		})
	}
	fixes, _ := s.ListFixes("p", FixQuery{})
	if len(fixes) != 0 {
		t.Fatalf("nothing should have been inserted, got %d", len(fixes))
	}
	// Batch size limits.
	if _, _, err := s.InsertFixes("p", nil); !isValidation(err) {
		t.Fatalf("empty batch: %v", err)
	}
	if _, _, err := s.InsertFixes("p", make([]NewFix, MaxBatch+1)); !isValidation(err) {
		t.Fatalf("oversize batch: %v", err)
	}
	// Boundary values and null-ish metadata are accepted.
	mustInsert(t, s, "p",
		NewFix{Lat: 90, Lon: 180, HeadingDeg: f64(360), BatteryPct: in(100), AccuracyM: f64(0), SpeedMps: f64(0), AltitudeM: f64(-400)},
		NewFix{Lat: -90, Lon: -180, HeadingDeg: f64(0), BatteryPct: in(0), Metadata: json.RawMessage(" null ")},
		NewFix{Lat: 0, Lon: 0, Metadata: json.RawMessage("")},
	)
	n, _, err := s.InsertFixes("p", make([]NewFix, MaxBatch))
	if err != nil || n != MaxBatch {
		t.Fatalf("full batch: n=%d err=%v", n, err)
	}
}

func TestListFixesRangeAndLimit(t *testing.T) {
	s := openTest(t)
	mustEntity(t, s, "p", "P", "")
	// Three batches at distinct receive times.
	var times []time.Time
	for i := 0; i < 3; i++ {
		l := mustInsert(t, s, "p", NewFix{Lat: float64(i), Lon: 0}, NewFix{Lat: float64(i), Lon: 1})
		ts, err := time.Parse(time.RFC3339Nano, l.ReceivedAt)
		if err != nil {
			t.Fatal(err)
		}
		times = append(times, ts)
		time.Sleep(2 * time.Millisecond)
	}
	all, err := s.ListFixes("p", FixQuery{})
	if err != nil || len(all) != 6 {
		t.Fatalf("all: %d %v", len(all), err)
	}
	for i := 1; i < len(all); i++ {
		if all[i].ID <= all[i-1].ID || all[i].ReceivedAt < all[i-1].ReceivedAt {
			t.Fatalf("not ordered: %+v", all)
		}
	}
	// from is inclusive: the second batch's exact time keeps it.
	mid, _ := s.ListFixes("p", FixQuery{From: times[1]})
	if len(mid) != 4 || mid[0].Lat != 1 {
		t.Fatalf("from: %+v", mid)
	}
	// to is exclusive: the third batch's exact time drops it.
	upto, _ := s.ListFixes("p", FixQuery{To: times[2]})
	if len(upto) != 4 || upto[3].Lat != 1 {
		t.Fatalf("to: %+v", upto)
	}
	both, _ := s.ListFixes("p", FixQuery{From: times[1], To: times[2]})
	if len(both) != 2 || both[0].Lat != 1 || both[1].Lat != 1 {
		t.Fatalf("from+to: %+v", both)
	}
	lim, _ := s.ListFixes("p", FixQuery{Limit: 3})
	if len(lim) != 3 || lim[2].ID != 3 {
		t.Fatalf("limit: %+v", lim)
	}
	for _, bad := range []int{-1, MaxLimit + 1} {
		if _, err := s.ListFixes("p", FixQuery{Limit: bad}); !isValidation(err) {
			t.Fatalf("limit %d: %v", bad, err)
		}
	}
}

// Sub-second timestamps must order correctly as strings, which RFC3339Nano's
// trimmed form does not guarantee ("…00.5Z" < "…00Z"); the stored layout is
// fixed-width.
func TestTimestampsSortAsStrings(t *testing.T) {
	a := time.Date(2026, 10, 22, 18, 0, 0, 0, time.UTC)
	b := a.Add(500 * time.Millisecond)
	c := a.Add(120 * time.Millisecond)
	if !(a.Format(tsLayout) < c.Format(tsLayout) && c.Format(tsLayout) < b.Format(tsLayout)) {
		t.Fatalf("stored layout does not sort: %s %s %s", a.Format(tsLayout), c.Format(tsLayout), b.Format(tsLayout))
	}
	if got := outTS(b.Format(tsLayout)); got != "2026-10-22T18:00:00.5Z" {
		t.Fatalf("outTS: %s", got)
	}
	if got := outTS(a.Format(tsLayout)); got != "2026-10-22T18:00:00Z" {
		t.Fatalf("outTS: %s", got)
	}
	if _, err := ParseTime("2026-10-22T18:00:00.123+02:00"); err != nil {
		t.Fatal(err)
	}
	if _, err := ParseTime("2026-10-22 18:00:00"); err == nil {
		t.Fatal("space-separated time should not parse")
	}
}

func TestBackupBeforeMigration(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.db")
	// A fresh database: no backup.
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if s.LastBackup != "" {
		t.Fatalf("fresh database should not be backed up, got %s", s.LastBackup)
	}
	mustEntity(t, s, "keep", "keep me", "")
	// Pretend it predates schema versioning.
	if _, err := s.db.Exec(`PRAGMA user_version = 0`); err != nil {
		t.Fatal(err)
	}
	s.Close()
	s2, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s2.Close()
	if s2.LastBackup == "" {
		t.Fatal("expected a backup before migrating an old database")
	}
	if !strings.HasPrefix(filepath.Base(s2.LastBackup), "t.db.bak-") || !strings.HasSuffix(s2.LastBackup, "-v0") {
		t.Fatalf("backup name: %s", s2.LastBackup)
	}
	if _, err := os.Stat(s2.LastBackup); err != nil {
		t.Fatal(err)
	}
	b, err := Open(s2.LastBackup)
	if err != nil {
		t.Fatalf("backup should be a valid database: %v", err)
	}
	defer b.Close()
	es, _ := b.ListEntities()
	if len(es) != 1 || es[0].Name != "keep me" {
		t.Fatalf("backup contents: %+v", es)
	}
	// Up to date now: reopening takes no further backup.
	s2.Close()
	s3, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s3.Close()
	if s3.LastBackup != "" {
		t.Fatalf("no backup expected when up to date, got %s", s3.LastBackup)
	}
}

// TestMigrateV1 opens a database laid out as schema version 1 and checks it
// is upgraded in place with its data intact and a server id made.
func TestMigrateV1(t *testing.T) {
	path := filepath.Join(t.TempDir(), "v1.db")
	db, err := sql.Open("sqlite", "file:"+path)
	if err != nil {
		t.Fatal(err)
	}
	for _, stmt := range []string{
		`CREATE TABLE entities (id TEXT PRIMARY KEY, name TEXT NOT NULL, source TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)`,
		`CREATE TABLE fixes (id INTEGER PRIMARY KEY AUTOINCREMENT, entity_id TEXT NOT NULL REFERENCES entities(id), received_at TEXT NOT NULL, device_time TEXT, lat REAL NOT NULL, lon REAL NOT NULL, accuracy_m REAL, altitude_m REAL, speed_mps REAL, heading_deg REAL, battery_pct INTEGER, metadata TEXT)`,
		`INSERT INTO entities VALUES ('old', 'Old', 'phone', '2026-10-22T00:00:00.000000000Z', '2026-10-22T00:00:00.000000000Z')`,
		`INSERT INTO fixes (entity_id, received_at, lat, lon) VALUES ('old', '2026-10-22T00:00:01.000000000Z', 1, 2)`,
		`PRAGMA user_version = 1`,
	} {
		if _, err := db.Exec(stmt); err != nil {
			t.Fatalf("%s: %v", stmt, err)
		}
	}
	db.Close()
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if !strings.HasSuffix(s.LastBackup, "-v1") {
		t.Fatalf("expected a v1 backup, got %q", s.LastBackup)
	}
	e, err := s.GetEntity("old")
	if err != nil {
		t.Fatal(err)
	}
	if e.Name != "Old" || e.Icon != "" || e.Color != "" || e.Latest == nil || e.Latest.Lat != 1 {
		t.Fatalf("migrated entity: %+v", e)
	}
	id, err := s.ServerID()
	if err != nil || len(id) != 36 {
		t.Fatalf("server id after migration: %q %v", id, err)
	}
	icon := "mx_shop_car"
	if e, err = s.PutEntity("old", EntityPatch{Icon: &icon}); err != nil || e.Icon != icon {
		t.Fatalf("icon after migration: %+v %v", e, err)
	}
	// Reopening is a no-op with the same id.
	s.Close()
	s2, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s2.Close()
	if id2, _ := s2.ServerID(); id2 != id || s2.LastBackup != "" {
		t.Fatalf("second open: id %s -> %s, backup %q", id, id2, s2.LastBackup)
	}
}

func TestWALMode(t *testing.T) {
	s := openTest(t)
	var mode string
	if err := s.db.QueryRow(`PRAGMA journal_mode`).Scan(&mode); err != nil {
		t.Fatal(err)
	}
	if mode != "wal" {
		t.Fatalf("journal_mode = %s", mode)
	}
}
