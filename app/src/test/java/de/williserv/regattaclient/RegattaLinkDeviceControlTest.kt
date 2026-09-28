package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RegattaLinkDeviceControlTest {

    @Test
    fun diagnosticLogEmptyResponseEndsDrain() {
        assertNull(parseRegattaLinkDiagnosticLogEntry(byteArrayOf()))
    }

    @Test
    fun diagnosticLogParsesLittleEndianTimestampAndZeroPaddedAscii() {
        val raw = ByteArray(REGATTALINK_DIAGNOSTIC_LOG_RECORD_SIZE)
        ByteBuffer.wrap(raw)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0, 0xffff.toShort())
        "FACTORY reset".toByteArray(Charsets.US_ASCII)
            .copyInto(raw, destinationOffset = 2)

        val parsed = requireNotNull(parseRegattaLinkDiagnosticLogEntry(raw))

        assertEquals(0xffff, parsed.timestamp10ms)
        assertEquals("FACTORY reset", parsed.message)
    }

    @Test(expected = IllegalArgumentException::class)
    fun diagnosticLogRejectsWrongRecordSize() {
        parseRegattaLinkDiagnosticLogEntry(ByteArray(21))
    }

    @Test(expected = IllegalArgumentException::class)
    fun diagnosticLogRejectsNonAsciiPayload() {
        val raw = ByteArray(REGATTALINK_DIAGNOSTIC_LOG_RECORD_SIZE)
        raw[2] = 0x1f
        parseRegattaLinkDiagnosticLogEntry(raw)
    }

    @Test
    fun deviceControlRequestUsesExactLittleEndianWireFormat() {
        val raw = buildRegattaLinkDeviceControlRequest(
            opcode = RegattaLinkDeviceControlOpcode.ADJUST_FORWARD,
            requestId = 0x89abcdefu,
            value = -1
        )

        assertEquals(REGATTALINK_DEVICE_CONTROL_REQUEST_SIZE, raw.size)
        assertEquals(1, raw[0].toInt() and 0xff)
        assertEquals(2, raw[1].toInt() and 0xff)
        assertEquals(0xef, raw[2].toInt() and 0xff)
        assertEquals(0xcd, raw[3].toInt() and 0xff)
        assertEquals(0xab, raw[4].toInt() and 0xff)
        assertEquals(0x89, raw[5].toInt() and 0xff)
        assertEquals(0xff, raw[6].toInt() and 0xff)
        assertEquals(0xff, raw[7].toInt() and 0xff)
    }

    @Test(expected = IllegalArgumentException::class)
    fun deviceControlRejectsZeroRequestId() {
        buildRegattaLinkDeviceControlRequest(
            RegattaLinkDeviceControlOpcode.SET_UPRIGHT,
            0u,
            0
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun setUprightRejectsNonZeroValue() {
        buildRegattaLinkDeviceControlRequest(
            RegattaLinkDeviceControlOpcode.SET_UPRIGHT,
            1u,
            1
        )
    }

    @Test
    fun deviceControlStatusParsesSignedTrimsFlagsAndUnsignedFields() {
        val raw = ByteArray(REGATTALINK_DEVICE_CONTROL_STATUS_SIZE)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        raw[0] = 1
        raw[1] = RegattaLinkDeviceControlOpcode.ADJUST_HEEL.wireValue.toByte()
        raw[2] = RegattaLinkDeviceControlPhase.SUCCESS.wireValue.toByte()
        raw[3] = RegattaLinkDeviceControlResult.OK.wireValue.toByte()
        buffer.putInt(4, 0x89abcdef.toInt())
        buffer.putShort(8, (-12).toShort())
        buffer.putShort(10, 34.toShort())
        buffer.putShort(12, (-56).toShort())
        raw[14] = 0x03
        buffer.putInt(16, 0xfedcba98.toInt())

        val parsed = parseRegattaLinkDeviceControlStatus(raw)

        assertEquals(RegattaLinkDeviceControlOpcode.ADJUST_HEEL, parsed.opcode)
        assertEquals(RegattaLinkDeviceControlPhase.SUCCESS, parsed.phase)
        assertEquals(RegattaLinkDeviceControlResult.OK, parsed.result)
        assertEquals(0x89abcdefu, parsed.requestId)
        assertEquals(-12, parsed.forwardTrimDeg)
        assertEquals(34, parsed.heelTrimDeg)
        assertEquals(-56, parsed.pitchTrimDeg)
        assertTrue(parsed.boatFrameValid)
        assertTrue(parsed.gyroBiasValid)
        assertFalse(parsed.factoryResetBondsCleared)
        assertEquals(0xfedcba98u, parsed.mountingEpoch)
    }

    @Test
    fun deviceControlStatusParsesConfirmedFactoryResetBondWipeFlag() {
        val raw = ByteArray(REGATTALINK_DEVICE_CONTROL_STATUS_SIZE)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        raw[0] = 1
        raw[1] = RegattaLinkDeviceControlOpcode.FACTORY_RESET.wireValue.toByte()
        raw[2] = RegattaLinkDeviceControlPhase.SUCCESS.wireValue.toByte()
        raw[3] = RegattaLinkDeviceControlResult.OK.wireValue.toByte()
        buffer.putInt(4, 77)
        raw[14] = 0x04

        val parsed = parseRegattaLinkDeviceControlStatus(raw)

        assertTrue(parsed.factoryResetBondsCleared)
        assertFalse(parsed.boatFrameValid)
        assertFalse(parsed.gyroBiasValid)
    }

    @Test(expected = IllegalArgumentException::class)
    fun bondsClearedFlagRejectsNonFactoryStatus() {
        val raw = ByteArray(REGATTALINK_DEVICE_CONTROL_STATUS_SIZE)
        raw[0] = 1
        raw[1] = RegattaLinkDeviceControlOpcode.SET_UPRIGHT.wireValue.toByte()
        raw[2] = RegattaLinkDeviceControlPhase.SUCCESS.wireValue.toByte()
        raw[3] = RegattaLinkDeviceControlResult.OK.wireValue.toByte()
        raw[14] = 0x04

        parseRegattaLinkDeviceControlStatus(raw)
    }

    @Test(expected = IllegalArgumentException::class)
    fun bondsClearedFlagRejectsNonTerminalFactoryStatus() {
        val raw = ByteArray(REGATTALINK_DEVICE_CONTROL_STATUS_SIZE)
        raw[0] = 1
        raw[1] = RegattaLinkDeviceControlOpcode.FACTORY_RESET.wireValue.toByte()
        raw[2] = RegattaLinkDeviceControlPhase.PENDING.wireValue.toByte()
        raw[3] = RegattaLinkDeviceControlResult.NONE.wireValue.toByte()
        raw[14] = 0x04

        parseRegattaLinkDeviceControlStatus(raw)
    }

    @Test
    fun deviceControlRejectionDetailIsDecodedFrom0008Status() {
        fun status(result: RegattaLinkDeviceControlResult, errorCode: Int):
            RegattaLinkDeviceControlStatus {
            val raw = ByteArray(REGATTALINK_DEVICE_CONTROL_STATUS_SIZE)
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            raw[0] = 1
            raw[1] = RegattaLinkDeviceControlOpcode.FACTORY_RESET.wireValue.toByte()
            raw[2] = RegattaLinkDeviceControlPhase.ERROR.wireValue.toByte()
            raw[3] = result.wireValue.toByte()
            buffer.putInt(4, 88)
            raw[15] = errorCode.toByte()
            return parseRegattaLinkDeviceControlStatus(raw)
        }

        val busy = status(RegattaLinkDeviceControlResult.BUSY, 3)
        assertEquals(3, busy.applicationErrorCode)
        assertEquals(
            "RegattaLink Device Control is busy",
            regattaLinkDeviceControlFailureText(busy)
        )

        val badState = status(RegattaLinkDeviceControlResult.INVALID, 4)
        assertEquals(
            "RegattaLink is not ready for this Device Control request",
            regattaLinkDeviceControlFailureText(badState)
        )

        val badRequest = status(RegattaLinkDeviceControlResult.INVALID, 5)
        assertEquals(
            "RegattaLink rejected the Device Control request",
            regattaLinkDeviceControlFailureText(badRequest)
        )
    }

    @Test
    fun deviceControlFailuresKeepActionableTypedUiSemantics() {
        fun status(
            result: RegattaLinkDeviceControlResult,
            applicationErrorCode: Int? = null
        ) = RegattaLinkDeviceControlStatus(
            opcode = RegattaLinkDeviceControlOpcode.SET_UPRIGHT,
            phase = RegattaLinkDeviceControlPhase.ERROR,
            result = result,
            requestId = 12u,
            forwardTrimDeg = 0,
            heelTrimDeg = 0,
            pitchTrimDeg = 0,
            boatFrameValid = false,
            gyroBiasValid = true,
            mountingEpoch = 3u,
            applicationErrorCode = applicationErrorCode
        )

        assertEquals(
            RegattaLinkUiMessage.CALIBRATION_MOTION_REJECTED,
            regattaLinkDeviceControlUiMessage(
                status(RegattaLinkDeviceControlResult.MOTION_REJECT),
                hasError = true
            )
        )
        assertEquals(
            RegattaLinkUiMessage.CALIBRATION_ORIENTATION_REJECTED,
            regattaLinkDeviceControlUiMessage(
                status(RegattaLinkDeviceControlResult.ORIENTATION_REJECT),
                hasError = true
            )
        )
        assertEquals(
            RegattaLinkUiMessage.CALIBRATION_TIMEOUT,
            regattaLinkDeviceControlUiMessage(
                status(RegattaLinkDeviceControlResult.TIMEOUT),
                hasError = true
            )
        )
        assertEquals(
            RegattaLinkUiMessage.DEVICE_CONTROL_BUSY,
            regattaLinkDeviceControlUiMessage(
                status(
                    RegattaLinkDeviceControlResult.BUSY,
                    applicationErrorCode = 3
                ),
                hasError = true
            )
        )
        assertEquals(
            RegattaLinkUiMessage.DEVICE_CONTROL_NOT_READY,
            regattaLinkDeviceControlUiMessage(
                status(
                    RegattaLinkDeviceControlResult.INVALID,
                    applicationErrorCode = 4
                ),
                hasError = true
            )
        )
        assertEquals(
            RegattaLinkUiMessage.CONFIGURATION_FAILED,
            regattaLinkDeviceControlUiMessage(
                status = null,
                hasError = true
            )
        )
        assertNull(
            regattaLinkDeviceControlUiMessage(
                status(RegattaLinkDeviceControlResult.MOTION_REJECT),
                hasError = false
            )
        )
    }

    @Test
    fun requestAcceptanceRequiresMatchingNonRejected0008Status() {
        val accepted = RegattaLinkDeviceControlStatus(
            opcode = RegattaLinkDeviceControlOpcode.FACTORY_RESET,
            phase = RegattaLinkDeviceControlPhase.PENDING,
            result = RegattaLinkDeviceControlResult.NONE,
            requestId = 90u,
            forwardTrimDeg = 0,
            heelTrimDeg = 0,
            pitchTrimDeg = 0,
            boatFrameValid = false,
            gyroBiasValid = false,
            mountingEpoch = 0u
        )
        val rejected = accepted.copy(
            phase = RegattaLinkDeviceControlPhase.ERROR,
            result = RegattaLinkDeviceControlResult.BUSY,
            applicationErrorCode = 3
        )
        val rejectedWithoutDetail = rejected.copy(applicationErrorCode = null)

        assertTrue(
            regattaLinkDeviceControlStatusConfirmsAcceptance(
                accepted,
                90u
            )
        )
        assertFalse(
            regattaLinkDeviceControlStatusConfirmsAcceptance(
                rejected,
                90u
            )
        )
        assertFalse(
            regattaLinkDeviceControlStatusConfirmsAcceptance(
                rejectedWithoutDetail,
                90u
            )
        )
        assertFalse(
            regattaLinkDeviceControlStatusConfirmsAcceptance(
                accepted,
                91u
            )
        )
    }

    @Test
    fun mismatchedRequestIdNeverCompletesCommand() {
        val status = RegattaLinkDeviceControlStatus(
            opcode = RegattaLinkDeviceControlOpcode.SET_UPRIGHT,
            phase = RegattaLinkDeviceControlPhase.SUCCESS,
            result = RegattaLinkDeviceControlResult.OK,
            requestId = 41u,
            forwardTrimDeg = 0,
            heelTrimDeg = 0,
            pitchTrimDeg = 0,
            boatFrameValid = true,
            gyroBiasValid = true,
            mountingEpoch = 7u
        )

        assertEquals(
            RegattaLinkDeviceControlPollDecision.IGNORE_OTHER_REQUEST,
            regattaLinkDeviceControlPollDecision(status, 42u)
        )
        assertEquals(
            RegattaLinkDeviceControlPollDecision.SUCCESS,
            regattaLinkDeviceControlPollDecision(status, 41u)
        )
    }

    @Test
    fun terminalErrorMapsToFailureAndTimeoutHasActionableText() {
        val status = RegattaLinkDeviceControlStatus(
            opcode = RegattaLinkDeviceControlOpcode.SET_UPRIGHT,
            phase = RegattaLinkDeviceControlPhase.ERROR,
            result = RegattaLinkDeviceControlResult.TIMEOUT,
            requestId = 1u,
            forwardTrimDeg = 0,
            heelTrimDeg = 0,
            pitchTrimDeg = 0,
            boatFrameValid = false,
            gyroBiasValid = false,
            mountingEpoch = 0u
        )

        assertEquals(
            RegattaLinkDeviceControlPollDecision.FAILURE,
            regattaLinkDeviceControlPollDecision(status, 1u)
        )
        assertTrue(
            regattaLinkDeviceControlFailureText(
                RegattaLinkDeviceControlResult.TIMEOUT
            ).contains("timed out")
        )
    }

    @Test
    fun trimDirectionsMatchFirmwareWireContract() {
        assertEquals(
            -1,
            regattaLinkTrimDelta(
                RegattaLinkDeviceControlOpcode.ADJUST_FORWARD,
                RegattaLinkTrimDirection.PORT
            )
        )
        assertEquals(
            1,
            regattaLinkTrimDelta(
                RegattaLinkDeviceControlOpcode.ADJUST_FORWARD,
                RegattaLinkTrimDirection.STARBOARD
            )
        )
        assertEquals(
            1,
            regattaLinkTrimDelta(
                RegattaLinkDeviceControlOpcode.ADJUST_HEEL,
                RegattaLinkTrimDirection.PORT
            )
        )
        assertEquals(
            -1,
            regattaLinkTrimDelta(
                RegattaLinkDeviceControlOpcode.ADJUST_HEEL,
                RegattaLinkTrimDirection.STARBOARD
            )
        )
        assertEquals(
            1,
            regattaLinkTrimDelta(
                RegattaLinkDeviceControlOpcode.ADJUST_PITCH,
                RegattaLinkTrimDirection.FRONT
            )
        )
        assertEquals(
            -1,
            regattaLinkTrimDelta(
                RegattaLinkDeviceControlOpcode.ADJUST_PITCH,
                RegattaLinkTrimDirection.BACK
            )
        )
    }

    @Test
    fun directionalTrimFormattingMatchesPhysicalBoatDirections() {
        assertEquals(
            "0°",
            formatRegattaLinkDirectionalTrim(0, "Starboard", "Port")
        )
        assertEquals(
            "8° Starboard",
            formatRegattaLinkDirectionalTrim(8, "Starboard", "Port")
        )
        assertEquals(
            "8° Port",
            formatRegattaLinkDirectionalTrim(-8, "Starboard", "Port")
        )
        assertEquals(
            "2° Port",
            formatRegattaLinkDirectionalTrim(2, "Port", "Starboard")
        )
        assertEquals(
            "2° Starboard",
            formatRegattaLinkDirectionalTrim(-2, "Port", "Starboard")
        )
        assertEquals(
            "3° Bow",
            formatRegattaLinkDirectionalTrim(3, "Bow", "Stern")
        )
        assertEquals(
            "3° Stern",
            formatRegattaLinkDirectionalTrim(-3, "Bow", "Stern")
        )
        assertEquals(
            "—",
            formatRegattaLinkDirectionalTrim(null, "Bow", "Stern")
        )
    }

    @Test
    fun setupMenuItemsMatchDestinationAndLabelContract() {
        assertEquals(
            listOf(
                RegattaLinkSetupDestination.IMU,
                RegattaLinkSetupDestination.NMEA,
                RegattaLinkSetupDestination.ADVANCED_DIAGNOSTICS,
                RegattaLinkSetupDestination.FIRMWARE
            ),
            regattaLinkSetupMenuItems.map { it.destination }
        )
        assertEquals(
            listOf(
                R.string.regattalink_setup_imu,
                R.string.regattalink_setup_nmea,
                R.string.regattalink_advanced_diagnostics,
                R.string.regattalink_firmware_title
            ),
            regattaLinkSetupMenuItems.map { it.labelResId }
        )
    }

    @Test
    fun userFacingAttitudeFormattingRoundsToWholeDegrees() {
        assertEquals("2°", formatRegattaLinkWholeDegreeAngle(2.34))
        assertEquals("3°", formatRegattaLinkWholeDegreeAngle(2.6))
        assertEquals("-3°", formatRegattaLinkWholeDegreeAngle(-2.6))
        assertEquals("-1°", formatRegattaLinkWholeDegreeAngle(-0.5))
        assertEquals("0°", formatRegattaLinkWholeDegreeAngle(-0.4))
    }

    @Test
    fun liveOrientationFormattingUsesPhysicalDirectionsAtWholeDegrees() {
        assertEquals(
            "3° Starboard",
            formatRegattaLinkDirectionalMeasurement(
                3.0,
                "Starboard",
                "Port"
            )
        )
        assertEquals(
            "3° Port",
            formatRegattaLinkDirectionalMeasurement(
                -3.0,
                "Starboard",
                "Port"
            )
        )
        assertEquals(
            "2° Bow up",
            formatRegattaLinkDirectionalMeasurement(
                2.34,
                "Bow up",
                "Bow down"
            )
        )
        assertEquals(
            "3° Bow down",
            formatRegattaLinkDirectionalMeasurement(
                -2.6,
                "Bow up",
                "Bow down"
            )
        )
        assertEquals(
            "0°",
            formatRegattaLinkDirectionalMeasurement(
                0.4,
                "Starboard",
                "Port"
            )
        )
        assertEquals(
            "—",
            formatRegattaLinkDirectionalMeasurement(
                null,
                "Starboard",
                "Port"
            )
        )
    }

    @Test
    fun manualOrientationControlsAreRenderedOnlyForReadyBoatFrame() {
        assertFalse(
            regattaLinkShouldShowManualOrientationControls(
                RegattaLinkBoatFramePresentation.UNKNOWN
            )
        )
        assertFalse(
            regattaLinkShouldShowManualOrientationControls(
                RegattaLinkBoatFramePresentation.PENDING
            )
        )
        assertFalse(
            regattaLinkShouldShowManualOrientationControls(
                RegattaLinkBoatFramePresentation.NOT_SET
            )
        )
        assertTrue(
            regattaLinkShouldShowManualOrientationControls(
                RegattaLinkBoatFramePresentation.READY
            )
        )
    }

    @Test
    fun boatFramePresentationDistinguishesPendingUnknownAndConfirmedState() {
        val valid = RegattaLinkDeviceControlStatus(
            opcode = RegattaLinkDeviceControlOpcode.ADJUST_FORWARD,
            phase = RegattaLinkDeviceControlPhase.SUCCESS,
            result = RegattaLinkDeviceControlResult.OK,
            requestId = 10u,
            forwardTrimDeg = 0,
            heelTrimDeg = 0,
            pitchTrimDeg = 0,
            boatFrameValid = true,
            gyroBiasValid = true,
            mountingEpoch = 1u
        )
        val invalid = valid.copy(boatFrameValid = false)

        assertEquals(
            RegattaLinkBoatFramePresentation.PENDING,
            regattaLinkBoatFramePresentation(
                status = null,
                deviceControlBusy = true
            )
        )
        assertEquals(
            RegattaLinkBoatFramePresentation.UNKNOWN,
            regattaLinkBoatFramePresentation(
                status = null,
                deviceControlBusy = false
            )
        )
        assertEquals(
            RegattaLinkBoatFramePresentation.NOT_SET,
            regattaLinkBoatFramePresentation(
                status = invalid,
                deviceControlBusy = false
            )
        )
        assertEquals(
            RegattaLinkBoatFramePresentation.READY,
            regattaLinkBoatFramePresentation(
                status = valid,
                deviceControlBusy = false
            )
        )
        assertEquals(
            RegattaLinkBoatFramePresentation.READY,
            regattaLinkBoatFramePresentation(
                status = valid,
                deviceControlBusy = true
            )
        )
    }

    @Test
    fun forwardAlignmentTopViewUsesPortCounterClockwiseStarboardClockwise() {
        // Bow is drawn at the top. Firmware sign contract: +Z rotates
        // physical FRONT toward Boat Starboard, i.e. clockwise in top view.
        assertEquals(
            -1,
            regattaLinkTrimDelta(
                RegattaLinkDeviceControlOpcode.ADJUST_FORWARD,
                RegattaLinkTrimDirection.PORT
            )
        )
        assertEquals(
            1,
            regattaLinkTrimDelta(
                RegattaLinkDeviceControlOpcode.ADJUST_FORWARD,
                RegattaLinkTrimDirection.STARBOARD
            )
        )
    }

    @Test
    fun orientationControlsRequireValidBoatFrameAndIdleDeviceControl() {
        assertFalse(
            regattaLinkOrientationControlsEnabled(
                baseControlsEnabled = true,
                boatFrameValid = false,
                deviceControlBusy = false
            )
        )
        assertFalse(
            regattaLinkOrientationControlsEnabled(
                baseControlsEnabled = true,
                boatFrameValid = true,
                deviceControlBusy = true
            )
        )
        assertFalse(
            regattaLinkOrientationControlsEnabled(
                baseControlsEnabled = false,
                boatFrameValid = true,
                deviceControlBusy = false
            )
        )
        assertTrue(
            regattaLinkOrientationControlsEnabled(
                baseControlsEnabled = true,
                boatFrameValid = true,
                deviceControlBusy = false
            )
        )
    }

    @Test
    fun factoryResetCalibrationRejectsStillContinueToBondReset() {
        listOf(
            RegattaLinkDeviceControlResult.MOTION_REJECT,
            RegattaLinkDeviceControlResult.ORIENTATION_REJECT
        ).forEach { result ->
            val status = RegattaLinkDeviceControlStatus(
                opcode = RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                phase = RegattaLinkDeviceControlPhase.ERROR,
                result = result,
                requestId = 17u,
                forwardTrimDeg = 0,
                heelTrimDeg = 0,
                pitchTrimDeg = 0,
                boatFrameValid = false,
                gyroBiasValid = false,
                mountingEpoch = 3u
            )

            assertEquals(
                RegattaLinkDeviceControlPollDecision.FAILURE,
                regattaLinkDeviceControlPollDecision(status, 17u)
            )
            assertTrue(regattaLinkFactoryResetContinuesToBondReset(status))
        }
    }

    @Test
    fun bondResetErrorStopsFactoryResetDisconnectWait() {
        val status = RegattaLinkDeviceControlStatus(
            opcode = RegattaLinkDeviceControlOpcode.FACTORY_RESET,
            phase = RegattaLinkDeviceControlPhase.ERROR,
            result = RegattaLinkDeviceControlResult.BOND_RESET_ERROR,
            requestId = 19u,
            forwardTrimDeg = 0,
            heelTrimDeg = 0,
            pitchTrimDeg = 0,
            boatFrameValid = true,
            gyroBiasValid = true,
            mountingEpoch = 4u
        )

        assertFalse(regattaLinkFactoryResetContinuesToBondReset(status))
        assertEquals(
            RegattaLinkDeviceControlPollDecision.FAILURE,
            regattaLinkDeviceControlPollDecision(status, 19u)
        )
    }

    @Test
    fun destructiveGattRecoveryStartsBeforeWaitingForWriteResponse() {
        val sequence = mutableListOf<String>()

        val result = afterRegattaLinkGattSubmissionAccepted(
            onSubmitted = { sequence += "submitted" },
            awaitResponse = {
                sequence += "await"
                7
            }
        )

        assertEquals(7, result)
        assertEquals(listOf("submitted", "await"), sequence)
    }

    @Test
    fun destructiveGattRecoveryIsClaimedEvenWhenWriteResponseFails() {
        var submitted = false

        try {
            afterRegattaLinkGattSubmissionAccepted(
                onSubmitted = { submitted = true },
                awaitResponse = {
                    throw RegattaLinkOtaTransportException(
                        "write callback lost",
                        ambiguous = true
                    )
                }
            )
        } catch (_: RegattaLinkOtaTransportException) {
            // Expected: the callback outcome is ambiguous after submission.
        }

        assertTrue(submitted)
    }

    @Test
    fun rejectedBeforeStartClearsBusyAndPreservesConfirmedStatus() {
        val confirmedStatus = RegattaLinkDeviceControlStatus(
            opcode = RegattaLinkDeviceControlOpcode.ADJUST_HEEL,
            phase = RegattaLinkDeviceControlPhase.SUCCESS,
            result = RegattaLinkDeviceControlResult.OK,
            requestId = 12u,
            forwardTrimDeg = 1,
            heelTrimDeg = -2,
            pitchTrimDeg = 3,
            boatFrameValid = true,
            gyroBiasValid = true,
            mountingEpoch = 4u
        )
        val state = RegattaLinkConfigurationState(
            deviceControlSupported = true,
            deviceControlBusy = true,
            deviceControlAcceptedOpcode =
                RegattaLinkDeviceControlOpcode.FACTORY_RESET,
            deviceControlAcceptedRequestId = 55u,
            factoryResetWriteAcceptedRequestId = 55u,
            factoryResetAwaitingDisconnect = true,
            deviceControlStatus = confirmedStatus
        )

        val rejected = regattaLinkDeviceControlRejectedBeforeStartState(
            state = state,
            errorMessage = "connection no longer ready"
        )

        assertFalse(rejected.deviceControlBusy)
        assertNull(rejected.deviceControlAcceptedOpcode)
        assertNull(rejected.deviceControlAcceptedRequestId)
        assertNull(rejected.factoryResetWriteAcceptedRequestId)
        assertFalse(rejected.factoryResetAwaitingDisconnect)
        assertEquals(confirmedStatus, rejected.deviceControlStatus)
        assertEquals("connection no longer ready", rejected.deviceControlError)
    }

    @Test
    fun staleDeviceControlExecutionCannotReleaseNewerLease() {
        val guard = RegattaLinkDeviceControlExecutionGuard<Any>()
        val firstSession = Any()
        val secondSession = Any()

        val first = requireNotNull(guard.tryAcquire(firstSession))
        assertTrue(guard.isActive())
        assertNull(guard.tryAcquire(firstSession))

        guard.clear()

        val second = requireNotNull(guard.tryAcquire(secondSession))
        assertTrue(guard.owns(second))
        assertFalse(guard.release(first))
        assertTrue(guard.owns(second))
        assertTrue(guard.isActive())

        assertTrue(guard.release(second))
        assertFalse(guard.isActive())
    }

    @Test
    fun acceptedFactoryResetOwnsManualDisconnectUntilConsumedOrExpired() {
        var now = 2_000L
        val tracker = RegattaLinkFactoryResetDisconnectTracker<Any>(
            nowElapsedMs = { now },
            expectedDisconnectTimeoutMs = 5_000L
        )
        val session = Any()

        assertFalse(tracker.ownsLifecycle(session))

        tracker.markAccepted(session, 31u)
        assertTrue(tracker.ownsLifecycle(session))

        assertTrue(tracker.consumeDisconnect(session))
        assertFalse(tracker.ownsLifecycle(session))

        tracker.markAccepted(session, 32u)
        now += 5_001L
        assertFalse(tracker.ownsLifecycle(session))
    }

    @Test
    fun localDisconnectFallbackCompletesOnlyCurrentOwnedResetSession() {
        val tracker = RegattaLinkFactoryResetDisconnectTracker<Any>(
            nowElapsedMs = { 1_000L },
            expectedDisconnectTimeoutMs = 15_000L
        )
        val session = Any()
        val otherSession = Any()

        tracker.markAccepted(session, 33u)

        assertFalse(
            consumeRegattaLinkFactoryResetFallback(
                session = session,
                sessionStillCurrent = false,
                tracker = tracker
            )
        )
        assertTrue(tracker.isExpected(session, 33u))

        assertFalse(
            consumeRegattaLinkFactoryResetFallback(
                session = otherSession,
                sessionStillCurrent = true,
                tracker = tracker
            )
        )
        assertTrue(tracker.isExpected(session, 33u))

        assertTrue(
            consumeRegattaLinkFactoryResetFallback(
                session = session,
                sessionStillCurrent = true,
                tracker = tracker
            )
        )
        assertFalse(tracker.isExpected(session, 33u))
    }

    @Test
    fun factoryResetDisconnectOwnershipStartsOnlyAfterAcceptedMatchingRequest() {
        var now = 1_000L
        val tracker = RegattaLinkFactoryResetDisconnectTracker<Any>(
            nowElapsedMs = { now },
            expectedDisconnectTimeoutMs = 15_000L
        )
        val session = Any()
        val otherSession = Any()

        assertFalse(tracker.consumeDisconnect(session))

        tracker.markAccepted(session, 23u)
        assertTrue(tracker.isExpected(session, 23u))
        assertFalse(tracker.isExpected(otherSession, 23u))
        assertFalse(tracker.consumeDisconnect(otherSession))
        assertTrue(tracker.isExpected(session, 23u))

        tracker.clear(session, 22u)
        assertTrue(tracker.isExpected(session, 23u))
        tracker.clear(session, 23u)
        assertFalse(tracker.consumeDisconnect(session))
    }

    @Test
    fun factoryResetDisconnectOwnershipExpiresWithinItsAcceptedSession() {
        var now = 10_000L
        val tracker = RegattaLinkFactoryResetDisconnectTracker<Any>(
            nowElapsedMs = { now },
            expectedDisconnectTimeoutMs = 15_000L
        )
        val session = Any()

        tracker.markAccepted(session, 41u)
        now += 15_001L

        assertFalse(tracker.consumeDisconnect(session))
        assertFalse(tracker.isExpected(session, 41u))
    }

    @Test
    fun nonTerminalMatchingStatusContinuesPolling() {
        val status = RegattaLinkDeviceControlStatus(
            opcode = RegattaLinkDeviceControlOpcode.SET_UPRIGHT,
            phase = RegattaLinkDeviceControlPhase.CAPTURING,
            result = RegattaLinkDeviceControlResult.NONE,
            requestId = 9u,
            forwardTrimDeg = 0,
            heelTrimDeg = 0,
            pitchTrimDeg = 0,
            boatFrameValid = false,
            gyroBiasValid = true,
            mountingEpoch = 2u
        )

        assertEquals(
            RegattaLinkDeviceControlPollDecision.CONTINUE,
            regattaLinkDeviceControlPollDecision(status, 9u)
        )
        assertFalse(status.phase.isTerminal)
    }
}
