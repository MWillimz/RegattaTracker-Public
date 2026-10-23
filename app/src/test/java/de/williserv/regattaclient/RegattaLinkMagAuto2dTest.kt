package de.williserv.regattaclient

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

internal object MagTestData {
    val basis = RegattaLinkMagBasis(doubleArrayOf(0.0,0.0,1.0),doubleArrayOf(1.0,0.0,0.0))
    val binding = RegattaLinkMagBinding("boat",0x12345678u,0x87654321u)
    fun metadata() = RegattaLinkMagAutoMetadata(0,0,79,0u,binding.factoryCrc,binding.uprightRef,1u,0u,
        doubleArrayOf(6.0,-4.0,0.0),basis,11u,20u,0u,0u,0u,1)
    fun bytes(m: RegattaLinkMagAutoMetadata): ByteArray {
        val b = ByteBuffer.allocate(88).order(ByteOrder.LITTLE_ENDIAN)
        b.put(1).put(m.phase.toByte()).put(m.errorCode.toByte()).put(m.flags.toByte())
        b.putInt(m.requestId.toInt()).putInt(m.factoryCrc.toInt()).putInt(m.uprightRef.toInt())
            .putInt(m.generation.toInt()).putInt(m.objectCrc.toInt())
        (m.offset+m.basis.down+m.basis.forward).forEach { b.putFloat(it.toFloat()) }
        b.putInt(m.bootId.toInt()).putInt(m.streamContext.toInt()).putInt(m.dropped.toInt())
            .putInt(m.storedRequestId.toInt()).putInt(m.storedObjectCrc.toInt()).put(m.activeKind.toByte())
        repeat(7) { b.put(0) }; return b.array()
    }
    fun live(sequence: Int, ms: UInt, xyz: DoubleArray = doubleArrayOf(1.0,2.0,3.0), flags: Int = 7): ByteArray =
        ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).put(1).put(flags.toByte()).putShort(sequence.toShort())
            .putInt(ms.toInt()).apply { xyz.forEach { putFloat(it.toFloat()) } }.array()
    fun circle(radius: Double = 30.0, arc: Double = 2*PI, tilt: Double = 0.0) = (0 until 120).map { i ->
        val angle = arc*i/120
        doubleArrayOf(6+radius*cos(angle),-4+radius*sin(angle),20+tilt*sin(angle))
    }
}

