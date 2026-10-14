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
    fun homeStatusUsesWaitingForNormalNonConnectedStates() {
        listOf(
            RegattaLinkConnectionStatus.IDLE,
            RegattaLinkConnectionStatus.WAITING,
            RegattaLinkConnectionStatus.SCANNING,
            RegattaLinkConnectionStatus.BONDING,
            RegattaLinkConnectionStatus.CONNECTING,
            RegattaLinkConnectionStatus.DISCOVERING,
            RegattaLinkConnectionStatus.READING_DEVICE_INFO
        ).forEach { status ->
            assertEquals(
                RegattaLinkHomeStatus.WAITING,
                regattaLinkHomeStatus(RegattaLinkClientState(status = status))
            )
        }
    }

    @Test
    fun connectedDifferentRLinkDoesNotMakePreferredStatusGreen() {
        assertEquals(
            RegattaLinkHomeStatus.WAITING,
            regattaLinkHomeStatus(
                state = RegattaLinkClientState(
                    status = RegattaLinkConnectionStatus.CONNECTED,
                    deviceInfo = RegattaLinkDeviceInfo(
                        protocolMajor = REGATTALINK_PROTOCOL_MAJOR,
                        protocolMinor = 0,
                        capabilities = 0u,
                        stableId = "connected",
                        productId = REGATTALINK_PRODUCT_ID,
                        profileId = REGATTALINK_PROFILE_ID,
                        runningBuild = 1uL,
                        otaSlotSize = 0u,
                        maxInflightBlocks = 0
                    )
                ),
                pairingRequired = false,
                selectedStableId = "preferred"
            )
        )
    }

    @Test
    fun homeStatusUsesGreenOnlyForConnected() {
        assertEquals(
            RegattaLinkHomeStatus.CONNECTED,
            regattaLinkHomeStatus(
                RegattaLinkClientState(
                    status = RegattaLinkConnectionStatus.CONNECTED
                )
            )
        )
    }

    @Test
    fun homeStatusUsesErrorForBluetoothOffTerminalErrorOrPairingRequirement() {
        assertEquals(
            RegattaLinkHomeStatus.ERROR,
            regattaLinkHomeStatus(
                RegattaLinkClientState(
                    status = RegattaLinkConnectionStatus.BLUETOOTH_OFF
                )
            )
        )
        assertEquals(
            RegattaLinkHomeStatus.ERROR,
            regattaLinkHomeStatus(
                RegattaLinkClientState(
                    status = RegattaLinkConnectionStatus.ERROR
                )
            )
        )
        assertEquals(
            RegattaLinkHomeStatus.ERROR,
            regattaLinkHomeStatus(
                state = RegattaLinkClientState(),
                pairingRequired = true
            )
        )
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
