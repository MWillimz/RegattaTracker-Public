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
    fun autoPgnRefreshWaitsForCaptureAndRetriesOnceAfterItEnds() {
        fun allowed(
            capture: Boolean = false,
            alreadyRequested: Boolean = false,
            loading: Boolean = false,
            ota: Boolean = false,
            expanded: Boolean = true
        ): Boolean = shouldAutoRefreshPgnInventory(
            detailsExpanded = expanded,
            inventorySupported = true,
            inventoryEmpty = true,
            inventoryLoading = loading,
            otaActive = ota,
            rawCaptureActive = capture,
            alreadyRequested = alreadyRequested
        )

        // Opening diagnostics during capture must not start a GATT read.
        assertFalse(allowed(capture = true))
        // Once capture is finished, the unopened request becomes eligible.
        assertTrue(allowed(capture = false))
        // A single automatic request is enough; no refresh loop.
        assertFalse(allowed(alreadyRequested = true))
        assertFalse(allowed(loading = true))
        assertFalse(allowed(ota = true))
        assertFalse(allowed(expanded = false))
        assertFalse(
            shouldAutoRefreshPgnInventory(
                detailsExpanded = true,
                inventorySupported = true,
                inventoryEmpty = false,
                inventoryLoading = false,
                otaActive = false,
                rawCaptureActive = false,
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
    @Test
    fun nmeaSetupHidesSourcesWithUnknownOrDisabledCurrentSession() {
        val sensors = RegattaLinkNmeaState(
            loadSupported = true,
            loadSensors = listOf(
                RegattaLinkLoadSensor(
                    identityKey = "load:1",
                    measurementKey = "nmea.load.1",
                    defaultLabel = "Load 1",
                    loadKg = 12.3,
                    stableIdentity = true
                )
            )
        )
        val unknown = regattaLinkNmeaSetupVisibility(
            RegattaLinkConfigurationState(configWordSupported = true),
            sensors,
            connected = true,
            calypsoSupported = true
        )
        assertFalse(unknown.forward0183)
        assertFalse(unknown.forwardAttitude)
        assertFalse(unknown.forwardCompass)
        assertFalse(unknown.forwardPhoneGps)
        assertFalse(unknown.forwardCalypsoWind)
        assertFalse(unknown.baudRate)
        assertFalse(unknown.loadSensors)

        val disabled = regattaLinkNmeaSetupVisibility(
            RegattaLinkConfigurationState(
                configWordSupported = true,
                configWord = REGATTALINK_CONFIG_CAN_STATUS_MIRROR or
                    REGATTALINK_CONFIG_TX_NMEA0183 or
                    REGATTALINK_CONFIG_TX_IMU
            ),
            sensors,
            connected = true,
            calypsoSupported = true
        )
        assertFalse(disabled.forward0183)
        assertFalse(disabled.forwardAttitude)
        assertFalse(disabled.forwardPhoneGps)
        assertFalse(disabled.loadSensors)
    }

    @Test
    fun nmeaSetupKeepsSwitchesForActiveSourcesEvenWhenTxOff() {
        val activeSessions = REGATTALINK_CONFIG_SESSION_CAN or
            REGATTALINK_CONFIG_SESSION_NMEA0183 or
            REGATTALINK_CONFIG_SESSION_IMU or
            REGATTALINK_CONFIG_SESSION_MAG or
            REGATTALINK_CONFIG_CALYPSO_ENABLE
        val config = RegattaLinkConfigurationState(
            configWordSupported = true,
            configWord = activeSessions
        )
        val namedSensor = RegattaLinkLoadSensor(
            identityKey = "load:1",
            measurementKey = "nmea.load.1",
            defaultLabel = "Load 1",
            alias = "Backstay",
            loadKg = 12.3,
            stableIdentity = true
        )
        val nmea = RegattaLinkNmeaState(
            loadSupported = true,
            loadSensors = listOf(namedSensor)
        )
        val shown = regattaLinkNmeaSetupVisibility(
            config, nmea, connected = true, calypsoSupported = true
        )
        assertTrue(shown.forward0183)
        assertTrue(shown.forwardAttitude)
        assertTrue(shown.forwardCompass)
        assertTrue(shown.forwardPhoneGps)
        assertTrue(shown.forwardCalypsoWind)
        assertTrue(shown.baudRate)
        assertTrue(shown.loadSensors)
        assertEquals("Backstay", namedSensor.label)

        val noSensors = regattaLinkNmeaSetupVisibility(
            config, nmea.copy(loadSensors = emptyList()),
            connected = true, calypsoSupported = true
        )
        assertFalse(noSensors.loadSensors)
        assertTrue(noSensors.forwardPhoneGps)

        val noCalypsoStatus = regattaLinkNmeaSetupVisibility(
            config, nmea, connected = true, calypsoSupported = false
        )
        assertFalse(noCalypsoStatus.forwardCalypsoWind)
        val disabledCalypso = regattaLinkNmeaSetupVisibility(
            config.copy(configWord = activeSessions and
                REGATTALINK_CONFIG_CALYPSO_ENABLE.inv()),
            nmea, connected = true, calypsoSupported = true
        )
        assertFalse(disabledCalypso.forwardCalypsoWind)

        val disconnected = regattaLinkNmeaSetupVisibility(
            config, nmea, connected = false, calypsoSupported = true
        )
        assertFalse(disconnected.loadSensors)
    }

    @Test
    fun nmea0183BaudRemainsAvailableWhenCanSessionIsOff() {
        val config = RegattaLinkConfigurationState(
            configWordSupported = true,
            configWord = REGATTALINK_CONFIG_SESSION_NMEA0183
        )
        val shown = regattaLinkNmeaSetupVisibility(
            config, RegattaLinkNmeaState(),
            connected = true, calypsoSupported = false
        )
        assertTrue(shown.baudRate)
        assertFalse(shown.forward0183)
    }

}
