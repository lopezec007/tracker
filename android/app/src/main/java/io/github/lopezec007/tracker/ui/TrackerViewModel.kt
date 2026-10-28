// The one ViewModel shared by every screen: the preferences, the service,
// sync, poll and OsmAnd states as StateFlows, the preference setters, and
// the per-entity actions (go to, show on map, icon, history) with their
// outcome as a transient message.
package io.github.lopezec007.tracker.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.lopezec007.tracker.Graph
import io.github.lopezec007.tracker.data.api.ApiException
import io.github.lopezec007.tracker.data.api.EntityDto
import io.github.lopezec007.tracker.data.api.TrackerApi
import io.github.lopezec007.tracker.data.sync.GateResult
import io.github.lopezec007.tracker.data.sync.HistoryPager
import io.github.lopezec007.tracker.data.sync.HistoryRange
import io.github.lopezec007.tracker.data.sync.PollState
import io.github.lopezec007.tracker.data.sync.SyncState
import io.github.lopezec007.tracker.location.ServiceStatus
import io.github.lopezec007.tracker.location.TrackerService
import io.github.lopezec007.tracker.osmand.EntityAppearance
import io.github.lopezec007.tracker.osmand.GpxBuilder
import io.github.lopezec007.tracker.osmand.OsmAndState
import io.github.lopezec007.tracker.osmand.PointFormat
import io.github.lopezec007.tracker.prefs.AppPrefs
import io.github.lopezec007.tracker.prefs.Prefs
import io.github.lopezec007.tracker.util.Ages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

class TrackerViewModel(val graph: Graph) : ViewModel() {
    private val prefsStore: Prefs get() = graph.prefs

    /** Null until DataStore has been read once (so Setup is not flashed on a configured app). */
    val prefs: StateFlow<AppPrefs?> = prefsStore.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val sync: StateFlow<SyncState> = graph.syncEngine.state
    val poll: StateFlow<PollState> = graph.poller.state
    val osmand: StateFlow<OsmAndState> = graph.osmand.state
    val service: StateFlow<ServiceStatus> = graph.serviceStatus

    val pendingCount: StateFlow<Int> = graph.db.pendingFixes().observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** The outcome of the last action, for a snackbar; cleared by [clearMessage]. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** An action that talks to the server or OsmAnd is in progress (history fetch, icon change). */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** Entity ids whose history track is currently shown in OsmAnd (this process only). */
    private val _historyShown = MutableStateFlow<Set<String>>(emptySet())
    val historyShown: StateFlow<Set<String>> = _historyShown

    fun clearMessage() {
        _message.value = null
    }

    private fun say(text: String) {
        _message.value = text
    }

    // ---- preference setters

    /**
     * Points the app at a server and pins [serverId] as its identity ("" when
     * it was not reached: the first successful probe pins). A changed address
     * drops the queue (those fixes belonged to the old server).
     */
    fun setServer(url: String, serverId: String = "") = viewModelScope.launch {
        val before = prefsStore.current().serverUrl
        if (before.isNotBlank() && before != url) {
            graph.syncEngine.reset()
            graph.poller.reset()
        }
        prefsStore.setServer(url, serverId)
        graph.gate.invalidate()
    }

    /** Drops the pinned server id (the user rebuilt the server's database on purpose); the next successful probe pins again. */
    fun forgetServerIdentity() = viewModelScope.launch {
        prefsStore.setPinnedServerId("")
        graph.gate.invalidate()
        graph.scheduler.requestNow()
    }

    fun setName(name: String) = viewModelScope.launch { prefsStore.setName(name) }
    fun setUpdateIntervalSec(s: Int) = viewModelScope.launch { prefsStore.setUpdateIntervalSec(s) }
    fun setMinDistanceM(m: Int) = viewModelScope.launch { prefsStore.setMinDistanceM(m) }
    fun setHeartbeatSec(s: Int) = viewModelScope.launch { prefsStore.setHeartbeatSec(s) }
    fun setPollSec(s: Int) = viewModelScope.launch { prefsStore.setPollSec(s) }
    fun setPaused(paused: Boolean) = viewModelScope.launch { prefsStore.setPaused(paused) }
    fun setGpsOnly(gpsOnly: Boolean) = viewModelScope.launch { prefsStore.setGpsOnly(gpsOnly) }
    fun setRoutingProfile(profile: String) = viewModelScope.launch { prefsStore.setRoutingProfile(profile) }

    // ---- actions

    fun requestSync() = graph.scheduler.requestNow()

    fun refreshEntities() = viewModelScope.launch { graph.refreshEntities() }

    fun startServiceIfAllowed(context: Context) = viewModelScope.launch {
        if (TrackerService.shouldRun(context, prefsStore.current())) TrackerService.start(context)
    }

    /** Opens OsmAnd; false when it is not installed. */
    fun openOsmAnd(context: Context): Boolean {
        val intent = graph.osmand.launchIntent() ?: return false
        context.startActivity(intent)
        return true
    }

    fun osmandInstalled(): Boolean = graph.osmand.installedPackage() != null

    private fun osmandProblem(): String = graph.osmand.state.value.lastError
        ?: if (osmandInstalled()) "OsmAnd did not answer" else "OsmAnd is not installed"

