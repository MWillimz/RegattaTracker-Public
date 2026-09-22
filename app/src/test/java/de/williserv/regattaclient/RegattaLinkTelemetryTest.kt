package de.williserv.regattaclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegattaLinkTelemetryTest {
    @Test
    fun parsesFastMotionWithSignedValuesAndUnsignedTimestamp() {
        val raw = byteArrayOf(
            1, 0,
            0xff.toByte(), 0xff.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0x2e, 0xfb.toByte(),
            0xeb.toByte(), 0x00,
            0xff.toByte(), 0x7f,
            0x00, 0x80.toByte(),
            0x7b, 0x00,
            0x18, 0xfc.toByte()
        )

        val parsed = parseRegattaLinkFastMotion(raw)

        assertEquals(0, parsed.confidencePct)
        assertEquals(65535, parsed.sequence)
        assertEquals(4_294_967_295L, parsed.timestampMs)
        assertEquals(-12.34, parsed.rollDeg, 0.001)
        assertEquals(2.35, parsed.pitchDeg, 0.001)
        assertEquals(327.67, parsed.rollRateDps, 0.001)
        assertEquals(-327.68, parsed.pitchRateDps, 0.001)
        assertEquals(1.23, parsed.yawRateDps, 0.001)
        assertEquals(-1.0, parsed.verticalAccelG, 0.001)
    }

    @Test
    fun parsesSummaryUnsignedFields() {
        val raw = ByteArray(20)
        raw[0] = 1
        raw[1] = 100.toByte()
        raw[2] = 0x34
        raw[3] = 0x12
        raw[12] = 0xff.toByte()
        raw[13] = 0xff.toByte()
        raw[18] = 0xff.toByte()
        raw[19] = 0xff.toByte()

        val parsed = parseRegattaLinkMotionSummary(raw)

        assertEquals(100, parsed.confidencePct)
        assertEquals(0x1234, parsed.sequence)
        assertEquals(655.35, parsed.rollRmsDeg, 0.001)
        assertEquals(65535, parsed.motionIntensity)
    }

    @Test
    fun parsesCalibrationFlagsAndState() {
        val raw = ByteArray(20)
        raw[0] = 1
        raw[1] = 61
        raw[2] = 82.toByte()
        raw[3] = 61
        raw[4] = 2
        raw[5] = 3
        raw[6] = 0xff.toByte()
        raw[7] = 0xff.toByte()

        val parsed = parseRegattaLinkCalibrationDiagnostics(raw)

        assertEquals(61, parsed.overallConfidencePct)
        assertEquals(82, parsed.forwardConfidencePct)
        assertEquals(2, parsed.learnerState)
        assertTrue(parsed.gyroBiasValid)
        assertTrue(parsed.boatFrameValid)
        assertEquals(65535, parsed.sequence)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongRecordLength() {
        parseRegattaLinkFastMotion(ByteArray(19))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownSchemaVersion() {
        parseRegattaLinkFastMotion(ByteArray(20).also { it[0] = 2 })
    }

    @Test
    fun measurementSnapshotIncludesFreshValuesAndOmitsStaleBlocks() {
        val state = RegattaLinkTelemetryState(
            supported = true,
            subscribed = true,
            fast = RegattaLinkFastMotion(
                confidencePct = 0,
                sequence = 65535,
                timestampMs = 4_000_000_000L,
                rollDeg = -4.2,
                pitchDeg = 1.1,
                rollRateDps = 2.2,
                pitchRateDps = 3.3,
                yawRateDps = 4.4,
                verticalAccelG = -0.02
            ),
            fastReceivedAtElapsedMs = 9_000L,
            summary = RegattaLinkMotionSummary(
                confidencePct = 50,
                sequence = 7,
                timestampMs = 100,
                heelFilteredDeg = 12.0,
                trimFilteredDeg = -2.0,
                rollRmsDeg = 1.2,
                pitchRmsDeg = 0.8,
                verticalAccelRmsG = 0.03,
                motionIntensity = 74
            ),
            summaryReceivedAtElapsedMs = 6_000L
        )

        val json = JSONObject(
            buildRegattaLinkMeasurementsJson(state, 10_000L)
                ?: error("measurements missing")
        )

        assertEquals(
            -4.2,
            json.getJSONObject("regattalink.fast.roll_deg").getDouble("value"),
            0.001
        )
        assertEquals(
            65535,
            json.getJSONObject("regattalink.fast.sequence").getInt("value")
        )
        assertFalse(json.has("regattalink.summary.heel_filtered_deg"))
    }

    @Test
    fun otaPauseSuppressesMeasurementSnapshot() {
        val state = RegattaLinkTelemetryState(
            supported = true,
            pausedForOta = true,
            fast = RegattaLinkFastMotion(
                confidencePct = 10,
                sequence = 1,
                timestampMs = 1,
                rollDeg = 0.0,
                pitchDeg = 0.0,
                rollRateDps = 0.0,
                pitchRateDps = 0.0,
                yawRateDps = 0.0,
                verticalAccelG = 0.0
            ),
            fastReceivedAtElapsedMs = 10L
        )

        assertNull(buildRegattaLinkMeasurementsJson(state, 11L))
    }
}
