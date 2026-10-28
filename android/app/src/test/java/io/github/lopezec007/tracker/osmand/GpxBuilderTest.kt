package io.github.lopezec007.tracker.osmand

import io.github.lopezec007.tracker.data.api.FixDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxBuilderTest {
    private fun fix(id: Long, received: String, device: String? = null, alt: Double? = null, lat: Double = 45.5, lon: Double = -73.6) =
        FixDto(id = id, entity_id = "a", received_at = received, device_time = device, lat = lat, lon = lon, altitude_m = alt)

    @Test
    fun oneTrackOneSegmentWithTimesAndElevation() {
        val gpx = GpxBuilder.build(
            "Evan last hour",
            listOf(
                fix(1, "2026-10-22T17:00:05Z", device = "2026-10-22T17:00:00Z", alt = 40.0),
                fix(2, "2026-10-22T17:02:00Z", device = null),
                fix(3, "2026-10-22T17:04:00Z", device = "not a time", alt = 41.25, lat = 45.123456789, lon = -73.0),
            ),
        )
        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Tracker for Android\" xmlns=\"http://www.topografix.com/GPX/1/1\">"))
        assertTrue(gpx.contains("<trk>\n    <name>Evan last hour</name>"))
        assertTrue(gpx.contains("<metadata><name>Evan last hour</name></metadata>"))
        assertEquals(1, GpxBuilder.segmentCount(gpx))
        // device_time preferred, received_at when it is missing or unusable; elevation only when given.
        assertTrue(gpx.contains("<trkpt lat=\"45.5\" lon=\"-73.6\"><ele>40.0</ele><time>2026-10-22T17:00:00Z</time></trkpt>"))
        assertTrue(gpx.contains("<trkpt lat=\"45.5\" lon=\"-73.6\"><time>2026-10-22T17:02:00Z</time></trkpt>"))
        assertTrue(gpx.contains("<trkpt lat=\"45.1234568\" lon=\"-73\"><ele>41.3</ele><time>2026-10-22T17:04:00Z</time></trkpt>"))
        assertTrue(gpx.trimEnd().endsWith("</trkseg>\n  </trk>\n</gpx>"))
    }

    @Test
    fun aGapOverAnHourStartsANewSegment() {
        val gpx = GpxBuilder.build(
            "x",
            listOf(
                fix(1, "2026-10-22T10:00:00Z"),
                fix(2, "2026-10-22T10:59:00Z"),
                fix(3, "2026-10-22T12:00:00Z"),   // 61 min after the previous: split
                fix(4, "2026-10-22T12:30:00Z"),
                fix(5, "2026-10-22T13:30:00Z"),   // exactly 60 min: no split
                fix(6, "2026-10-23T09:00:00Z"),   // split
            ),
        )
        assertEquals(3, GpxBuilder.segmentCount(gpx))
        val segments = gpx.split("<trkseg>").drop(1)
        assertEquals(listOf(2, 3, 1), segments.map { Regex("<trkpt ").findAll(it).count() })
        assertTrue(gpx.contains("</trkseg>\n    <trkseg>"))
    }

    @Test
    fun gapsUseTheDeviceTimeWhenThereIsOne() {
        // Received late in one burst (an offline queue flushed), but taken hours apart on the phone.
        val gpx = GpxBuilder.build(
            "x",
            listOf(
                fix(1, "2026-10-22T18:00:00Z", device = "2026-10-22T08:00:00Z"),
                fix(2, "2026-10-22T18:00:01Z", device = "2026-10-22T12:00:00Z"),
            ),
        )
        assertEquals(2, GpxBuilder.segmentCount(gpx))
    }

    @Test
    fun namesAreEscapedAndFixesWithoutATimeNeverSplit() {
        val gpx = GpxBuilder.build("Tom & \"Jerry\" <3 'x'", listOf(fix(1, ""), fix(2, ""), fix(3, "2026-10-22T18:00:00Z")))
        assertTrue(gpx.contains("<name>Tom &amp; &quot;Jerry&quot; &lt;3 &apos;x&apos;</name>"))
        assertFalse(gpx.contains("<time></time>"))
        assertEquals(1, GpxBuilder.segmentCount(gpx))
        assertEquals("Tracker for Android", GpxBuilder.CREATOR)
        assertEquals(3_600_000L, GpxBuilder.GAP_SPLIT_MS)
    }

    @Test
    fun fileNamesAreFlatAndSafe() {
        assertEquals("tracker-6d1f0a2e-1111-2222-3333-444444444444.gpx", GpxBuilder.fileName("6d1f0a2e-1111-2222-3333-444444444444"))
        assertEquals("tracker-a.b_c-d.gpx", GpxBuilder.fileName("a.b_c-d"))
        assertEquals("tracker-_.._____.gpx", GpxBuilder.fileName("/.. \\?*é"))
    }
}
