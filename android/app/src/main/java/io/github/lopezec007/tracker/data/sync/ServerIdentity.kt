// Is the thing that answered at the server address *our* server? (README
// "Server identity"). The phone may roam onto another LAN where the same
// address belongs to a different machine; nothing may ever be sent there.
// Pure Kotlin: the decision is made here from the probe's answer and the
// pinned id, and tested on the JVM.
package io.github.lopezec007.tracker.data.sync

import io.github.lopezec007.tracker.data.api.VersionDto

object ServerIdentity {
    /** What /api/version's "app" must say. */
    const val APP = "tracker"

    const val NOT_A_TRACKER = "no tracker server at this address (a tracker server older than 0.1.0 must be upgraded)"

    sealed class Check {
        /** Nothing was pinned yet: this id becomes the pinned one. */
        data class Pin(val serverId: String) : Check()
        object Match : Check()
        /** Something answered, but it is a different tracker server. */
        data class Mismatch(val pinned: String, val actual: String) : Check()
        /** Something answered, but not like a tracker server (or an old one without an identity). */
        data class NotTracker(val reason: String) : Check()
    }

    /** True when the answer comes from a tracker server that identifies itself. */
    fun isTracker(v: VersionDto): Boolean = v.app == APP && v.server_id.isNotBlank()

    /** [v] is what the probe got back; [pinned] is the stored id, "" when none. */
    fun check(v: VersionDto, pinned: String): Check = when {
        !isTracker(v) -> Check.NotTracker(NOT_A_TRACKER)
        pinned.isBlank() -> Check.Pin(v.server_id)
        pinned == v.server_id -> Check.Match
        else -> Check.Mismatch(pinned, v.server_id)
    }

    /** The error shown while a different server answers. */
    fun mismatchMessage(address: String, actual: String): String =
        "a different server answered at $address (id ${shortId(actual)}); fixes are kept until the right one is back"

    /** The first block of a UUID, enough to tell two apart on a screen. */
    fun shortId(id: String): String = id.substringBefore('-').ifBlank { id }
}
