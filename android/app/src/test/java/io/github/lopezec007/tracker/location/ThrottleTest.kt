package io.github.lopezec007.tracker.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrottleTest {
    private val settings = ThrottleSettings(intervalMs = 120_000, minDistanceM = 25.0, heartbeatMs = 900_000)

    /** Roughly 25 m north of (45.5, -73.6) is 0.000225 degrees of latitude. */
    private val lat = 45.5
    private val lon = -73.6
    private fun north(m: Double) = lat + m / 111_195.0

    @Test
    fun distanceIsHaversine() {
        assertEquals(0.0, Throttle.distanceMeters(lat, lon, lat, lon), 1e-9)
        assertEquals(25.0, Throttle.distanceMeters(lat, lon, north(25.0), lon), 0.05)
        // Montreal to Toronto, about 504 km.
        assertEquals(504_000.0, Throttle.distanceMeters(45.5017, -73.5673, 43.6532, -79.3832), 2_000.0)
    }

    @Test
    fun firstLocationIsAlwaysRecorded() {
        val t = Throttle(settings)
        assertEquals(FixReason.FIRST, t.decide(lat, lon, 1_000_000))
        assertTrue(t.heartbeatDue(1_000_000))
    }

    @Test
    fun movedNeedsBothIntervalAndDistance() {
        val t = Throttle(settings)
        val t0 = 1_000_000L
        t.accept(lat, lon, t0)
        // Far but too soon.
        assertNull(t.decide(north(100.0), lon, t0 + 60_000))
        // Late enough but too close.
        assertNull(t.decide(north(10.0), lon, t0 + 130_000))
        // Both: recorded.
        assertEquals(FixReason.MOVED, t.decide(north(30.0), lon, t0 + 130_000))
        // Exactly at the thresholds counts.
        assertEquals(FixReason.MOVED, t.decide(north(25.0), lon, t0 + 120_000))
    }

    @Test
    fun heartbeatRecordsAStationaryPhone() {
        val t = Throttle(settings)
        val t0 = 1_000_000L
        t.accept(lat, lon, t0)
        assertFalse(t.heartbeatDue(t0 + 899_000))
        assertNull(t.decide(lat, lon, t0 + 899_000))
        assertTrue(t.heartbeatDue(t0 + 900_000))
        assertEquals(FixReason.HEARTBEAT, t.decide(lat, lon, t0 + 900_000))
        // A moved phone at heartbeat time is "moved", not "heartbeat".
        assertEquals(FixReason.MOVED, t.decide(north(50.0), lon, t0 + 900_000))
    }

    @Test
    fun acceptRestartsTheClocksAndSettingsCanChange() {
        val t = Throttle(settings)
        t.accept(lat, lon, 0)
        t.accept(north(100.0), lon, 500_000)
        assertNull(t.decide(north(200.0), lon, 500_000 + 60_000))
        t.settings = settings.copy(intervalMs = 30_000)
        assertEquals(FixReason.MOVED, t.decide(north(200.0), lon, 500_000 + 60_000))
        t.reset()
        assertEquals(FixReason.FIRST, t.decide(lat, lon, 999))
    }
}
