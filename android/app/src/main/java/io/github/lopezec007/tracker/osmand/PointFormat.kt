// From the server's entities to what OsmAnd shows: one point per entity with
// the name as its label and the fix's details (time, age, accuracy, altitude,
// speed, heading, battery) in the context menu. Pure Kotlin, tested on the JVM.
package io.github.lopezec007.tracker.osmand

import io.github.lopezec007.tracker.data.api.EntityDto
import io.github.lopezec007.tracker.util.Ages
import java.time.ZoneId
import java.util.Locale

/** A map point as the bridge sends it (independent of the AIDL classes so it can be built and tested anywhere). */
data class TrackerPoint(
    val id: String,
    val name: String,
    val typeName: String,
    val lat: Double,
    val lon: Double,
    /** ARGB. */
    val color: Int,
    val details: List<String>,
    val params: Map<String, String>,
)

object PointFormat {
    /** A fix older than this is drawn as stale (grey, OsmAnd's own "stale location" style). */
    const val STALE_AFTER_MS = 30L * 60 * 1000

    const val COLOR_ME = 0xFF1F6FB2.toInt()
    const val COLOR_OTHER = 0xFF2E9E4F.toInt()
    const val COLOR_STALE = 0xFF9AA3AD.toInt()

    // AMapPoint's own parameter keys (net.osmand.aidlapi.maplayer.point.AMapPoint),
    // repeated here so this file needs no Android classes.
    const val PARAM_SPEED = "point_speed_param"
    const val PARAM_BEARING = "point_bearing_param"
    const val PARAM_STALE = "point_stale_loc_param"

    /** The epoch millis the age of an entity's latest fix is measured from: the server's receive time. */
    fun fixTime(e: EntityDto): Long? {
        val f = e.latest ?: return null
        return Ages.parseRfc3339(f.received_at) ?: Ages.parseRfc3339(f.device_time)
    }

    /** Points for every entity that has a fix (one without cannot be placed). */
    fun points(entities: List<EntityDto>, myId: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<TrackerPoint> =
        entities.mapNotNull { point(it, myId, nowMs, zone) }

    fun point(e: EntityDto, myId: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): TrackerPoint? {
        val f = e.latest ?: return null
        val at = fixTime(e)
        val age = at?.let { nowMs - it }
        val stale = age == null || age > STALE_AFTER_MS
        val name = e.name.ifBlank { e.id }
        val source = e.source.ifBlank { "unknown" }
        val ageText = age?.let { Ages.formatAgo(it) } ?: "time unknown"

        val details = ArrayList<String>()
        details += "Last fix: " + (at?.let { Ages.formatTime(it, nowMs, zone) } ?: "?") + " ($ageText)"
        f.accuracy_m?.let { details += "Accuracy: ±${fmt(it, 0)} m" }
        f.altitude_m?.let { details += "Altitude: ${fmt(it, 0)} m" }
        f.speed_mps?.let { details += "Speed: ${fmt(it * 3.6, 1)} km/h" }
        f.heading_deg?.let { details += "Heading: ${fmt(it, 0)}°" }
        f.battery_pct?.let { details += "Battery: $it%" }
        details += "Source: $source"

        val params = LinkedHashMap<String, String>()
        params["entity_id"] = e.id
        params["source"] = source
        params["last_fix"] = f.received_at.ifBlank { f.device_time.orEmpty() }
        params["age"] = ageText
        f.device_time?.let { params["device_time"] = it }
        f.accuracy_m?.let { params["accuracy_m"] = fmt(it, 1) }
        f.altitude_m?.let { params["altitude_m"] = fmt(it, 1) }
        f.speed_mps?.let { params["speed_mps"] = fmt(it, 2); params[PARAM_SPEED] = fmt(it, 2) }
        f.heading_deg?.let { params["heading_deg"] = fmt(it, 1); params[PARAM_BEARING] = fmt(it, 1) }
        f.battery_pct?.let { params["battery_pct"] = it.toString() }
        params[PARAM_STALE] = stale.toString()

        val color = when {
            stale -> COLOR_STALE
            e.id == myId -> COLOR_ME
            else -> COLOR_OTHER
        }
        return TrackerPoint(
            id = e.id,
            name = name,
            typeName = "$source · $ageText",
            lat = f.lat,
            lon = f.lon,
            color = color,
            details = details,
            params = params,
        )
    }

    private fun fmt(v: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", v)
}
