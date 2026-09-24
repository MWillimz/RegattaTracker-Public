package de.williserv.regattaclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
    fun fullTelemetrySnapshotFitsServerMeasurementAndSampleBudgets() {
        val state = RegattaLinkTelemetryState(
            supported = true,
            subscribed = true,
            fast = RegattaLinkFastMotion(
                confidencePct = 100,
                sequence = 65535,
                timestampMs = 4_294_967_295L,
                rollDeg = -180.0,
                pitchDeg = 180.0,
                rollRateDps = 327.67,
                pitchRateDps = -327.68,
                yawRateDps = 123.45,
                verticalAccelG = 32.767
            ),
            fastReceivedAtElapsedMs = 10_000L,
            summary = RegattaLinkMotionSummary(
                confidencePct = 100,
                sequence = 65535,
                timestampMs = 4_294_967_295L,
                heelFilteredDeg = -180.0,
                trimFilteredDeg = 180.0,
                rollRmsDeg = 655.35,
                pitchRmsDeg = 655.35,
                verticalAccelRmsG = 65.535,
                motionIntensity = 65535
            ),
            summaryReceivedAtElapsedMs = 10_000L,
            calibration = RegattaLinkCalibrationDiagnostics(
                overallConfidencePct = 100,
                forwardConfidencePct = 100,
                rollConfidencePct = 100,
                learnerState = 2,
                gyroBiasValid = true,
                boatFrameValid = true,
                sequence = 65535,
                positiveManeuvers = 65535,
                negativeManeuvers = 65535,
                rollPairObservations = 65535,
                contradictoryManeuvers = 65535,
                mountingEpoch = 65535,
                calibrationRevision = 65535
            ),
            calibrationReceivedAtElapsedMs = 10_000L
        )

        val measurements = JSONObject(
            requireNotNull(buildRegattaLinkMeasurementsJson(state, 10_000L))
        )
        assertEquals(31, measurements.length())

        val sample = JSONObject()
            .put("sequence_id", Long.MAX_VALUE)
            .put("timestamp", "2026-09-22T17:30:00+02:00")
            .put("client_version_code", 2_100_000_000)
            .put("client_build_id", "26.09.22-1730-production")
            .put("boat_name", "Test Boat")
            .put("captain_name", "Test Captain")
            .put("hull_color", "white")
            .put("sail_number", "GER 12345")
            .put("yardstick", 100.0)
            .put("boat_type", "Test Type")
            .put("lat", 54.0)
            .put("lon", 10.0)
            .put("accuracy", 5.0)
            .put("cog", 180.0)
            .put("sog", 5.0)
            .put("measurements", measurements)

        assertTrue(sample.toString().toByteArray(Charsets.UTF_8).size < 5 * 1024)
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
