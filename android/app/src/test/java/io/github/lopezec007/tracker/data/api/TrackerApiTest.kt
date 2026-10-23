package io.github.lopezec007.tracker.data.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TrackerApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: TrackerApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = TrackerApi(TrackerApi.parseBaseUrl(server.url("/").toString())!!)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun parsesLooseAddresses() {
        assertEquals("http://10.8.0.1:8080/", TrackerApi.parseBaseUrl("10.8.0.1:8080").toString())
        assertEquals("http://tracker.lan:8080/", TrackerApi.parseBaseUrl(" tracker.lan:8080/ ").toString())
        assertEquals("https://x.example/", TrackerApi.parseBaseUrl("https://x.example").toString())
        assertNull(TrackerApi.parseBaseUrl(""))
        assertNull(TrackerApi.parseBaseUrl("ftp://x"))
    }

    @Test
    fun probeReadsTheVersion() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"version":"0.1.0","date":"2026-10-22","build":"abc"}"""))
        assertEquals("0.1.0", api.probe().version)
        assertEquals("/api/version", server.takeRequest().path)
    }

    @Test
    fun listsEntitiesWithNullsAndUnknownFields() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """[{"id":"a1","name":"Evan","source":"phone","created_at":"c","updated_at":"u","latest":
                     {"id":5,"entity_id":"a1","received_at":"2026-10-22T18:00:00Z","device_time":null,"lat":45.5,"lon":-73.6,
                      "accuracy_m":null,"altitude_m":40.0,"speed_mps":null,"heading_deg":null,"battery_pct":83,"metadata":{"provider":"gps"},"extra":1}},
                    {"id":"b2","name":"Plane","source":"adsb","created_at":"c","updated_at":"u","latest":null}]""",
            ),
        )
        val list = api.listEntities()
        assertEquals(2, list.size)
        assertEquals("Evan", list[0].name)
        assertNull(list[0].latest!!.accuracy_m)
        assertEquals(83, list[0].latest!!.battery_pct)
        assertEquals("gps", list[0].latest!!.metadata!!["provider"]!!.toString().trim('"'))
        assertNull(list[1].latest)
        assertEquals("/api/entities", server.takeRequest().path)
    }

    @Test
    fun putEntitySendsNameAndSource() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"id":"a1","name":"Evan","source":"phone","created_at":"c","updated_at":"u","latest":null}"""))
        val e = api.putEntity("a1", "Evan")
        assertEquals("a1", e.id)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/entities/a1", req.path)
        assertEquals("""{"name":"Evan","source":"phone"}""", req.body.readUtf8())
    }

    @Test
    fun postFixesSendsExplicitNullsAndReadsAccepted() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody("""{"accepted":1,"latest":{"id":9,"entity_id":"a1","received_at":"r","device_time":"d","lat":1.0,"lon":2.0}}"""))
        val fix = FixInput(
            device_time = "2026-10-22T18:00:00Z", lat = 45.5, lon = -73.6, accuracy_m = 12.5, altitude_m = null,
            speed_mps = null, heading_deg = 270.0, battery_pct = 83, metadata = buildJsonObject { put("provider", "gps") },
        )
        val res = api.postFixes("a1", listOf(fix))
        assertEquals(1, res.accepted)
        assertEquals(9L, res.latest!!.id)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/entities/a1/fixes", req.path)
        assertEquals(
            """[{"device_time":"2026-10-22T18:00:00Z","lat":45.5,"lon":-73.6,"accuracy_m":12.5,"altitude_m":null,"speed_mps":null,"heading_deg":270.0,"battery_pct":83,"metadata":{"provider":"gps"}}]""",
            req.body.readUtf8(),
        )
    }

    @Test
    fun errorsSurfaceTheServersMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"heading_deg out of range"}"""))
        try {
            api.postFixes("a1", listOf(FixInput("t", 1.0, 2.0, null, null, null, 400.0, null, null)))
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(400, e.code)
            assertTrue(e.isClientError)
            assertEquals("heading_deg out of range", e.message)
        }
        server.enqueue(MockResponse().setResponseCode(404).setBody("not json"))
        try {
            api.getEntity("zz")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(404, e.code)
            assertEquals("HTTP 404", e.message)
        }
    }

    @Test
    fun batchSizeIsBounded() = runBlocking {
        try {
            api.postFixes("a1", emptyList())
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(500, TrackerApi.MAX_BATCH)
    }
}
