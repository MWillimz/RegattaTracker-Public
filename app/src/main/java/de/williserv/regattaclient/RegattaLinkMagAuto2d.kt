package de.williserv.regattaclient

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.ArrayRealVector
import org.apache.commons.math3.linear.SingularValueDecomposition

internal fun magNorm(v: DoubleArray) = sqrt(v.sumOf { it * it })
internal fun magDot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }
internal fun magMedian(a: List<Double>): Double {
    require(a.isNotEmpty())
    val s = a.sorted()
    return (s[(s.size - 1) / 2] + s[s.size / 2]) / 2
}
private fun magLe(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

data class RegattaLinkMagBasis(val down: DoubleArray, val forward: DoubleArray) {
    val side: DoubleArray get() = doubleArrayOf(
        down[1]*forward[2]-down[2]*forward[1], down[2]*forward[0]-down[0]*forward[2],
        down[0]*forward[1]-down[1]*forward[0])
    fun validate() {
        require(down.size == 3 && forward.size == 3 && (down + forward).all { it.isFinite() })
        require(abs(magNorm(down)-1) < 0.001 && abs(magNorm(forward)-1) < 0.001 &&
            abs(magDot(down, forward)) < 0.001) { "Invalid fixed MAG plane" }
    }
    fun same(other: RegattaLinkMagBasis) = down.contentEquals(other.down) && forward.contentEquals(other.forward)
}

data class RegattaLinkMagBinding(val stableId: String, val factoryCrc: UInt, val uprightRef: UInt)

data class RegattaLinkMagAutoMetadata(
    val phase: Int, val errorCode: Int, val flags: Int, val requestId: UInt,
    val factoryCrc: UInt, val uprightRef: UInt, val generation: UInt, val objectCrc: UInt,
    val offset: DoubleArray, val basis: RegattaLinkMagBasis,
    val bootId: UInt, val streamContext: UInt, val dropped: UInt,
    val storedRequestId: UInt, val storedObjectCrc: UInt, val activeKind: Int
) {
    val factoryValid get() = flags and 1 != 0
    val referenceValid get() = flags and 2 != 0
    val autoStored get() = flags and 4 != 0
    val autoSelected get() = flags and 8 != 0
    val manualStored get() = flags and 16 != 0
    val offSelected get() = flags and 32 != 0
    fun binding(stableId: String) = RegattaLinkMagBinding(stableId, factoryCrc, uprightRef)
    fun requireReady() {
        require(factoryValid && referenceValid && uprightRef != 0u) { "Factory calibration and Set Upright required" }
        basis.validate()
    }
}

internal fun parseRegattaLinkMagAutoMetadata(bytes: ByteArray): RegattaLinkMagAutoMetadata {
    require(bytes.size == 88) { "MAG metadata requires 88 bytes" }
    val b = magLe(bytes)
    require(b.get().toInt() == 1) { "Unsupported MAG metadata" }
    val phase = b.get().toInt() and 255
    val error = b.get().toInt() and 255
    val flags = b.get().toInt() and 255
    require(phase in 0..5 && error in 0..5 && flags and 128 == 0)
    fun u32() = b.int.toUInt()
    fun vec() = DoubleArray(3) { b.float.toDouble() }
    val request = u32(); val factory = u32(); val reference = u32(); val generation = u32(); val crc = u32()
    val offset = vec(); val basis = RegattaLinkMagBasis(vec(), vec())
    val boot = u32(); val context = u32(); val dropped = u32(); val stored = u32(); val storedCrc = u32()
    val kind = b.get().toInt() and 255
    require(kind in setOf(0, 1, 3) && (0 until 7).all { b.get().toInt() == 0 })
    require((offset + basis.down + basis.forward).all { it.isFinite() }) { "Non-finite MAG metadata" }
    if (flags and 2 != 0) { require(reference != 0u); basis.validate() }
    require(listOf(8, 32).count { flags and it != 0 } <= 1) { "Conflicting MAG selection" }
    return RegattaLinkMagAutoMetadata(phase, error, flags, request, factory, reference, generation, crc,
        offset, basis, boot, context, dropped, stored, storedCrc, kind)
}

internal fun magMetadataSelect(page: Int): ByteArray {
    require(page in 0..5 || page == 255)
    return byteArrayOf(5, 1, page.toByte())
}

// Caller serializes the entire freeze/select/read transaction on the GATT executor.
internal fun readRegattaLinkMagAutoMetadata(write: (ByteArray) -> Unit, read: () -> ByteArray): RegattaLinkMagAutoMetadata {
    write(magMetadataSelect(255))
    val output = java.io.ByteArrayOutputStream(88)
    var snapshot: Int? = null
    repeat(6) { page ->
        write(magMetadataSelect(page))
        val bytes = read()
        require(bytes.size == if (page == 5) 12 else 20) { "Invalid MAG metadata page length" }
        val b = magLe(bytes)
        require(b.get().toInt() == 1 && b.get().toInt() == page) { "Wrong MAG metadata page" }
        val id = b.short.toInt() and 65535
        require(snapshot == null || snapshot == id) { "MAG metadata snapshot changed" }
        snapshot = id
        output.write(bytes, 4, bytes.size - 4)
    }
    return parseRegattaLinkMagAutoMetadata(output.toByteArray())
}

data class RegattaLinkMagLiveSample(val sequence: Int, val sampleMs: UInt, val xyz: DoubleArray, val valid: Boolean)
internal fun parseRegattaLinkMagLive(bytes: ByteArray): RegattaLinkMagLiveSample {
    require(bytes.size == 20) { "MAG live requires 20 bytes" }
    val b = magLe(bytes)
    require(b.get().toInt() == 1) { "Unsupported MAG live version" }
    val flags = b.get().toInt() and 255
    require(flags and 0xf8 == 0) { "Unknown MAG live flags" }
    val sequence = b.short.toInt() and 65535
    val ms = b.int.toUInt()
    val xyz = DoubleArray(3) { b.float.toDouble() }
    require(xyz.all { it.isFinite() }) { "Non-finite MAG live measurement" }
    return RegattaLinkMagLiveSample(sequence, ms, xyz, flags == 7)
}

// 180 seconds / 1800 raw points; at most 10% missing samples, no gap >1 second.
// Invalid packets and duplicate/out-of-order packets are rejected, never fed to the fitter.
internal class RegattaLinkMagCapture(val initial: RegattaLinkMagAutoMetadata) {
    companion object { const val MAX_SAMPLES = 1800; const val MAX_MS = 180_000L }
    private val samples = ArrayList<DoubleArray>(MAX_SAMPLES)
    private var previous: RegattaLinkMagLiveSample? = null
    private var gaps = 0
    private var largestGapMs = 0L
    @Synchronized fun accept(bytes: ByteArray): Boolean {
        val sample = parseRegattaLinkMagLive(bytes)
        require(sample.valid) { "MAG live reference or sample invalid" }
        previous?.let { prev ->
            val delta = (sample.sequence - prev.sequence) and 65535
            if (delta == 0 || delta >= 32768) return false
            val elapsed = (sample.sampleMs - prev.sampleMs).toLong()
            require(elapsed in 1..10_000) { "MAG live timestamp changed" }
            gaps += delta - 1
            largestGapMs = max(largestGapMs, elapsed)
        }
        if (samples.size >= MAX_SAMPLES) return false
        previous = sample
        samples += sample.xyz
        return true
    }
    @Synchronized fun size() = samples.size
    @Synchronized fun finish(final: RegattaLinkMagAutoMetadata): List<DoubleArray> {
        final.requireReady()
        require(initial.factoryCrc == final.factoryCrc && initial.uprightRef == final.uprightRef &&
            initial.basis.same(final.basis) && initial.bootId == final.bootId &&
            initial.streamContext == final.streamContext) { "MAG capture context changed" }
        require(samples.size >= 24) { "Record at least 24 MAG samples" }
        require(gaps.toDouble() / (samples.size + gaps) <= 0.1 && largestGapMs <= 1000) {
            "MAG stream gaps too large; repeat capture"
        }
        return samples.map { it.copyOf() }
    }
}

data class RegattaLinkMagAutoFit(
    val binding: RegattaLinkMagBinding, val basis: RegattaLinkMagBasis, val offset: DoubleArray,
    val radiusUt: Double, val rmsUt: Double, val maxGapDeg: Double, val downStdUt: Double,
    val inliers: Int, val rejected: Int
)

internal object RegattaLinkMagAutoFitter {
    fun fit(points: List<DoubleArray>, binding: RegattaLinkMagBinding, basis: RegattaLinkMagBasis): RegattaLinkMagAutoFit {
        basis.validate()
        require(points.size >= 24 && points.all { it.size == 3 && it.all(Double::isFinite) }) { "At least 24 finite MAG samples required" }
        val xy = points.map { doubleArrayOf(magDot(it, basis.forward), magDot(it, basis.side)) }
        fun solve(indices: List<Int>): DoubleArray {
            val origin = DoubleArray(2) { k -> indices.map { xy[it][k] }.average() }
            val scale = sqrt(indices.map { (xy[it][0]-origin[0]).pow(2) + (xy[it][1]-origin[1]).pow(2) }.average())
            require(scale.isFinite() && scale > 1e-6) { "MAG capture span too small" }
            val design = Array2DRowRealMatrix(indices.size, 3)
            val target = DoubleArray(indices.size)
            indices.forEachIndexed { row, i ->
                val x = (xy[i][0]-origin[0])/scale; val y = (xy[i][1]-origin[1])/scale
                design.setRow(row, doubleArrayOf(2*x, 2*y, 1.0)); target[row] = x*x+y*y
            }
            val svd = SingularValueDecomposition(design)
            require(svd.rank == 3 && svd.conditionNumber <= 1e4) { "MAG circle ill-conditioned" }
            val c = svd.solver.solve(ArrayRealVector(target)).toArray()
            return doubleArrayOf(origin[0]+scale*c[0], origin[1]+scale*c[1])
        }
        var mask = points.indices.toList()
        repeat(5) {
            val c = solve(mask)
            val radii = xy.map { hypot(it[0]-c[0], it[1]-c[1]) }
            val radius = magMedian(mask.map { radii[it] })
            val errors = radii.map { abs(it-radius) }
            val med = magMedian(mask.map { errors[it] })
            val mad = magMedian(mask.map { abs(errors[it]-med) })
            val threshold = max(0.065*radius, med+3.5*1.4826*mad)
            val next = points.indices.filter { errors[it] <= threshold }
            require(next.size >= max(24, ceil(0.8*points.size).toInt())) { "More than 20% inconsistent MAG samples" }
            if (mask == next) return@repeat
            mask = next
        }
        val c = solve(mask)
        val radii = mask.map { hypot(xy[it][0]-c[0], xy[it][1]-c[1]) }
        val radius = magMedian(radii)
        require(radius > 1e-6 && radius.isFinite())
        val angles = mask.map { atan2(xy[it][1]-c[1], xy[it][0]-c[0]) }.sorted()
        val gap = max(angles.zipWithNext { a,b -> b-a }.max(), angles.first()+2*PI-angles.last())*180/PI
        require(gap <= 30.0001) { "Incomplete MAG circle; angular gap >30 degrees" }
        val rms = sqrt(radii.map { (it-radius).pow(2) }.average())
        val down = mask.map { magDot(points[it], basis.down) }
        val mean = down.average()
        val std = sqrt(down.map { (it-mean).pow(2) }.average())
        require(rms <= min(2.5, 0.07*radius)) { "MAG circle residual too high" }
        require(std <= max(0.5, 0.1*radius)) { "Keep the boat level during MAG capture" }
        val offset = DoubleArray(3) { c[0]*basis.forward[it]+c[1]*basis.side[it] }
        validateMag2Offset(offset, basis)
        return RegattaLinkMagAutoFit(binding, basis, offset, radius, rms, gap, std, mask.size, points.size-mask.size)
    }
}

internal fun validateMag2Offset(offset: DoubleArray, basis: RegattaLinkMagBasis) {
    basis.validate()
    require(offset.size == 3 && offset.all { it.isFinite() } && magNorm(offset) <= 80.0) { "MAG offset outside 80 uT limit" }
    require(abs(magDot(offset, basis.down)) <= 0.10) { "MAG offset outside fixed plane" }
}

internal fun encodeRegattaLinkMagAutoModel(binding: RegattaLinkMagBinding, basis: RegattaLinkMagBasis, offset: DoubleArray): ByteArray {
    require(binding.uprightRef != 0u)
    val f32 = offset.map { it.toFloat().toDouble() }.toDoubleArray()
    validateMag2Offset(f32, basis)
    val b = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
    b.put(byteArrayOf(77,65,71,50)).putShort(1).putShort(32)
    b.putInt(binding.factoryCrc.toInt()).putInt(binding.uprightRef.toInt())
    f32.forEach { b.putFloat(it.toFloat()) }
    b.putInt(magCrc32(b.array().copyOfRange(0,28)).toInt())
    return b.array()
}

internal fun magAutoCommand(opcode: Int, request: UInt, crc: UInt = 0u): ByteArray {
    require(opcode in setOf(1,3,4) && request != 0u)
    val b = ByteBuffer.allocate(if (opcode == 1) 12 else 6).order(ByteOrder.LITTLE_ENDIAN)
    b.put(opcode.toByte()).put(1).putInt(request.toInt())
    if (opcode == 1) b.putShort(32).putInt(crc.toInt())
    return b.array()
}
internal fun magAutoFragment(request: UInt, offset: Int, bytes: ByteArray): ByteArray {
    require(request != 0u && offset in 0..31 && bytes.size in 1..12 && offset+bytes.size <= 32)
    return ByteBuffer.allocate(8+bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        .put(2).put(1).putInt(request.toInt()).putShort(offset.toShort()).put(bytes).array()
}
