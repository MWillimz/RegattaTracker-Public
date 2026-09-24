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
    fun validatesDeviceNameByUtf8ByteLength() {
        assertNull(validateRegattaLinkDeviceName("RegattaLink-31B2"))
        assertNull(validateRegattaLinkDeviceName("ääääääääääää"))
        assertTrue(validateRegattaLinkDeviceName("äääääääääääää") != null)
        assertTrue(validateRegattaLinkDeviceName("bad\nname") != null)
    }

    @Test
    fun parsesLedBrightnessStrictly() {
        assertEquals(0, parseRegattaLinkLedBrightness(byteArrayOf(0)))
        assertEquals(50, parseRegattaLinkLedBrightness(byteArrayOf(50)))
        assertEquals(100, parseRegattaLinkLedBrightness(byteArrayOf(100)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangeLedBrightness() {
        parseRegattaLinkLedBrightness(byteArrayOf(101))
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
