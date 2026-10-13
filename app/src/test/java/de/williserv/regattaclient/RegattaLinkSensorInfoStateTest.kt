package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegattaLinkSensorInfoStateTest {
    private fun status(boundId: String?): RegattaLinkDeviceControlStatus =
        RegattaLinkDeviceControlStatus(
            opcode = RegattaLinkDeviceControlOpcode.CALYPSO_STATUS,
            phase = RegattaLinkDeviceControlPhase.SUCCESS,
            result = RegattaLinkDeviceControlResult.OK,
            requestId = 1u,
            forwardTrimDeg = 0,
            heelTrimDeg = 0,
            pitchTrimDeg = 0,
            boatFrameValid = false,
            gyroBiasValid = false,
            mountingEpoch = 0u,
            calypso = RegattaLinkCalypsoControlStatus(
                boundId = boundId,
                bound = boundId != null,
                connected = false,
                scanning = false,
                detail = 0
            )
        )

    @Test
    fun changingOrDeletingBindingDropsPreviousMetadata() {
        val old = RegattaLinkConfigurationState(
            calypso = RegattaLinkCalypsoState(
                statusKnown = true,
                boundId = "AA:BB:CC:DD:EE:FF"
            ),
            calypsoSensorInfo = RegattaLinkSensorInfoState(
                entries = listOf(RegattaLinkSensorInfoEntry("firmware", "1.2.3"))
            )
        )
        val rebound = regattaLinkApplyCalypsoControlStatus(old, status("11:22:33:44:55:66"))
        val deleted = regattaLinkApplyCalypsoControlStatus(old, status(null))
        assertTrue(rebound.calypsoSensorInfo.entries.isEmpty())
        assertTrue(deleted.calypsoSensorInfo.entries.isEmpty())
        assertEquals("11:22:33:44:55:66", rebound.calypso.boundId)
        assertEquals(null, deleted.calypso.boundId)
    }

    @Test
    fun sameBindingStatusRefreshKeepsMetadata() {
        val metadata = RegattaLinkSensorInfoState(
            entries = listOf(RegattaLinkSensorInfoEntry("battery", "88"))
        )
        val old = RegattaLinkConfigurationState(
            calypso = RegattaLinkCalypsoState(
                statusKnown = true,
                boundId = "AA:BB:CC:DD:EE:FF"
            ),
            calypsoSensorInfo = metadata
        )
        val refreshed = regattaLinkApplyCalypsoControlStatus(old, status("AA:BB:CC:DD:EE:FF"))
        assertEquals(metadata, refreshed.calypsoSensorInfo)
    }
}
