// Package api exposes the store over a JSON HTTP API.
//
// Conventions:
//   - All responses are JSON. Errors are {"error": "message"} with 400 for a
//     bad request, 404 for an unknown entity or path and 405 for a wrong
//     method.
//   - Timestamps are RFC 3339 in UTC. Clients may send any offset and any
//     sub-second precision; the server emits time.RFC3339Nano.
//   - Nullable fix fields are always present in responses (as null when
//     unset). In requests an absent field is the same as null, and unknown
//     fields are ignored.
//   - The API is source-agnostic: the same endpoints serve a phone, an APRS
//     station or an aircraft; only the entity's source and the metadata
//     differ.
package api

import (
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strconv"
	"strings"

	"tracker/internal/store"
)

// maxBody caps request bodies; a full batch of 500 fixes is well under it.
const maxBody = 4 << 20

type Server struct {
	st      *store.Store
	version map[string]string
}

// New makes a server over the store. The version strings are what
// GET /api/version reports.
func New(st *store.Store, version, date, build string) *Server {
	return &Server{st: st, version: map[string]string{"version": version, "date": date, "build": build}}
}

// Handler returns the API router mounted under /api/.
func Handler(st *store.Store, version, date, build string) http.Handler {
	return New(st, version, date, build).Handler()
}

// Handler returns the server's router mounted under /api/. Each path is also
// registered without a method so that a wrong method gets a JSON 405 rather
// than the mux's plain-text one.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/version", s.getVersion)
	mux.HandleFunc("GET /api/entities", s.listEntities)
	mux.HandleFunc("GET /api/entities/{id}", s.getEntity)
	mux.HandleFunc("PUT /api/entities/{id}", s.putEntity)
	mux.HandleFunc("GET /api/entities/{id}/fixes", s.listFixes)
	mux.HandleFunc("POST /api/entities/{id}/fixes", s.postFixes)
	mux.HandleFunc("/api/version", methodNotAllowed("GET"))
	mux.HandleFunc("/api/entities", methodNotAllowed("GET"))
	mux.HandleFunc("/api/entities/{id}", methodNotAllowed("GET, PUT"))
	mux.HandleFunc("/api/entities/{id}/fixes", methodNotAllowed("GET, POST"))
	mux.HandleFunc("/api/", func(w http.ResponseWriter, r *http.Request) { writeErr(w, store.ErrNotFound) })
	return mux
}

func methodNotAllowed(allow string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Allow", allow)
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
	}
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		log.Printf("write response: %v", err)
	}
}

func writeErr(w http.ResponseWriter, err error) {
	var ve *store.ValidationError
	switch {
	case errors.As(err, &ve):
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": ve.Msg})
	case errors.Is(err, store.ErrNotFound):
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "not found"})
	default:
		log.Printf("internal error: %v", err)
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "internal error"})
	}
}

// decode reads a JSON body into v. Unknown fields are ignored so that a newer
// client can talk to an older server.
func decode(w http.ResponseWriter, r *http.Request, v any) error {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBody))
	if err := dec.Decode(v); err != nil {
		return &store.ValidationError{Msg: "invalid JSON body: " + err.Error()}
	}
	return nil
}

func (s *Server) getVersion(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, s.version)
}

// ---------------------------------------------------------------- entities

func (s *Server) listEntities(w http.ResponseWriter, r *http.Request) {
	es, err := s.st.ListEntities()
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, 200, es)
}

func (s *Server) getEntity(w http.ResponseWriter, r *http.Request) {
	e, err := s.st.GetEntity(r.PathValue("id"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, 200, e)
}

func (s *Server) putEntity(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Name   string `json:"name"`
		Source string `json:"source"`
	}
	if err := decode(w, r, &in); err != nil {
		writeErr(w, err)
		return
	}
	e, err := s.st.UpsertEntity(r.PathValue("id"), in.Name, in.Source)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, 200, e)
}

// ---------------------------------------------------------------- fixes

// fixBody is the wire format of one fix in a POST batch. lat and lon are
// pointers only to tell "absent" from 0.
type fixBody struct {
	DeviceTime *string         `json:"device_time"`
	Lat        *float64        `json:"lat"`
	Lon        *float64        `json:"lon"`
	AccuracyM  *float64        `json:"accuracy_m"`
	AltitudeM  *float64        `json:"altitude_m"`
	SpeedMps   *float64        `json:"speed_mps"`
	HeadingDeg *float64        `json:"heading_deg"`
	BatteryPct *int            `json:"battery_pct"`
	Metadata   json.RawMessage `json:"metadata"`
}

func (s *Server) postFixes(w http.ResponseWriter, r *http.Request) {
	var in []fixBody
	if err := decode(w, r, &in); err != nil {
		writeErr(w, err)
		return
	}
	fixes := make([]store.NewFix, len(in))
	for i, b := range in {
		if b.Lat == nil || b.Lon == nil {
			writeErr(w, &store.ValidationError{Msg: "fix " + strconv.Itoa(i) + ": lat and lon are required"})
			return
		}
		fixes[i] = store.NewFix{
			DeviceTime: b.DeviceTime, Lat: *b.Lat, Lon: *b.Lon,
			AccuracyM: b.AccuracyM, AltitudeM: b.AltitudeM, SpeedMps: b.SpeedMps,
			HeadingDeg: b.HeadingDeg, BatteryPct: b.BatteryPct, Metadata: b.Metadata,
		}
	}
	n, latest, err := s.st.InsertFixes(r.PathValue("id"), fixes)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, 201, map[string]any{"accepted": n, "latest": latest})
}

func (s *Server) listFixes(w http.ResponseWriter, r *http.Request) {
	var q store.FixQuery
	var err error
	vals := r.URL.Query()
	if v := strings.TrimSpace(vals.Get("from")); v != "" {
		if q.From, err = store.ParseTime(v); err != nil {
			writeErr(w, &store.ValidationError{Msg: "from must be an RFC 3339 timestamp"})
			return
		}
	}
	if v := strings.TrimSpace(vals.Get("to")); v != "" {
		if q.To, err = store.ParseTime(v); err != nil {
			writeErr(w, &store.ValidationError{Msg: "to must be an RFC 3339 timestamp"})
			return
		}
	}
	if v := strings.TrimSpace(vals.Get("limit")); v != "" {
		if q.Limit, err = strconv.Atoi(v); err != nil || q.Limit < 1 || q.Limit > store.MaxLimit {
			writeErr(w, &store.ValidationError{Msg: "limit must be an integer between 1 and " + strconv.Itoa(store.MaxLimit)})
			return
		}
	}
	fixes, err := s.st.ListFixes(r.PathValue("id"), q)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, 200, fixes)
}
