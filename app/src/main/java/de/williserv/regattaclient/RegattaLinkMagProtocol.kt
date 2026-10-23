package de.williserv.regattaclient

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.abs

internal const val MAG_SCHEMA_MIN = 21
internal const val MAG_ARCHIVE_BYTES = 11712
internal const val MAG_PAGE_COUNT = 66
internal const val MAG_GROUP_BYTES = 976
internal const val MAG_MODEL_BYTES = 80

internal fun magCrc32(bytes: ByteArray): UInt =
    CRC32().apply { update(bytes) }.value.toUInt()

private fun ByteBuffer.u8(): Int = get().toInt() and 255
private fun ByteBuffer.u16(): Int = short.toInt() and 65535
private fun ByteBuffer.u32(): UInt = int.toUInt()
private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

data class RegattaLinkMagStatus(
    val phase: Int,
    val errorCode: Int,
    val activeKind: Int,
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
    require(error in 0..6 && flags and 0x70 == 0) { "Invalid MAG status flags/error" }
    val active = flags and 15
    require(active in setOf(0, 1, 3)) { "Invalid active MAG model" }
    return RegattaLinkMagStatus(
        phase = state, errorCode = error, activeKind = active,
        installed3d = flags and 0x80 != 0,
        requestId = b.u32(), factoryCrc = b.u32(),
        archiveRevision = b.u32(), storedObjectCrc = b.u32()
    )
}

data class RegattaLinkMagManifest(
    val factoryCrc: UInt,
    val archiveCrc: UInt,
    val revision: UInt,
    val byteCount: Int = MAG_ARCHIVE_BYTES,
    val pageBytes: Int = 180,
    val pageCount: Int = MAG_PAGE_COUNT
)

internal fun parseRegattaLinkMagManifest(bytes: ByteArray): RegattaLinkMagManifest {
    require(bytes.size == 28) { "MAG manifest requires 28 bytes" }
    val b = le(bytes)
    require(b.u8() == 2 && b.u8() == 0) { "Unsupported MAG manifest" }
    require(b.u16() == 144 && b.u16() == 5) { "Unsupported MAG cell format" }
    val pageBytes = b.u16()
    val pageCount = b.u16()
    require(pageBytes == 180 && pageCount == MAG_PAGE_COUNT && b.u16() == 0) {
        "Unsupported MAG paging"
    }
    val total = b.u32().toInt()
    require(total == MAG_ARCHIVE_BYTES && pageCount == (total + pageBytes - 1) / pageBytes) {
        "Invalid MAG archive length"
    }
    return RegattaLinkMagManifest(b.u32(), b.u32(), b.u32(), total, pageBytes, pageCount)
}

data class RegattaLinkMagSample(
    val x: Double, val y: Double, val z: Double,
    val downX: Int, val downY: Int, val downZ: Int
)

data class RegattaLinkMagSector(val samples: List<RegattaLinkMagSample>) {
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

data class RegattaLinkMagArchive(
    val manifest: RegattaLinkMagManifest,
    val sectors: List<RegattaLinkMagSector>,
    val uprightRef: UInt = 0u,
    val basis: RegattaLinkMagBasis? = null
) {
    val occupiedSectors get() = sectors.count { it.samples.isNotEmpty() }
}

internal fun parseRegattaLinkMagPage(
    page: ByteArray, index: Int,
    manifest: RegattaLinkMagManifest = RegattaLinkMagManifest(0u, 0u, 0u)
): ByteArray {
    require(index in 0 until manifest.pageCount)
    require(page.size >= 4) { "Truncated MAG page" }
    val b = le(page)
    val receivedIndex = b.u16()
    val count = b.u16()
    val expected = minOf(manifest.pageBytes, manifest.byteCount - index * manifest.pageBytes)
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
    var reference: UInt? = null
    var basis: RegattaLinkMagBasis? = null
    for (group in 0 until 12) {
        val data = bytes.copyOfRange(group * MAG_GROUP_BYTES, (group + 1) * MAG_GROUP_BYTES)
        require(data.copyOfRange(0, 4).contentEquals(byteArrayOf(77, 76, 71, 2))) {
            "MAG group signature mismatch"
        }
        val b = le(data)
        b.position(4)
        require(b.u32() == manifest.factoryCrc) { "MAG factory CRC mismatch" }
        val expectedCrc = b.u32()
        data.fill(0, 8, 12)
        require(magCrc32(data) == expectedCrc) { "MAG group CRC mismatch" }
        b.position(12)
        val groupRef = b.u32()
        val forward = DoubleArray(3) { b.float.toDouble() }
        val side = DoubleArray(3) { b.float.toDouble() }
        val down = DoubleArray(3) { b.float.toDouble() }
        val groupBasis = RegattaLinkMagBasis(down, forward)
        require((forward + side + down).all { it.isFinite() }) { "Invalid MAG basis" }
        if (groupRef != 0u) {
            groupBasis.validate()
            require(magNorm(DoubleArray(3) { side[it] - groupBasis.side[it] }) < 0.002) {
                "Invalid MAG Side basis"
            }
        }
        if (reference == null) { reference = groupRef; basis = groupBasis }
        else require(reference == groupRef && basis!!.same(groupBasis)) { "Mixed MAG reference" }
        repeat(12) {
            val count = b.u8()
            val nextSlot = b.u8()
            require(count in 0..5 && nextSlot == 0) { "Invalid packed MAG cell" }
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
            val valid = all.take(count) // v2 slots are packed physical observations, not an age ring.
            require(count == 0 || groupRef != 0u) { "MAG observations lack an upright reference" }
            require(valid.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }) {
                "Non-finite MAG measurement"
            }
            sectors += RegattaLinkMagSector(valid)
        }
    }
    return RegattaLinkMagArchive(manifest, sectors, reference ?: 0u, basis)
}

internal fun magExportSelect(index: Int): ByteArray =
    ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(index.toShort()).array()

internal val MAG_EXPORT_OPEN = byteArrayOf(0xff.toByte(), 0xff.toByte())
internal val MAG_EXPORT_CLOSE = byteArrayOf(0xfe.toByte(), 0xff.toByte())

data class RegattaLinkMagFit(
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
    validateMag3Numbers(fit.offset.map { it.toFloat().toDouble() }.toDoubleArray(),
        fit.matrix.map { it.toFloat().toDouble() }.toDoubleArray())
    require(fit.rmsUt.toFloat() in 0f..3f)
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

internal fun validateMag3Numbers(offset: DoubleArray, matrix: DoubleArray) {
    require(offset.size == 3 && matrix.size == 9 && (offset + matrix).all { it.isFinite() })
    require(magNorm(offset) <= 80.0) { "MAG installation offset >80 uT" }
    repeat(3) { i ->
        val diagonal = matrix[i * 3 + i]
        val cross = (0..2).filter { it != i }.sumOf { j ->
            require(abs(matrix[i * 3 + j] - matrix[j * 3 + i]) <= 0.001) { "Asymmetric MAG matrix" }
            abs(matrix[i * 3 + j])
        }
        require(diagonal - cross >= 0.45 && diagonal + cross <= 2.2) { "MAG matrix outside firmware bounds" }
    }
    val m = matrix
    val det = m[0]*(m[4]*m[8]-m[5]*m[7])-m[1]*(m[3]*m[8]-m[5]*m[6])+m[2]*(m[3]*m[7]-m[4]*m[6])
    require(det in 0.3..3.5) { "Invalid MAG matrix determinant" }
}
