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

    private fun entity(id: String, ageMs: Long?, name: String = id, icon: String = "", color: String = "") = EntityDto(
        id = id, name = name, source = "phone", icon = icon, color = color,
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
    fun iconGoesToOsmAndAsTheTypeIconParam() {
        val p = PointFormat.point(entity("x", 60_000L, icon = "ic_action_car_dark"), myId = "me", nowMs = now, zone = zone)!!
        assertEquals("ic_action_car_dark", p.params[PointFormat.PARAM_ICON_NAME])
        assertEquals("point_type_icon_name_param", PointFormat.PARAM_ICON_NAME)
        val none = PointFormat.point(entity("y", 60_000L), myId = "me", nowMs = now, zone = zone)!!
        assertTrue(PointFormat.PARAM_ICON_NAME !in none.params)
    }

    @Test
    fun ownColourWinsEvenWhenStaleButTheStaleParamStays() {
        val fresh = PointFormat.point(entity("me", 60_000L, color = "#E53935"), myId = "me", nowMs = now, zone = zone)!!
        assertEquals(0xFFE53935.toInt(), fresh.color)
        val stale = PointFormat.point(entity("x", 2 * 3_600_000L, color = "#1e88e5"), myId = "me", nowMs = now, zone = zone)!!
        assertEquals(0xFF1E88E5.toInt(), stale.color)
        assertEquals("true", stale.params[PointFormat.PARAM_STALE])
        assertTrue(stale.typeName.endsWith("2 h 00 min ago"))
        // Malformed or empty: today's rule.
        assertEquals(PointFormat.COLOR_OTHER, PointFormat.point(entity("x", 60_000L, color = "red"), myId = "me", nowMs = now, zone = zone)!!.color)
        assertEquals(PointFormat.COLOR_STALE, PointFormat.point(entity("x", 2 * 3_600_000L, color = ""), myId = "me", nowMs = now, zone = zone)!!.color)
    }

    @Test
    fun entitiesWithoutAFixAreSkippedButStillListed() {
        val points = PointFormat.points(listOf(entity("a", 60_000), entity("b", null)), myId = "me", nowMs = now, zone = zone)
        assertEquals(listOf("a"), points.map { it.id })
        assertNull(PointFormat.fixTime(entity("b", null)))
    }
}
