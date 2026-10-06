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
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    fun parsesRawImuV2WithSignedSensorCounts() {
        val raw = ByteArray(REGATTALINK_TELEMETRY_RECORD_SIZE)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        raw[0] = REGATTALINK_RAW_IMU_SCHEMA_VERSION.toByte()
        buffer.putShort(2, 0xffff.toShort())
        buffer.putInt(4, 0xffffffff.toInt())
        buffer.putShort(8, 1234.toShort())
        buffer.putShort(10, (-2345).toShort())
        buffer.putShort(12, 32767.toShort())
        buffer.putShort(14, (-32768).toShort())
        buffer.putShort(16, 3456.toShort())
        buffer.putShort(18, (-4567).toShort())

        val parsed = parseRegattaLinkRawImu(raw)

        assertEquals(65535, parsed.sequence)
        assertEquals(4_294_967_295L, parsed.timestampMs)
        assertEquals(1234, parsed.accelXRaw)
        assertEquals(-2345, parsed.accelYRaw)
        assertEquals(32767, parsed.accelZRaw)
        assertEquals(-32768, parsed.gyroXRaw)
        assertEquals(3456, parsed.gyroYRaw)
        assertEquals(-4567, parsed.gyroZRaw)
    }

    @Test
    fun rawImuFrontTiltUsesSensorFrameAccelDirection() {
        val level = RegattaLinkRawImu(1, 1, 0, 0, 8192, 0, 0, 0)
        val frontUp = RegattaLinkRawImu(2, 2, 5793, 0, 5793, 0, 0, 0)
        val frontDown = RegattaLinkRawImu(3, 3, -5793, 0, 5793, 0, 0, 0)

        assertEquals(0.0, requireNotNull(regattaLinkRawImuFrontTiltDeg(level)), 0.001)
        assertEquals(45.0, requireNotNull(regattaLinkRawImuFrontTiltDeg(frontUp)), 0.01)
        assertEquals(-45.0, requireNotNull(regattaLinkRawImuFrontTiltDeg(frontDown)), 0.01)
    }

    @Test
    fun rawImuFrontTiltRejectsZeroAccelVector() {
        val zero = RegattaLinkRawImu(1, 1, 0, 0, 0, 0, 0, 0)
        assertNull(regattaLinkRawImuFrontTiltDeg(zero))
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

    @Test
    fun parsesMotionOneHzSignedAndUnsignedValues() {
        val raw = ByteArray(20)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        raw[0] = 1
        raw[1] = 0x1f
        buffer.putShort(2, 0xffff.toShort())
        buffer.putInt(4, 0xffffffff.toInt())
        buffer.putShort(8, (-1234).toShort())
        buffer.putShort(10, 235.toShort())
        buffer.putShort(12, (-123).toShort())
        buffer.putShort(14, 567.toShort())
        buffer.putShort(16, 890.toShort())
        buffer.putShort(18, 1234.toShort())

        val parsed = parseRegattaLinkMotionOneHz(raw)

        assertEquals(0x1f, parsed.validityFlags)
        assertEquals(65535, parsed.sequence)
        assertEquals(4_294_967_295L, parsed.timestampMs)
        assertEquals(-12.34, parsed.heelDeg ?: error("heel missing"), 0.001)
        assertEquals(2.35, parsed.pitchDeg ?: error("pitch missing"), 0.001)
        assertEquals(-1.23, parsed.yawRateDps ?: error("yaw missing"), 0.001)
        assertEquals(
            5.67,
            parsed.encounterPeriodS ?: error("period missing"),
            0.001
        )
        assertEquals(
            8.90,
            parsed.pitchPeakToPeakDeg ?: error("pitch p-p missing"),
            0.001
        )
        assertEquals(
            12.34,
            parsed.rollPeakToPeakDeg ?: error("roll p-p missing"),
            0.001
        )
    }

    @Test
    fun motionOneHzValidityFlagsSuppressInvalidFields() {
        val raw = ByteArray(20)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        raw[0] = 1
        raw[1] = 0x03
        buffer.putShort(8, 1200.toShort())
        buffer.putShort(10, (-300).toShort())
        buffer.putShort(12, 250.toShort())
        buffer.putShort(14, 700.toShort())
        buffer.putShort(16, 800.toShort())
        buffer.putShort(18, 900.toShort())

        val parsed = parseRegattaLinkMotionOneHz(raw)

        assertEquals(12.0, parsed.heelDeg ?: error("heel missing"), 0.001)
        assertEquals(-3.0, parsed.pitchDeg ?: error("pitch missing"), 0.001)
        assertEquals(2.5, parsed.yawRateDps ?: error("yaw missing"), 0.001)
        assertNull(parsed.encounterPeriodS)
        assertNull(parsed.pitchPeakToPeakDeg)
        assertNull(parsed.rollPeakToPeakDeg)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongRecordLength() {
        parseRegattaLinkMotionOneHz(ByteArray(19))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownSchemaVersion() {
        parseRegattaLinkMotionOneHz(ByteArray(20).also { it[0] = 2 })
    }

    @Test
    fun measurementSnapshotContainsOnlyFreshLowRateMotionValues() {
        val state = RegattaLinkTelemetryState(
            supported = true,
            subscribed = true,
            motionOneHz = RegattaLinkMotionOneHz(
                validityFlags = 0x1f,
                sequence = 7,
                timestampMs = 100,
                heelDeg = 12.0,
                pitchDeg = -2.0,
                yawRateDps = 1.5,
                encounterPeriodS = 5.2,
                pitchPeakToPeakDeg = 4.4,
                rollPeakToPeakDeg = 8.8
            ),
            motionOneHzReceivedAtElapsedMs = 9_000L,
            fast = RegattaLinkFastMotion(
                confidencePct = 100,
                sequence = 1,
                timestampMs = 1,
                rollDeg = 99.0,
                pitchDeg = 99.0,
                rollRateDps = 99.0,
                pitchRateDps = 99.0,
                yawRateDps = 99.0,
                verticalAccelG = 9.9
            ),
            fastReceivedAtElapsedMs = 9_000L,
            summary = RegattaLinkMotionSummary(
                confidencePct = 100,
                sequence = 1,
                timestampMs = 1,
                heelFilteredDeg = 99.0,
                trimFilteredDeg = 99.0,
                rollRmsDeg = 99.0,
                pitchRmsDeg = 99.0,
                verticalAccelRmsG = 9.9,
                motionIntensity = 999
            ),
            summaryReceivedAtElapsedMs = 9_000L
        )

        val json = JSONObject(
            buildRegattaLinkMeasurementsJson(state, 10_000L)
                ?: error("measurements missing")
        )

        assertEquals(6, json.length())
        assertEquals(
            12.0,
            json.getJSONObject("regattalink.motion.heel_deg")
                .getDouble("value"),
            0.001
        )
        assertEquals(
            -2.0,
            json.getJSONObject("regattalink.motion.pitch_deg")
                .getDouble("value"),
            0.001
        )
        assertEquals(
            1.5,
            json.getJSONObject("regattalink.motion.yaw_rate_dps")
                .getDouble("value"),
            0.001
        )
        assertEquals(
            5.2,
            json.getJSONObject("regattalink.motion.encounter_period_s")
                .getDouble("value"),
            0.001
        )
        assertFalse(json.has("regattalink.summary.heel_filtered_deg"))
        assertFalse(json.has("regattalink.summary.trim_filtered_deg"))
        assertFalse(json.has("regattalink.fast.roll_deg"))
        assertFalse(json.has("regattalink.summary.roll_rms_deg"))
        assertFalse(json.has("regattalink.summary.sequence"))
    }

    @Test
    fun invalidMotionOneHzFieldsAreOmittedInsteadOfStoredAsZero() {
        val state = RegattaLinkTelemetryState(
            supported = true,
            subscribed = true,
            motionOneHz = RegattaLinkMotionOneHz(
                validityFlags = 0x03,
                sequence = 1,
                timestampMs = 1,
                heelDeg = 4.0,
                pitchDeg = 1.0,
                yawRateDps = -0.5,
                encounterPeriodS = null,
                pitchPeakToPeakDeg = null,
                rollPeakToPeakDeg = null
            ),
            motionOneHzReceivedAtElapsedMs = 10_000L
        )

        val json = JSONObject(
            buildRegattaLinkMeasurementsJson(state, 10_000L)
                ?: error("measurements missing")
        )

        assertEquals(3, json.length())
        assertFalse(json.has("regattalink.motion.encounter_period_s"))
        assertFalse(json.has("regattalink.motion.pitch_peak_to_peak_deg"))
        assertFalse(json.has("regattalink.motion.roll_peak_to_peak_deg"))
    }

    @Test
    fun legacyTelemetryWithoutMotionOneHzIsNotPersisted() {
        val state = RegattaLinkTelemetryState(
            supported = true,
            subscribed = false,
            summary = RegattaLinkMotionSummary(
                confidencePct = 100,
                sequence = 1,
                timestampMs = 1,
                heelFilteredDeg = 4.0,
                trimFilteredDeg = 1.0,
                rollRmsDeg = 0.2,
                pitchRmsDeg = 0.2,
                verticalAccelRmsG = 0.01,
                motionIntensity = 1
            ),
            summaryReceivedAtElapsedMs = 10_000L
        )

        assertNull(buildRegattaLinkMeasurementsJson(state, 10_000L))
    }

    @Test
    fun staleMotionOneHzIsNotPersisted() {
        val state = RegattaLinkTelemetryState(
            supported = true,
            subscribed = true,
            motionOneHz = RegattaLinkMotionOneHz(
                validityFlags = 0x01,
                sequence = 1,
                timestampMs = 1,
                heelDeg = 1.0,
                pitchDeg = 2.0,
                yawRateDps = null,
                encounterPeriodS = null,
                pitchPeakToPeakDeg = null,
                rollPeakToPeakDeg = null
            ),
            motionOneHzReceivedAtElapsedMs = 1_000L
        )

        assertNull(buildRegattaLinkMeasurementsJson(state, 5_000L))
    }

    @Test
    fun otaPauseSuppressesMeasurementSnapshot() {
        val state = RegattaLinkTelemetryState(
            supported = true,
            pausedForOta = true,
            motionOneHz = RegattaLinkMotionOneHz(
                validityFlags = 0x1f,
                sequence = 1,
                timestampMs = 1,
                heelDeg = 0.0,
                pitchDeg = 0.0,
                yawRateDps = 0.0,
                encounterPeriodS = 5.0,
                pitchPeakToPeakDeg = 2.0,
                rollPeakToPeakDeg = 2.0
            ),
            motionOneHzReceivedAtElapsedMs = 10L
        )

        assertNull(buildRegattaLinkMeasurementsJson(state, 11L))
    }
}
