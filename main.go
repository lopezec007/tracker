// Command tracker serves the location tracker API.
//
// Usage:
//
//	tracker [-addr :8081] [-db tracker.db]
//	tracker -version
//
// Environment variables TRACKER_ADDR and TRACKER_DB are used as defaults for
// the corresponding flags.
package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"tracker/internal/api"
	"tracker/internal/store"
)

// Version is the semantic version of this release and VersionDate the day it
// was cut. build is the git description, set via -ldflags "-X main.build=...".
const (
	Version     = "0.1.0"
	VersionDate = "2026-10-28"
)

var build = "dev"

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func main() {
	addr := flag.String("addr", envOr("TRACKER_ADDR", ":8081"), "listen address")
	dbPath := flag.String("db", envOr("TRACKER_DB", "tracker.db"), "path to SQLite database file")
	showVersion := flag.Bool("version", false, "print version and exit")
	flag.Parse()

	if *showVersion {
		fmt.Printf("tracker %s (%s, build %s)\n", Version, VersionDate, build)
		return
	}

	// SQLite reports a missing parent directory only as a cryptic
	// "unable to open database file"; check it up front instead.
	if dir := filepath.Dir(*dbPath); dir != "." {
		if fi, err := os.Stat(dir); err != nil {
			log.Fatalf("database directory %s does not exist (create it first): %v", dir, err)
		} else if !fi.IsDir() {
			log.Fatalf("database directory %s is not a directory", dir)
		}
	}

	st, err := store.Open(*dbPath)
	if err != nil {
		log.Fatalf("open database %s: %v", *dbPath, err)
	}
	defer st.Close()
	if st.LastBackup != "" {
		log.Printf("database schema upgraded; a copy of the previous database was saved to %s", st.LastBackup)
	}

	mux := http.NewServeMux()
	mux.Handle("/api/", api.Handler(st, Version, VersionDate, build))
	mux.Handle("/", http.NotFoundHandler())

	log.Printf("tracker %s (%s) listening on %s (db %s)", Version, build, *addr, *dbPath)
	if err := http.ListenAndServe(*addr, logRequests(mux)); err != nil {
		log.Fatal(err)
	}
}

// logRequests writes one journal line per request: method, path, status,
// duration and client address.
func logRequests(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w, status: http.StatusOK}
		h.ServeHTTP(sw, r)
		log.Printf("%s %s %d %s %s", r.Method, r.URL.RequestURI(), sw.status,
			time.Since(start).Round(time.Millisecond), r.RemoteAddr)
	})
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(code int) {
	w.status = code
	w.ResponseWriter.WriteHeader(code)
}
