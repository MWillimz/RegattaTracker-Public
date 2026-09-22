package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RegattaLinkDeviceInfoTest {

    @Test
    fun parseDeviceInfo_readsContractLayout() {
        val raw = validDeviceInfo()

        val info = parseRegattaLinkDeviceInfo(raw)

        assertEquals(1, info.protocolMajor)
        assertEquals(0, info.protocolMinor)
        assertEquals("44:b1:76:48:31:b2", info.stableId)
        assertEquals(1, info.productId)
        assertEquals(1, info.profileId)
        assertEquals(22_786_837uL, info.runningBuild)
        assertEquals(1_572_864u, info.otaSlotSize)
        assertEquals(16, info.maxInflightBlocks)
        assertTrue(info.otaAvailable)
        assertTrue(info.telemetryAvailable)
        assertNull(validateRegattaLinkDeviceInfo(info))
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseDeviceInfo_rejectsWrongRecordLength() {
        parseRegattaLinkDeviceInfo(ByteArray(REGATTALINK_DEVICE_INFO_SIZE - 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseDeviceInfo_rejectsWrongDeclaredRecordSize() {
        val raw = validDeviceInfo()
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putShort(2, 31.toShort())
        parseRegattaLinkDeviceInfo(raw)
    }

    @Test
    fun validateDeviceInfo_rejectsProtocolProductAndProfileMismatches() {
        val valid = parseRegattaLinkDeviceInfo(validDeviceInfo())

        assertTrue(
            validateRegattaLinkDeviceInfo(valid.copy(protocolMajor = 2))
                ?.contains("protocol") == true
        )
        assertTrue(
            validateRegattaLinkDeviceInfo(valid.copy(productId = 2))
                ?.contains("product") == true
        )
        assertTrue(
            validateRegattaLinkDeviceInfo(valid.copy(profileId = 2))
                ?.contains("profile") == true
        )
        assertFalse(valid.copy(capabilities = 0u).otaAvailable)
    }

    private fun validDeviceInfo(): ByteArray {
        val raw = ByteArray(REGATTALINK_DEVICE_INFO_SIZE)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        raw[0] = 1
        raw[1] = 0
        buffer.putShort(2, REGATTALINK_DEVICE_INFO_SIZE.toShort())
        buffer.putInt(4, (1 shl 1) or (1 shl 3))
        byteArrayOf(
            0x44, 0xb1.toByte(), 0x76, 0x48, 0x31, 0xb2.toByte()
        ).copyInto(raw, destinationOffset = 8)
        buffer.putShort(14, 1.toShort())
        buffer.putShort(16, 1.toShort())
        buffer.putLong(18, 22_786_837L)
        buffer.putInt(26, 1_572_864)
        buffer.putShort(30, 16.toShort())
        return raw
    }
}
