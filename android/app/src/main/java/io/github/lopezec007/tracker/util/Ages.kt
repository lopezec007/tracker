// Time helpers kept free of Android so a plain JVM test can call them:
// RFC 3339 on the wire, epoch millis in memory, and "3 min ago" for people.
package io.github.lopezec007.tracker.util

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

object Ages {
    /** "2026-10-22T18:00:00Z" (whole seconds, UTC), what the server expects. */
    fun formatRfc3339(epochMs: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS))

    /** Any RFC 3339 / ISO offset timestamp to epoch millis; null for blank or malformed input. */
    fun parseRfc3339(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(s.trim()).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** "just now", "4 min", "2 h 05 min", "3 d 4 h"; a negative age (clock skew) is "just now". */
    fun formatAge(ageMs: Long): String {
        val s = ageMs / 1000
        if (s < 60) return "just now"
        val minutes = s / 60
        if (minutes < 60) return "$minutes min"
        val hours = minutes / 60
        if (hours < 24) return "$hours h ${(minutes % 60).toString().padStart(2, '0')} min"
        val days = hours / 24
        return "$days d ${hours % 24} h"
    }

    /** [formatAge] with "ago", except "just now". */
    fun formatAgo(ageMs: Long): String {
        val a = formatAge(ageMs)
        return if (a == "just now") a else "$a ago"
    }

    /** "18:02" today, else "22 Oct 18:02" (local time). */
    fun formatTime(epochMs: Long, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val pattern = if (t.toLocalDate() == today) "HH:mm" else "d MMM HH:mm"
        return DateTimeFormatter.ofPattern(pattern).format(t)
    }
}
