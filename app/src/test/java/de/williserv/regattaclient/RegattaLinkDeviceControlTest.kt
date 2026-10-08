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
    fun restartRequestUsesOpcodeSixAndRequiresZeroValue() {
        val raw = buildRegattaLinkDeviceControlRequest(
            opcode = RegattaLinkDeviceControlOpcode.RESTART,
            requestId = 7u,
            value = 0
        )

        assertEquals(6, raw[1].toInt() and 0xff)
        assertEquals(0, raw[6].toInt() and 0xff)
        assertEquals(0, raw[7].toInt() and 0xff)
    }

    @Test(expected = IllegalArgumentException::class)
    fun restartRejectsNonZeroValue() {
        buildRegattaLinkDeviceControlRequest(
            RegattaLinkDeviceControlOpcode.RESTART,
            7u,
            1
        )
    }

    @Test
    fun imuRawModeUsesOpcodeSevenAndAcceptsEnableDisableValues() {
        val enabled = buildRegattaLinkDeviceControlRequest(
            opcode = RegattaLinkDeviceControlOpcode.IMU_RAW_MODE,
            requestId = 8u,
            value = 1
        )
        val disabled = buildRegattaLinkDeviceControlRequest(
            opcode = RegattaLinkDeviceControlOpcode.IMU_RAW_MODE,
            requestId = 9u,
            value = 0
        )

        assertEquals(7, enabled[1].toInt() and 0xff)
        assertEquals(1, ByteBuffer.wrap(enabled).order(ByteOrder.LITTLE_ENDIAN).getShort(6).toInt())
        assertEquals(7, disabled[1].toInt() and 0xff)
        assertEquals(0, ByteBuffer.wrap(disabled).order(ByteOrder.LITTLE_ENDIAN).getShort(6).toInt())
    }

    @Test(expected = IllegalArgumentException::class)
    fun imuRawModeRejectsOtherValues() {
        buildRegattaLinkDeviceControlRequest(
            opcode = RegattaLinkDeviceControlOpcode.IMU_RAW_MODE,
            requestId = 10u,
            value = 2
        )
    }

    @Test
    fun canErrorTraceUsesOpcodeEightAndRequiresZeroValue() {
        val raw = buildRegattaLinkDeviceControlRequest(
            opcode = RegattaLinkDeviceControlOpcode.CAN_ERROR_TRACE_60S,
            requestId = 11u,
            value = 0
        )

        assertEquals(
            RegattaLinkDeviceControlOpcode.CAN_ERROR_TRACE_60S,
            RegattaLinkDeviceControlOpcode.fromWire(8)
        )
        assertEquals(8, raw[1].toInt() and 0xff)
        assertEquals(
            0,
            ByteBuffer.wrap(raw)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort(6)
                .toInt()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun canErrorTraceRejectsNonZeroValue() {
        buildRegattaLinkDeviceControlRequest(
            opcode = RegattaLinkDeviceControlOpcode.CAN_ERROR_TRACE_60S,
            requestId = 12u,
            value = 1
        )
    }

    @Test
    fun canErrorTraceStatusParsesThroughGenericDeviceControlContract() {
        val raw = ByteArray(REGATTALINK_DEVICE_CONTROL_STATUS_SIZE)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        raw[0] = REGATTALINK_DEVICE_CONTROL_VERSION.toByte()
        raw[1] =
            RegattaLinkDeviceControlOpcode.CAN_ERROR_TRACE_60S.wireValue.toByte()
        raw[2] = RegattaLinkDeviceControlPhase.SUCCESS.wireValue.toByte()
        raw[3] = RegattaLinkDeviceControlResult.OK.wireValue.toByte()
        buffer.putInt(4, 13)

        val parsed = parseRegattaLinkDeviceControlStatus(raw)

        assertEquals(
            RegattaLinkDeviceControlOpcode.CAN_ERROR_TRACE_60S,
            parsed.opcode
        )
        assertEquals(RegattaLinkDeviceControlPhase.SUCCESS, parsed.phase)
        assertEquals(RegattaLinkDeviceControlResult.OK, parsed.result)
        assertEquals(13u, parsed.requestId)
    }

    @Test
    fun canErrorTraceDiagnosticLinesRemainRawAsciiMessages() {
        val messages = listOf(
            "CANe RS DAT X0R8",
            "CANf 19F80123 D8",
            "CANd0102030405060708",
            "CANp 19F80123 D8",
            "CANtrace drop3"
        )

        messages.forEachIndexed { index, message ->
            val raw = ByteArray(REGATTALINK_DIAGNOSTIC_LOG_RECORD_SIZE)
            ByteBuffer.wrap(raw)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0, index.toShort())
            message.toByteArray(Charsets.US_ASCII)
                .copyInto(raw, destinationOffset = 2)

            val parsed = requireNotNull(
                parseRegattaLinkDiagnosticLogEntry(raw)
            )

            assertEquals(index, parsed.timestamp10ms)
            assertEquals(message, parsed.message)
        }
    }

    @Test
    fun existingDeviceControlOpcodeWireValuesRemainStable() {
        assertEquals(1, RegattaLinkDeviceControlOpcode.SET_UPRIGHT.wireValue)
        assertEquals(2, RegattaLinkDeviceControlOpcode.ADJUST_FORWARD.wireValue)
        assertEquals(3, RegattaLinkDeviceControlOpcode.ADJUST_HEEL.wireValue)
        assertEquals(4, RegattaLinkDeviceControlOpcode.ADJUST_PITCH.wireValue)
        assertEquals(5, RegattaLinkDeviceControlOpcode.FACTORY_RESET.wireValue)
        assertEquals(6, RegattaLinkDeviceControlOpcode.RESTART.wireValue)
        assertEquals(7, RegattaLinkDeviceControlOpcode.IMU_RAW_MODE.wireValue)
        assertEquals(
            8,
            RegattaLinkDeviceControlOpcode.CAN_ERROR_TRACE_60S.wireValue
        )
        assertEquals(9, RegattaLinkDeviceControlOpcode.CALYPSO_SCAN.wireValue)
        assertEquals(10, RegattaLinkDeviceControlOpcode.CALYPSO_STATUS.wireValue)
    }

    @Test
    fun calypsoCommandsRequireZeroValueAndUseFrozenOpcodes() {
        val scan = buildRegattaLinkDeviceControlRequest(
            RegattaLinkDeviceControlOpcode.CALYPSO_SCAN,
            21u,
            0
        )
        val status = buildRegattaLinkDeviceControlRequest(
            RegattaLinkDeviceControlOpcode.CALYPSO_STATUS,
            22u,
            0
        )

        assertEquals(9, scan[1].toInt() and 0xff)
        assertEquals(10, status[1].toInt() and 0xff)
        assertEquals(
            RegattaLinkDeviceControlOpcode.CALYPSO_SCAN,
            RegattaLinkDeviceControlOpcode.fromWire(9)
        )
        assertEquals(
            RegattaLinkDeviceControlOpcode.CALYPSO_STATUS,
            RegattaLinkDeviceControlOpcode.fromWire(10)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun calypsoScanRejectsNonZeroValue() {
        buildRegattaLinkDeviceControlRequest(
            RegattaLinkDeviceControlOpcode.CALYPSO_SCAN,
            23u,
            1
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun calypsoStatusRejectsNonZeroValue() {
        buildRegattaLinkDeviceControlRequest(
            RegattaLinkDeviceControlOpcode.CALYPSO_STATUS,
            24u,
            -1
        )
    }

    @Test
    fun calypsoStatusParsesCanonicalIdAndRuntimeFlags() {
        val raw = ByteArray(REGATTALINK_DEVICE_CONTROL_STATUS_SIZE)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        raw[0] = REGATTALINK_DEVICE_CONTROL_VERSION.toByte()
        raw[1] = RegattaLinkDeviceControlOpcode.CALYPSO_STATUS.wireValue.toByte()
        raw[2] = RegattaLinkDeviceControlPhase.SUCCESS.wireValue.toByte()
        raw[3] = RegattaLinkDeviceControlResult.OK.wireValue.toByte()
        buffer.putInt(4, 0x10203040)
        byteArrayOf(
            0xaa.toByte(),
            0xbb.toByte(),
            0xcc.toByte(),
            0xdd.toByte(),
            0xee.toByte(),
            0xff.toByte()
        ).copyInto(raw, destinationOffset = 8)
        raw[14] = 0x18
        raw[15] = 7

        val parsed = parseRegattaLinkDeviceControlStatus(raw)
        val calypso = requireNotNull(parsed.calypso)

        assertEquals("AA:BB:CC:DD:EE:FF", calypso.boundId)
        assertTrue(calypso.bound)
        assertTrue(calypso.connected)
        assertFalse(calypso.scanning)
        assertEquals(7, calypso.detail)
        assertNull(parsed.applicationErrorCode)
        assertEquals(0, parsed.forwardTrimDeg)
        assertEquals(0u, parsed.mountingEpoch)
    }

    @Test
    fun unboundCalypsoStatusIsSuccessfulWithNoId() {
        val raw = ByteArray(REGATTALINK_DEVICE_CONTROL_STATUS_SIZE)
        raw[0] = REGATTALINK_DEVICE_CONTROL_VERSION.toByte()
        raw[1] = RegattaLinkDeviceControlOpcode.CALYPSO_STATUS.wireValue.toByte()
        raw[2] = RegattaLinkDeviceControlPhase.SUCCESS.wireValue.toByte()
        raw[3] = RegattaLinkDeviceControlResult.OK.wireValue.toByte()

        val parsed = parseRegattaLinkDeviceControlStatus(raw)
        val calypso = requireNotNull(parsed.calypso)

        assertFalse(calypso.bound)
        assertFalse(calypso.connected)
        assertNull(calypso.boundId)
        assertEquals(RegattaLinkDeviceControlResult.OK, parsed.result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun connectedCalypsoRequiresBoundFlag() {
        val raw = ByteArray(REGATTALINK_DEVICE_CONTROL_STATUS_SIZE)
        raw[0] = REGATTALINK_DEVICE_CONTROL_VERSION.toByte()
        raw[1] = RegattaLinkDeviceControlOpcode.CALYPSO_STATUS.wireValue.toByte()
        raw[2] = RegattaLinkDeviceControlPhase.SUCCESS.wireValue.toByte()
        raw[3] = RegattaLinkDeviceControlResult.OK.wireValue.toByte()
        raw[14] = 0x10

        parseRegattaLinkDeviceControlStatus(raw)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unboundCalypsoRejectsNonZeroId() {
        val raw = ByteArray(REGATTALINK_DEVICE_CONTROL_STATUS_SIZE)
        raw[0] = REGATTALINK_DEVICE_CONTROL_VERSION.toByte()
        raw[1] = RegattaLinkDeviceControlOpcode.CALYPSO_STATUS.wireValue.toByte()
        raw[2] = RegattaLinkDeviceControlPhase.SUCCESS.wireValue.toByte()
        raw[3] = RegattaLinkDeviceControlResult.OK.wireValue.toByte()
        raw[8] = 1

        parseRegattaLinkDeviceControlStatus(raw)
    }

    @Test(expected = IllegalArgumentException::class)
    fun calypsoStatusRejectsNonZeroReservedTail() {
        val raw = ByteArray(REGATTALINK_DEVICE_CONTROL_STATUS_SIZE)
        raw[0] = REGATTALINK_DEVICE_CONTROL_VERSION.toByte()
        raw[1] = RegattaLinkDeviceControlOpcode.CALYPSO_STATUS.wireValue.toByte()
        raw[2] = RegattaLinkDeviceControlPhase.SUCCESS.wireValue.toByte()
        raw[3] = RegattaLinkDeviceControlResult.OK.wireValue.toByte()
        raw[16] = 1

        parseRegattaLinkDeviceControlStatus(raw)
    }

    @Test(expected = IllegalArgumentException::class)
    fun legacyDeviceControlStillRejectsCalypsoFlags() {
        val raw = ByteArray(REGATTALINK_DEVICE_CONTROL_STATUS_SIZE)
        raw[0] = REGATTALINK_DEVICE_CONTROL_VERSION.toByte()
        raw[1] = RegattaLinkDeviceControlOpcode.ADJUST_HEEL.wireValue.toByte()
        raw[2] = RegattaLinkDeviceControlPhase.SUCCESS.wireValue.toByte()
        raw[3] = RegattaLinkDeviceControlResult.OK.wireValue.toByte()
        raw[14] = 0x08

        parseRegattaLinkDeviceControlStatus(raw)
    }

    @Test
    fun rejectedCalypsoCommandDoesNotRequireAValidCalypsoPayload() {
        val raw = ByteArray(REGATTALINK_DEVICE_CONTROL_STATUS_SIZE)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        raw[0] = REGATTALINK_DEVICE_CONTROL_VERSION.toByte()
        raw[1] = RegattaLinkDeviceControlOpcode.CALYPSO_SCAN.wireValue.toByte()
        raw[2] = RegattaLinkDeviceControlPhase.ERROR.wireValue.toByte()
        raw[3] = RegattaLinkDeviceControlResult.BUSY.wireValue.toByte()
        buffer.putInt(4, 25)
        buffer.putShort(8, 99)
        raw[14] = 0x01
        raw[15] = 3
        buffer.putInt(16, 1234)

        val parsed = parseRegattaLinkDeviceControlStatus(raw)

        assertEquals(RegattaLinkDeviceControlOpcode.CALYPSO_SCAN, parsed.opcode)
        assertEquals(RegattaLinkDeviceControlResult.BUSY, parsed.result)
        assertEquals(3, parsed.applicationErrorCode)
        assertNull(parsed.calypso)
    }

    @Test
    fun calypsoResultCodesAndTimeoutPolicyMatchFirmwareContract() {
        assertEquals(
            RegattaLinkDeviceControlResult.NOT_FOUND,
            RegattaLinkDeviceControlResult.fromWire(11)
        )
        assertEquals(
            RegattaLinkDeviceControlResult.AMBIGUOUS,
            RegattaLinkDeviceControlResult.fromWire(12)
        )
        assertEquals(
            RegattaLinkDeviceControlResult.VERIFY_FAILED,
            RegattaLinkDeviceControlResult.fromWire(13)
        )
        assertEquals(
            60_000L,
            regattaLinkDeviceControlClientTimeoutMs(
                RegattaLinkDeviceControlOpcode.CALYPSO_SCAN
            )
        )
        assertEquals(
            12_000L,
            regattaLinkDeviceControlClientTimeoutMs(
                RegattaLinkDeviceControlOpcode.CALYPSO_STATUS
            )
        )
        assertEquals(
            12_000L,
            regattaLinkDeviceControlClientTimeoutMs(
                RegattaLinkDeviceControlOpcode.RESTART
            )
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
                RegattaLinkSetupDestination.BLUETOOTH_DEVICES,
                RegattaLinkSetupDestination.ADVANCED_DIAGNOSTICS,
                RegattaLinkSetupDestination.FIRMWARE
            ),
            regattaLinkSetupMenuItems.map { it.destination }
        )
        assertEquals(
            listOf(
                R.string.regattalink_setup_imu,
                R.string.regattalink_setup_nmea,
                R.string.regattalink_bluetooth_devices,
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
        assertTrue(rejected.factoryResetAwaitingDisconnect)
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
    fun restartDisconnectIsOwnedOnlyAfterConfirmedRestartAndExpires() {
        var now = 1_000L
        val tracker = RegattaLinkRestartDisconnectTracker<Any>(
            nowElapsedMs = { now },
            expectedDisconnectTimeoutMs = 5_000L
        )
        val session = Any()
        val other = Any()

        assertFalse(tracker.consumeDisconnect(session))
        tracker.markExpected(session)
        assertFalse(tracker.consumeDisconnect(other))
        assertTrue(tracker.consumeDisconnect(session))

        tracker.markExpected(session)
        now += 5_001L
        assertFalse(tracker.consumeDisconnect(session))
    }

    @Test
    fun restartSuccessWithoutDisconnectTimesOutAndUnblocksConfiguration() {
        var now = 1_000L
        val tracker = RegattaLinkRestartDisconnectTracker<Any>(
            nowElapsedMs = { now },
            expectedDisconnectTimeoutMs = 5_000L
        )
        val session = Any()
        val success = RegattaLinkDeviceControlStatus(
            opcode = RegattaLinkDeviceControlOpcode.RESTART,
            phase = RegattaLinkDeviceControlPhase.SUCCESS,
            result = RegattaLinkDeviceControlResult.OK,
            requestId = 51u,
            forwardTrimDeg = 0,
            heelTrimDeg = 0,
            pitchTrimDeg = 0,
            boatFrameValid = true,
            gyroBiasValid = true,
            mountingEpoch = 7u
        )
        val awaiting = RegattaLinkConfigurationState(
            deviceControlSupported = true,
            restartAwaitingDisconnect = true,
            deviceControlStatus = success
        )

        tracker.markExpected(session)
        now += 4_999L
        assertFalse(tracker.consumeTimeout(session))

        now += 1L
        assertTrue(tracker.consumeTimeout(session))
        assertFalse(tracker.consumeDisconnect(session))

        val timedOut = regattaLinkRestartDisconnectTimedOutState(awaiting)
        assertFalse(timedOut.restartAwaitingDisconnect)
        assertEquals(success, timedOut.deviceControlStatus)
        assertEquals(
            REGATTALINK_RESTART_DISCONNECT_TIMEOUT_ERROR,
            timedOut.deviceControlError
        )
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
