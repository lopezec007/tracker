// A GPX 1.1 document from an entity's fixes, for OsmAnd's importGpx (README
// "History in OsmAnd"): one track, split into a new segment wherever two
// consecutive fixes are more than an hour apart so OsmAnd does not draw a
// straight line across the gap. Pure Kotlin, tested on the JVM.
package io.github.lopezec007.tracker.osmand

import io.github.lopezec007.tracker.data.api.FixDto
import io.github.lopezec007.tracker.util.Ages
import java.util.Locale

object GpxBuilder {
    /** A gap longer than this between two fixes starts a new <trkseg>. */
    const val GAP_SPLIT_MS = 60L * 60 * 1000

    const val CREATOR = "Tracker for Android"

    /** The time a fix is placed at: the device's clock when it gave one, else the server's receive time. */
    fun fixTime(f: FixDto): Long? = Ages.parseRfc3339(f.device_time) ?: Ages.parseRfc3339(f.received_at)

    /**
     * [fixes] in order (as the server returns them, by received_at). A fix
     * without a usable time is written without <time> and never splits.
     */
    fun build(name: String, fixes: List<FixDto>, gapMs: Long = GAP_SPLIT_MS): String {
        val sb = StringBuilder(64 + fixes.size * 96)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"").append(escape(CREATOR)).append("\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata><name>").append(escape(name)).append("</name></metadata>\n")
        sb.append("  <trk>\n    <name>").append(escape(name)).append("</name>\n")
        sb.append("    <trkseg>\n")
        var previous: Long? = null
        for (f in fixes) {
            val t = fixTime(f)
            if (t != null && previous != null && t - previous > gapMs) {
                sb.append("    </trkseg>\n    <trkseg>\n")
            }
            sb.append("      <trkpt lat=\"").append(coord(f.lat)).append("\" lon=\"").append(coord(f.lon)).append("\">")
            f.altitude_m?.let { sb.append("<ele>").append(String.format(Locale.ROOT, "%.1f", it)).append("</ele>") }
            t?.let { sb.append("<time>").append(Ages.formatRfc3339(it)).append("</time>") }
            sb.append("</trkpt>\n")
            if (t != null) previous = t
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    /** Segment count of a document built by [build]; what a test (or a message) can count. */
    fun segmentCount(gpx: String): Int = Regex("<trkseg>").findAll(gpx).count()

    private fun coord(v: Double): String = String.format(Locale.ROOT, "%.7f", v).trimEnd('0').trimEnd('.')

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }

    /** "tracker-<entity id>.gpx": flat in OsmAnd's tracks folder (no sub-folder, see README), only safe characters. */
    fun fileName(entityId: String): String =
        "tracker-" + entityId.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("") + ".gpx"
}
