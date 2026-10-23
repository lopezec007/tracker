// The phone's own settings, in DataStore: the server, the identity of this
// phone (a display name and the entity id generated once), the collection
// intervals and the pause switch.
package io.github.lopezec007.tracker.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tracker")

data class AppPrefs(
    val serverUrl: String = "",
    val name: String = "",
    /** Generated once by [Prefs.ensureEntityId]; "" only before that first run. Never changes. */
    val entityId: String = "",
    val updateIntervalSec: Int = DEFAULT_UPDATE_INTERVAL_SEC,
    val minDistanceM: Int = DEFAULT_MIN_DISTANCE_M,
    val heartbeatSec: Int = DEFAULT_HEARTBEAT_SEC,
    val pollSec: Int = DEFAULT_POLL_SEC,
    val paused: Boolean = false,
    /** Use the GPS provider even when the platform offers a fused one. */
    val gpsOnly: Boolean = false,
    val setupDone: Boolean = false,
    /** Epoch millis of the last fix recorded and of the last completed sync, kept across restarts for the status screen. */
    val lastFixAt: Long? = null,
    val lastSyncAt: Long? = null,
) {
    val isConfigured get() = serverUrl.isNotBlank() && name.isNotBlank() && setupDone

    companion object {
        const val DEFAULT_UPDATE_INTERVAL_SEC = 120
        const val DEFAULT_MIN_DISTANCE_M = 25
        const val DEFAULT_HEARTBEAT_SEC = 900
        const val DEFAULT_POLL_SEC = 60

        const val MIN_UPDATE_INTERVAL_SEC = 10
        const val MIN_HEARTBEAT_SEC = 60
        const val MIN_POLL_SEC = 15
        const val MAX_SECONDS = 24 * 3600

        /** The server's rule for a display name: trimmed, 1..64 characters. */
        const val NAME_MAX = 64

        fun cleanName(raw: String): String = raw.trim().take(NAME_MAX)
    }
}

class Prefs(private val context: Context) {
    private object K {
        val serverUrl = stringPreferencesKey("server_url")
        val name = stringPreferencesKey("name")
        val entityId = stringPreferencesKey("entity_id")
        val updateIntervalSec = intPreferencesKey("update_interval_sec")
        val minDistanceM = intPreferencesKey("min_distance_m")
        val heartbeatSec = intPreferencesKey("heartbeat_sec")
        val pollSec = intPreferencesKey("poll_sec")
        val paused = booleanPreferencesKey("paused")
        val gpsOnly = booleanPreferencesKey("gps_only")
        val setupDone = booleanPreferencesKey("setup_done")
        val lastFixAt = longPreferencesKey("last_fix_at")
        val lastSyncAt = longPreferencesKey("last_sync_at")
    }

    val flow: Flow<AppPrefs> = context.dataStore.data.map { p -> fromPrefs(p) }

    suspend fun current(): AppPrefs = flow.first()

    private fun fromPrefs(p: Preferences) = AppPrefs(
        serverUrl = p[K.serverUrl] ?: "",
        name = p[K.name] ?: "",
        entityId = p[K.entityId] ?: "",
        updateIntervalSec = p[K.updateIntervalSec] ?: AppPrefs.DEFAULT_UPDATE_INTERVAL_SEC,
        minDistanceM = p[K.minDistanceM] ?: AppPrefs.DEFAULT_MIN_DISTANCE_M,
        heartbeatSec = p[K.heartbeatSec] ?: AppPrefs.DEFAULT_HEARTBEAT_SEC,
        pollSec = p[K.pollSec] ?: AppPrefs.DEFAULT_POLL_SEC,
        paused = p[K.paused] ?: false,
        gpsOnly = p[K.gpsOnly] ?: false,
        setupDone = p[K.setupDone] ?: false,
        lastFixAt = p[K.lastFixAt],
        lastSyncAt = p[K.lastSyncAt],
    )

    /**
     * The entity id is created the first time the app runs and then kept for
     * ever: a rename keeps the id, so the server sees the same entity.
     */
    suspend fun ensureEntityId(): String {
        val cur = current().entityId
        if (cur.isNotBlank()) return cur
        val id = UUID.randomUUID().toString()
        context.dataStore.edit { if (it[K.entityId].isNullOrBlank()) it[K.entityId] = id }
        return current().entityId
    }

    suspend fun setServer(url: String) = context.dataStore.edit {
        it[K.serverUrl] = url
        it[K.setupDone] = url.isNotBlank() && !it[K.name].isNullOrBlank()
    }

    suspend fun setName(name: String) = context.dataStore.edit {
        it[K.name] = AppPrefs.cleanName(name)
        it[K.setupDone] = !it[K.serverUrl].isNullOrBlank() && AppPrefs.cleanName(name).isNotBlank()
    }

    suspend fun setUpdateIntervalSec(s: Int) = context.dataStore.edit { it[K.updateIntervalSec] = s.coerceIn(AppPrefs.MIN_UPDATE_INTERVAL_SEC, AppPrefs.MAX_SECONDS) }
    suspend fun setMinDistanceM(m: Int) = context.dataStore.edit { it[K.minDistanceM] = m.coerceIn(0, 100_000) }
    suspend fun setHeartbeatSec(s: Int) = context.dataStore.edit { it[K.heartbeatSec] = s.coerceIn(AppPrefs.MIN_HEARTBEAT_SEC, AppPrefs.MAX_SECONDS) }
    suspend fun setPollSec(s: Int) = context.dataStore.edit { it[K.pollSec] = s.coerceIn(AppPrefs.MIN_POLL_SEC, AppPrefs.MAX_SECONDS) }
    suspend fun setPaused(paused: Boolean) = context.dataStore.edit { it[K.paused] = paused }
    suspend fun setGpsOnly(gpsOnly: Boolean) = context.dataStore.edit { it[K.gpsOnly] = gpsOnly }
    suspend fun setLastFixAt(at: Long) = context.dataStore.edit { it[K.lastFixAt] = at }
    suspend fun setLastSyncAt(at: Long) = context.dataStore.edit { it[K.lastSyncAt] = at }
}
