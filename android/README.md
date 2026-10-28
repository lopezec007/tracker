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
    ./gradlew test                     # JVM unit tests (queue/sync, server identity, API client, history paging,
                                       #   GPX building, throttle, ages, OsmAnd points and appearance)
    ./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk (R8, signed when the key exists)
    ./gradlew installDebug             # onto the device on adb

Release builds are signed with the key named in `android/keystore.properties`
(gitignored; see `keystore.properties.example`). Without that file
`assembleRelease` produces an unsigned APK. The root `Makefile` wraps these
as `make android`, `android-test`, `android-release` (copies the signed APK
to `dist/`) and `android-install`.

## Design

### Server contract

The app uses five endpoints of the server's JSON API (see "API" in the
top-level README); timestamps are RFC 3339 UTC strings, errors are
`{"error": "..."}`:

- `GET /api/version` → `{"version", "date", "build", "app": "tracker",
  "server_id": "<uuid>"}` — the reachability *and identity* probe (short
  timeouts) before anything else is sent, and what Setup shows as "Tracker
  x.y.z found". `app` must be exactly `tracker` and `server_id` non-blank
  (it is stable for the life of that server's database); an answer without
  them — a tracker server older than 0.1.0, or any other web server at
  that address — counts as *unavailable* ("no tracker server at this
  address (… must be upgraded)") and nothing else is sent. See "Server
  identity" below.
- `PUT /api/entities/{id}` `{"name", "source": "phone"}` — at the start of
  every sync run. Cheap and idempotent: the server learns the current name
  and the entity exists before its fixes arrive. `id` is a UUID generated
  once on the phone (`Prefs.ensureEntityId`) and never changed; renaming
  keeps it, so the same entity is renamed everywhere. The routine PUT
  deliberately carries **no** `icon`/`color`: a field that is absent leaves
  the server's value unchanged, an empty string clears it. A deliberate
  icon change PUTs `{"icon", "color"}` (plus `name`/`source` for this
  phone's own entity) to that entity's id (`TrackerApi.putAppearance`).
  The server validates `icon` against `^[a-z0-9_]{0,64}$` and `color`
  against `""` or `^#[0-9a-fA-F]{6}$` (400 otherwise); the app checks the
  same rules first (`EntityAppearance`).
- `POST /api/entities/{id}/fixes` — a JSON array of up to 500 fixes, in
  order. 201 acknowledges the whole batch; 400 refuses the whole batch; 404
  means the entity is unknown (the server's database was reset), in which
  case the entity is PUT again and the batch retried once.
- `GET /api/entities` — every entity with its latest fix, `icon` and
  `color` (always present, possibly `""`), for the Main screen and for
  OsmAnd. Ages are measured from the server's `received_at`
  (authoritative), falling back to `device_time`.
- `GET /api/entities/{id}/fixes?from=<rfc3339>&to=<rfc3339>&limit=<1..10000>`
  — an entity's history, ordered by `received_at` ascending, for "History"
  (below). Fetched a page of 10 000 at a time: a full page means there may
  be more, and the next page starts `from` the last fix's `received_at`;
  fixes that come back again (an inclusive `from`) are dropped by id. At
  most 100 000 fixes are fetched; the user is told when that cap was hit
  (`data/sync/History.kt`, unit tested).

### Server identity

The phone may roam onto another LAN where the server's address belongs to
a different machine; nothing must ever be sent there. Every request is
therefore gated (`data/sync/ServerGate`) by the probe above and the id it
returns, compared in the pure `ServerIdentity`:

- On the **first successful probe** after the server address was set
  (Setup's "Finish" pins the id its own probe saw; "Use anyway" pins
  nothing and the first successful sync does), `server_id` is stored in
  Prefs as the pinned id. Settings shows it under the server address.
- On every later probe a **different** `server_id` means a different
  server: nothing is PUT or POSTed, the entities are not polled, the
  history is not fetched; the queue is kept. The sync state goes to
  `SyncPhase.WRONG_SERVER` with "a different server answered at <addr> (id
  …); fixes are kept until the right one is back", shown as a red card on
  Main, in the Sync row, and in the notification. Back on the right
  network the next probe matches and everything resumes by itself.
- An answer without `app`/`server_id` is `SyncResult.Offline` (the server
  is "not available"), never pinned, never matched.
- The gate reuses a probe result for 5 s and keys it by the API instance,
  so a sync followed by a poll probes once, and a changed address or a
  forgotten pin probes afresh (`invalidate`).
- Changing the server address in Settings clears the pin (and the queue,
  as before). **Forget server identity** in Settings drops the pin on
  purpose (the user rebuilt the server's database): the next successful
  probe pins the new id.

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
a short timeout through the identity gate. If the server is not reachable
(no network, or the tunnel is down), is not a tracker server, or is not the
pinned one, nothing else is sent and the queue waits. WorkManager retries
when connectivity changes.

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
2. probes `/api/version` through `ServerGate` (an `IOException` or a
   non-tracker answer means offline, a foreign `server_id` means
   `WRONG_SERVER`: either way the queue waits and the run ends here);
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
when a run does not finish (offline, failed, or wrong server), and keeps a
periodic WorkManager job every 15 minutes (WorkManager's minimum) as a
safety net. WorkManager is initialised
on demand by `TrackerApp` so the workers can reach the object graph.

### OsmAnd integration

OsmAnd is driven over its AIDL API (`net.osmand.aidlapi`), approach 1 of the
spec; the in-app-map fallback was not needed (GPX is used only for history).

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
colour (the entity's own `color` when it has one, else blue for this
phone, green for others, grey when the fix is older than 30 minutes), a
`details` list shown in the context menu — last fix time and age,
accuracy, altitude, speed, heading, battery, source — and a `params` map
with the same values under plain keys (`last_fix`, `age`, `accuracy_m`,
`altitude_m`, `speed_mps`, `heading_deg`, `battery_pct`, `device_time`,
`entity_id`, `source`) plus OsmAnd's own `point_speed_param`,
`point_bearing_param` (used for the direction arrow),
`point_stale_loc_param` (drawn as stale; set even when the entity has its
own colour) and, when the entity has an icon, `point_type_icon_name_param`
(see "Custom icons"). `osmand/PointFormat` builds these from the server's
entities and is unit tested.

**Go to / Show on map.** Each entity's menu on Main (⋮) offers, through
the same bridge (bound lazily, as for pushes):

- *Go to*: `navigate(NavigateParams(startName = "", startLat = 0,
  startLon = 0, destName = <entity name>, destLat, destLon, profile,
  force = true, needLocationPermission = false))`. OsmAnd's receiver turns
  a 0/0 start into a null start, which its `NavigateGpxHelper.startNavigation`
  treats as "from my position"; `force` skips the "stop the current
  navigation?" question. `profile` is the Settings choice: `car`
  (default), `bicycle` or `pedestrian` — OsmAnd's own profile names.
- *Show on map*: `showMapPoint(ShowMapPointParams("tracker", <the
  entity's AMapPoint>))`, which centres the map on the point and opens its
  context menu (OsmAnd swaps in its own copy of the point when the layer
  already holds that id).

After either call the app brings OsmAnd to the front with its launch
intent. A refused call (`false`) or a missing OsmAnd is reported in a
snackbar.

**Custom icons.** Entities carry an `icon` (an OsmAnd drawable name, `""`
= default) and a `color` (`#rrggbb`, `""` = default). Settings → "My icon"
sets this phone's own; the entity menu's *Edit icon…* sets any entity's
(there is no authentication; a household can fix each other's icons).
Both open the same picker (`ui/common/IconPickerDialog`): a curated grid,
a free-text field for any other OsmAnd drawable name, and a palette of 11
colours plus default. The change is PUT at once (online only — the gate
must pass — otherwise a snackbar says why) and the list refreshed.

How the icon reaches the map, verified in OsmAnd's source. Two separate
mechanisms are used:

- **On the marker itself**: `AidlMapLayer` draws, for a layer with
  `imagePoints` on, each point's `POINT_IMAGE_URI_PARAM` image — loaded
  through the content resolver, circle-cropped, 40 dp at zoom 14+, 20 dp
  on a background tinted with the point colour at zooms 7–13, a plain
  coloured circle below that — and OsmAnd's generic user placeholder for a
  point without one. So `osmand/PointIcons` renders every entity's glyph
  (the same Material glyph the app's list shows, `ui/common/IconGlyphs`)
  in white on a square of the entity's colour (the crop makes it a disc),
  as a 160 px PNG in the cache directory, named by icon and colour so a
  change is a new URI (OsmAnd caches images per URI), served through the
  app's `FileProvider` (`<applicationId>.files`, `res/xml/file_paths.xml`)
  with a read grant to the OsmAnd package on every push. The bridge sets
  `layer.setImagePoints(true)` and adds the URI to each point's params; a
  rendering failure only drops the image (OsmAnd then draws its
  placeholder) and is shown as the OsmAnd card's last error.
- **In the context menu**: `AMapPointMenuController` reads
  `AMapPoint.POINT_TYPE_ICON_NAME_PARAM` and resolves it with
  `AndroidUtils.getDrawableId` = `Resources.getIdentifier(name,
  "drawable", <OsmAnd package>)` for the type icon of the point's menu, so
  the OsmAnd icon *name* is sent as well. `AidlMapLayer` itself never reads
  that param.

The curated names (`osmand/EntityAppearance.ICONS`)
all exist as `OsmAnd/res/drawable/<name>.xml` in the OsmAnd repository:
`ic_action_pedestrian_dark`, `ic_action_car_dark`,
`ic_action_bicycle_dark`, `ic_action_motorcycle_dark`,
`ic_action_scooter`, `ic_action_bus_dark`, `ic_action_truck_dark`,
`ic_action_taxi`, `ic_action_train`, `ic_action_sail_boat_dark`,
`ic_action_aircraft`, `ic_action_home_dark`, `ic_action_work`,
`ic_action_favorite`, `ic_action_flag`, `ic_action_phone`,
`ic_action_photo_dark`, `ic_action_user`. POI icons (`mx_shop_car`, …)
are generated into OsmAnd's APK at build time and should resolve the same
way through the free-text field, but were not verified. The app's own list
draws a Material glyph for each curated name (`ui/common/IconGlyphs`) in
the entity's colour; a free-text name falls back to a generic marker.

**History.** The entity menu's *History…* opens a sheet with a range (last
hour, 6 h, 24 h — the default —, 7 days, 30 days) and three actions:

- *Show in OsmAnd*: the fixes are fetched through the gate (see the
  endpoint above), built into a GPX 1.1 document by the pure, unit-tested
  `osmand/GpxBuilder` — one `<trk>` named `"<entity> <range>"`, `<trkpt
  lat lon>` with `<ele>` from `altitude_m` when present and `<time>` from
  `device_time` when present else `received_at`; a new `<trkseg>` wherever
  two consecutive fixes are more than an hour apart, so OsmAnd does not
  draw a straight line across a gap — and imported with
  `importGpx(ImportGpxParams(sourceRawData = <gpx>, destinationPath =
  "tracker-<entity id>.gpx", color = <entity colour "#rrggbb" or null>,
  show = true))`. The file lands in OsmAnd's `tracks/` folder; importing
  the same name again replaces the track. Then OsmAnd is brought to the
  front. Which histories are shown is remembered per process (the row says
  "history shown"); nothing is persisted.
- *Hide*: `hideGpx(HideGpxParams(fileName = "tracker-<id>.gpx"))` —
  OsmAnd matches a selected track whose path ends in `/<fileName>`.
- *Remove*: `removeGpx(RemoveGpxParams(fileName = "tracker-<id>.gpx"))` —
  OsmAnd deletes it only because it was imported through the API
  (`API_IMPORTED`).

Two details decided from OsmAnd's source: the file name is **flat**
(`tracker-<id>.gpx`, not a `tracker/` sub-folder) because
`importGpxFromData` opens its `FileOutputStream` before creating parent
directories, so a sub-folder fails on the first import; and the colour is
passed as the **hex** string, because `GpxAppearanceAdapter.parseTrackColor`
falls through to `GpxUtilities.parseColor`, which accepts `#rrggbb`
besides the renderer's names (`red`, `orange`, `lightblue`, … listed in the
AIDL comment), in current OsmAnd as well as in 4.0/4.4.

**Known limitations / untested on a device.** No phone was attached while
this was written, so the binding, the drawing, navigation, the marker
images, the context-menu icon and the GPX import have not been exercised:

- The marker images depend on OsmAnd being able to open our content URIs.
  The grant is per package and per URI (`grantUriPermission`, repeated on
  every push, valid until reboot); if OsmAnd shows its generic placeholder
  instead of the coloured glyph, that grant is the first thing to check.

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
- `navigate`, `showMapPoint` and `importGpx` belong to OsmAnd's
  "navigation" / "map" / "gpx" permission groups of the connected app;
  OsmAnd may ask the user once, or answer `false` until enabled.

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
  probe against it (which must answer like a tracker server with an
  identity; "Finish" pins it), and the display name. Both are changeable in
  Settings. "Use anyway" accepts an unreachable address (fixes queue until
  it can be reached; the first successful probe pins).
- **Main**: a permissions card while anything is missing (with why each is
  needed); the tracking card — tracking / paused / stopped with the reason,
  the provider, last fix (age, reason, accuracy), queue length, sync state,
  last sync, the device time of the last fix the server acknowledged, sync
  errors, the pause switch and "Sync now"; a red "Different server" card
  whenever the gate refuses the address; the OsmAnd card — connection
  state, last push, errors, "Push now" and "Open OsmAnd"; the entity list —
  every entity (stale ones included, this phone marked "(you)") with its
  icon in its colour, accuracy, speed, battery and source, the age of its
  last fix (green when fresh, orange when stale), and a ⋮ menu with Show
  on map, Go to, History… and Edit icon…, with Refresh and the time of the
  last poll. Outcomes of the actions appear in a snackbar.
- **Settings**: server (with Change… back to Setup) and its pinned
  identity (with Forget), display name (shows the entity id that stays),
  my icon and colour, update interval, minimum distance, heartbeat, GPS
  only, poll interval, the OsmAnd routing profile for "Go to", about with
  the version.

### Layout of the code

    app/src/main/java/io/github/lopezec007/tracker/
      TrackerApp.kt            Application: builds the dependency graph (Graph), refreshEntities()
      MainActivity.kt          single activity, Compose content, starts the service
      data/api/                TrackerApi (OkHttp + kotlinx.serialization), wire DTOs
      data/db/                 Room database: the pending_fixes queue
      data/sync/               ServerIdentity (pure: app/server_id check), ServerGate (probe + pin,
                               gates every request), SyncEngine (the queue), SyncScheduler +
                               SyncWorker (WorkManager), EntityPoller (GET /api/entities),
                               History (ranges, paging over GET /fixes; pure)
      location/                TrackerService (foreground service), Throttle (pure), TrackingNotifier,
                               BootReceiver, Permissions
      osmand/                  OsmAndBridge (AIDL binding: layer pushes, navigate, showMapPoint,
                               importGpx/hideGpx/removeGpx), PointFormat (pure), PointIcons (marker
                               images for OsmAnd), EntityAppearance (curated icons, palette,
                               validation; pure), GpxBuilder (pure)
      prefs/                   DataStore: server URL + pinned server id, name, entity id, intervals,
                               paused, GPS only, routing profile
      util/                    Ages: RFC 3339 and "3 min ago"
      ui/theme/                Material 3 theme (dynamic colour off; follows system light/dark)
      ui/nav/                  Screen stack (no Navigation library)
      ui/common/               IconGlyphs (OsmAnd icon name → Material glyph), IconPickerDialog
      ui/setup/  ui/main/  ui/settings/   (ui/main also holds HistorySheet)
    app/src/main/aidl/net/osmand/aidlapi/     OsmAnd's AIDL interface and parcelable declarations
    app/src/main/java/net/osmand/aidlapi/     OsmAnd's Parcelable classes (see NOTICE above)
    app/src/test/java/...                     JUnit 4 tests: SyncEngineTest (Robolectric + Room +
                                              MockWebServer; includes the identity cases),
                                              ServerGateTest, EntityPollerTest, HistoryPagerTest,
                                              TrackerApiTest (MockWebServer), ServerIdentityTest,
                                              GpxBuilderTest, EntityAppearanceTest, ThrottleTest,
                                              AgesTest, PointFormatTest (pure)

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
  (`Throttle`, `PointFormat`, `Ages`, `ServerIdentity`, `ServerGate`,
  `HistoryPager`, `GpxBuilder`, `EntityAppearance`, `SyncEngine` given a
  database).
- Everything new since 0.0.1 lives in Prefs or in memory; the Room schema
  stays at version 1.
