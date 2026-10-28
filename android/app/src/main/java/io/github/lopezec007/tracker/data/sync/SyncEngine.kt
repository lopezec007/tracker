// Push the queued fixes. See README "Offline queue".
//
//   sync()      one full run; safe to call from anywhere, serialised by a
//               mutex, never throws (the outcome is in the returned result
//               and in `state`).
//   state       what the UI shows: phase, pending count, last sync, last error.
package io.github.lopezec007.tracker.data.sync

import io.github.lopezec007.tracker.data.api.ApiException
import io.github.lopezec007.tracker.data.api.FixInput
import io.github.lopezec007.tracker.data.api.TrackerApi
import io.github.lopezec007.tracker.data.db.PendingFixEntity
import io.github.lopezec007.tracker.data.db.TrackerDatabase
import io.github.lopezec007.tracker.util.Ages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.io.IOException

enum class SyncPhase {
    IDLE, SYNCING, OFFLINE, NOT_CONFIGURED,
    /** A tracker server answered, but not the pinned one: nothing is sent, the queue is kept (README "Server identity"). */
    WRONG_SERVER,
}

data class SyncState(
    val phase: SyncPhase = SyncPhase.IDLE,
    val pending: Int = 0,
    val lastSuccess: Long? = null,     // epoch millis of the last completed run
    val lastError: String? = null,     // the latest problem worth showing
    val serverVersion: String? = null,
    /** Device time of the newest fix the server has acknowledged. */
    val lastSentDeviceTime: Long? = null,
    /** Fixes the server refused as invalid (dropped), since the app started. */
    val dropped: Int = 0,
)

sealed class SyncResult {
    object Done : SyncResult()
    object NotConfigured : SyncResult()
    data class Offline(val reason: String) : SyncResult()
    data class Failed(val reason: String) : SyncResult()
    /** A different server answered at the address: nothing was sent; the queue is kept. */
    data class WrongServer(val reason: String) : SyncResult()
}

/** What the server must know about this phone before it takes fixes. */
data class Identity(val entityId: String, val name: String)

