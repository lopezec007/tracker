# Tracker for Android

A phone client for the tracker server in the parent directory. Kotlin and
Jetpack Compose, portrait only, Android 16+ (API 36), built for GrapheneOS
first: no Google Play Services, no Fused Location Provider from Play, no
third-party services of any kind. The app talks to the configured server and
to OsmAnd on the same phone, and to nothing else.

## Build

Requires a JDK (21) and the Android SDK (`ANDROID_HOME`, with
`platforms;android-36` and `build-tools;36.0.0`; see `SETUP.md`). Gradle
itself is fetched by the wrapper.

    cd android
    ./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
    ./gradlew test                     # JVM unit tests (queue/sync, API client, throttle, ages, OsmAnd points)
    ./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk (R8, signed when the key exists)
    ./gradlew installDebug             # onto the device on adb

Release builds are signed with the key named in `android/keystore.properties`
(gitignored; see `keystore.properties.example`). Without that file
`assembleRelease` produces an unsigned APK. The root `Makefile` wraps these
as `make android`, `android-test`, `android-release` (copies the signed APK
to `dist/`) and `android-install`.

## Design

### Server contract

The app uses four endpoints of the server's JSON API (see "API" in the
top-level README); timestamps are RFC 3339 UTC strings, errors are
`{"error": "..."}`:

- `GET /api/version` — the reachability probe (short timeouts) before
  anything else is sent, and what Setup shows as "Tracker x.y.z found".
- `PUT /api/entities/{id}` `{"name", "source": "phone"}` — at the start of
  every sync run. Cheap and idempotent: the server learns the current name
  and the entity exists before its fixes arrive. `id` is a UUID generated
  once on the phone (`Prefs.ensureEntityId`) and never changed; renaming
  keeps it, so the same entity is renamed everywhere.
