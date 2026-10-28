// The gate against a fake server: pinned on the first success, a different
// id refused, a missing identity treated as unavailable, a cleared pin
// re-pinning, and a recent result reused instead of a second probe.
package io.github.lopezec007.tracker.data.sync

import io.github.lopezec007.tracker.data.api.TrackerApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerGateTest {
    private lateinit var server: MockWebServer
    private lateinit var api: TrackerApi
    private var serverId = "srv-1"
    private var body: () -> String = { """{"version":"0.1.0","date":"d","build":"b","app":"tracker","server_id":"$serverId"}""" }
    private var probes = 0
    private var pinned = ""
    private var now = 1_792_692_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/version") probes++
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body())
            }
        }
        server.start()
        api = TrackerApi(TrackerApi.parseBaseUrl(server.url("/").toString())!!)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun gate() = ServerGate({ api }, pinned = { pinned }, pin = { pinned = it }, now = { now })

    @Test
    fun pinsOnTheFirstSuccessAndMatchesAfter() = runBlocking {
        val g = gate()
        val first = g.check()
        assertTrue(first is GateResult.Ok && first.pinnedNow)
        assertEquals("srv-1", pinned)
        now += 10_000
        val second = g.check()
        assertTrue(second is GateResult.Ok && !second.pinnedNow)
        assertEquals("0.1.0", (second as GateResult.Ok).version.version)
    }

    @Test
    fun aDifferentIdIsRefusedAndNotRepinned() = runBlocking {
        pinned = "srv-1"
        serverId = "srv-2"
        val r = gate().check()
        assertTrue(r is GateResult.WrongServer)
        r as GateResult.WrongServer
        assertEquals("srv-1", r.pinned)
        assertEquals("srv-2", r.actual)
        assertTrue(r.reason.startsWith("a different server answered at ${server.hostName}:${server.port}"))
        assertEquals("srv-1", pinned)
    }

    @Test
    fun missingIdentityIsOfflineAndPinsNothing() = runBlocking {
        body = { """{"version":"0.0.1","date":"d","build":"b"}""" }
        val r = gate().check()
        assertTrue(r is GateResult.Offline)
        assertTrue((r as GateResult.Offline).reason.contains("no tracker server at this address"))
        assertEquals("", pinned)
        // Not even JSON: a web server of something else at that address.
        body = { "<html>hello</html>" }
        now += 10_000
        assertTrue(gate().check() is GateResult.Offline)
        assertEquals("", pinned)
    }

    @Test
    fun unreachableIsOffline() = runBlocking {
        server.shutdown()
        assertTrue(gate().check() is GateResult.Offline)
        assertEquals("", pinned)
    }

    @Test
    fun forgetThenRepin() = runBlocking {
        val g = gate()
        g.check()
        assertEquals("srv-1", pinned)
        serverId = "srv-2"
        now += 10_000
        assertTrue(g.check() is GateResult.WrongServer)
        pinned = ""        // Settings → Forget server identity
        g.invalidate()
        val r = g.check()
        assertTrue(r is GateResult.Ok && r.pinnedNow)
        assertEquals("srv-2", pinned)
    }

    @Test
    fun aRecentResultIsReusedUntilItAgesOrIsInvalidated() = runBlocking {
        val g = gate()
        g.check()
        now += 1_000
        g.check()
        assertEquals(1, probes)
        now += ServerGate.REUSE_MS + 1
        g.check()
        assertEquals(2, probes)
        g.invalidate()
        g.check()
        assertEquals(3, probes)
        // Another API instance (the address changed) probes afresh too.
        api = TrackerApi(TrackerApi.parseBaseUrl(server.url("/").toString())!!)
        g.check()
        assertEquals(4, probes)
    }

    @Test
    fun notConfiguredIsOffline() = runBlocking {
        val g = ServerGate({ null }, pinned = { "" }, pin = { })
        assertEquals(GateResult.Offline("not configured"), g.check())
    }
}