class SyncEngine(
    private val db: TrackerDatabase,
    /** The API for the configured server, or null before setup. */
    private val apiProvider: () -> TrackerApi?,
    /** This phone's entity id and display name, or null before setup. */
    private val identityProvider: suspend () -> Identity?,
    /** Probes the server and checks its identity before anything is sent. */
    private val gate: ServerGate,
    private val now: () -> Long = System::currentTimeMillis,
    private val batchSize: Int = TrackerApi.MAX_BATCH,
    /** Queued fixes older than this are pruned instead of sent. */
    private val maxAgeMs: Long = MAX_QUEUE_AGE_MS,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    suspend fun refreshPending() {
        _state.update { it.copy(pending = db.pendingFixes().count()) }
    }

    suspend fun sync(): SyncResult = mutex.withLock {
        val api = apiProvider()
        val identity = identityProvider()
        if (api == null || identity == null) {
            _state.update { it.copy(phase = SyncPhase.NOT_CONFIGURED, pending = db.pendingFixes().count()) }
            return SyncResult.NotConfigured
        }
        _state.update { it.copy(phase = SyncPhase.SYNCING, pending = db.pendingFixes().count()) }
        val droppedBefore = _state.value.dropped
        val result = try {
            runCycle(api, identity)
        } catch (e: IOException) {
            SyncResult.Offline(e.message ?: "network error")
        } catch (e: ApiException) {
            SyncResult.Failed(e.message ?: "HTTP ${e.code}")
        } catch (e: Exception) {
            SyncResult.Failed(e.toString())
        }
        val pending = db.pendingFixes().count()
        _state.update { s ->
            when (result) {
                // A run that dropped an invalid fix still counts as done, but keeps that message visible.
                is SyncResult.Done -> s.copy(
                    phase = SyncPhase.IDLE, pending = pending, lastSuccess = now(),
                    lastError = if (s.dropped > droppedBefore) s.lastError else null,
                )
                is SyncResult.Offline -> s.copy(phase = SyncPhase.OFFLINE, pending = pending, lastError = result.reason)
                is SyncResult.Failed -> s.copy(phase = SyncPhase.IDLE, pending = pending, lastError = result.reason)
                is SyncResult.WrongServer -> s.copy(phase = SyncPhase.WRONG_SERVER, pending = pending, lastError = result.reason)
                is SyncResult.NotConfigured -> s.copy(phase = SyncPhase.NOT_CONFIGURED, pending = pending)
            }
        }
        result
    }

    private suspend fun runCycle(api: TrackerApi, identity: Identity): SyncResult {
        db.pendingFixes().deleteOlderThan(now() - maxAgeMs)
        // Probe and identity check first: an unreachable or foreign address gets nothing.
        val version = when (val g = gate.check(api)) {
            is GateResult.Ok -> g.version
            is GateResult.Offline -> return SyncResult.Offline(g.reason)
            is GateResult.WrongServer -> return SyncResult.WrongServer(g.reason)
        }
        _state.update { it.copy(serverVersion = version.version) }
        // Cheap and idempotent: the server learns the current name, and the
        // entity exists before its fixes arrive.
        api.putEntity(identity.entityId, identity.name)
        while (true) {
            val batch = db.pendingFixes().oldest(batchSize)
            if (batch.isEmpty()) break
            post(api, identity, batch, retryOnUnknownEntity = true)
            _state.update { it.copy(pending = db.pendingFixes().count()) }
        }
        return SyncResult.Done
    }

    /**
     * Sends one batch and deletes it from the queue once the server has
     * acknowledged it. A 404 (entity unknown, e.g. the server's database was
     * reset) re-creates the entity and tries once more. A 400 means the
     * batch is invalid as a whole: it is split and each half tried alone, so
     * that only the offending fix is dropped and the rest still go.
     */
    private suspend fun post(api: TrackerApi, identity: Identity, batch: List<PendingFixEntity>, retryOnUnknownEntity: Boolean) {
        try {
            api.postFixes(identity.entityId, batch.map { it.toInput() })
        } catch (e: ApiException) {
            when {
                e.code == 404 && retryOnUnknownEntity -> {
                    api.putEntity(identity.entityId, identity.name)
                    post(api, identity, batch, retryOnUnknownEntity = false)
                    return
                }
                e.code == 400 && batch.size > 1 -> {
                    val half = batch.size / 2
                    post(api, identity, batch.subList(0, half), retryOnUnknownEntity)
                    post(api, identity, batch.subList(half, batch.size), retryOnUnknownEntity)
                    return
                }
                e.code == 400 -> {
                    db.pendingFixes().deleteIds(batch.map { it.id })
                    _state.update { it.copy(dropped = it.dropped + 1, lastError = "a fix was refused as invalid and dropped: ${e.message}") }
                    return
                }
                else -> throw e  // 5xx or another 4xx: keep the queue, retry later
            }
        }
        db.pendingFixes().deleteIds(batch.map { it.id })
        _state.update { it.copy(lastSentDeviceTime = batch.last().deviceTime) }
    }

    /** Forgets the queue (used when the server address changes). */
    suspend fun reset() {
        db.pendingFixes().deleteAll()
        _state.value = SyncState()
    }

    companion object {
        const val MAX_QUEUE_AGE_MS = 30L * 24 * 3600 * 1000

        private val json = Json { ignoreUnknownKeys = true }

        fun PendingFixEntity.toInput(): FixInput = FixInput(
            device_time = Ages.formatRfc3339(deviceTime),
            lat = lat,
            lon = lon,
            accuracy_m = accuracyM,
            altitude_m = altitudeM,
            speed_mps = speedMps,
            heading_deg = headingDeg,
            battery_pct = batteryPct,
            metadata = parseMetadata(metadata),
        )

        private fun parseMetadata(s: String): JsonObject =
            runCatching { json.parseToJsonElement(s) as? JsonObject }.getOrNull() ?: buildJsonObject { }
    }
}
