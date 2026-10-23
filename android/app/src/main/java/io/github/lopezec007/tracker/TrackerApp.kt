// Application: owns the object graph (no DI framework) and configures
// WorkManager by hand so workers can reach the graph.
package io.github.lopezec007.tracker

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import io.github.lopezec007.tracker.data.api.TrackerApi
import io.github.lopezec007.tracker.data.db.TrackerDatabase
import io.github.lopezec007.tracker.data.sync.EntityPoller
import io.github.lopezec007.tracker.data.sync.Identity
import io.github.lopezec007.tracker.data.sync.SyncEngine
import io.github.lopezec007.tracker.data.sync.SyncScheduler
import io.github.lopezec007.tracker.location.ServiceStatus
import io.github.lopezec007.tracker.location.TrackingNotifier
import io.github.lopezec007.tracker.osmand.OsmAndBridge
import io.github.lopezec007.tracker.osmand.PointFormat
import io.github.lopezec007.tracker.prefs.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class Graph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = Prefs(app)
    val db = TrackerDatabase.build(app)

    /** The API for the configured server; rebuilt when the address changes. */
    @Volatile private var api: TrackerApi? = null
    @Volatile private var apiUrl: String = ""

    fun api(): TrackerApi? = api

    private fun setServer(url: String) {
        if (url == apiUrl) return
        apiUrl = url
        api = TrackerApi.parseBaseUrl(url)?.let { TrackerApi(it) }
    }

    val syncEngine = SyncEngine(db, { api }, { identity() })
    val scheduler = SyncScheduler(app, syncEngine, scope)
    val poller = EntityPoller({ api })
    val osmand = OsmAndBridge(app)
    val notifier = TrackingNotifier(app)

    /** What the location service reports about itself (it is the only writer). */
    val serviceStatus: StateFlow<ServiceStatus> get() = _serviceStatus
    internal val _serviceStatus = MutableStateFlow(ServiceStatus())

    /** This phone's identity for the server, or null until setup is done. */
    suspend fun identity(): Identity? {
        val p = prefs.current()
        if (!p.isConfigured || p.entityId.isBlank()) return null
        return Identity(p.entityId, p.name)
    }

    /** One poll of the server's entities followed by a push into OsmAnd; the service loop and the Refresh button. */
    suspend fun refreshEntities(): Boolean {
        val polled = poller.poll()
        val myId = prefs.current().entityId
        osmand.push(PointFormat.points(poller.state.value.entities, myId, System.currentTimeMillis()))
        return polled
    }

    init {
        scope.launch { prefs.ensureEntityId() }
        scope.launch {
            prefs.flow.map { it.serverUrl }.distinctUntilChanged().collect { url ->
                setServer(url)
                if (url.isNotBlank()) {
                    scheduler.ensurePeriodic()
                    scheduler.requestNow()
                }
            }
        }
        scope.launch {
            // A rename goes to the server at once (every sync run PUTs the entity).
            prefs.flow.map { it.name }.distinctUntilChanged().drop(1).collect { scheduler.requestSoon() }
        }
        scope.launch {
            syncEngine.state.map { it.lastSuccess }.filterNotNull().distinctUntilChanged().collect { prefs.setLastSyncAt(it) }
        }
        scope.launch { syncEngine.refreshPending() }
    }
}

class TrackerApp : Application(), Configuration.Provider {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        graph.notifier.ensureChannel()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()

    companion object {
        fun graph(context: Context): Graph = (context.applicationContext as TrackerApp).graph
    }
}