- `POST /api/entities/{id}/fixes` — a JSON array of up to 500 fixes, in
  order. 201 acknowledges the whole batch; 400 refuses the whole batch; 404
  means the entity is unknown (the server's database was reset), in which
  case the entity is PUT again and the batch retried once.
- `GET /api/entities` — every entity with its latest fix, for the Main
  screen and for OsmAnd. Ages are measured from the server's `received_at`
  (authoritative), falling back to `device_time`.

`GET /api/entities/{id}/fixes` (history) is not used.

A fix carries `device_time`, `lat`, `lon`, `accuracy_m`, `altitude_m`,
`speed_mps`, `heading_deg`, `battery_pct` and a `metadata` object; nulls are
sent explicitly for what the provider did not give. Metadata holds
`provider` (`fused` or `gps`), `reason` (`first`, `moved`, `heartbeat`),
`location_time` when a remembered location was reused (then `device_time` is
the time of the report, "still here now"), and `mock` when the location was
a mock.

### Network policy

The app talks to exactly one host, the server address entered at setup, and
to nothing else over the network. There is no analytics, crash reporting,
push, update check or tile fetching (the map is OsmAnd's). Every request goes
through `TrackerApi`, which is built on the base URL; the network security
config permits cleartext HTTP because the server speaks plain HTTP (over the
LAN or through the user's WireGuard tunnel), and that permission is only ever
used for that one host.

Before pushing queued fixes the sync engine probes `GET /api/version` with
a short timeout. If the server is not reachable (no network, or the tunnel is
down) nothing else is sent and the queue waits. WorkManager retries when
connectivity changes.

### Location collection

`location/TrackerService` is a foreground service of type `location` with a
persistent notification (tracking / paused / last fix / queue length, and a
Pause/Resume action). It is started by `MainActivity` whenever the app is
opened and configured, and by `location/BootReceiver` after a reboot.
`START_STICKY` brings it back if the system kills it.

It uses only `android.location.LocationManager`: the platform's
`FUSED_PROVIDER` when the phone has one (on GrapheneOS that is the OS's own
fused provider, no Play Services; on stock Android it may be backed by
Google's, which is why Settings has a "GPS only" switch), else
`GPS_PROVIDER`. Updates are requested with the configured interval and
minimum distance; on top of that `location/Throttle` (pure Kotlin, unit
tested) decides what becomes a fix:

- the first location after the service starts;
- a location when the **update interval** (default 2 min) has elapsed *and*
  the phone has moved at least the **minimum distance** (default 25 m) since
  the last fix;
- a **heartbeat** (default 15 min) whenever that much time has passed since
  the last fix, regardless of movement, so a stationary phone still shows
  as alive. A 30-second ticker in the service reuses the newest location
  known (the last one delivered, or the platform's last known location).

The battery level comes from `BatteryManager`. Pausing (the switch on Main,
or the notification action) removes the location request; nothing is
collected or sent until resumed, and the notification says "Paused".

Permissions, asked for on the Main screen with an explanation of each:
fine and coarse location first (the service cannot even start in the
foreground without them), then background location ("Allow all the time",
which the platform requires for a service started from a boot broadcast to
read the location at all; without it tracking starts the next time the app
is opened), and notifications (without them the notification is hidden but
the service still runs).

### Offline queue

Every fix is written to the Room table `pending_fixes` first (`data/db`),
then a sync run is requested. `data/sync/SyncEngine.sync()`:

1. prunes queued fixes older than 30 days (the only pruning there is);
2. probes `/api/version` (an `IOException` here means offline: the queue
   waits);
3. `PUT`s the entity with the current name;
4. sends the queue oldest-first in batches of up to 500 and deletes each
   batch **only after** the server has acknowledged it. A 5xx stops the run
   and keeps the queue. A 404 re-creates the entity and retries once. A 400
   (the whole batch is invalid) is bisected: each half is tried on its own,
   down to the single fix that is at fault, which is dropped and counted on
   the Main screen; every other fix still goes, in order.

Queued fixes keep their original `device_time`. The queue is persistent
across app and device restarts; it is only forgotten when the server address
is changed (those fixes belonged to the old server).

`SyncScheduler` runs the engine right after every new fix (in the app
scope), schedules a WorkManager retry with a network constraint and backoff
when a run does not finish, and keeps a periodic WorkManager job every 15
minutes (WorkManager's minimum) as a safety net. WorkManager is initialised
on demand by `TrackerApp` so the workers can reach the object graph.

### OsmAnd integration

OsmAnd is driven over its AIDL API (`net.osmand.aidlapi`), approach 1 of the
spec; the GPX and in-app-map fallbacks were not needed.

**What is used.** `osmand/OsmAndBridge` binds to the service with action
`net.osmand.aidl.OsmandAidlServiceV2` of the first installed package among
`net.osmand.plus`, `net.osmand`, `net.osmand.dev` (declared in the
manifest's `<queries>`), lazily on the first push and again after a
disconnect (`onServiceDisconnected`, `onBindingDied`), waiting up to 8 s for
OsmAnd to start. On every poll (`Graph.refreshEntities`, from the service's
poll loop every *poll interval*, default 1 min, and from the Refresh / Push
now buttons) it calls:

- `IOsmAndAidlInterface.addMapLayer(AddMapLayerParams(AMapLayer))` with a
  layer whose id is `tracker` and name "Tracker", carrying one `AMapPoint`
  per entity that has a fix. In OsmAnd this call is an upsert: a new layer
  is created, an existing one takes the points (merged by id) and the map
  is reloaded, which is what gives in-place updates without a separate
  `updateMapLayer`/`updateMapPoint` call per point.
- `IOsmAndAidlInterface.removeMapPoint(RemoveMapPointParams(layerId, pointId))`
  for every point shown by the previous push that is no longer in the list
  (the server never deletes entities, so this is only defensive).

Each `AMapPoint` has the entity's display name as both `shortName` (the
label OsmAnd draws next to the point) and `fullName` (the context menu
title), `"<source> · <age>"` as `typeName` (the context menu subtitle), a
colour (blue for this phone, green for others, grey when the fix is older
than 30 minutes), a `details` list shown in the context menu — last fix
time and age, accuracy, altitude, speed, heading, battery, source — and a
`params` map with the same values under plain keys (`last_fix`, `age`,
`accuracy_m`, `altitude_m`, `speed_mps`, `heading_deg`, `battery_pct`,
`device_time`, `entity_id`, `source`) plus OsmAnd's own
`point_speed_param`, `point_bearing_param` (used for the direction arrow)
and `point_stale_loc_param` (drawn as stale). `osmand/PointFormat` builds
these from the server's entities and is unit tested.

**Known limitations / untested on a device.** No phone was attached while
this was written, so the binding and the drawing have not been exercised:

- OsmAnd gates AIDL calls per connected app: the app must be enabled under
  OsmAnd → Plugins → "Connected apps" (OsmAnd adds it on first contact,
  enabled, and the map-layer calls belong to the "map" permission group
  that is granted by default). When OsmAnd answers `false` the Main screen
  says so.
- The layer lives in OsmAnd's memory for the connected app: after OsmAnd is
  killed the layer is gone until the next push re-creates it, and after our
  service is killed OsmAnd keeps showing the last points (the bridge unbinds
  in `onDestroy`; OsmAnd may clear the layer then, or not).
- `addMapLayer` merges points; it never removes them, hence the explicit
  `removeMapPoint` pass.
- The layer can be hidden by the user in OsmAnd's Configure map (under the
  connected app's name) and OsmAnd's own stale/direction rendering depends
  on its version.
- The OsmAnd packages are declared in `<queries>`; on a phone with none of
  them the Main screen shows "Not installed" and tracking carries on.

**Transaction codes.** The AIDL compiler numbers binder transactions by
method position, so `IOsmAndAidlInterface.aidl` must be kept verbatim and
complete even though only three of its methods are used; that is why the
whole `net.osmand.aidlapi` package (every `parcelable` declaration and its
Java class) is in the tree rather than just the few types needed.

NOTICE: `app/src/main/aidl/net/osmand/aidlapi/**` and
`app/src/main/java/net/osmand/aidlapi/**` are copied unmodified from the
`OsmAnd-api` module of https://github.com/osmandapp/OsmAnd (published by
OsmAnd BV as `net.osmand:android-aidl-lib` for third-party apps and
documented in https://github.com/osmandapp/osmand-api-demo, MIT). The
OsmAnd repository's LICENSE covers its code under GPLv3; the files carry no
per-file header. Keep this notice when redistributing.

### Screens

- **Setup**: server address (host:port, http:// assumed), a reachability
  probe against it, and the display name. Both are changeable in Settings.
  "Use anyway" accepts an unreachable address (fixes queue until it can be
  reached).
- **Main**: a permissions card while anything is missing (with why each is
  needed); the tracking card — tracking / paused / stopped with the reason,
  the provider, last fix (age, reason, accuracy), queue length, sync state,
  last sync, the device time of the last fix the server acknowledged, sync
  errors, the pause switch and "Sync now"; the OsmAnd card — connection
  state, last push, errors, "Push now" and "Open OsmAnd"; the entity list —
  every entity (stale ones included, this phone marked "(you)") with
  accuracy, speed, battery and source, and the age of its last fix (green
  when fresh, orange when stale), with Refresh and the time of the last
  poll.
- **Settings**: server (with Change… back to Setup), display name (shows
  the entity id that stays), update interval, minimum distance, heartbeat,
  GPS only, poll interval, about with the version.

### Layout of the code

    app/src/main/java/io/github/lopezec007/tracker/
      TrackerApp.kt            Application: builds the dependency graph (Graph), refreshEntities()
      MainActivity.kt          single activity, Compose content, starts the service
      data/api/                TrackerApi (OkHttp + kotlinx.serialization), wire DTOs
      data/db/                 Room database: the pending_fixes queue
      data/sync/               SyncEngine (the queue), SyncScheduler + SyncWorker (WorkManager),
                               EntityPoller (GET /api/entities)
      location/                TrackerService (foreground service), Throttle (pure), TrackingNotifier,
                               BootReceiver, Permissions
      osmand/                  OsmAndBridge (AIDL binding and layer pushes), PointFormat (pure)
      prefs/                   DataStore: server URL, name, entity id, intervals, paused, GPS only
      util/                    Ages: RFC 3339 and "3 min ago"
      ui/theme/                Material 3 theme (dynamic colour off; follows system light/dark)
      ui/nav/                  Screen stack (no Navigation library)
      ui/setup/  ui/main/  ui/settings/
    app/src/main/aidl/net/osmand/aidlapi/     OsmAnd's AIDL interface and parcelable declarations
    app/src/main/java/net/osmand/aidlapi/     OsmAnd's Parcelable classes (see NOTICE above)
    app/src/test/java/...                     JUnit 4 tests: SyncEngineTest (Robolectric + Room +
                                              MockWebServer), TrackerApiTest (MockWebServer),
                                              ThrottleTest, AgesTest, PointFormatTest (pure)

### Conventions

- One `Graph` object built in `TrackerApp` holds the singletons; the
  ViewModel and the service take what they need from it. No DI framework.
- Times are epoch millis in memory and in Room, RFC 3339 UTC strings on the
  wire (`Ages.formatRfc3339` truncates to whole seconds). "Now" for ages is
  the phone's clock against the server's `received_at`.
- Intervals are stored in seconds; the UI shows seconds with the equivalent
  minutes as a hint.
- The server's entity id is a string (`^[A-Za-z0-9._-]{1,64}$`); this
  phone's is a UUID. Names are trimmed to 1..64 characters before they are
  sent.
- Everything that can be tested without Android is a plain Kotlin class
  (`Throttle`, `PointFormat`, `Ages`, `SyncEngine` given a database).