class RegattaLinkMagAuto2dTest {
    @Test fun mag2IndependentPythonStructGoldenAndMtu23Fragments() {
        // Python struct <4sHHII3fI>, CRC32 from RegattaLink CLI; not a Kotlin round-trip.
        val golden = "4d4147320100200078563412214365870000c040000080c0000000001b66808a"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val model = encodeRegattaLinkMagAutoModel(MagTestData.binding,MagTestData.basis,doubleArrayOf(6.0,-4.0,0.0))
        assertArrayEquals(golden,model)
        assertEquals(0x2144df1cu,magCrc32(model))
        val begin = magAutoCommand(1,0xfedcba98u,magCrc32(model))
        assertEquals(12,begin.size)
        assertEquals(0xfedcba98.toInt(),ByteBuffer.wrap(begin).order(ByteOrder.LITTLE_ENDIAN).getInt(2))
        assertEquals(listOf(20,20,16),(0 until 32 step 12).map { offset ->
            magAutoFragment(3u,offset,model.copyOfRange(offset,minOf(offset+12,32))).size })
        assertEquals(6,magAutoCommand(3,3u).size)
        assertEquals(6,magAutoCommand(4,3u).size)
    }
    @Test fun metadataMatchesIndependentPythonStructGolden() {
        val raw = "0100004f00000000785634122143658701000000000000000000c040000080c00000000000000000000000000000803f0000803f00000000000000000b000000140000000000000000000000000000000100000000000000".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val m = parseRegattaLinkMagAutoMetadata(raw)
        assertEquals(0x12345678u,m.factoryCrc); assertEquals(0x87654321u,m.uprightRef)
        assertEquals(11u,m.bootId); assertEquals(20u,m.streamContext)
        assertTrue(m.autoStored); assertTrue(m.autoSelected); assertFalse(m.offSelected)
        assertArrayEquals(doubleArrayOf(6.0,-4.0,0.0),m.offset,0.0)
        m.requireReady()
    }
    @Test fun metadataFreezesSixPagesWithShortLastPageAndRejectsMixedSnapshots() {
        val raw = MagTestData.bytes(MagTestData.metadata())
        val writes = mutableListOf<Int>(); var page = 0
        fun read(mixed: Boolean): RegattaLinkMagAutoMetadata = readRegattaLinkMagAutoMetadata({ command ->
            assertEquals(3,command.size); assertEquals(5,command[0].toInt()); assertEquals(1,command[1].toInt())
            page = command[2].toInt() and 255; writes += page
        },{
            val data = raw.copyOfRange(page*16,minOf(page*16+16,88))
            ByteBuffer.allocate(4+data.size).order(ByteOrder.LITTLE_ENDIAN).put(1).put(page.toByte())
                .putShort(if (mixed && page == 5) 8 else 7).put(data).array()
        })
        val parsed = read(false)
        assertEquals(listOf(255,0,1,2,3,4,5),writes)
        assertEquals(20u,parsed.streamContext)
        assertEquals(0x87654321u,parsed.uprightRef)
        assertArrayEquals(doubleArrayOf(6.0,-4.0,0.0),parsed.offset,0.0)
        assertThrows(IllegalArgumentException::class.java) { read(true) }
        assertThrows(IllegalArgumentException::class.java) { parseRegattaLinkMagAutoMetadata(raw.copyOf(87)) }
        val invalid = raw.clone(); invalid[87] = 1
        assertThrows(IllegalArgumentException::class.java) { parseRegattaLinkMagAutoMetadata(invalid) }
        ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putFloat(36,Float.NaN); invalid[87] = 0
        assertThrows(IllegalArgumentException::class.java) { parseRegattaLinkMagAutoMetadata(invalid) }
    }
    @Test fun liveDecoderValidatesFlagsAndFiniteness() {
        val sample = parseRegattaLinkMagLive(MagTestData.live(65535,0xffffffffu))
        assertEquals(65535,sample.sequence); assertEquals(0xffffffffu,sample.sampleMs)
        assertTrue(sample.valid)
        assertFalse(parseRegattaLinkMagLive(MagTestData.live(1,0u,flags=3)).valid)
        assertThrows(IllegalArgumentException::class.java) { parseRegattaLinkMagLive(MagTestData.live(1,0u,flags=8)) }
        assertThrows(IllegalArgumentException::class.java) { parseRegattaLinkMagLive(ByteArray(19)) }
        assertThrows(IllegalArgumentException::class.java) { parseRegattaLinkMagLive(MagTestData.live(1,0u,doubleArrayOf(Double.NaN,0.0,0.0))) }
    }
    @Test fun wrapDuplicateOutOfOrderAndContextChecks() {
        val m = MagTestData.metadata(); val capture = RegattaLinkMagCapture(m)
        repeat(30) { i -> assertTrue(capture.accept(MagTestData.live((65530+i) and 65535,0xffffff00u+i.toUInt()*100u))) }
        assertFalse(capture.accept(MagTestData.live(23,2644u))) // same sequence as last point
        assertFalse(capture.accept(MagTestData.live(22,2544u)))
        assertEquals(30,capture.finish(m).size)
        assertThrows(IllegalArgumentException::class.java) { capture.finish(m.copy(bootId=12u)) }
        assertThrows(IllegalArgumentException::class.java) { capture.finish(m.copy(streamContext=21u)) }
        assertThrows(IllegalArgumentException::class.java) { capture.finish(m.copy(uprightRef=1u)) }
    }
    @Test fun gapsAndInvalidSamplesRejectCapture() {
        val m = MagTestData.metadata(); val c = RegattaLinkMagCapture(m)
        repeat(30) { c.accept(MagTestData.live(it*2,it.toUInt()*200u)) }
        assertThrows(IllegalArgumentException::class.java) { c.finish(m) }
        assertThrows(IllegalArgumentException::class.java) { c.accept(MagTestData.live(61,6100u,flags=3)) }
    }
    @Test fun fullAndSmallCircleRecoverNativeOffsetAndPartialArcIsRejected() {
        for (radius in listOf(30.0,3.0)) {
            val fit = RegattaLinkMagAutoFitter.fit(MagTestData.circle(radius),MagTestData.binding,MagTestData.basis)
            assertArrayEquals(doubleArrayOf(6.0,-4.0,0.0),fit.offset,1e-9)
            assertEquals(radius,fit.radiusUt,1e-9)
            assertEquals(3.0,fit.maxGapDeg,1e-8)
            assertTrue(fit.rmsUt < 1e-9)
        }
        assertThrows(IllegalArgumentException::class.java) { RegattaLinkMagAutoFitter.fit(MagTestData.circle(arc=PI),MagTestData.binding,MagTestData.basis) }
        assertThrows(IllegalArgumentException::class.java) { RegattaLinkMagAutoFitter.fit(MagTestData.circle(tilt=10.0),MagTestData.binding,MagTestData.basis) }
    }
    @Test fun thirtyDegreeCoverageBoundaryAllowsWireFloatRoundingLikeTheCli() {
        val points = (0 until 24).map { i ->
            val a = (i % 12)*PI/6
            doubleArrayOf((6+3*cos(a)).toFloat().toDouble(),(-4+3*sin(a)).toFloat().toDouble(),20.0)
        }
        val fit = RegattaLinkMagAutoFitter.fit(points,MagTestData.binding,MagTestData.basis)
        assertTrue(fit.maxGapDeg <= 30.0001)
    }
    @Test fun fixedTiltedNativeBasisIsUsedWithoutRotatingMeasurementsIntoBoatFrame() {
        val angle = 0.7
        val basis = RegattaLinkMagBasis(doubleArrayOf(sin(angle),0.0,cos(angle)),doubleArrayOf(cos(angle),0.0,-sin(angle)))
        val points = (0 until 120).map { i ->
            val a = i*2*PI/120
            DoubleArray(3) { k -> (6+3*cos(a))*basis.forward[k]+(-4+3*sin(a))*basis.side[k]+20*basis.down[k] }
        }
        val fit = RegattaLinkMagAutoFitter.fit(points,MagTestData.binding,basis)
        assertArrayEquals(DoubleArray(3) { 6*basis.forward[it]-4*basis.side[it] },fit.offset,1e-8)
        assertEquals(0.0,magDot(fit.offset,basis.down),1e-8)
        assertEquals(3.0,fit.radiusUt,1e-8)
    }
    @Test fun robustFitKeepsAtLeastEightyPercentAndRejectsInvalidPlaneOrF32Overflow() {
        val points = MagTestData.circle().toMutableList()
        repeat(10) { i -> val a = 2*PI*i/10; points[i*12] = doubleArrayOf(6+45*cos(a),-4+45*sin(a),20.0) }
        val fit = RegattaLinkMagAutoFitter.fit(points,MagTestData.binding,MagTestData.basis)
        assertArrayEquals(doubleArrayOf(6.0,-4.0,0.0),fit.offset,1e-8)
        assertTrue(fit.rejected >= 10)
        assertThrows(IllegalArgumentException::class.java) { encodeRegattaLinkMagAutoModel(MagTestData.binding,MagTestData.basis,doubleArrayOf(0.0,0.0,0.11)) }
        assertThrows(IllegalArgumentException::class.java) { encodeRegattaLinkMagAutoModel(MagTestData.binding,MagTestData.basis,doubleArrayOf(Double.MAX_VALUE,0.0,0.0)) }
        assertThrows(IllegalArgumentException::class.java) { RegattaLinkMagAutoFitter.fit(points,MagTestData.binding,
            RegattaLinkMagBasis(doubleArrayOf(0.0,0.0,2.0),doubleArrayOf(1.0,0.0,0.0))) }
    }
}
