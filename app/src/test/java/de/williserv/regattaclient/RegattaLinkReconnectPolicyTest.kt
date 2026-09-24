package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegattaLinkReconnectPolicyTest {
    @Test
    fun establishedNormalDisconnectStartsOutageReconnect() {
        assertTrue(
            shouldStartRegattaLinkOutageReconnect(
                connectionWasReady = true,
                otaOwnsConnection = false,
                knownReconnectAlreadyActive = false
            )
        )
    }

    @Test
    fun otaOwnedDisconnectNeverStartsGenericReconnect() {
        assertFalse(
            shouldStartRegattaLinkOutageReconnect(
                connectionWasReady = true,
                otaOwnsConnection = true,
                knownReconnectAlreadyActive = false
            )
        )
    }

    @Test
    fun reconnectFailureDoesNotCreateNestedReconnectWindow() {
        assertFalse(
            shouldStartRegattaLinkOutageReconnect(
                connectionWasReady = true,
                otaOwnsConnection = false,
                knownReconnectAlreadyActive = true
            )
        )
    }

    @Test
    fun reconnectDeadlineNeverExtendsPastOriginalDeadline() {
        assertEquals(20_000L, regattaLinkReconnectRemainingMs(60_000L, 40_000L))
        assertEquals(0L, regattaLinkReconnectRemainingMs(60_000L, 61_000L))
    }
    @Test
    fun terminalOtaStateIsDeferredUntilOwnershipIsReleased() {
        listOf(
            RegattaLinkOtaPhase.SUCCESS,
            RegattaLinkOtaPhase.CANCELLED,
            RegattaLinkOtaPhase.ERROR
        ).forEach { phase ->
            assertTrue(
                shouldDeferRegattaLinkTerminalOtaState(
                    otaOwnsConnection = true,
                    phase = phase
                )
            )
            assertFalse(
                shouldDeferRegattaLinkTerminalOtaState(
                    otaOwnsConnection = false,
                    phase = phase
                )
            )
        }

        assertFalse(
            shouldDeferRegattaLinkTerminalOtaState(
                otaOwnsConnection = true,
                phase = RegattaLinkOtaPhase.TRANSFERRING
            )
        )
    }

}
