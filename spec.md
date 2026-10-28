# Tracker

A private location tracker: an Android app that reports each user's position to a
small server, and shows every tracked entity on the phone's map in OsmAnd. The
server also records history and is designed to take in other position sources
later (APRS, live aircraft, ...).

The technology stack, service management and install method follow the adjacent
`planner` repository (`~/Git/planner`), which works well; see "Tech stack and
layout" below.

# Definitions
- **Entity**: anything with a position: a phone running the app, or in future an
  APRS station, an aircraft, etc. Every entity has a stable id and a display
  name.
- **Fix**: one position report for an entity.
- **Real-time / near-real-time**: on the order of single-digit minutes. Nothing
  needs to be faster than that.

# Android App
The Android app must:
- Send the user's location fixes to the server. A fix carries latitude,
  longitude, accuracy, altitude, speed, heading, battery level, the device's
  else.
- Run continuously as a foreground service with a persistent notification,
  surviving reboots, and not rely on the app being open.
- Let the user configure the update interval and the minimum distance between
  fixes, with reasonable defaults (proposed: every 2 minutes, 25 metres;
  a fix is sent when the interval has elapsed *and* the device has moved at
  least the distance, plus a heartbeat fix at a longer interval, proposed
  15 minutes, so a stationary device still shows as alive).
- Offer an option to pause location updating. While paused nothing is
  collected or sent; the notification shows the paused state.
- Let the user set the server address (host:port, plain HTTP assumed).
- Let the user set their display name. The name is the entity's label on the
  server; the entity's identity is a stable id generated once on the device.
  Renaming updates the same entity everywhere (server and other phones); it
  never creates a new entity.
- Queue fixes locally when the server is unreachable (no network, VPN down) and
  send them, in order, when it is reachable again. Queued fixes keep their
  original device timestamp. The queue is persistent across app and device
  restarts and has no practical size limit for single-digit-minute reporting
  (prune only if it exceeds, say, 30 days).
- Use only the platform location APIs. No Google Play Services, no Fused
  Location Provider, no third-party services of any kind. The app talks to
  exactly one host, the configured server.
- Fetch the latest location of every tracked entity from the server at a
  user-configurable interval (default: 1 minute) and display them in OsmAnd,
  updating as new fixes arrive.
- Display all entities regardless of how stale their last fix is. The age of
  the fix should be visible (e.g. in the marker's description).
- Offer, per entity, a **history** view in OsmAnd: the user picks a range
  (last 1 h, 6 h, 24 h, 7 d, 30 d), the app fetches the fixes, builds a GPX
  track (a new segment wherever the gap between fixes exceeds an hour) and
  imports it into OsmAnd through the AIDL API, shown at once; the same
  sheet hides or removes it again.
- Offer, per entity, **Go to**: start OsmAnd navigation from the phone's
  position to the entity's latest fix, with the routing profile (car,
  bicycle, pedestrian) chosen in Settings; and **Show on map**, which
  centres OsmAnd on the entity.
- Let users give any entity a **custom icon and colour**: an OsmAnd built-in
  icon name picked from a curated grid (or typed), and a colour from a
  palette. They are stored on the server with the entity, so every phone
  shows the same icon; the owner's own choice lives in Settings, other
  entities' in the list.
- Never send to, or read from, a server it was not set up with. The server
  identifies itself with a stable `server_id` in `GET /api/version`; the app
  pins the id on the first successful contact after the address is set and,
  whenever a probe returns a different id or no tracker at all (e.g. the
  phone is on another network where the same IP is some other machine),
  neither uploads nor polls and keeps its queue. Settings shows the pinned
  id and can forget it (after the server database was rebuilt).
- Be designed for the latest GrapheneOS first and support the latest two major
  versions of regular Android (Android 16 and 17 at the time of writing; bump
  as new versions ship).
- Be distributed as a simple sideloaded APK to begin with (signed release build
  copied to `dist/`); an Obtainium/GitHub-releases flow like planner's can
  follow later.

## OsmAnd integration
OsmAnd is a hard requirement unless integration turns out to be completely
impossible. Try the approaches in this order and document what worked:

1. **OsmAnd AIDL API** (preferred): bind to OsmAnd's `IOsmAndAidlInterface`
   and add/update one map marker (or map point in a dedicated layer) per
   entity, updating positions in place as fixes arrive. This is what gives
   true in-place updates.
2. If the AIDL API proves unusable, fall back to writing a GPX file of
   waypoints that OsmAnd shows, refreshed on each poll.
3. Only if both fail: an in-app map (OSM tiles) as a last resort, and flag
   this to the user before building it.

Markers must show the entity's display name and, on tap or in the
description, the time of the last fix (and its age), accuracy, and whatever
of altitude/speed/heading/battery is present.

# Server
The server service must:
- Run on Debian 13 as a single static binary under systemd. No Docker or other
  complications.
- Receive fixes from the Android app and record the full history for every
  entity. Nothing is ever pruned.
- Serve the latest fix of every entity to the Android app.
- Treat the server clock as authoritative: every fix stores both the device's
  reported timestamp and the server's receive time, and "latest" is decided by
  the server's receive time. A device timestamp is kept for information and
  for later offline-queue replay analysis.
