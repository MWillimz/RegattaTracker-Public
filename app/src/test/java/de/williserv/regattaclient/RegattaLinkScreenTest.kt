package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegattaLinkScreenTest {

    @Test
    fun bluetoothDevicesOpenRequestsStatusOnlyOnceWhenControlIsReady() {
        assertTrue(
            shouldRefreshCalypsoStatusOnOpen(
                bluetoothDevicesOpen = true,
                connected = true,
                deviceControlSupported = true,
                deviceControlEnabled = true,
                alreadyRequested = false
            )
        )
        assertFalse(
            shouldRefreshCalypsoStatusOnOpen(
                bluetoothDevicesOpen = true,
                connected = true,
                deviceControlSupported = true,
                deviceControlEnabled = true,
                alreadyRequested = true
            )
        )
        assertFalse(
            shouldRefreshCalypsoStatusOnOpen(
                bluetoothDevicesOpen = false,
                connected = true,
                deviceControlSupported = true,
                deviceControlEnabled = true,
                alreadyRequested = false
            )
        )
    }

    @Test
    fun boatOverviewRespectsValidityAndDoesNotHideValidZero() {
        val state = RegattaLinkBoatState(
            sequence = 1,
            timestampMs = 1000L,
            validityBitmap = (1L shl 0) or (1L shl 14),
            headingDeg = 0.0,
            sogMps = 2.0,
            windSpeedMps = 4.0
        )
        assertEquals(
            0.0,
            requireNotNull(regattaLinkBoatValidNumber(state, 0, state.headingDeg)),
            0.0
        )
        assertEquals(
            2.0,
            requireNotNull(regattaLinkBoatValidNumber(state, 14, state.sogMps)),
            0.0
        )
        assertNull(regattaLinkBoatValidNumber(state, 17, state.windSpeedMps))
        assertNull(regattaLinkBoatValidNumber(state, 0, Double.NaN))
        assertNull(regattaLinkBoatValidNumber(state, 0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun windReferenceLabelsKeepApparentTrueAndAbsoluteDirectionDistinct() {
        assertEquals(RegattaLinkWindLabelKind.DIRECTION_TRUE, regattaLinkWindLabelKind(0))
        assertEquals(RegattaLinkWindLabelKind.DIRECTION_MAGNETIC, regattaLinkWindLabelKind(1))
        assertEquals(RegattaLinkWindLabelKind.APPARENT, regattaLinkWindLabelKind(2))
        assertEquals(RegattaLinkWindLabelKind.TRUE, regattaLinkWindLabelKind(3))
        assertEquals(RegattaLinkWindLabelKind.TRUE, regattaLinkWindLabelKind(4))
        assertEquals(RegattaLinkWindLabelKind.UNKNOWN, regattaLinkWindLabelKind(null))
        assertEquals(RegattaLinkWindLabelKind.UNKNOWN, regattaLinkWindLabelKind(255))
    }

    @Test
    fun speedUnitConversionAndSiModelStaySeparate() {
        assertEquals(1.9438444924, regattaLinkBoatKnots(1.0), 0.000001)
        assertEquals(0.0, regattaLinkBoatKnots(0.0), 0.0)
    }

    @Test
    fun boatStateIsHiddenAfterSixtySecondsWithoutAnUpdate() {
        val receivedAt = 10_000L
        assertTrue(isRegattaLinkTelemetryFresh(receivedAt, REGATTALINK_NMEA_STALE_MS, 70_000L))
        assertFalse(isRegattaLinkTelemetryFresh(receivedAt, REGATTALINK_NMEA_STALE_MS, 70_001L))
    }
}
