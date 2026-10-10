package de.williserv.regattaclient

import org.junit.Assert.assertArrayEquals
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
    fun configWordRoundTripsAll32BitsLittleEndian() {
        val raw = byteArrayOf(0x78, 0x56, 0x34, 0xF2.toByte())
        val parsed = parseRegattaLinkConfigWord(raw)

        assertEquals(0xF2345678u, parsed)
        assertArrayEquals(raw, encodeRegattaLinkConfigWord(parsed))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongConfigWordLength() {
        parseRegattaLinkConfigWord(byteArrayOf(0, 0, 0))
    }

    @Test
    fun configWordBitMutationPreservesUnknownAndReservedBits() {
        val original = 0xA5A54000u or REGATTALINK_CONFIG_TX_NMEA0183
        val enabled = regattaLinkConfigWordWithBit(
            original,
            REGATTALINK_CONFIG_TX_COMPASS,
            true
        )
        val disabled = regattaLinkConfigWordWithBit(
            enabled,
            REGATTALINK_CONFIG_TX_COMPASS,
            false
        )

        assertTrue(enabled and REGATTALINK_CONFIG_TX_COMPASS != 0u)
        assertEquals(original, disabled)
        assertEquals(
            original and REGATTALINK_CONFIG_TX_COMPASS.inv(),
            enabled and REGATTALINK_CONFIG_TX_COMPASS.inv()
        )
    }

    @Test
    fun configWordDrivesExistingParityControlsFromOneAuthoritativeValue() {
        val word =
            REGATTALINK_CONFIG_SESSION_CAN or
                REGATTALINK_CONFIG_TX_IMU or
                REGATTALINK_CONFIG_LOAD_PRECISION_X10 or
                0x80000000u
        val state = regattaLinkApplyConfigWord(
            RegattaLinkConfigurationState(),
            word
        )

        assertTrue(state.configWordSupported)
        assertEquals(word, state.configWord)
        assertEquals(true, state.nmeaAttitudeTxEnabled)
        assertEquals(true, state.loadPrecisionX10)
    }

    @Test
    fun configWordDecodesAllAssignedV2ControlsAndBaud() {
        val word =
            REGATTALINK_CONFIG_SESSION_CAN or
                REGATTALINK_CONFIG_TX_IMU or
                REGATTALINK_CONFIG_TX_NMEA0183 or
                REGATTALINK_CONFIG_TX_PHONE_GPS or
                REGATTALINK_CONFIG_TX_COMPASS or
                REGATTALINK_CONFIG_LOAD_PRECISION_X10 or
                REGATTALINK_CONFIG_MAG_BACKGROUND_LEARNING or
                RegattaLinkNmea0183Baud.BAUD_38400.encodedBits
        val state = regattaLinkApplyConfigWord(
            RegattaLinkConfigurationState(),
            word
        )

        assertEquals(true, state.nmeaAttitudeTxEnabled)
        assertEquals(true, state.nmea0183TxEnabled)
        assertEquals(true, state.phoneGpsTxEnabled)
        assertEquals(true, state.compassTxEnabled)
        assertEquals(true, state.loadPrecisionX10)
        assertEquals(true, state.magBackgroundLearningEnabled)
        assertEquals(
            RegattaLinkNmea0183Baud.BAUD_38400,
            state.nmea0183Baud
        )
        assertTrue(state.phoneGnssForwardingDesired)
    }

    @Test
    fun configWordDecodesCalypsoEnableAndTxBits() {
        val state = regattaLinkApplyConfigWord(
            RegattaLinkConfigurationState(),
            REGATTALINK_CONFIG_CALYPSO_ENABLE or
                REGATTALINK_CONFIG_TX_CALYPSO_WIND
        )

        assertEquals(true, state.calypsoEnabled)
        assertEquals(true, state.calypsoWindTxEnabled)
    }

    @Test
    fun bluetoothDeviceDraftOwnsOnlyCalypsoEnableBit() {
        val original =
            0xa5a00000u or
                REGATTALINK_CONFIG_TX_CALYPSO_WIND or
                REGATTALINK_CONFIG_TX_LOAD
        val changed = regattaLinkConfigWordWithMask(
            current = original,
            mask = REGATTALINK_CONFIG_BLUETOOTH_DEVICE_MASK,
            encodedBits = REGATTALINK_CONFIG_CALYPSO_ENABLE
        )

        assertTrue(changed and REGATTALINK_CONFIG_CALYPSO_ENABLE != 0u)
        assertEquals(
            original and REGATTALINK_CONFIG_BLUETOOTH_DEVICE_MASK.inv(),
            changed and REGATTALINK_CONFIG_BLUETOOTH_DEVICE_MASK.inv()
        )
    }

    @Test
    fun decodesSubsystemSessionAvailabilityFromConfigWord() {
        val state = regattaLinkApplyConfigWord(
            RegattaLinkConfigurationState(),
            REGATTALINK_CONFIG_SESSION_IMU or
                REGATTALINK_CONFIG_SESSION_CAN
        )

        assertEquals(true, state.imuSessionAvailable)
        assertEquals(false, state.magSessionAvailable)
        assertEquals(true, state.canSessionAvailable)
        assertEquals(false, state.nmea0183SessionAvailable)
    }

    @Test
    fun allZeroSessionNibbleMeansNoSubsystemsAvailable() {
        val state = regattaLinkApplyConfigWord(
            RegattaLinkConfigurationState(),
            REGATTALINK_CONFIG_CAN_STATUS_MIRROR
        )

        assertEquals(false, state.imuSessionAvailable)
        assertEquals(false, state.magSessionAvailable)
        assertEquals(false, state.canSessionAvailable)
        assertEquals(false, state.nmea0183SessionAvailable)
    }

    @Test
    fun fullSessionNibbleMeansAllSubsystemsAvailable() {
        val state = regattaLinkApplyConfigWord(
            RegattaLinkConfigurationState(),
            REGATTALINK_CONFIG_SESSION_SUBSYSTEM_MASK
        )

        assertEquals(true, state.imuSessionAvailable)
        assertEquals(true, state.magSessionAvailable)
        assertEquals(true, state.canSessionAvailable)
        assertEquals(true, state.nmea0183SessionAvailable)
    }

    @Test
    fun configMutationsPreserveSubsystemSessionReadbackBits() {
        val original =
            REGATTALINK_CONFIG_SESSION_IMU or
                REGATTALINK_CONFIG_SESSION_CAN or
                REGATTALINK_CONFIG_TX_NMEA0183

        val changed = regattaLinkConfigWordWithBit(
            original,
            REGATTALINK_CONFIG_TX_COMPASS,
            true
        )

        assertEquals(
            original and REGATTALINK_CONFIG_SESSION_SUBSYSTEM_MASK,
            changed and REGATTALINK_CONFIG_SESSION_SUBSYSTEM_MASK
        )
    }

    @Test
    fun subsystemBitAssignmentsMatchRegattaLink285Contract() {
        assertEquals(0x00010000u, RegattaLinkSubsystem.IMU.configBit)
        assertEquals(0x00020000u, RegattaLinkSubsystem.MAG.configBit)
        assertEquals(0x00040000u, RegattaLinkSubsystem.BOAT_DATA.configBit)
        assertEquals(0x00080000u, RegattaLinkSubsystem.NMEA0183_RX.configBit)
        assertEquals(
            REGATTALINK_CONFIG_SESSION_SUBSYSTEM_MASK,
            RegattaLinkSubsystem.entries.fold(0u) { mask, subsystem ->
                mask or subsystem.configBit
            }
        )
    }

    @Test
    fun explicitSubsystemSwitchSeesAllZeroAsUnavailableAndOff() {
        val state = regattaLinkApplyConfigWord(
            RegattaLinkConfigurationState(),
            0u
        )

        assertEquals(false, state.imuSessionAvailable)
        assertEquals(false, state.magSessionAvailable)
        assertEquals(false, state.canSessionAvailable)
        assertEquals(false, state.nmea0183SessionAvailable)
        assertEquals(false, state.subsystemSessionBit(RegattaLinkSubsystem.IMU))
        assertEquals(false, state.subsystemSessionBit(RegattaLinkSubsystem.MAG))
        assertEquals(false, state.subsystemSessionBit(RegattaLinkSubsystem.BOAT_DATA))
        assertEquals(false, state.subsystemSessionBit(RegattaLinkSubsystem.NMEA0183_RX))
    }

    @Test
    fun subsystemMutationChangesOnlyRequestedSessionBit() {
        val original =
            0xa5a00000u or
                REGATTALINK_CONFIG_SESSION_IMU or
                REGATTALINK_CONFIG_SESSION_CAN or
                REGATTALINK_CONFIG_CAN_STATUS_MIRROR

        RegattaLinkSubsystem.entries.forEach { subsystem ->
            val enabled = regattaLinkConfigWordWithBit(
                original,
                subsystem.configBit,
                true
            )
            val disabled = regattaLinkConfigWordWithBit(
                original,
                subsystem.configBit,
                false
            )
            assertEquals(
                original and subsystem.configBit.inv(),
                enabled and subsystem.configBit.inv()
            )
            assertEquals(
                original and subsystem.configBit.inv(),
                disabled and subsystem.configBit.inv()
            )
        }
    }

    @Test
    fun txSelectionMaskContainsOnlyUserFacingTxSelectors() {
        assertEquals(
            REGATTALINK_CONFIG_TX_IMU or
                REGATTALINK_CONFIG_TX_NMEA0183 or
                REGATTALINK_CONFIG_TX_PHONE_GPS or
                REGATTALINK_CONFIG_TX_COMPASS or
                REGATTALINK_CONFIG_TX_CALYPSO_WIND,
            REGATTALINK_CONFIG_TX_SELECTION_MASK
        )
        assertEquals(
            0u,
            REGATTALINK_CONFIG_TX_SELECTION_MASK and
                REGATTALINK_CONFIG_CAN_STATUS_MIRROR
        )
        assertEquals(
            0u,
            REGATTALINK_CONFIG_TX_SELECTION_MASK and
                REGATTALINK_CONFIG_TX_LOAD
        )
        assertEquals(
            0u,
            REGATTALINK_CONFIG_TX_SELECTION_MASK and
                REGATTALINK_CONFIG_CALYPSO_ENABLE
        )
    }

    @Test
    fun groupedTxSelectionPreservesUnownedConfigBits() {
        val original =
            REGATTALINK_CONFIG_CAN_STATUS_MIRROR or
                0xa5a00000u or
                REGATTALINK_CONFIG_TX_LOAD or
                REGATTALINK_CONFIG_CALYPSO_ENABLE or
                REGATTALINK_CONFIG_LOAD_PRECISION_X10 or
                REGATTALINK_CONFIG_MAG_BACKGROUND_LEARNING or
                RegattaLinkNmea0183Baud.BAUD_38400.encodedBits
        val draft =
            REGATTALINK_CONFIG_TX_IMU or
                REGATTALINK_CONFIG_TX_COMPASS

        val changed = regattaLinkConfigWordWithMask(
            current = original,
            mask = REGATTALINK_CONFIG_TX_SELECTION_MASK,
            encodedBits = draft
        )

        assertEquals(
            original and REGATTALINK_CONFIG_TX_SELECTION_MASK.inv(),
            changed and REGATTALINK_CONFIG_TX_SELECTION_MASK.inv()
        )
        assertEquals(draft, changed and REGATTALINK_CONFIG_TX_SELECTION_MASK)
        assertEquals(
            original and REGATTALINK_CONFIG_CAN_STATUS_MIRROR,
            changed and REGATTALINK_CONFIG_CAN_STATUS_MIRROR
        )
    }

    @Test
    fun groupedSubsystemSelectionPreservesEveryOtherConfigBit() {
        val original =
            0xa5a00000u or
                REGATTALINK_CONFIG_SESSION_IMU or
                REGATTALINK_CONFIG_SESSION_CAN or
                REGATTALINK_CONFIG_CAN_STATUS_MIRROR or
                REGATTALINK_CONFIG_TX_LOAD or
                REGATTALINK_CONFIG_LOAD_PRECISION_X10
        val draft =
            REGATTALINK_CONFIG_SESSION_MAG or
                REGATTALINK_CONFIG_SESSION_NMEA0183

        val changed = regattaLinkConfigWordWithMask(
            current = original,
            mask = REGATTALINK_CONFIG_SESSION_SUBSYSTEM_MASK,
            encodedBits = draft
        )

        assertEquals(
            original and REGATTALINK_CONFIG_SESSION_SUBSYSTEM_MASK.inv(),
            changed and REGATTALINK_CONFIG_SESSION_SUBSYSTEM_MASK.inv()
        )
        assertEquals(
            draft,
            changed and REGATTALINK_CONFIG_SESSION_SUBSYSTEM_MASK
        )
    }

    @Test
    fun configDraftHelpersKeepPendingSelectionSeparateFromBaseline() {
        val baseline =
            REGATTALINK_CONFIG_CAN_STATUS_MIRROR or
                REGATTALINK_CONFIG_TX_PHONE_GPS
        val draft = regattaLinkConfigDraftWithBit(
            baseline,
            REGATTALINK_CONFIG_TX_PHONE_GPS,
            false
        )

        assertEquals(true, regattaLinkConfigDraftBit(baseline, REGATTALINK_CONFIG_TX_PHONE_GPS))
        assertEquals(false, regattaLinkConfigDraftBit(draft, REGATTALINK_CONFIG_TX_PHONE_GPS))
        assertTrue(
            regattaLinkConfigDraftBitChanged(
                baseline,
                draft,
                REGATTALINK_CONFIG_TX_PHONE_GPS
            )
        )
        assertFalse(
            regattaLinkConfigDraftBitChanged(
                baseline,
                draft,
                REGATTALINK_CONFIG_CAN_STATUS_MIRROR
            )
        )
    }

    @Test
    fun baudEncodingUsesOnlyBits14And15AndPreservesEverythingElse() {
        val original = 0xa5a53fffu
        RegattaLinkNmea0183Baud.entries.forEach { baud ->
            val changed = regattaLinkConfigWordWithMask(
                current = original,
                mask = REGATTALINK_CONFIG_NMEA0183_BAUD_MASK,
                encodedBits = baud.encodedBits
            )
            assertEquals(
                original and REGATTALINK_CONFIG_NMEA0183_BAUD_MASK.inv(),
                changed and REGATTALINK_CONFIG_NMEA0183_BAUD_MASK.inv()
            )
            assertEquals(baud, RegattaLinkNmea0183Baud.fromConfigWord(changed))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun configMaskRejectsBitsOutsideRequestedField() {
        regattaLinkConfigWordWithMask(
            current = 0u,
            mask = REGATTALINK_CONFIG_TX_IMU,
            encodedBits = REGATTALINK_CONFIG_TX_PHONE_GPS
        )
    }

    @Test
    fun headingTrimRoundTripsSignedLittleEndianBoundaries() {
        listOf(-180, 0, 180).forEach { value ->
            val encoded = encodeRegattaLinkHeadingTrim(value)
            assertEquals(2, encoded.size)
            assertEquals(value, parseRegattaLinkHeadingTrim(encoded))
        }
        assertArrayEquals(
            byteArrayOf(0x4c, 0xff.toByte()),
            encodeRegattaLinkHeadingTrim(-180)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun headingTrimRejectsOutOfRangeWrite() {
        encodeRegattaLinkHeadingTrim(181)
    }

    @Test(expected = IllegalArgumentException::class)
    fun headingTrimRejectsWrongLength() {
        parseRegattaLinkHeadingTrim(byteArrayOf(0))
    }

    @Test
    fun configDirtyStateMakesExistingRestartActionAvailable() {
        assertFalse(regattaLinkNmeaRestartRequired(RegattaLinkConfigurationState()))
        assertTrue(
            regattaLinkNmeaRestartRequired(
                RegattaLinkConfigurationState(configRestartRequired = true)
            )
        )
    }

    @Test
    fun globalTxStatusMirrorDoesNotTriggerRestart() {
        val state = RegattaLinkConfigurationState(
            configWordSupported = true,
            configWord = REGATTALINK_CONFIG_CAN_STATUS_MIRROR or
                REGATTALINK_CONFIG_SESSION_CAN,
            nmeaTxRuntimeStatusSupported = true,
            nmeaTxBootSelected = false,
            nmeaBootOutputMask = 0
        )
        assertFalse(regattaLinkNmeaRestartRequired(state))
        assertEquals(
            0u,
            state.configWord!! and REGATTALINK_CONFIG_TX_SELECTION_MASK
        )
    }

    @Test
    fun reconnectStillRequiresRestartWhenPhoneGpsDesiredDiffersFromBootMask() {
        val state = RegattaLinkConfigurationState(
            configWordSupported = true,
            configWord =
                REGATTALINK_CONFIG_CAN_STATUS_MIRROR or
                    REGATTALINK_CONFIG_TX_PHONE_GPS,
            configRestartRequired = false,
            nmeaTxRuntimeStatusSupported = true,
            nmeaTxBootSelected = true,
            nmeaBootOutputMask = 0,
            nmeaActiveOutputMask = 0
        )

        assertTrue(regattaLinkNmeaRestartRequired(state))
    }

    @Test
    fun reconnectDetectsEveryAssignedOutputSelectorDifference() {
        val cases = listOf(
            REGATTALINK_CONFIG_TX_IMU to REGATTALINK_TX_OUTPUT_IMU,
            REGATTALINK_CONFIG_TX_NMEA0183 to REGATTALINK_TX_OUTPUT_NMEA0183,
            REGATTALINK_CONFIG_TX_PHONE_GPS to REGATTALINK_TX_OUTPUT_PHONE_GPS,
            REGATTALINK_CONFIG_TX_COMPASS to REGATTALINK_TX_OUTPUT_COMPASS,
            REGATTALINK_CONFIG_TX_CALYPSO_WIND to
                REGATTALINK_TX_OUTPUT_CALYPSO_WIND
        )

        cases.forEach { (configBit, runtimeBit) ->
            val pending = RegattaLinkConfigurationState(
                configWordSupported = true,
                configWord = configBit,
                nmeaTxRuntimeStatusSupported = true,
                nmeaTxBootSelected = false,
                nmeaBootOutputMask = 0
            )
            assertTrue(regattaLinkNmeaRestartRequired(pending))

            val applied = pending.copy(
                nmeaBootOutputMask = runtimeBit
            )
            assertFalse(regattaLinkNmeaRestartRequired(applied))
        }
    }

    @Test
    fun calypsoControlStatusUpdatesDedicatedStateWithoutReplacingCalibrationStatus() {
        val calibrationStatus = RegattaLinkDeviceControlStatus(
            opcode = RegattaLinkDeviceControlOpcode.ADJUST_HEEL,
            phase = RegattaLinkDeviceControlPhase.SUCCESS,
            result = RegattaLinkDeviceControlResult.OK,
            requestId = 1u,
            forwardTrimDeg = 1,
            heelTrimDeg = 2,
            pitchTrimDeg = 3,
            boatFrameValid = true,
            gyroBiasValid = true,
            mountingEpoch = 4u
        )
        val calypsoStatus = RegattaLinkDeviceControlStatus(
            opcode = RegattaLinkDeviceControlOpcode.CALYPSO_STATUS,
            phase = RegattaLinkDeviceControlPhase.SUCCESS,
            result = RegattaLinkDeviceControlResult.OK,
            requestId = 2u,
            forwardTrimDeg = 0,
            heelTrimDeg = 0,
            pitchTrimDeg = 0,
            boatFrameValid = false,
            gyroBiasValid = false,
            mountingEpoch = 0u,
            calypso = RegattaLinkCalypsoControlStatus(
                boundId = "AA:BB:CC:DD:EE:FF",
                bound = true,
                connected = true,
                scanning = false,
                detail = 0
            )
        )

        val state = regattaLinkApplyObservedDeviceControlStatus(
            RegattaLinkConfigurationState(
                deviceControlStatus = calibrationStatus
            ),
            calypsoStatus
        )

        assertEquals(calibrationStatus, state.deviceControlStatus)
        assertTrue(state.calypso.statusKnown)
        assertEquals("AA:BB:CC:DD:EE:FF", state.calypso.boundId)
        assertTrue(state.calypso.connected)
    }

    @Test
    fun failedCalypsoScanKeepsReportedOldBindingAndSurvivesStatusRefresh() {
        val failed = RegattaLinkDeviceControlStatus(
            opcode = RegattaLinkDeviceControlOpcode.CALYPSO_SCAN,
            phase = RegattaLinkDeviceControlPhase.ERROR,
            result = RegattaLinkDeviceControlResult.AMBIGUOUS,
            requestId = 3u,
            forwardTrimDeg = 0,
            heelTrimDeg = 0,
            pitchTrimDeg = 0,
            boatFrameValid = false,
            gyroBiasValid = false,
            mountingEpoch = 0u,
            calypso = RegattaLinkCalypsoControlStatus(
                boundId = "11:22:33:44:55:66",
                bound = true,
                connected = false,
                scanning = false,
                detail = 0
            )
        )
        val afterFailure = regattaLinkApplyCalypsoControlStatus(
            RegattaLinkConfigurationState(),
            failed
        )
        val refreshed = regattaLinkApplyCalypsoControlStatus(
            afterFailure,
            failed.copy(
                opcode = RegattaLinkDeviceControlOpcode.CALYPSO_STATUS,
                phase = RegattaLinkDeviceControlPhase.SUCCESS,
                result = RegattaLinkDeviceControlResult.OK,
                requestId = 4u
            )
        )

        assertEquals(
            RegattaLinkDeviceControlResult.AMBIGUOUS,
            refreshed.calypso.lastScanResult
        )
        assertEquals("11:22:33:44:55:66", refreshed.calypso.boundId)
    }

    @Test
    fun parsesRuntimeStatusV2AndPreservesFutureOutputBits() {
        val parsed = parseRegattaLinkNmeaTxRuntimeStatus(
            byteArrayOf(
                2,
                0x03,
                (
                    REGATTALINK_TX_OUTPUT_IMU or
                        REGATTALINK_TX_OUTPUT_NMEA0183 or
                        0x80
                    ).toByte(),
                REGATTALINK_TX_OUTPUT_IMU.toByte()
            )
        )

        assertTrue(parsed.canInterfaceBootEnabled)
        assertTrue(parsed.activeNode)
        assertTrue(parsed.bootAttitudeSelected)
        assertTrue(parsed.attitudeActive)
        assertTrue(
            parsed.bootOutputMask and REGATTALINK_TX_OUTPUT_NMEA0183 != 0
        )
        assertTrue(parsed.bootOutputMask and 0x80 != 0)
        assertFalse(
            parsed.activeOutputMask and REGATTALINK_TX_OUTPUT_NMEA0183 != 0
        )
    }

    @Test
    fun individualTxSelectionAndBootAppliedStateRemainSeparateAcrossRestartLifecycle() {
        val bootOff = regattaLinkApplyNmeaTxRuntimeStatus(
            regattaLinkApplyConfigWord(
                RegattaLinkConfigurationState(),
                0u
            ),
            RegattaLinkNmeaTxRuntimeStatus(
                canInterfaceBootEnabled = false,
                activeNode = false,
                bootOutputMask = 0,
                activeOutputMask = 0
            )
        )
        assertFalse(regattaLinkNmeaRestartRequired(bootOff))

        val selectedOn = regattaLinkApplyConfigWord(
            bootOff,
            REGATTALINK_CONFIG_CAN_STATUS_MIRROR or REGATTALINK_CONFIG_TX_IMU
        )
        assertTrue(selectedOn.nmeaAttitudeTxRestartRequired)

        val afterRestart = regattaLinkApplyNmeaTxRuntimeStatus(
            selectedOn.copy(configRestartRequired = false),
            RegattaLinkNmeaTxRuntimeStatus(
                canInterfaceBootEnabled = true,
                activeNode = true,
                bootOutputMask = REGATTALINK_TX_OUTPUT_IMU,
                activeOutputMask = REGATTALINK_TX_OUTPUT_IMU
            )
        )
        assertFalse(regattaLinkNmeaRestartRequired(afterRestart))
        assertEquals(true, afterRestart.nmeaTxActive)
    }

    @Test
    fun missingRuntimeStatusDoesNotInventAppliedState() {
        val pending = regattaLinkApplyConfigWord(
            RegattaLinkConfigurationState(),
            REGATTALINK_CONFIG_CAN_STATUS_MIRROR
        )

        assertTrue(regattaLinkNmeaAppliedStateUnknown(pending))
        assertNull(pending.nmeaTxBootSelected)
        assertNull(pending.nmeaTxActive)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongRuntimeStatusLength() {
        parseRegattaLinkNmeaTxRuntimeStatus(byteArrayOf(2, 0, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLegacyRuntimeStatusVersion() {
        parseRegattaLinkNmeaTxRuntimeStatus(byteArrayOf(1, 0, 0, 0))
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

    @Test
    fun diagnosticLogKeepsNewestTwoThousandAndAllowsRepeatedMessages() {
        val entries = mutableListOf<RegattaLinkDiagnosticLogEntry>()
        for (i in 0 until 2_010) {
            regattaLinkAppendDiagnosticEntry(
                entries,
                RegattaLinkDiagnosticLogEntry(timestamp10ms = i, message = "CAN")
            )
        }
        assertEquals(2_000, entries.size)
        assertEquals(10, entries.first().timestamp10ms)
        assertEquals(2_009, entries.last().timestamp10ms)

        val repeated = entries.last()
        regattaLinkAppendDiagnosticEntry(entries, repeated)
        assertEquals(2_000, entries.size)
        assertEquals(repeated, entries.last())
        assertEquals(repeated, entries[entries.lastIndex - 1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongBoatStateSize() {
        parseRegattaLinkBoatState(ByteArray(79))
    }
}
