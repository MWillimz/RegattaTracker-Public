package de.williserv.regattaclient

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.abs

internal const val MAG_SCHEMA_MIN = 20
internal const val MAG_ARCHIVE_BYTES = 11232
internal const val MAG_PAGE_COUNT = 63
internal const val MAG_GROUP_BYTES = 936
internal const val MAG_MODEL_BYTES = 80

internal fun magCrc32(bytes: ByteArray): UInt =
    CRC32().apply { update(bytes) }.value.toUInt()

private fun ByteBuffer.u8(): Int = get().toInt() and 255
private fun ByteBuffer.u16(): Int = short.toInt() and 65535
private fun ByteBuffer.u32(): UInt = int.toUInt()
private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

internal data class RegattaLinkMagStatus(
    val phase: Int,
    val errorCode: Int,
    val activeKind: Int,
    val completedTurns: Int,
    val installed3d: Boolean,
    val requestId: UInt,
    val factoryCrc: UInt,
    val archiveRevision: UInt,
    val storedObjectCrc: UInt
) {
    val activeAuto2d get() = activeKind == 1
    val activeManual3d get() = activeKind == 3
}

internal fun parseRegattaLinkMagStatus(bytes: ByteArray): RegattaLinkMagStatus {
    require(bytes.size == 20) { "MAG status requires 20 bytes" }
    val b = le(bytes)
    require(b.u8() == 1) { "Unsupported MAG status version" }
    val state = b.u8()
    require(state in 0..5) { "Unsupported MAG status phase" }
    val error = b.u8()
    val flags = b.u8()
    val active = flags and 15
    require(active in setOf(0, 1, 3)) { "Invalid active MAG model" }
    return RegattaLinkMagStatus(
        phase = state, errorCode = error, activeKind = active,
        completedTurns = (flags shr 4) and 3,
        installed3d = flags and 0x80 != 0,
        requestId = b.u32(), factoryCrc = b.u32(),
        archiveRevision = b.u32(), storedObjectCrc = b.u32()
    )
}

internal data class RegattaLinkMagManifest(
    val factoryCrc: UInt,
    val archiveCrc: UInt,
    val revision: UInt
)

internal fun parseRegattaLinkMagManifest(bytes: ByteArray): RegattaLinkMagManifest {
    require(bytes.size == 28) { "MAG manifest requires 28 bytes" }
    val b = le(bytes)
    require(b.u8() == 1 && b.u8() == 0) { "Unsupported MAG manifest" }
    require(b.u16() == 144 && b.u16() == 5) { "Unsupported MAG sector format" }
    require(b.u16() == 180 && b.u16() == 63 && b.u16() == 0) {
        "Unsupported MAG paging"
    }
    require(b.u32() == MAG_ARCHIVE_BYTES.toUInt()) { "Invalid MAG archive length" }
    return RegattaLinkMagManifest(b.u32(), b.u32(), b.u32())
}

internal data class RegattaLinkMagSample(
    val x: Double, val y: Double, val z: Double,
    val downX: Int, val downY: Int, val downZ: Int
)

internal data class RegattaLinkMagSector(val samples: List<RegattaLinkMagSample>) {
    val median: DoubleArray? get() {
        if (samples.isEmpty()) return null
        fun median(values: List<Double>): Double {
            val a = values.sorted()
            return if (a.size % 2 != 0) a[a.size / 2]
                else (a[a.size / 2 - 1] + a[a.size / 2]) / 2
        }
        return doubleArrayOf(
            median(samples.map { it.x }), median(samples.map { it.y }),
            median(samples.map { it.z })
        )
    }
}

internal data class RegattaLinkMagArchive(
    val manifest: RegattaLinkMagManifest,
    val sectors: List<RegattaLinkMagSector>
) {
    val occupiedSectors get() = sectors.count { it.samples.isNotEmpty() }
}

internal fun parseRegattaLinkMagPage(page: ByteArray, index: Int): ByteArray {
    require(index in 0 until MAG_PAGE_COUNT)
    require(page.size >= 4) { "Truncated MAG page" }
    val b = le(page)
    val receivedIndex = b.u16()
    val count = b.u16()
    val expected = if (index == MAG_PAGE_COUNT - 1) 72 else 180
    require(receivedIndex == index && count == expected && page.size == count + 4) {
        "MAG page $index has wrong index/length"
    }
    return page.copyOfRange(4, page.size)
}

