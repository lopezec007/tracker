// The queue against a fake server: batches of 500 in order, deletion only
// after the server's acknowledgement, a 4xx dropping only the invalid fix,
// a 404 re-creating the entity, and a network failure keeping everything.
// Runs on Robolectric so Room can be used in-memory; a plain Application
// keeps the app's own Graph out.
package io.github.lopezec007.tracker.data.sync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.lopezec007.tracker.data.api.TrackerApi
import io.github.lopezec007.tracker.data.db.PendingFixEntity
import io.github.lopezec007.tracker.data.db.TrackerDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SyncEngineTest {
    private lateinit var db: TrackerDatabase
    private lateinit var server: MockWebServer
    private lateinit var engine: SyncEngine
    private var api: TrackerApi? = null
    private var identity: Identity? = Identity("phone-1", "Evan")
    private var now = 1_792_692_000_000L

    /** A tiny fake server: entities by id -> name, every accepted fix's device_time in order. */
    private val entities = LinkedHashMap<String, String>()
    private val received = ArrayList<String>()
    private val requests = ArrayList<RecordedRequest>()

    /** Whether the fake refuses fixes with a heading above 360 (one invalid fix spoils its batch, like the real server). */
    private var validate = true
    private var failPosts = false

    private fun connect() {
        api = TrackerApi(TrackerApi.parseBaseUrl(server.url("/").toString())!!)
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val path = request.path ?: ""
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when {
                    path == "/api/version" -> json.setBody("""{"version":"9.9.9","date":"d","build":"b"}""")
                    path.startsWith("/api/entities/") && path.endsWith("/fixes") && request.method == "POST" -> {
                        val id = path.removePrefix("/api/entities/").removeSuffix("/fixes")
                        if (id !in entities) return json.setResponseCode(404).setBody("""{"error":"entity not found"}""")
                        if (failPosts) return json.setResponseCode(500).setBody("""{"error":"database is locked"}""")
                        val arr = Json.parseToJsonElement(request.body.readUtf8()) as JsonArray
                        if (arr.isEmpty() || arr.size > 500) return json.setResponseCode(400).setBody("""{"error":"1..500 fixes"}""")
                        if (validate && arr.any { (it.jsonObject["heading_deg"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) > 360 }) {
                            return json.setResponseCode(400).setBody("""{"error":"heading_deg out of range"}""")
                        }
                        arr.forEach { received.add(it.jsonObject["device_time"]!!.jsonPrimitive.content) }
                        json.setResponseCode(201).setBody("""{"accepted":${arr.size},"latest":null}""")
                    }
                    path.startsWith("/api/entities/") && request.method == "PUT" -> {
                        val id = path.removePrefix("/api/entities/")
                        val name = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["name"]!!.jsonPrimitive.content
                        entities[id] = name
                        json.setBody("""{"id":"$id","name":"$name","source":"phone","created_at":"c","updated_at":"u","latest":null}""")
                    }
                    else -> json.setResponseCode(404).setBody("""{"error":"not found"}""")
                }
            }
        }
        server.start()
        connect()
        engine = SyncEngine(db, { api }, { identity }, now = { now })
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun fix(i: Int, heading: Double? = null) = PendingFixEntity(
        deviceTime = now - 1_000_000L + i * 1000L, lat = 45.5, lon = -73.6, accuracyM = 10.0, altitudeM = null,
        speedMps = null, headingDeg = heading, batteryPct = 50, metadata = """{"provider":"gps"}""",
    )

    private suspend fun queue(n: Int) {
        for (i in 0 until n) db.pendingFixes().insert(fix(i))
    }

    private fun posts() = requests.filter { it.method == "POST" }

    @Test
    fun putsTheEntityThenSendsBatchesOf500InOrder() = runBlocking {
        queue(1200)
        assertEquals(SyncResult.Done, engine.sync())
        assertEquals(0, db.pendingFixes().count())
        assertEquals(1200, received.size)
        assertEquals(received, received.sorted())
        assertEquals(listOf("GET", "PUT", "POST", "POST", "POST"), requests.map { it.method })
        assertEquals("/api/entities/phone-1", requests[1].path)
        assertEquals("Evan", entities["phone-1"])
        val s = engine.state.value
        assertEquals(SyncPhase.IDLE, s.phase)
        assertEquals(0, s.pending)
        assertEquals(now, s.lastSuccess)
        assertEquals("9.9.9", s.serverVersion)
        assertEquals(fix(1199).deviceTime, s.lastSentDeviceTime)
    }

    @Test
    fun deletesOnlyAfterTheServerAcknowledges() = runBlocking {
        entities["phone-1"] = "Evan"
        queue(3)
        failPosts = true
        val r = engine.sync()
        assertTrue(r is SyncResult.Failed)
        assertEquals(3, db.pendingFixes().count())
        assertEquals("database is locked", engine.state.value.lastError)
        assertEquals(SyncPhase.IDLE, engine.state.value.phase)

        failPosts = false
        assertEquals(SyncResult.Done, engine.sync())
        assertEquals(0, db.pendingFixes().count())
        assertEquals(3, received.size)
        assertNull(engine.state.value.lastError)
    }

    @Test
    fun networkFailureKeepsTheQueue() = runBlocking {
        queue(2)
        server.shutdown()
        val r = engine.sync()
        assertTrue(r is SyncResult.Offline)
        assertEquals(2, db.pendingFixes().count())
        assertEquals(SyncPhase.OFFLINE, engine.state.value.phase)
        assertEquals(2, engine.state.value.pending)
    }

    @Test
    fun notConfiguredLeavesTheQueueAlone() = runBlocking {
        queue(1)
        identity = null
        assertEquals(SyncResult.NotConfigured, engine.sync())
        assertEquals(1, db.pendingFixes().count())
        assertEquals(SyncPhase.NOT_CONFIGURED, engine.state.value.phase)
        identity = Identity("phone-1", "Evan")
        api = null
        assertEquals(SyncResult.NotConfigured, engine.sync())
        assertEquals(1, db.pendingFixes().count())
    }

    @Test
    fun anInvalidFixIsDroppedAndTheRestStillGo() = runBlocking {
        for (i in 0 until 7) db.pendingFixes().insert(fix(i, heading = if (i == 4) 400.0 else 90.0))
        assertEquals(SyncResult.Done, engine.sync())
        assertEquals(0, db.pendingFixes().count())
        assertEquals(6, received.size)
        assertEquals(received, received.sorted())
        assertTrue(received.none { it == io.github.lopezec007.tracker.util.Ages.formatRfc3339(fix(4).deviceTime) })
        assertEquals(1, engine.state.value.dropped)
        // The run is Done, but the drop stays visible until a later clean run.
        assertTrue(engine.state.value.lastError?.contains("dropped") == true)
        queue(1)
        assertEquals(SyncResult.Done, engine.sync())
        assertNull(engine.state.value.lastError)
        assertEquals(1, engine.state.value.dropped)
    }

    @Test
    fun unknownEntityIsCreatedAndTheBatchRetriedOnce() = runBlocking {
        queue(2)
        assertEquals(SyncResult.Done, engine.sync())
        // The server "lost" the entity (database reset) after our PUT...
        entities.clear()
        received.clear()
        requests.clear()
        queue(1)
        // ...but PUT at the start of the run re-creates it, so the POST succeeds first time.
        assertEquals(SyncResult.Done, engine.sync())
        assertEquals(listOf("GET", "PUT", "POST"), requests.map { it.method })
        assertEquals(1, received.size)
    }

    @Test
    fun unknownEntityMidRunRetriesAfterAPut() = runBlocking {
        // A fake whose PUT is accepted but forgotten at once: the first POST 404s,
        // the engine PUTs again and retries once, and gives up if it still fails.
        val forgetful = object : Dispatcher() {
            var puts = 0
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when {
                    request.path == "/api/version" -> json.setBody("""{"version":"1","date":"d","build":"b"}""")
                    request.method == "PUT" -> { puts++; json.setBody("""{"id":"phone-1","name":"Evan","source":"phone","created_at":"c","updated_at":"u","latest":null}""") }
                    request.method == "POST" -> if (puts >= 2) json.setResponseCode(201).setBody("""{"accepted":1,"latest":null}""") else json.setResponseCode(404).setBody("""{"error":"entity not found"}""")
                    else -> json.setResponseCode(404)
                }
            }
        }
        server.dispatcher = forgetful
        queue(1)
        assertEquals(SyncResult.Done, engine.sync())
        assertEquals(listOf("GET", "PUT", "POST", "PUT", "POST"), requests.map { it.method })
        assertEquals(0, db.pendingFixes().count())
    }

    @Test
    fun oldFixesArePrunedInsteadOfSent() = runBlocking {
        db.pendingFixes().insert(fix(0).copy(deviceTime = now - 31L * 24 * 3600 * 1000))
        db.pendingFixes().insert(fix(1))
        assertEquals(SyncResult.Done, engine.sync())
        assertEquals(1, received.size)
        assertNotNull(received[0])
    }

    @Test
    fun resetForgetsTheQueue() = runBlocking {
        queue(5)
        engine.refreshPending()
        assertEquals(5, engine.state.value.pending)
        engine.reset()
        assertEquals(0, db.pendingFixes().count())
        assertEquals(SyncState(), engine.state.value)
    }
}
