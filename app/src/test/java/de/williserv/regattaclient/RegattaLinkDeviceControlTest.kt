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
        assertEquals(0xfedcba98u, parsed.mountingEpoch)
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
