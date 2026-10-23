# Tracker

A private location tracker: an Android app reports each phone's position to
this small server, which keeps the full history and serves every entity's
latest fix back to the phones for display in OsmAnd. See [spec.md](spec.md)
for the full requirements. This directory is the server; the app lives in
[android/](android/).

## Build and run

Requires Go 1.26+. There is no web UI and no other toolchain.

    make build
    ./tracker -addr :8081 -db /var/lib/tracker/tracker.db

Flags can also be given as environment variables `TRACKER_ADDR` and
`TRACKER_DB`; `tracker -version` prints the semantic version, its release date
and the git build id (`GET /api/version` reports the same). The version and
date live at the top of `main.go`; bump them when cutting a release. All data
lives in the single SQLite file. The directory containing it must already
exist.

## How it works

- An **entity** is anything with a position: a phone running the app, or in
  future an APRS station or an aircraft. It has a stable id (generated once
  by the phone), a display name, and a `source` (`phone` for the app). The
  app sends `PUT /api/entities/{id}` with its name on start-up and whenever
  the name changes; the server creates or renames, so a rename reaches every
  other phone on its next poll and never creates a second entity.
- A **fix** is one position report. The server stamps each fix with its own
  receive time (`received_at`), which is authoritative: the entity's
  **latest** fix is the one with the greatest `received_at`, and among fixes
  received in the same batch the one posted last. The device's own clock
  (`device_time`) is kept for information, e.g. to see when a fix queued
  offline was really taken.
- Nothing is ever pruned. History is available per entity with a time range.
- Nothing is phone-specific. New sources (APRS, ADS-B, ...) are meant to be
  added as ingesters inside this binary that write through the same store
  with their own `source` and `metadata`; the HTTP API does not care where a
  fix came from.

## API

All JSON; errors are `{"error": "message"}` with 400 (bad input), 404
(unknown entity or path) or 405 (wrong method). Timestamps are RFC 3339 in
UTC; clients may send any offset and any sub-second precision.

    GET  /api/version                  {"version", "date", "build"}  liveness probe
    PUT  /api/entities/{id}            {"name", "source"?}  create or rename; 200 with the entity
    GET  /api/entities                 every entity with its latest fix, ordered by name then id
    GET  /api/entities/{id}            one entity with its latest fix, or 404
    POST /api/entities/{id}/fixes      [fix, ...] 1 to 500 fixes; 201 {"accepted": n, "latest": fix}
    GET  /api/entities/{id}/fixes      history, oldest first; ?from=&to=&limit=

`PUT /api/entities/{id}`: `id` is 1 to 64 characters of `A-Za-z0-9._-`;
`name` is trimmed and must be 1 to 64 characters; `source` is 1 to 32
characters of `a-z0-9_-`, defaults to `phone` when creating, and cannot be
changed afterwards (400). `updated_at` moves on a rename, not on fixes and not
on a PUT that changes nothing.

`POST /api/entities/{id}/fixes`: the body is a JSON array of fixes. All of
them are validated first and inserted in one transaction, so on a 400 nothing
is stored. 404 if the entity does not exist (the app always PUTs its entity
first). A fix is:

    {"device_time": "2026-10-22T18:00:00Z", "lat": 45.5, "lon": -73.6,
     "accuracy_m": 12.5, "altitude_m": 40.0, "speed_mps": 1.2, "heading_deg": 270.0,
     "battery_pct": 83, "metadata": {"provider": "gps"}}

`lat` (-90..90) and `lon` (-180..180) are required; everything else may be
null or absent. `accuracy_m` and `speed_mps` must not be negative,
`heading_deg` is 0..360, `battery_pct` an integer 0..100, `metadata` a JSON
object, `device_time` RFC 3339. Unknown fields are ignored.

`GET /api/entities/{id}/fixes`: `from` (inclusive) and `to` (exclusive) filter
on `received_at`; `limit` defaults to 1000 and may be at most 10000. The
result is ordered by `received_at` then id.

Entity JSON: `id`, `name`, `source`, `created_at`, `updated_at`, `latest`
(a fix, or null). Fix JSON: `id`, `entity_id`, `received_at`, `device_time`,
`lat`, `lon`, `accuracy_m`, `altitude_m`, `speed_mps`, `heading_deg`,
`battery_pct`, `metadata`. Nullable fields are always present, as `null` when
unset.

## Data model

Two tables in one SQLite file (WAL mode):

    entities  id TEXT PK, name, source, created_at, updated_at
    fixes     id INTEGER PK AUTOINCREMENT, entity_id -> entities, received_at,
              device_time, lat, lon, accuracy_m, altitude_m, speed_mps,
              heading_deg, battery_pct, metadata (JSON text)
              index (entity_id, received_at, id)

The schema version is kept in `PRAGMA user_version`. Timestamps are stored as
RFC 3339 UTC with a fixed nine fractional digits so that they sort as plain
strings; the API emits them in the usual trimmed form.

## Android app

