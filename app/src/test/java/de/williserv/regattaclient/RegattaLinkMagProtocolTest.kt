package de.williserv.regattaclient

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RegattaLinkMagProtocolTest {
    private fun manifest(data: ByteArray, factory: UInt = 0x12345678u): ByteArray =
        ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
            .put(1).put(0).putShort(144).putShort(5)
            .putShort(180).putShort(63).putShort(0)
            .putInt(11232).putInt(factory.toInt())
            .putInt(magCrc32(data).toInt()).putInt(7).array()

    private fun emptyArchive(factory: UInt = 0x12345678u): ByteArray {
        val groups = ByteArray(MAG_ARCHIVE_BYTES)
        for (g in 0 until 12) {
            val b = ByteBuffer.wrap(groups, g * 936, 936).slice()
                .order(ByteOrder.LITTLE_ENDIAN)
            b.put(byteArrayOf(77, 76, 71, 1))
            b.putInt(factory.toInt()).putInt(0)
            repeat(12) {
                b.put(0).put(0)
                repeat(5) { b.putFloat(0f).putFloat(0f).putFloat(0f)
                    b.put(0).put(0).put(0) }
            }
            val grp = groups.copyOfRange(g * 936, (g + 1) * 936)
            val crc = magCrc32(grp)
            ByteBuffer.wrap(groups).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(g * 936 + 8, crc.toInt())
        }
        return groups
    }

    @Test fun manifestAndPageAreStrict() {
        val data = emptyArchive()
        val m = parseRegattaLinkMagManifest(manifest(data))
        assertEquals(7u, m.revision)
        assertEquals(0, parseRegattaLinkMagArchive(m, data).occupiedSectors)
        val raw = ByteBuffer.allocate(184).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0).putShort(180).put(ByteArray(180)).array()
        assertEquals(180, parseRegattaLinkMagPage(raw, 0).size)
        assertThrows(IllegalArgumentException::class.java) {
            parseRegattaLinkMagPage(raw, 1)
        }
        val last = ByteBuffer.allocate(76).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(62).putShort(72).put(ByteArray(72)).array()
        assertEquals(72, parseRegattaLinkMagPage(last, 62).size)
        assertThrows(IllegalArgumentException::class.java) {
            parseRegattaLinkMagPage(last.copyOfRange(0, 20), 62)
        }
    }

    @Test fun rejectsCorruptedGroupsAndTotalCrc() {
        val data = emptyArchive()
        val m = parseRegattaLinkMagManifest(manifest(data))
        val broken = data.clone()
        broken[98] = 1
        assertThrows(IllegalArgumentException::class.java) {
            parseRegattaLinkMagArchive(m, broken)
        }
        val wrongGroup = parseRegattaLinkMagManifest(manifest(broken))
        assertThrows(IllegalArgumentException::class.java) {
            parseRegattaLinkMagArchive(wrongGroup, broken)
        }
    }

    @Test fun mag3Uses80ByteLittleEndianAndBothCrcs() {
        val model = encodeRegattaLinkMagModel(
            RegattaLinkMagManifest(0x12345678u, 0u, 7u),
            RegattaLinkMagFit(doubleArrayOf(1.0, 2.0, 3.0),
                doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),
                50.0, 0.3, 0.6, 40, 0, 8, 0.9)
        )
        assertEquals(80, model.size)
        val b = ByteBuffer.wrap(model).order(ByteOrder.LITTLE_ENDIAN)
        assertArrayEquals(byteArrayOf(77, 65, 71, 51), model.copyOfRange(0, 4))
        assertEquals(0x12345678, b.getInt(8))
        assertEquals(magCrc32(model.copyOfRange(0, 76)).toInt(), b.getInt(76))
        val crc = magCrc32(model)
        assertEquals(11, magUploadCommand(1, 123u, crc).size)
        assertEquals(20, magUploadFragment(18, ByteArray(18)).size)
        assertEquals(5, magUploadCommand(3, 123u).size)
    }

    @Test fun confirmsOnlyCorrelatedPersistedFullObjectCrc() {
        val b = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        b.put(1).put(4).put(0).put(0x81.toByte())
            .putInt(42).putInt(11).putInt(77).putInt(99)
        val state = parseRegattaLinkMagStatus(b.array())
        assertEquals(1, state.activeKind)
        assertTrue(state.installed3d)
        assertTrue(magImportConfirmed(state, 42u, 99u))
        assertFalse(magImportConfirmed(state, 43u, 99u))
        assertFalse(magImportConfirmed(state, 42u, 100u))
    }
}
