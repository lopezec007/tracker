// The foreground service that does the work: collects fixes from the
// platform LocationManager, queues them in Room, triggers a sync after
// each, polls the server's entities and pushes them into OsmAnd. It runs
// as long as the app is configured (started by MainActivity and by
// BootReceiver) and shows its state in a persistent notification.
package io.github.lopezec007.tracker.location

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import io.github.lopezec007.tracker.Graph
import io.github.lopezec007.tracker.TrackerApp
import io.github.lopezec007.tracker.data.db.PendingFixEntity
import io.github.lopezec007.tracker.prefs.AppPrefs
import io.github.lopezec007.tracker.util.Ages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the service reports (Graph.serviceStatus). */
data class ServiceStatus(
    val running: Boolean = false,
    /** The provider fixes come from (LocationManager.FUSED_PROVIDER or GPS_PROVIDER). */
    val provider: String? = null,
    val lastFixAt: Long? = null,
    val lastFixReason: FixReason? = null,
    val lastLat: Double? = null,
    val lastLon: Double? = null,
    val lastAccuracyM: Double? = null,
    /** Why collection is not happening (permission missing, no provider); the notification shows it. */
    val error: String? = null,
)

class TrackerService : Service() {
    private lateinit var graph: Graph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private val batteryManager by lazy { getSystemService(BatteryManager::class.java) }

    private val throttle = Throttle(ThrottleSettings(120_000, 25.0, 900_000))
    private val recordMutex = Mutex()

    @Volatile private var listener: LocationListener? = null
    @Volatile private var lastLocation: Location? = null
    @Volatile private var paused = false
    @Volatile private var pending = 0