- Store entity display names, icons and colours so a rename or icon change
  sent by a phone propagates to every other client on its next poll. A PUT
  leaves absent fields unchanged.
- Identify itself with a `server_id` (a UUID made once per database and
  kept in it) in `GET /api/version`, alongside `"app": "tracker"`.
- Be extensible for future data sources (APRS, live aircraft, ...). Concretely:
  every entity has a `source` (e.g. `phone`, `aprs`, `adsb`), the fix schema is
  shared by all sources with nullable fields for what a source cannot provide,
  and the metadata field holds source-specific extras. New sources are added
  as ingesters inside the same binary writing through the same store; the
  HTTP API is source-agnostic.
- Keep all data in a single SQLite file (WAL mode) in the systemd state
  directory, so it is easily backed up by Restic exactly as planner is (see
  planner's README "Backup": `sqlite3 .backup` snapshot or copying the db with
  its `-wal` companion).
- Expose a plain HTTP + JSON API; no WebSockets or SSE. Polling from the app
  is sufficient for single-digit-minute latency.

## Data model
Entity: `id` (stable string, generated by the device for phones), `name`,
`source`, `icon` (OsmAnd icon name or ""), `color` ("#rrggbb" or ""),
`created_at`, `updated_at`.

Fix: `entity_id`, `received_at` (server, authoritative), `device_time`,
`lat`, `lon`, `accuracy_m`, `altitude_m`, `speed_mps`, `heading_deg`,
`battery_pct`, `metadata` (JSON object). All but `entity_id`, `received_at`,
`lat`, `lon` are nullable.

## API (sketch; the implementer may refine)
    GET  /api/version                       {app, server_id, version, date, build}
    PUT  /api/entities/{id}   {name?, icon?, color?}  create or update an entity (idempotent;
                                            absent fields unchanged)
    GET  /api/entities                      all entities with their latest fix
    POST /api/entities/{id}/fixes [fix,...] one or more fixes (batch, for the offline queue)
    GET  /api/entities/{id}/fixes?from&to   history (the app's history view pages through it)

The app sends its own name with `PUT /api/entities/{id}` on start-up and
whenever the name changes; the server upserts.

# Tech stack and layout (from planner)
Mirror planner's choices and conventions; where this spec is silent, do what
planner does.

**Server**
- Go (1.26+), single `main.go` plus `internal/store` (SQLite persistence) and
  `internal/api` (JSON handlers). SQLite via `modernc.org/sqlite` (pure Go)
  so the binary builds with `CGO_ENABLED=0` and runs on any x86-64 Debian.
- Flags `-addr` and `-db`, also settable as `TRACKER_ADDR` and `TRACKER_DB`;
  `-version` prints version, date and git build id. Schema migrations run at
  start-up, writing a `.bak-<timestamp>-v<n>` copy first.
- `Makefile` with `build`, `test` (vet + unit tests), `run` (dev server on a
  local `dev.db`), `dist` (static linux/amd64 binary), `install`, `clean`,
  and the `android*` targets.
- `deploy/`: `tracker.service` (same hardening as planner's unit:
  `StateDirectory=tracker`, `ProtectSystem=strict`, dedicated `tracker`
  system user, `EnvironmentFile=-/etc/tracker/tracker.env`),
  `tracker.env.example`, an idempotent `install.sh` (build or take a prebuilt
  binary, create user, install binary and unit, create env file only if
  absent, enable and restart, print the URL) and `uninstall.sh` that leaves
  data and config in place. Binary at `/usr/local/bin/tracker`, data at
  `/var/lib/tracker/tracker.db`, config at `/etc/tracker/tracker.env`.
- README sections mirroring planner's: build and run, deploying on Debian
  (install, configuration, where the data lives, upgrade, backup, uninstall),
  development layout, API summary.

**Android** (`android/` in this repository)
- Kotlin, Jetpack Compose, Material 3, portrait only. Gradle version catalog,
  wrapper checked in, `keystore.properties` (gitignored, example checked in)
  for release signing. `SETUP.md` as in planner for the toolchain.
- OkHttp + kotlinx.serialization for the API, Room for the offline fix queue,
  WorkManager for retrying the queue when connectivity returns, DataStore for
  preferences (server address, display name, entity id, intervals, paused).
  No DI framework; one `Graph` object of singletons.
- Network security config permitting cleartext HTTP; the app never contacts
  anything but the configured server.
- Screens: Setup (server, name), Main (status: last fix sent, queue length,
  last successful sync, pause toggle, open OsmAnd), Settings (server, name,
  update interval, minimum distance, heartbeat interval, poll interval,
  about).
- JVM unit tests for the queue/sync logic and the fix model.

# Misc
- No auth or encryption is needed; the whole system runs on a private network
  and VPN (WireGuard).
- Scale is tiny: single-digit users, a fix every few minutes each. Correctness
  and simplicity over performance.
- No web UI is needed for now.
- Any language or tech stack may be used, but the planner stack above is the
  default and should be used unless there is a strong reason not to.