The phone client lives in [android/](android/): Kotlin and Jetpack Compose,
built for GrapheneOS first, queueing fixes offline and showing every entity
in OsmAnd. See [android/README.md](android/README.md) for the design and
[android/SETUP.md](android/SETUP.md) for the toolchain; `make android`,
`make android-test`, `make android-release` and `make android-install` wrap
the Gradle build.

## Deploying on Debian

The server runs as a systemd service on a Debian 13 host; see `deploy/`. It
listens on plain HTTP with no authentication, so keep it on a private network
(the intended setup is a WireGuard VPN).

### Install

On a machine with Go, build a static linux/amd64 binary (no cgo, so it runs
on any x86-64 Debian regardless of installed libraries):

    make dist            # -> dist/tracker, versioned from `git describe`

Then, on the target host as root, from a checkout of this repo:

    sudo deploy/install.sh dist/tracker      # or: sudo make install BIN=dist/tracker

If Go is installed on the host you can omit the argument and the script builds
the binary itself (`sudo make install`). The script is idempotent and:

- installs the binary to `/usr/local/bin/tracker`
- creates the `tracker` system user (no shell, no home)
- installs `deploy/tracker.service` to `/etc/systemd/system/`
- creates `/etc/tracker/tracker.env` from `deploy/tracker.env.example` if it
  does not already exist (an existing file is never overwritten), and keeps a
  current copy of the example alongside it
- runs `systemctl daemon-reload`, enables the service, (re)starts it, and
  prints the URL

### Configuration

`/etc/tracker/tracker.env` holds `KEY=VALUE` lines read by systemd:

    TRACKER_ADDR=0.0.0.0:8081            # listen address; default reaches the whole LAN
    TRACKER_DB=/var/lib/tracker/tracker.db

Both are optional; the defaults above are built into the unit. After editing
run `systemctl restart tracker`. Logs go to the journal, one line per request:
`journalctl -u tracker -f`.

The unit runs with `ProtectSystem=strict` and related hardening, so the only
writable location is the state directory `/var/lib/tracker` (created and owned
by systemd via `StateDirectory=`). If you point `TRACKER_DB` elsewhere, add
that directory to `ReadWritePaths=` in the unit. To listen on a port below
1024, see the capability note in `deploy/tracker.service`.

### Where the data lives

Everything is in `/var/lib/tracker/tracker.db`. SQLite runs in WAL mode, so
`tracker.db-wal` and `tracker.db-shm` sit next to it while the service is
running; they are part of the database state, not temp files.

### Upgrade

Build a new binary and re-run the installer; it replaces the binary and unit
and restarts the service, leaving the env file and database alone:

    make dist && sudo deploy/install.sh dist/tracker

Or by hand: copy the new binary over `/usr/local/bin/tracker` and run
`systemctl restart tracker`. Schema migrations run automatically at startup;
before migrating an existing database the service first writes a copy next to
it (`tracker.db.bak-<timestamp>-v<version>`) and logs the path, so a failed
upgrade can be rolled back by stopping the service and copying it back.

### Backup

The database is a single file, so it fits any file-level backup tool such as
restic. Two safe approaches:

1. Snapshot with SQLite (most robust; produces a consistent single file with
   no `-wal` companion, and works while the service is running):

       sqlite3 /var/lib/tracker/tracker.db ".backup /var/backups/tracker.db"

   then point restic at `/var/backups/tracker.db`. `sqlite3` is in the Debian
   package of the same name. Run it as root or as the `tracker` user; the
   state directory is mode 0750.

2. Copy the files directly. Because WAL mode is used, copying `tracker.db`
   together with `tracker.db-wal` (if present) while the service runs yields a
   usable database; backing up `tracker.db` alone can miss the most recent
   writes still in the WAL. Stopping the service first
   (`systemctl stop tracker`) checkpoints the WAL and makes a plain copy of
   `tracker.db` exact.

To restore: stop the service, replace `/var/lib/tracker/tracker.db` (removing
any stale `-wal`/`-shm` files), `chown tracker:tracker` it, and start the
service.

### Uninstall

    sudo deploy/uninstall.sh

This stops and disables the service and removes the unit and binary. It
deliberately leaves `/var/lib/tracker` (the database), `/etc/tracker`, and the
`tracker` user in place; remove them yourself if you want a clean slate.

## Development

Layout:

    main.go               flags, request logging, HTTP server
    internal/store        SQLite persistence and validation (entities, fixes, migrations with backup)
    internal/api          JSON API handlers (see package doc for conventions)
    deploy/               systemd unit, env example, install/uninstall scripts
    android/              the Android app (see its README)

Commands:

    make test             # go vet + unit tests
    make run              # dev server on 127.0.0.1:8081 with dev.db
    make dist             # static linux/amd64 binary in dist/ (CGO_ENABLED=0)
    make install          # install as a systemd service on this host (root; see Deploying)
    make android          # debug APK; android-test, android-release, android-install likewise

A quick manual check against a dev server:

    curl -X PUT localhost:8081/api/entities/phone-1 -d '{"name":"Evan"}'
    curl -X POST localhost:8081/api/entities/phone-1/fixes -d '[{"lat":45.5,"lon":-73.6}]'
    curl localhost:8081/api/entities
