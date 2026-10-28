// Paging over GET /fixes against a fake: full pages continue from the last
// received_at, repeats are dropped by id, a short page ends it, and the cap.
package io.github.lopezec007.tracker.data.sync

import io.github.lopezec007.tracker.data.api.FixDto
import io.github.lopezec007.tracker.data.api.TrackerApi
import io.github.lopezec007.tracker.util.Ages
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

class HistoryPagerTest {
    private lateinit var server: MockWebServer
    private lateinit var api: TrackerApi
    private val t0 = 1_792_692_000_000L
    private val calls = ArrayList<String>()

    /** A fake with [count] fixes one second apart; `from` inclusive, `to` inclusive, ordered by received_at. */
    private fun serve(count: Int) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls.add(request.path!!)
                val url = request.requestUrl!!
                val from = Ages.parseRfc3339(url.queryParameter("from"))!!
                val to = Ages.parseRfc3339(url.queryParameter("to"))!!
                val limit = url.queryParameter("limit")!!.toInt()
                val rows = (0 until count).map { i -> i to (t0 + i * 1000L) }
                    .filter { (_, t) -> t in from..to }
                    .take(limit)
                    .joinToString(",") { (i, t) -> """{"id":${i + 1},"entity_id":"a","received_at":"${Ages.formatRfc3339(t)}","lat":45.0,"lon":-73.0}""" }
                return MockResponse().setHeader("Content-Type", "application/json").setBody("[$rows]")
            }
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = TrackerApi(TrackerApi.parseBaseUrl(server.url("/").toString())!!)
    }

    @After
    fun tearDown() = server.shutdown()

    private suspend fun fetch(pageSize: Int, maxPoints: Int = HistoryPager.MAX_POINTS) =
        HistoryPager.fetchAll(Ages.formatRfc3339(t0 - 3_600_000), Ages.formatRfc3339(t0 + 3_600_000), pageSize, maxPoints) { from, to, limit ->
            api.listFixes("a", from, to, limit)
        }

    @Test
    fun pagesPastTheLastReceivedAtAndDropsTheRepeat() = runBlocking {
        serve(25)
        val r = fetch(pageSize = 10)
        assertEquals((1L..25L).toList(), r.fixes.map { it.id })
        assertFalse(r.capped)
        assertEquals(3, calls.size)
        // The second page starts at the last fix's received_at (inclusive on this fake: id 10 comes back and is dropped).
        assertTrue(calls[1].contains("from=" + Ages.formatRfc3339(t0 + 9_000).replace(":", "%3A")))
    }

    @Test
    fun aShortFirstPageIsTheWholeHistory() = runBlocking {
        serve(4)
        val r = fetch(pageSize = 10)
        assertEquals(4, r.fixes.size)
        assertEquals(1, calls.size)
    }

    @Test
    fun exactlyOnePageEndsAfterAnEmptySecond() = runBlocking {
        serve(10)
        val r = fetch(pageSize = 10)
        assertEquals(10, r.fixes.size)
        assertFalse(r.capped)
        assertEquals(2, calls.size)  // the second page brought only the repeat
    }

    @Test
    fun stopsAtTheCapAndSaysSo() = runBlocking {
        serve(50)
        val r = fetch(pageSize = 10, maxPoints = 25)
        assertEquals(25, r.fixes.size)
        assertTrue(r.capped)
        assertEquals(3, calls.size)
    }

    @Test
    fun nothingInRange() = runBlocking {
        serve(0)
        val r = fetch(pageSize = 10)
        assertTrue(r.fixes.isEmpty())
        assertFalse(r.capped)
    }

    @Test
    fun rangesAreWhatTheSheetOffers() {
        assertEquals(listOf("last hour", "last 6 hours", "last 24 hours", "last 7 days", "last 30 days"), HistoryRange.entries.map { it.label })
        assertEquals(HistoryRange.D1, HistoryRange.DEFAULT)
        assertEquals(30L * 24 * 3_600_000, HistoryRange.D30.ms)
        assertEquals(TrackerApi.MAX_HISTORY_PAGE, HistoryPager.PAGE_SIZE)
        assertEquals(100_000, HistoryPager.MAX_POINTS)
        @Suppress("UNUSED_VARIABLE") val unused: FixDto? = null
    }
}
