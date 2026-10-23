package io.github.lopezec007.tracker.osmand

import io.github.lopezec007.tracker.data.api.EntityDto
import io.github.lopezec007.tracker.data.api.FixDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class PointFormatTest {
    private val now = 1_792_692_000_000L // 2026-10-22T18:00:00Z
    private val zone = ZoneId.of("UTC")

    private fun entity(id: String, ageMs: Long?, name: String = id) = EntityDto(
        id = id, name = name, source = "phone",
        latest = ageMs?.let {
            FixDto(
                id = 1, entity_id = id, received_at = io.github.lopezec007.tracker.util.Ages.formatRfc3339(now - it),
                device_time = "2026-10-22T17:57:00Z", lat = 45.5, lon = -73.6, accuracy_m = 12.4, altitude_m = 40.0,
                speed_mps = 1.25, heading_deg = 270.0, battery_pct = 83,
            )
        },
    )

    @Test
    fun detailsAndParamsCarryTheFix() {
        val p = PointFormat.point(entity("me", 3 * 60_000L, "Evan"), myId = "me", nowMs = now, zone = zone)!!
        assertEquals("Evan", p.name)
        assertEquals("phone · 3 min ago", p.typeName)
        assertEquals(PointFormat.COLOR_ME, p.color)
        assertEquals(45.5, p.lat, 0.0)
        assertEquals(
            listOf("Last fix: 17:57 (3 min ago)", "Accuracy: ±12 m", "Altitude: 40 m", "Speed: 4.5 km/h", "Heading: 270°", "Battery: 83%", "Source: phone"),
            p.details,
        )
        assertEquals("false", p.params[PointFormat.PARAM_STALE])
        assertEquals("1.25", p.params[PointFormat.PARAM_SPEED])
        assertEquals("270.0", p.params[PointFormat.PARAM_BEARING])
        assertEquals("83", p.params["battery_pct"])
        assertEquals("3 min ago", p.params["age"])
    }

    @Test
    fun staleAndOthersAreColouredApart() {
        val other = PointFormat.point(entity("x", 10 * 60_000L), myId = "me", nowMs = now, zone = zone)!!
        assertEquals(PointFormat.COLOR_OTHER, other.color)
        val stale = PointFormat.point(entity("y", 2 * 3_600_000L), myId = "me", nowMs = now, zone = zone)!!
        assertEquals(PointFormat.COLOR_STALE, stale.color)
        assertEquals("true", stale.params[PointFormat.PARAM_STALE])
        assertTrue(stale.typeName.endsWith("2 h 00 min ago"))
    }

    @Test
    fun entitiesWithoutAFixAreSkippedButStillListed() {
        val points = PointFormat.points(listOf(entity("a", 60_000), entity("b", null)), myId = "me", nowMs = now, zone = zone)
        assertEquals(listOf("a"), points.map { it.id })
        assertNull(PointFormat.fixTime(entity("b", null)))
    }
}
