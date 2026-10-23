// The one ViewModel shared by every screen: the preferences, the service,
// sync, poll and OsmAnd states as StateFlows, plus the preference setters.
package io.github.lopezec007.tracker.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.lopezec007.tracker.Graph
import io.github.lopezec007.tracker.data.sync.PollState
import io.github.lopezec007.tracker.data.sync.SyncState
import io.github.lopezec007.tracker.location.ServiceStatus
import io.github.lopezec007.tracker.location.TrackerService
import io.github.lopezec007.tracker.osmand.OsmAndState
import io.github.lopezec007.tracker.prefs.AppPrefs
import io.github.lopezec007.tracker.prefs.Prefs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    // ---- preference setters

    /** Points the app at a server. A changed address drops the queue (those fixes belonged to the old server). */
    fun setServer(url: String) = viewModelScope.launch {
        val before = prefsStore.current().serverUrl
        if (before.isNotBlank() && before != url) {
            graph.syncEngine.reset()
            graph.poller.reset()
        }
        prefsStore.setServer(url)
    }

    fun setName(name: String) = viewModelScope.launch { prefsStore.setName(name) }
    fun setUpdateIntervalSec(s: Int) = viewModelScope.launch { prefsStore.setUpdateIntervalSec(s) }
    fun setMinDistanceM(m: Int) = viewModelScope.launch { prefsStore.setMinDistanceM(m) }
    fun setHeartbeatSec(s: Int) = viewModelScope.launch { prefsStore.setHeartbeatSec(s) }
    fun setPollSec(s: Int) = viewModelScope.launch { prefsStore.setPollSec(s) }
    fun setPaused(paused: Boolean) = viewModelScope.launch { prefsStore.setPaused(paused) }
    fun setGpsOnly(gpsOnly: Boolean) = viewModelScope.launch { prefsStore.setGpsOnly(gpsOnly) }

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

    class Factory(private val graph: Graph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TrackerViewModel(graph) as T
    }
}
