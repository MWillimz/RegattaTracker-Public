package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RegattaLinkConfigurationNmeaTest {

    @Test
    fun validatesDeviceNameWithTypedErrorsAndUtf8ByteBoundaries() {
        assertEquals(
            RegattaLinkDeviceNameValidationError.EMPTY,
            validateRegattaLinkDeviceName("")
        )
        assertNull(validateRegattaLinkDeviceName("RegattaLink-31B2"))

        assertNull(validateRegattaLinkDeviceName("a".repeat(24)))
        assertEquals(
            RegattaLinkDeviceNameValidationError.TOO_LONG_UTF8,
            validateRegattaLinkDeviceName("a".repeat(25))
        )

        // 'ä' uses two UTF-8 bytes, so 12 characters are exactly 24 bytes.
        assertNull(validateRegattaLinkDeviceName("ä".repeat(12)))
        assertEquals(
            RegattaLinkDeviceNameValidationError.TOO_LONG_UTF8,
            validateRegattaLinkDeviceName("ä".repeat(13))
        )

        // '船' uses three UTF-8 bytes, exercising a different multibyte boundary.
        assertNull(validateRegattaLinkDeviceName("船".repeat(8)))
        assertEquals(
            RegattaLinkDeviceNameValidationError.TOO_LONG_UTF8,
            validateRegattaLinkDeviceName("船".repeat(9))
        )
    }

    @Test
    fun rejectsUnsupportedDeviceNameControlCharactersWithTypedError() {
        listOf(
            "bad\nname",
            "bad\u0000name",
            "bad\u007fname"
        ).forEach { name ->
            assertEquals(
                RegattaLinkDeviceNameValidationError.UNSUPPORTED_CONTROL_CHARACTER,
                validateRegattaLinkDeviceName(name)
            )
        }
    }

    @Test
    fun parsesLedBrightnessStrictly() {
        assertEquals(0, parseRegattaLinkLedBrightness(byteArrayOf(0)))
        assertEquals(50, parseRegattaLinkLedBrightness(byteArrayOf(50)))
        assertEquals(100, parseRegattaLinkLedBrightness(byteArrayOf(100)))
    }

    @Test
    fun configSliderSubmissionReturnsDraftToConfirmedFirmwareValue() {
        val brightness = prepareRegattaLinkConfigSliderSubmission(
            draftValue = 75.4f,
            confirmedValue = 50,
            validRange = 0..100
        )
        assertEquals(75, brightness.requestedValue)
        assertEquals(50f, brightness.confirmedDraft)

        val damping = prepareRegattaLinkConfigSliderSubmission(
            draftValue = 5.2f,
            confirmedValue = 3,
            validRange = 1..10
        )
        assertEquals(5, damping.requestedValue)
        assertEquals(3f, damping.confirmedDraft)
    }

    @Test
    fun configSliderSubmissionKeepsRangeClamping() {
        val brightness = prepareRegattaLinkConfigSliderSubmission(
            draftValue = 120f,
            confirmedValue = 50,
            validRange = 0..100
        )
        assertEquals(100, brightness.requestedValue)
        assertEquals(50f, brightness.confirmedDraft)

        val damping = prepareRegattaLinkConfigSliderSubmission(
            draftValue = 0f,
            confirmedValue = 3,
            validRange = 1..10
        )
        assertEquals(1, damping.requestedValue)
        assertEquals(3f, damping.confirmedDraft)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangeLedBrightness() {
        parseRegattaLinkLedBrightness(byteArrayOf(101))
    }

    @Test
    fun parsesLoadPrecisionStrictly() {
        assertFalse(parseRegattaLinkLoadPrecision(byteArrayOf(0)))
        assertTrue(parseRegattaLinkLoadPrecision(byteArrayOf(1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidLoadPrecision() {
        parseRegattaLinkLoadPrecision(byteArrayOf(2))
    }

    @Test
    fun parsesNmeaTxSettingsStrictlyAndTracksRestartRequirement() {
        assertFalse(parseRegattaLinkNmeaTxEnabled(byteArrayOf(0)))
        assertTrue(parseRegattaLinkNmeaTxEnabled(byteArrayOf(1)))
        assertFalse(parseRegattaLinkNmeaAttitudeTxEnabled(byteArrayOf(0)))
        assertTrue(parseRegattaLinkNmeaAttitudeTxEnabled(byteArrayOf(1)))

        assertFalse(regattaLinkNmeaRestartRequired(RegattaLinkConfigurationState()))
        assertTrue(
            regattaLinkNmeaRestartRequired(
                RegattaLinkConfigurationState(nmeaTxRestartRequired = true)
            )
        )
        assertTrue(
            regattaLinkNmeaRestartRequired(
                RegattaLinkConfigurationState(
                    nmeaAttitudeTxRestartRequired = true
                )
            )
        )
    }

    @Test
    fun parsesNmeaRuntimeStatusAndPreservesFutureOutputBits() {
        val parsed = parseRegattaLinkNmeaTxRuntimeStatus(
            byteArrayOf(
                1,
                0x03,
                (
                    REGATTALINK_NMEA_TX_OUTPUT_ATTITUDE or
                        REGATTALINK_NMEA_TX_OUTPUT_TRACKER_GNSS
                    ).toByte(),
                REGATTALINK_NMEA_TX_OUTPUT_ATTITUDE.toByte()
            )
        )

        assertTrue(parsed.bootMasterSelected)
        assertTrue(parsed.masterActive)
        assertTrue(parsed.bootAttitudeSelected)
        assertTrue(parsed.attitudeActive)
        assertTrue(
            parsed.bootOutputMask and REGATTALINK_NMEA_TX_OUTPUT_TRACKER_GNSS != 0
        )
        assertFalse(
            parsed.activeOutputMask and REGATTALINK_NMEA_TX_OUTPUT_TRACKER_GNSS != 0
        )
    }

    @Test
    fun selectedAndBootAppliedNmeaStateRemainSeparateAcrossRestartLifecycle() {
        val bootOff = regattaLinkApplyNmeaTxRuntimeStatus(
            RegattaLinkConfigurationState(
                nmeaTxSupported = true,
                nmeaTxEnabled = false,
                nmeaAttitudeTxSupported = true,
                nmeaAttitudeTxEnabled = false
            ),
            RegattaLinkNmeaTxRuntimeStatus(
                bootMasterSelected = false,
                masterActive = false,
                bootOutputMask = 0,
                activeOutputMask = 0
            )
        )
        assertFalse(regattaLinkNmeaRestartRequired(bootOff))

        val selectedOn = regattaLinkReconcileNmeaTxState(
            bootOff.copy(
                nmeaTxEnabled = true,
                nmeaAttitudeTxEnabled = true
            )
        )
        assertTrue(selectedOn.nmeaTxRestartRequired)
        assertTrue(selectedOn.nmeaAttitudeTxRestartRequired)

        val afterRestart = regattaLinkApplyNmeaTxRuntimeStatus(
            selectedOn,
            RegattaLinkNmeaTxRuntimeStatus(
                bootMasterSelected = true,
                masterActive = true,
                bootOutputMask = REGATTALINK_NMEA_TX_OUTPUT_ATTITUDE,
                activeOutputMask = REGATTALINK_NMEA_TX_OUTPUT_ATTITUDE
            )
        )
        assertFalse(regattaLinkNmeaRestartRequired(afterRestart))
        assertEquals(true, afterRestart.nmeaTxActive)
    }

    @Test
    fun factoryResetSelectionDoesNotHideStillAppliedTxBootState() {
        val afterFactoryResetReconnect = regattaLinkApplyNmeaTxRuntimeStatus(
            RegattaLinkConfigurationState(
                nmeaTxSupported = true,
                nmeaTxEnabled = false,
                nmeaAttitudeTxSupported = true,
                nmeaAttitudeTxEnabled = false
            ),
            RegattaLinkNmeaTxRuntimeStatus(
                bootMasterSelected = true,
                masterActive = true,
                bootOutputMask = REGATTALINK_NMEA_TX_OUTPUT_ATTITUDE,
                activeOutputMask = REGATTALINK_NMEA_TX_OUTPUT_ATTITUDE
            )
        )

        assertTrue(afterFactoryResetReconnect.nmeaTxRestartRequired)
        assertTrue(afterFactoryResetReconnect.nmeaAttitudeTxRestartRequired)
        assertEquals(true, afterFactoryResetReconnect.nmeaTxActive)
    }

    @Test
    fun missingRuntimeStatusDoesNotInventAppliedState() {
        val pending = regattaLinkReconcileNmeaTxState(
            RegattaLinkConfigurationState(
                nmeaTxSupported = true,
                nmeaTxEnabled = false,
                nmeaTxRestartRequired = true,
                nmeaTxRuntimeStatusSupported = false
            )
        )

        assertTrue(pending.nmeaTxRestartRequired)
        assertNull(pending.nmeaTxBootSelected)
        assertNull(pending.nmeaTxActive)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongNmeaRuntimeStatusLength() {
        parseRegattaLinkNmeaTxRuntimeStatus(byteArrayOf(1, 0, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownNmeaRuntimeStatusVersion() {
        parseRegattaLinkNmeaTxRuntimeStatus(byteArrayOf(2, 0, 0, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidNmeaTxSetting() {
        parseRegattaLinkNmeaTxEnabled(byteArrayOf(2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidNmeaAttitudeTxSettingLength() {
        parseRegattaLinkNmeaAttitudeTxEnabled(byteArrayOf(0, 1))
    }

    @Test
    fun parsesMotionDampingStrictly() {
        assertEquals(1, parseRegattaLinkMotionDamping(byteArrayOf(1)))
        assertEquals(3, parseRegattaLinkMotionDamping(byteArrayOf(3)))
        assertEquals(10, parseRegattaLinkMotionDamping(byteArrayOf(10)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMotionDampingBelowRange() {
        parseRegattaLinkMotionDamping(byteArrayOf(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMotionDampingAboveRange() {
        parseRegattaLinkMotionDamping(byteArrayOf(11))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMotionDampingWrongLength() {
        parseRegattaLinkMotionDamping(byteArrayOf(3, 3))
    }

    @Test
    fun configurationMutationPolicyBlocksDeviceControlAndResetOwnership() {
        assertFalse(
            regattaLinkConfigurationMutationBlocked(
                RegattaLinkConfigurationState()
            )
        )
        assertTrue(
            regattaLinkConfigurationMutationBlocked(
                RegattaLinkConfigurationState(deviceControlBusy = true)
            )
        )
        assertTrue(
            regattaLinkConfigurationMutationBlocked(
                RegattaLinkConfigurationState(
                    factoryResetAwaitingDisconnect = true
                )
            )
        )
        assertTrue(
            regattaLinkConfigurationMutationBlocked(
                RegattaLinkConfigurationState(
                    factoryResetWriteAcceptedRequestId = 9u
                )
            )
        )
        assertTrue(
            regattaLinkConfigurationMutationBlocked(
                RegattaLinkConfigurationState(
                    restartAwaitingDisconnect = true
                )
            )
        )
        assertTrue(
            regattaLinkConfigurationMutationBlocked(
                RegattaLinkConfigurationState(),
                factoryResetOwned = true
            )
        )
        assertTrue(
            regattaLinkConfigurationMutationBlocked(
                RegattaLinkConfigurationState(),
                deviceControlRunning = true
            )
        )
        assertTrue(
            regattaLinkConfigurationMutationBlocked(
                RegattaLinkConfigurationState(diagnosticLogLoading = true)
            )
        )
        assertTrue(
            regattaLinkConfigurationMutationBlocked(
                RegattaLinkConfigurationState(),
                diagnosticLogRunning = true
            )
        )
    }

    @Test
    fun firmwareInstallPolicyStaysBlockedThroughResetDisconnectOwnership() {
        assertFalse(
            regattaLinkFirmwareInstallBlocked(
                RegattaLinkConfigurationState()
            )
        )
        assertTrue(
            regattaLinkFirmwareInstallBlocked(
                RegattaLinkConfigurationState(busy = true)
            )
        )
        assertTrue(
            regattaLinkFirmwareInstallBlocked(
                RegattaLinkConfigurationState(deviceControlBusy = true)
            )
        )
        assertTrue(
            regattaLinkFirmwareInstallBlocked(
                RegattaLinkConfigurationState(
                    diagnosticLogLoading = true
                )
            )
        )
        assertTrue(
            regattaLinkFirmwareInstallBlocked(
                RegattaLinkConfigurationState(
                    factoryResetAwaitingDisconnect = true
                )
            )
        )
        assertTrue(
            regattaLinkFirmwareInstallBlocked(
                RegattaLinkConfigurationState(
                    factoryResetWriteAcceptedRequestId = 11u
                )
            )
        )
    }

    @Test
    fun parsesPgnInventoryAsUnsignedLittleEndianValues() {
        val raw = ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(129025)
            .putInt(250)
            .putInt(0xfedcba98.toInt())
            .putInt(0xffffffff.toInt())
            .array()

        val parsed = parseRegattaLinkPgnInventory(raw)

        assertEquals(2, parsed.size)
        assertEquals(129025L, parsed[0].pgn)
        assertEquals(250L, parsed[0].lastSeenMs)
        assertEquals(0xfedcba98L, parsed[1].pgn)
        assertEquals(0xffffffffL, parsed[1].lastSeenMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedPgnInventoryLength() {
        parseRegattaLinkPgnInventory(ByteArray(7))
    }

    @Test
    fun parsesRawCanFrameWithoutLosingUnsignedTimestampOrIdentifier() {
        val raw = ByteArray(REGATTALINK_RAW_CAN_RECORD_SIZE)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        raw[0] = 1
        raw[1] = 7
        raw[2] = 1
        buffer.putInt(4, 0xffffffff.toInt())
        buffer.putInt(8, 0x1abcdeff)
        raw[12] = 3
        raw[13] = 0x11
        raw[14] = 0x22
        raw[15] = 0x33

        val parsed = parseRegattaLinkRawCanRead(raw)

        assertEquals(7, parsed.remainingCount)
        val frame = requireNotNull(parsed.frame)
        assertEquals(0xffffffffL, frame.timestampUsLow)
        assertEquals(0x1abcdeffL, frame.canId)
        assertEquals(3, frame.dlc)
        assertEquals("112233", frame.dataHex)
    }

    @Test
    fun parsesEmptyRawCanRecord() {
        val raw = ByteArray(REGATTALINK_RAW_CAN_RECORD_SIZE)
        raw[0] = 1

        val parsed = parseRegattaLinkRawCanRead(raw)

        assertEquals(0, parsed.remainingCount)
        assertNull(parsed.frame)
    }

    @Test
    fun zeroValidityBoatStateIsAvailableButContainsNoMeasurements() {
        val raw = ByteArray(REGATTALINK_BOAT_STATE_RECORD_SIZE)
        raw[0] = 1
        raw[1] = REGATTALINK_BOAT_STATE_RECORD_SIZE.toByte()

        val parsed = parseRegattaLinkBoatState(raw)

        assertFalse(parsed.hasAnyValidData)
        assertNull(parsed.headingDeg)
        assertNull(parsed.depthM)
        assertNull(parsed.latitudeDeg)
    }

    @Test
    fun parsesBoatStateValiditySignednessAndScaling() {
        val raw = ByteArray(REGATTALINK_BOAT_STATE_RECORD_SIZE)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        raw[0] = 1
        raw[1] = REGATTALINK_BOAT_STATE_RECORD_SIZE.toByte()
        buffer.putShort(2, 0xffff.toShort())
        buffer.putInt(4, 0xffffffff.toInt())

        val validity =
            (1 shl 0) or
                (1 shl 3) or
                (1 shl 8) or
                (1 shl 12) or
                (1 shl 13) or
                (1 shl 15) or
                (1 shl 17) or
                (1 shl 18)
        buffer.putInt(8, validity)

        buffer.putShort(12, 12345.toShort())
        raw[14] = 1
        buffer.putShort(20, (-250).toShort())
        buffer.putShort(26, 9999.toShort())
        buffer.putInt(32, 123456)
        buffer.putInt(48, 512_345_678)
        buffer.putInt(52, 123_456_789)
        buffer.putShort(56, 27890.toShort())
        raw[60] = 0
        raw[62] = 2
        raw[63] = 3
        raw[64] = 11
        buffer.putShort(66, 85.toShort())
        buffer.putShort(68, 140.toShort())
        buffer.putShort(74, 650.toShort())
        buffer.putShort(76, 22500.toShort())
        raw[78] = 4

        val parsed = parseRegattaLinkBoatState(raw)

        assertTrue(parsed.hasAnyValidData)
        assertEquals(65535, parsed.sequence)
        assertEquals(0xffffffffL, parsed.timestampMs)
        assertEquals(123.45, parsed.headingDeg!!, 0.001)
        assertEquals(-2.5, parsed.rateOfTurnDps!!, 0.001)
        assertNull(parsed.rollDeg)
        assertEquals(1234.56, parsed.depthM!!, 0.001)
        assertEquals(51.2345678, parsed.latitudeDeg!!, 0.0000001)
        assertEquals(12.3456789, parsed.longitudeDeg!!, 0.0000001)
        assertEquals(278.90, parsed.cogDeg!!, 0.001)
        assertEquals(11, parsed.satellites)
        assertEquals(0.85, parsed.hdop!!, 0.001)
        assertEquals(6.5, parsed.windSpeedMps!!, 0.001)
        assertEquals(225.0, parsed.windAngleDeg!!, 0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongBoatStateSize() {
        parseRegattaLinkBoatState(ByteArray(79))
    }
}
