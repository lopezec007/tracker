package io.github.lopezec007.tracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class AgesTest {
    @Test
    fun formatsWholeSecondsUtc() {
        assertEquals("2026-10-22T18:00:00Z", Ages.formatRfc3339(1_792_692_000_000L + 123))
    }

    @Test
    fun parsesOffsetsAndFractions() {
        assertEquals(1_792_692_000_000L, Ages.parseRfc3339("2026-10-22T18:00:00Z"))
        assertEquals(1_792_692_000_000L, Ages.parseRfc3339("2026-10-22T14:00:00-04:00"))
        assertEquals(1_792_692_000_250L, Ages.parseRfc3339("2026-10-22T18:00:00.25Z"))
        assertNull(Ages.parseRfc3339(""))
        assertNull(Ages.parseRfc3339(null))
        assertNull(Ages.parseRfc3339("yesterday"))
    }

    @Test
    fun agesReadNaturally() {
        assertEquals("just now", Ages.formatAge(0))
        assertEquals("just now", Ages.formatAge(-5_000))
        assertEquals("just now", Ages.formatAge(59_999))
        assertEquals("1 min", Ages.formatAge(60_000))
        assertEquals("59 min", Ages.formatAge(59 * 60_000L + 59_000))
        assertEquals("1 h 00 min", Ages.formatAge(3_600_000))
        assertEquals("2 h 05 min", Ages.formatAge(2 * 3_600_000L + 5 * 60_000))
        assertEquals("1 d 0 h", Ages.formatAge(24 * 3_600_000L))
        assertEquals("3 d 4 h", Ages.formatAge(76 * 3_600_000L + 59 * 60_000))
        assertEquals("just now", Ages.formatAgo(1_000))
        assertEquals("4 min ago", Ages.formatAgo(4 * 60_000L))
    }

    @Test
    fun timeShowsTheDateOnlyWhenNotToday() {
        val zone = ZoneId.of("UTC")
        val t = 1_792_692_000_000L // 2026-10-22T18:00:00Z
        assertEquals("18:00", Ages.formatTime(t, nowMs = t + 3_600_000, zone = zone))
        assertEquals("22 Oct 18:00", Ages.formatTime(t, nowMs = t + 24 * 3_600_000, zone = zone))
    }
}
