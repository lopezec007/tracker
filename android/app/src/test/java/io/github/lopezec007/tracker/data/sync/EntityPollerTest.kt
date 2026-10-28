// The poller is gated like the queue: a different server is probed and
// never asked for its entities; the list from the right server is kept.
package io.github.lopezec007.tracker.data.sync

import io.github.lopezec007.tracker.data.api.TrackerApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EntityPollerTest {
    private lateinit var server: MockWebServer
    private var api: TrackerApi? = null
    private var serverId = "srv-1"
    private var versionBody: () -> String = { """{"version":"0.1.0","date":"d","build":"b","app":"tracker","server_id":"$serverId"}""" }
    private val requests = ArrayList<RecordedRequest>()
    private var pinned = ""
    private var now = 1_792_692_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when (request.path) {
                    "/api/version" -> json.setBody(versionBody())
                    "/api/entities" -> json.setBody("""[{"id":"b","name":"Zed","source":"phone","icon":"ic_action_flag","color":"#E53935","latest":null},{"id":"a","name":"amy","source":"phone","latest":null}]""")
                    else -> json.setResponseCode(404).setBody("""{"error":"not found"}""")
                }
            }
        }
        server.start()
        api = TrackerApi(TrackerApi.parseBaseUrl(server.url("/").toString())!!)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun poller() = EntityPoller({ api }, ServerGate({ api }, pinned = { pinned }, pin = { pinned = it }, now = { now }), now = { now })

    @Test
    fun probesFirstThenListsSortedByName() = runBlocking {
        val p = poller()
        assertTrue(p.poll())
        assertEquals(listOf("/api/version", "/api/entities"), requests.map { it.path })
        assertEquals(listOf("amy", "Zed"), p.state.value.entities.map { it.name })
        assertEquals("ic_action_flag", p.state.value.entities[1].icon)
        assertEquals("srv-1", pinned)
        assertEquals(now, p.state.value.lastSuccess)
        assertFalse(p.state.value.wrongServer)
    }

    @Test
    fun aDifferentServerIsNeverPolled() = runBlocking {
        val p = poller()
        assertTrue(p.poll())
        requests.clear()
        serverId = "srv-2"
        now += 60_000
        assertFalse(p.poll())
        assertEquals(listOf("/api/version"), requests.map { it.path })
        val s = p.state.value
        assertTrue(s.wrongServer)
        assertTrue(s.lastError!!.contains("a different server answered"))
        assertEquals(2, s.entities.size)  // the right server's list is kept
    }

    @Test
    fun anOldServerIsNotPolledEither() = runBlocking {
        versionBody = { """{"version":"0.0.1","date":"d","build":"b"}""" }
        val p = poller()
        assertFalse(p.poll())
        assertEquals(listOf("/api/version"), requests.map { it.path })
        assertTrue(p.state.value.lastError!!.contains("no tracker server"))
        assertFalse(p.state.value.wrongServer)
    }

    @Test
    fun aRecentProbeIsReusedBetweenSyncAndPoll() = runBlocking {
        val gate = ServerGate({ api }, pinned = { pinned }, pin = { pinned = it }, now = { now })
        gate.check()   // the sync engine just probed
        val p = EntityPoller({ api }, gate, now = { now })
        now += 1_000
        assertTrue(p.poll())
        assertEquals(listOf("/api/version", "/api/entities"), requests.map { it.path })  // one probe for both
    }
}
