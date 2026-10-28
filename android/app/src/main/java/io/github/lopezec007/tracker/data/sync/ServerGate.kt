// The one place that decides whether the server may be talked to at all:
// probes GET /api/version, checks the answer against the pinned server id
// (ServerIdentity) and pins it on the first success. The sync engine, the
// entity poller and the history fetch all pass through here, so a server
// whose id does not match is never PUT, POSTed or polled. A result is
// reused for a few seconds so a sync followed by a poll probes once.
package io.github.lopezec007.tracker.data.sync

import io.github.lopezec007.tracker.data.api.ApiException
import io.github.lopezec007.tracker.data.api.TrackerApi
import io.github.lopezec007.tracker.data.api.VersionDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

sealed class GateResult {
    data class Ok(val version: VersionDto, val pinnedNow: Boolean) : GateResult()
    /** Not reachable, or reachable but not a tracker server: the queue waits. */
    data class Offline(val reason: String) : GateResult()
    /** A tracker server, but not the pinned one: nothing is sent, the queue is kept. */
    data class WrongServer(val reason: String, val pinned: String, val actual: String) : GateResult()
}

class ServerGate(
    private val apiProvider: () -> TrackerApi?,
    /** The pinned server id, "" when none. */
    private val pinned: suspend () -> String,
    private val pin: suspend (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    /** How long a result stands in for a new probe. */
    private val reuseMs: Long = REUSE_MS,
) {
    private val mutex = Mutex()
    private var cachedFor: TrackerApi? = null
    private var cachedAt = 0L
    private var cached: GateResult? = null

    /** Probes (or reuses a recent probe) and says whether [api] may be used. */
    suspend fun check(api: TrackerApi): GateResult = mutex.withLock {
        val c = cached
        if (c != null && cachedFor === api && now() - cachedAt <= reuseMs) return c
        val result = probe(api)
        cachedFor = api
        cachedAt = now()
        cached = result
        result
    }

    /** Like [check] with the configured API; Offline("not configured") before setup. */
    suspend fun check(): GateResult {
        val api = apiProvider() ?: return GateResult.Offline("not configured")
        return check(api)
    }

    /** Forgets the reused result (after the pin was cleared, or the address changed). */
    suspend fun invalidate() = mutex.withLock {
        cached = null
        cachedFor = null
    }

    private suspend fun probe(api: TrackerApi): GateResult {
        val version = try {
            api.probe()
        } catch (e: IOException) {
            return GateResult.Offline(e.message ?: "network error")
        } catch (e: ApiException) {
            // 404/5xx on /api/version: whatever answers there is not a tracker server.
            return GateResult.Offline("${ServerIdentity.NOT_A_TRACKER}: ${e.message}")
        } catch (e: Exception) {
            // Not JSON, or JSON of another shape: not a tracker server either.
            return GateResult.Offline(ServerIdentity.NOT_A_TRACKER)
        }
        return when (val c = ServerIdentity.check(version, pinned())) {
            is ServerIdentity.Check.NotTracker -> GateResult.Offline(c.reason)
            is ServerIdentity.Check.Pin -> {
                pin(c.serverId)
                GateResult.Ok(version, pinnedNow = true)
            }
            ServerIdentity.Check.Match -> GateResult.Ok(version, pinnedNow = false)
            is ServerIdentity.Check.Mismatch -> GateResult.WrongServer(
                ServerIdentity.mismatchMessage(address(api), c.actual), c.pinned, c.actual,
            )
        }
    }

    companion object {
        const val REUSE_MS = 5_000L

        /** "host:port" of the base URL, as the user typed it. */
        fun address(api: TrackerApi): String {
            val u = api.baseUrl
            val port = if (u.port != okhttp3.HttpUrl.defaultPort(u.scheme)) ":${u.port}" else ""
            return u.host + port
        }
    }
}