    /** Starts OsmAnd navigation from here to [e]'s latest position with the configured profile, then brings OsmAnd up. */
    fun goTo(e: EntityDto, context: Context) = viewModelScope.launch {
        val f = e.latest ?: return@launch say("${e.name.ifBlank { e.id }} has no position yet")
        val profile = prefsStore.current().routingProfile
        if (graph.osmand.navigateTo(e.name.ifBlank { e.id }, f.lat, f.lon, profile)) {
            openOsmAnd(context)
        } else {
            say("Cannot start navigation: ${osmandProblem()}")
        }
    }

    /** Centres OsmAnd on [e]'s latest position (its point on the Tracker layer), then brings OsmAnd up. */
    fun showOnMap(e: EntityDto, context: Context) = viewModelScope.launch {
        val myId = prefsStore.current().entityId
        val point = PointFormat.point(e, myId, System.currentTimeMillis())
            ?: return@launch say("${e.name.ifBlank { e.id }} has no position yet")
        if (graph.osmand.showPoint(point)) {
            openOsmAnd(context)
        } else {
            say("Cannot show on the map: ${osmandProblem()}")
        }
    }

    /** The API once the server has been reached and is the pinned one; null (with a message) otherwise. */
    private suspend fun onlineApi(what: String): TrackerApi? {
        val api = graph.api() ?: run { say("Not configured"); return null }
        return when (val g = graph.gate.check(api)) {
            is GateResult.Ok -> api
            is GateResult.Offline -> { say("$what needs the server: ${g.reason}"); null }
            is GateResult.WrongServer -> { say("$what refused: ${g.reason}"); null }
        }
    }

    /**
     * PUTs [icon] and [color] to [entityId] at once (online only). This
     * phone's own entity also sends its name; another entity only the
     * appearance, so its name stays.
     */
    fun setAppearance(entityId: String, icon: String, color: String) = viewModelScope.launch {
        if (!EntityAppearance.isValidIcon(icon) || !EntityAppearance.isValidColor(color)) return@launch say("That icon name or colour is not valid")
        _busy.value = true
        try {
            val api = onlineApi("Changing the icon") ?: return@launch
            val p = prefsStore.current()
            try {
                api.putAppearance(entityId, icon, color, name = if (entityId == p.entityId) p.name else null)
                graph.refreshEntities()
                say("Icon saved")
            } catch (e: IOException) {
                say("Server not reachable; the icon was not changed")
            } catch (e: ApiException) {
                say("The server refused the icon: ${e.message}")
            }
        } finally {
            _busy.value = false
        }
    }

    /** Fetches [e]'s fixes over [range], builds a GPX track and shows it in OsmAnd. */
    fun showHistory(e: EntityDto, range: HistoryRange, context: Context) = viewModelScope.launch {
        _busy.value = true
        try {
            val api = onlineApi("History") ?: return@launch
            val name = e.name.ifBlank { e.id }
            val now = System.currentTimeMillis()
            val result = try {
                HistoryPager.fetchAll(Ages.formatRfc3339(now - range.ms), Ages.formatRfc3339(now)) { from, to, limit ->
                    api.listFixes(e.id, from, to, limit)
                }
            } catch (ex: IOException) {
                return@launch say("Server not reachable while fetching the history")
            } catch (ex: ApiException) {
                return@launch say("The server refused the history request: ${ex.message}")
            }
            if (result.fixes.isEmpty()) return@launch say("No fixes of $name in the ${range.label}")
            val gpx = GpxBuilder.build("$name ${range.label}", result.fixes)
            if (graph.osmand.importGpx(gpx, GpxBuilder.fileName(e.id), EntityAppearance.gpxColor(e.color))) {
                _historyShown.value = _historyShown.value + e.id
                openOsmAnd(context)
                say(
                    "${result.fixes.size} fixes of $name shown in OsmAnd" +
                        if (result.capped) " (capped at ${HistoryPager.MAX_POINTS}; the oldest part of the range is missing)" else "",
                )
            } else {
                say("Cannot show the history: ${osmandProblem()}")
            }
        } finally {
            _busy.value = false
        }
    }

    /** Hides [e]'s track in OsmAnd (the file stays; "Show" again replaces it). */
    fun hideHistory(e: EntityDto) = viewModelScope.launch {
        if (graph.osmand.hideGpx(GpxBuilder.fileName(e.id))) {
            _historyShown.value = _historyShown.value - e.id
            say("History of ${e.name.ifBlank { e.id }} hidden")
        } else {
            say("Cannot hide the history: ${osmandProblem()}")
        }
    }

    /** Deletes [e]'s track file from OsmAnd. */
    fun removeHistory(e: EntityDto) = viewModelScope.launch {
        if (graph.osmand.removeGpx(GpxBuilder.fileName(e.id))) {
            _historyShown.value = _historyShown.value - e.id
            say("History of ${e.name.ifBlank { e.id }} removed from OsmAnd")
        } else {
            say("Cannot remove the history: ${osmandProblem()}")
        }
    }

    class Factory(private val graph: Graph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TrackerViewModel(graph) as T
    }
}