internal fun parseRegattaLinkMagArchive(
    manifest: RegattaLinkMagManifest, bytes: ByteArray
): RegattaLinkMagArchive {
    require(bytes.size == MAG_ARCHIVE_BYTES) { "Incomplete MAG archive" }
    require(magCrc32(bytes) == manifest.archiveCrc) { "MAG archive CRC mismatch" }
    val sectors = ArrayList<RegattaLinkMagSector>(144)
    for (group in 0 until 12) {
        val data = bytes.copyOfRange(group * MAG_GROUP_BYTES, (group + 1) * MAG_GROUP_BYTES)
        require(data.copyOfRange(0, 4).contentEquals(byteArrayOf(77, 76, 71, 1))) {
            "MAG group signature mismatch"
        }
        val b = le(data)
        b.position(4)
        require(b.u32() == manifest.factoryCrc) { "MAG factory CRC mismatch" }
        val expectedCrc = b.u32()
        data.fill(0, 8, 12)
        require(magCrc32(data) == expectedCrc) { "MAG group CRC mismatch" }
        b.position(12)
        repeat(12) {
            val count = b.u8()
            val nextSlot = b.u8()
            require(count in 0..5 && nextSlot in 0..4) { "Invalid MAG sector ring" }
            val all = ArrayList<RegattaLinkMagSample>(5)
            repeat(5) {
                val x = b.float.toDouble()
                val y = b.float.toDouble()
                val z = b.float.toDouble()
                val dx = b.get().toInt()
                val dy = b.get().toInt()
                val dz = b.get().toInt()
                all += RegattaLinkMagSample(x, y, z, dx, dy, dz)
            }
            val valid = if (count == 5) {
                (0 until 5).map { all[(nextSlot + it) % 5] }
            } else all.take(count)
            require(valid.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }) {
                "Non-finite MAG measurement"
            }
            sectors += RegattaLinkMagSector(valid)
        }
    }
    return RegattaLinkMagArchive(manifest, sectors)
}

internal fun magExportSelect(index: Int): ByteArray =
    ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(index.toShort()).array()

internal val MAG_EXPORT_OPEN = byteArrayOf(0xff.toByte(), 0xff.toByte())
internal val MAG_EXPORT_CLOSE = byteArrayOf(0xfe.toByte(), 0xff.toByte())

internal data class RegattaLinkMagFit(
    val offset: DoubleArray,
    val matrix: DoubleArray, // row-major 3x3
    val radiusUt: Double,
    val rmsUt: Double,
    val p95Ut: Double,
    val usedSectors: Int,
    val rejectedSectors: Int,
    val octants: Int,
    val eigenRatio: Double
)

internal fun encodeRegattaLinkMagModel(
    manifest: RegattaLinkMagManifest,
    fit: RegattaLinkMagFit
): ByteArray {
    require(fit.offset.size == 3 && fit.matrix.size == 9)
    require((fit.offset.asList() + fit.matrix.asList() + listOf(fit.radiusUt, fit.rmsUt))
        .all { it.isFinite() })
    require(fit.usedSectors in 36..144 && fit.radiusUt in 10.0..100.0)
    val b = ByteBuffer.allocate(MAG_MODEL_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    b.put(byteArrayOf(77, 65, 71, 51)) // MAG3
    b.putShort(1)
    b.putShort(80)
    b.putInt(manifest.factoryCrc.toInt())
    b.putInt(manifest.revision.toInt())
    fit.offset.forEach { b.putFloat(it.toFloat()) }
    fit.matrix.forEach { b.putFloat(it.toFloat()) }
    b.putFloat(fit.radiusUt.toFloat())
    b.putFloat(fit.rmsUt.toFloat())
    b.putShort(fit.usedSectors.toShort())
    b.putShort(0)
    val result = b.array()
    b.putInt(magCrc32(result.copyOfRange(0, 76)).toInt())
    return result
}

internal fun magUploadCommand(opcode: Int, id: UInt, modelCrc: UInt = 0u): ByteArray {
    require(opcode in 1..4 && id != 0u)
    val b = ByteBuffer.allocate(if (opcode == 1) 11 else 5)
        .order(ByteOrder.LITTLE_ENDIAN)
    b.put(opcode.toByte()).putInt(id.toInt())
    if (opcode == 1) b.putShort(80).putInt(modelCrc.toInt())
    return b.array()
}

internal fun magUploadFragment(offset: Int, data: ByteArray): ByteArray {
    require(offset in 0 until 80 && data.isNotEmpty() && offset + data.size <= 80)
    require(data.size <= 18) { "Use MTU-23-safe MAG fragments" }
    return ByteBuffer.allocate(2 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(offset.toShort()).put(data).array()
}

internal fun magImportConfirmed(
    status: RegattaLinkMagStatus, id: UInt, completeObjectCrc: UInt
): Boolean = status.requestId == id && status.phase == 4 &&
    status.installed3d && status.storedObjectCrc == completeObjectCrc
