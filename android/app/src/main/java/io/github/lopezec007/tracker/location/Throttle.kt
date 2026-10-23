// Which locations become fixes. Pure Kotlin so it is tested on the JVM:
// a fix is recorded when the update interval has elapsed AND the device has
// moved at least the minimum distance since the last fix; a heartbeat fix is
// recorded whenever the heartbeat interval has elapsed regardless of movement
// (the service then reuses the last known location).
package io.github.lopezec007.tracker.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class ThrottleSettings(
    val intervalMs: Long,
    val minDistanceM: Double,
    val heartbeatMs: Long,
)

/** Why a location was recorded; goes into the fix's metadata. */
enum class FixReason(val wire: String) {
    /** Nothing recorded yet since the service started. */
    FIRST("first"),
    MOVED("moved"),
    HEARTBEAT("heartbeat"),
}

class Throttle(@Volatile var settings: ThrottleSettings) {
    data class Accepted(val lat: Double, val lon: Double, val atMs: Long)

    @Volatile var last: Accepted? = null
        private set

    /** Whether a location at ([lat], [lon]) seen at [nowMs] should be recorded, and why; null to skip it. */
    fun decide(lat: Double, lon: Double, nowMs: Long): FixReason? {
        val l = last ?: return FixReason.FIRST
        val s = settings
        val elapsed = nowMs - l.atMs
        if (elapsed >= s.intervalMs && distanceMeters(l.lat, l.lon, lat, lon) >= s.minDistanceM) return FixReason.MOVED
        if (elapsed >= s.heartbeatMs) return FixReason.HEARTBEAT
        return null
    }

    /** Whether a heartbeat is due even without any new location (the ticker asks this). */
    fun heartbeatDue(nowMs: Long): Boolean {
        val l = last ?: return true
        return nowMs - l.atMs >= settings.heartbeatMs
    }

    fun accept(lat: Double, lon: Double, nowMs: Long) {
        last = Accepted(lat, lon, nowMs)
    }

    fun reset() {
        last = null
    }

    companion object {
        private const val EARTH_RADIUS_M = 6_371_008.8

        /** Great-circle distance (haversine), good to well under a metre at these scales. */
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = Math.toRadians(lat2 - lat1)
            val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
