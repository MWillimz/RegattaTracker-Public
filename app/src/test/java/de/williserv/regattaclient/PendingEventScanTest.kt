package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingEventScanTest {
    private val oldAccess = EventAccessKey("https://server.test", "Old Race", "old-secret")
    private val newAccess = EventAccessKey("https://server.test", "New Race", "new-secret")

    @Test
    fun pendingScanDoesNotReplacePreviouslyLoadedRaceUntilAcceptance() {
        val currentActiveAccess = oldAccess
        val pending = PendingEventScan(token = 1L, access = newAccess, previousAccess = oldAccess)

        assertEquals(oldAccess, currentActiveAccess)
        assertEquals(newAccess, pending.access)
        assertTrue(isCurrentPendingEventScan(pending, pending, currentActiveAccess))
        assertFalse(isCurrentPendingEventScan(null, pending, currentActiveAccess))
        // Rejecting a scan just drops the pending request; the old context remains.
        assertEquals(oldAccess, currentActiveAccess)
    }

    @Test
    fun repliesFromRejectedOrReplacedScansCannotApply() {
        val first = PendingEventScan(token = 1L, access = newAccess, previousAccess = oldAccess)
        val second = PendingEventScan(token = 2L, access = newAccess, previousAccess = oldAccess)

        assertFalse(isCurrentPendingEventScan(second, first, oldAccess))
        assertFalse(isCurrentPendingEventScan(first, first, null))
        assertFalse(isCurrentPendingEventScan(first, first, newAccess))
        assertTrue(isCurrentPendingEventScan(second, second, oldAccess))
    }

    @Test
    fun firstEventMayBeStagedWithoutLoadedRace() {
        val pending = PendingEventScan(token = 3L, access = newAccess, previousAccess = null)
        assertTrue(isCurrentPendingEventScan(pending, pending, null))
        assertFalse(isCurrentPendingEventScan(pending, pending, oldAccess))
    }

    @Test
    fun eventPageLinkUsesUnacceptedScanNotPreviouslyLoadedRace() {
        val pending = PendingEventScan(token = 1L, access = newAccess, previousAccess = oldAccess)
        val expected = buildEventAccessUrl(
            newAccess.server, newAccess.event, newAccess.secret
        )
        assertEquals(expected, raceNoticeEventPageUrl(pending, oldAccess))
        assertFalse(requireNotNull(expected).contains("old-secret"))
        assertEquals(
            buildEventAccessUrl(oldAccess.server, oldAccess.event, oldAccess.secret),
            raceNoticeEventPageUrl(null, oldAccess)
        )
        assertNull(raceNoticeEventPageUrl(null, null))
    }

    @Test
    fun raceNoticeRequiresResolvedIdentityTextAndHashBeforeAcceptance() {
        val valid = parsePendingEventNotice(
            """{"event_name":"Race 2","invitation_legal_text":"Please read","legal_text_hash":"hash"}"""
        )
        assertEquals(PendingEventNotice("Race 2", "Please read", "hash"), valid)
        assertNull(parsePendingEventNotice(
            """{"event_name":"Race 2","invitation_legal_text":"Please read","legal_text_hash":""}"""
        ))
        assertNull(parsePendingEventNotice(
            """{"event_name":"","invitation_legal_text":"Please read","legal_text_hash":"hash"}"""
        ))
        assertNull(parsePendingEventNotice(
            """{"event_name":"Race 2","invitation_legal_text":"","legal_text_hash":"hash"}"""
        ))
    }
}
