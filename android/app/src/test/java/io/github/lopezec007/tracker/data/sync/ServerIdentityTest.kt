package io.github.lopezec007.tracker.data.sync

import io.github.lopezec007.tracker.data.api.VersionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerIdentityTest {
    private val tracker = VersionDto(version = "0.1.0", app = "tracker", server_id = "6d1f0a2e-1111-2222-3333-444444444444")

    @Test
    fun aTrackerServerNeedsBothFields() {
        assertTrue(ServerIdentity.isTracker(tracker))
        assertFalse(ServerIdentity.isTracker(VersionDto(version = "0.0.1")))               // old server
        assertFalse(ServerIdentity.isTracker(tracker.copy(app = "")))
        assertFalse(ServerIdentity.isTracker(tracker.copy(app = "Tracker")))               // exact match
        assertFalse(ServerIdentity.isTracker(tracker.copy(server_id = " ")))
        assertFalse(ServerIdentity.isTracker(VersionDto(app = "other", server_id = "x")))
    }

    @Test
    fun firstSuccessPinsThenOnlyThatIdMatches() {
        assertEquals(ServerIdentity.Check.Pin(tracker.server_id), ServerIdentity.check(tracker, pinned = ""))
        assertEquals(ServerIdentity.Check.Match, ServerIdentity.check(tracker, pinned = tracker.server_id))
        assertEquals(
            ServerIdentity.Check.Mismatch(pinned = "other-id", actual = tracker.server_id),
            ServerIdentity.check(tracker, pinned = "other-id"),
        )
    }

    @Test
    fun aNonTrackerIsNeverPinnedNorMatched() {
        val old = VersionDto(version = "0.0.1")
        assertEquals(ServerIdentity.Check.NotTracker(ServerIdentity.NOT_A_TRACKER), ServerIdentity.check(old, pinned = ""))
        assertEquals(ServerIdentity.Check.NotTracker(ServerIdentity.NOT_A_TRACKER), ServerIdentity.check(old, pinned = "anything"))
        assertTrue(ServerIdentity.NOT_A_TRACKER.contains("upgraded"))
    }

    @Test
    fun messagesNameTheAddressAndTheShortId() {
        assertEquals("6d1f0a2e", ServerIdentity.shortId(tracker.server_id))
        assertEquals("plain", ServerIdentity.shortId("plain"))
        assertEquals(
            "a different server answered at 10.8.0.1:8081 (id 6d1f0a2e); fixes are kept until the right one is back",
            ServerIdentity.mismatchMessage("10.8.0.1:8081", tracker.server_id),
        )
    }
}