    private fun status() = graph._serviceStatus
    private fun update(f: (ServiceStatus) -> ServiceStatus) {
        status().value = f(status().value)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        graph = TrackerApp.graph(this)
        graph.notifier.ensureChannel()
        update { it.copy(running = true, error = null) }
        if (!goForeground()) return

        // Collection follows the preferences: intervals, provider, pause.
        scope.launch {
            graph.prefs.flow
                .map { CollectionSettings(it.updateIntervalSec, it.minDistanceM, it.heartbeatSec, it.paused, it.gpsOnly) }
                .distinctUntilChanged()
                .collect { c ->
                    throttle.settings = ThrottleSettings(c.intervalSec * 1000L, c.minDistanceM.toDouble(), c.heartbeatSec * 1000L)
                    paused = c.paused
                    if (c.paused) stopUpdates() else startUpdates(c)
                    refreshNotification()
                }
        }
        // The heartbeat ticker (also keeps the "x min ago" of the notification fresh).
        scope.launch {
            while (true) {
                delay(TICK_MS)
                if (!paused && listener != null && throttle.heartbeatDue(System.currentTimeMillis())) {
                    // The newest location anyone has: ours, or what the platform remembers.
                    val loc = listOfNotNull(lastLocation, lastKnown()).maxByOrNull { it.time }
                    if (loc != null) consider(loc, fromTicker = true)
                }
                refreshNotification()
            }
        }
        // The entity poll + OsmAnd push loop.
        scope.launch {
            while (true) {
                val p = graph.prefs.current()
                if (p.isConfigured) graph.refreshEntities()
                delay(p.pollSec * 1000L)
            }
        }
        // The notification mirrors the queue and the sync phase.
        scope.launch {
            combine(graph.db.pendingFixes().observeCount(), graph.syncEngine.state.map { it.phase }) { n, phase -> n to phase }
                .distinctUntilChanged()
                .collect { (n, _) ->
                    pending = n
                    refreshNotification()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> scope.launch { graph.prefs.setPaused(true) }
            ACTION_RESUME -> scope.launch { graph.prefs.setPaused(false) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopUpdates()
        scope.cancel()
        graph.osmand.unbind()
        update { it.copy(running = false, provider = null) }
        super.onDestroy()
    }

    /**
     * Promotes the service with the location type. Without the location
     * permission the system refuses, which ends the service with the reason
     * in the status (Main shows it and asks for the permission).
     */
    private fun goForeground(): Boolean {
        if (!Permissions.hasLocation(this)) {
            update { it.copy(running = false, error = "location permission not granted") }
            stopSelf()
            return false
        }
        return try {
            startForeground(TrackingNotifier.NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            true
        } catch (e: Exception) {
            // SecurityException (permission) or a start-not-allowed exception.
            update { it.copy(running = false, error = "cannot run in the foreground: ${e.message}") }
            stopSelf()
            false
        }
    }

    private fun buildNotification() =
        graph.notifier.build(status().value, paused, pending, graph.syncEngine.state.value.phase)

    private fun refreshNotification() {
        if (!status().value.running) return
        try {
            startForeground(TrackingNotifier.NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (_: Exception) {
            // Already stopping; nothing to show.
        }
    }

    // ---------------------------------------------------------------- location

    private data class CollectionSettings(val intervalSec: Int, val minDistanceM: Int, val heartbeatSec: Int, val paused: Boolean, val gpsOnly: Boolean)

    /** The platform's fused provider when it exists and is allowed, else GPS; null when the phone has neither. */
    private fun pickProvider(gpsOnly: Boolean): String? {
        val lm = locationManager
        val providers = lm.allProviders
        if (!gpsOnly && LocationManager.FUSED_PROVIDER in providers && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
            return LocationManager.FUSED_PROVIDER
        }
        if (LocationManager.GPS_PROVIDER in providers) return LocationManager.GPS_PROVIDER
        return null
    }

    private fun startUpdates(c: CollectionSettings) {
        stopUpdates()
        if (!Permissions.hasLocation(this)) {
            update { it.copy(error = "location permission not granted") }
            return
        }
        val provider = pickProvider(c.gpsOnly)
        if (provider == null) {
            update { it.copy(provider = null, error = "no location provider on this phone") }
            return
        }
        val request = LocationRequest.Builder(c.intervalSec * 1000L)
            .setMinUpdateDistanceMeters(c.minDistanceM.toFloat())
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .build()
        val l = LocationListener { loc -> consider(loc, fromTicker = false) }
        try {
            locationManager.requestLocationUpdates(provider, request, ContextCompat.getMainExecutor(this), l)
        } catch (e: SecurityException) {
            update { it.copy(error = "location permission not granted") }
            return
        }
        listener = l
        update { it.copy(provider = provider, error = null) }
        // Whatever the platform remembers gives a first fix at once.
        lastKnown()?.let { consider(it, fromTicker = true) }
    }

    private fun stopUpdates() {
        listener?.let { locationManager.removeUpdates(it) }
        listener = null
    }

    private fun lastKnown(): Location? = try {
        val lm = locationManager
        listOfNotNull(
            status().value.provider?.let { lm.getLastKnownLocation(it) },
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER),
        ).maxByOrNull { it.time }
    } catch (_: SecurityException) {
        null
    }

    /** Runs the throttle on [loc] and records a fix when it says so. */
    private fun consider(loc: Location, fromTicker: Boolean) {
        if (!fromTicker) lastLocation = loc
        if (paused) return
        val now = System.currentTimeMillis()
        if (throttle.decide(loc.latitude, loc.longitude, now) == null) return
        scope.launch { record(loc, now) }
    }

    private suspend fun record(loc: Location, now: Long) = recordMutex.withLock {
        // Decided under the lock: a ticker and a listener update can race.
        val reason = throttle.decide(loc.latitude, loc.longitude, now) ?: return
        val reused = reason == FixReason.HEARTBEAT || now - loc.time > REUSE_AFTER_MS
        val metadata = buildJsonObject {
            put("provider", loc.provider ?: "unknown")
            put("reason", reason.wire)
            if (reused) put("location_time", Ages.formatRfc3339(loc.time))
            if (loc.isMock) put("mock", true)
        }
        val fix = PendingFixEntity(
            // A reused location is a statement "still here at <now>"; a fresh one carries its own time.
            deviceTime = if (reused || loc.time <= 0) now else loc.time,
            lat = loc.latitude,
            lon = loc.longitude,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
            altitudeM = if (loc.hasAltitude()) loc.altitude else null,
            speedMps = if (loc.hasSpeed()) loc.speed.toDouble().coerceAtLeast(0.0) else null,
            headingDeg = if (loc.hasBearing()) ((loc.bearing.toDouble() % 360) + 360) % 360 else null,
            batteryPct = batteryPct(),
            metadata = metadata.toString(),
        )
        graph.db.pendingFixes().insert(fix)
        throttle.accept(loc.latitude, loc.longitude, now)
        update {
            it.copy(
                lastFixAt = now, lastFixReason = reason,
                lastLat = loc.latitude, lastLon = loc.longitude, lastAccuracyM = fix.accuracyM,
            )
        }
        graph.prefs.setLastFixAt(now)
        graph.scheduler.requestNow()
    }

    private fun batteryPct(): Int? {
        val v = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return null
        return if (v in 0..100) v else null
    }

    companion object {
        const val ACTION_PAUSE = "io.github.lopezec007.tracker.PAUSE"
        const val ACTION_RESUME = "io.github.lopezec007.tracker.RESUME"

        /** How often the ticker looks at the heartbeat and refreshes the notification. */
        const val TICK_MS = 30_000L

        /** A location older than this is treated as reused (device_time = now, location_time in metadata). */
        const val REUSE_AFTER_MS = 60_000L

        /** Starts (or pokes) the service; a no-op in effect when it is already running. */
        fun start(context: android.content.Context) {
            if (!Permissions.hasLocation(context)) return
            ContextCompat.startForegroundService(context, Intent(context, TrackerService::class.java))
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, TrackerService::class.java))
        }

        /** Whether the service should run at all: set up, and allowed to collect. */
        fun shouldRun(context: android.content.Context, prefs: AppPrefs): Boolean =
            prefs.isConfigured && Permissions.hasLocation(context)
    }
}
