// An entity's history (README "History in OsmAnd"): the range choices and
// the paging over GET /api/entities/{id}/fixes, which returns at most
// 10 000 fixes per call. Pure Kotlin: the page fetch is a function, so the
// paging is tested against a fake.
package io.github.lopezec007.tracker.data.sync

import io.github.lopezec007.tracker.data.api.FixDto
import io.github.lopezec007.tracker.data.api.TrackerApi

enum class HistoryRange(val label: String, val ms: Long) {
    H1("last hour", 3_600_000L),
    H6("last 6 hours", 6 * 3_600_000L),
    D1("last 24 hours", 24 * 3_600_000L),
    D7("last 7 days", 7 * 24 * 3_600_000L),
    D30("last 30 days", 30 * 24 * 3_600_000L);

    companion object {
        val DEFAULT = D1
    }
}

object HistoryPager {
    const val PAGE_SIZE = TrackerApi.MAX_HISTORY_PAGE

    /** Fixes beyond this are not fetched; the user is told. */
    const val MAX_POINTS = 100_000

    data class Result(val fixes: List<FixDto>, val capped: Boolean)

    /**
     * Fetches every fix in [from]..[to] (RFC 3339) a page at a time: a full
     * page means there may be more, so the next page starts at the last
     * fix's received_at. Should the server treat `from` as inclusive, the
     * fixes already seen come back first and are dropped by id (ids grow
     * with received_at). Stops after [maxPoints] with `capped = true`.
     */
    suspend fun fetchAll(
        from: String,
        to: String,
        pageSize: Int = PAGE_SIZE,
        maxPoints: Int = MAX_POINTS,
        page: suspend (from: String, to: String, limit: Int) -> List<FixDto>,
    ): Result {
        val out = ArrayList<FixDto>()
        var cursor = from
        var lastId = Long.MIN_VALUE
        while (true) {
            val got = page(cursor, to, pageSize)
            val fresh = got.filter { it.id > lastId }
            out += fresh
            if (got.size < pageSize || fresh.isEmpty()) return Result(out, false)
            if (out.size >= maxPoints) return Result(out.take(maxPoints), true)
            lastId = out.last().id
            cursor = out.last().received_at
        }
    }
}
