package de.williserv.regattaclient

import android.os.SystemClock
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val REGATTALINK_TELEMETRY_RECORD_SIZE = 20
internal const val REGATTALINK_TELEMETRY_SCHEMA_VERSION = 1
internal const val REGATTALINK_FAST_STALE_MS = 2_000L
internal const val REGATTALINK_SLOW_STALE_MS = 3_000L

data class RegattaLinkFastMotion(
    val confidencePct: Int,
    val sequence: Int,
    val timestampMs: Long,
    val rollDeg: Double,
    val pitchDeg: Double,
    val rollRateDps: Double,
    val pitchRateDps: Double,
    val yawRateDps: Double,
    val verticalAccelG: Double
)

data class RegattaLinkMotionSummary(
    val confidencePct: Int,
    val sequence: Int,
    val timestampMs: Long,
    val heelFilteredDeg: Double,
    val trimFilteredDeg: Double,
    val rollRmsDeg: Double,
    val pitchRmsDeg: Double,
    val verticalAccelRmsG: Double,
    val motionIntensity: Int
)

data class RegattaLinkCalibrationDiagnostics(
    val overallConfidencePct: Int,
    val forwardConfidencePct: Int,
    val rollConfidencePct: Int,
    val learnerState: Int,
    val gyroBiasValid: Boolean,
    val boatFrameValid: Boolean,
    val sequence: Int,
    val positiveManeuvers: Int,
    val negativeManeuvers: Int,
    val rollPairObservations: Int,
    val contradictoryManeuvers: Int,
    val mountingEpoch: Int,
    val calibrationRevision: Int
)

data class RegattaLinkTelemetryState(
    val supported: Boolean = false,
    val subscribed: Boolean = false,
    val fast: RegattaLinkFastMotion? = null,
    val fastReceivedAtElapsedMs: Long? = null,
    val summary: RegattaLinkMotionSummary? = null,
    val summaryReceivedAtElapsedMs: Long? = null,
    val calibration: RegattaLinkCalibrationDiagnostics? = null,
    val calibrationReceivedAtElapsedMs: Long? = null,
    val pausedForOta: Boolean = false,
    val error: String = ""
)

internal fun parseRegattaLinkFastMotion(raw: ByteArray): RegattaLinkFastMotion {
    val buffer = telemetryBuffer(raw)
    val confidence = raw[1].toInt() and 0xff
    require(confidence <= 100) { "Invalid RegattaLink fast-motion confidence $confidence" }

    return RegattaLinkFastMotion(
        confidencePct = confidence,
        sequence = buffer.getShort(2).toInt() and 0xffff,
        timestampMs = buffer.getInt(4).toLong() and 0xffffffffL,
        rollDeg = buffer.getShort(8).toInt() / 100.0,
        pitchDeg = buffer.getShort(10).toInt() / 100.0,
        rollRateDps = buffer.getShort(12).toInt() / 100.0,
        pitchRateDps = buffer.getShort(14).toInt() / 100.0,
        yawRateDps = buffer.getShort(16).toInt() / 100.0,
        verticalAccelG = buffer.getShort(18).toInt() / 1000.0
    )
}

internal fun parseRegattaLinkMotionSummary(raw: ByteArray): RegattaLinkMotionSummary {
    val buffer = telemetryBuffer(raw)
    val confidence = raw[1].toInt() and 0xff
    require(confidence <= 100) { "Invalid RegattaLink summary confidence $confidence" }

    return RegattaLinkMotionSummary(
        confidencePct = confidence,
        sequence = buffer.getShort(2).toInt() and 0xffff,
        timestampMs = buffer.getInt(4).toLong() and 0xffffffffL,
        heelFilteredDeg = buffer.getShort(8).toInt() / 100.0,
        trimFilteredDeg = buffer.getShort(10).toInt() / 100.0,
        rollRmsDeg = (buffer.getShort(12).toInt() and 0xffff) / 100.0,
        pitchRmsDeg = (buffer.getShort(14).toInt() and 0xffff) / 100.0,
        verticalAccelRmsG = (buffer.getShort(16).toInt() and 0xffff) / 1000.0,
        motionIntensity = buffer.getShort(18).toInt() and 0xffff
    )
}

internal fun parseRegattaLinkCalibrationDiagnostics(
    raw: ByteArray
): RegattaLinkCalibrationDiagnostics {
    val buffer = telemetryBuffer(raw)
    val overall = raw[1].toInt() and 0xff
    val forward = raw[2].toInt() and 0xff
    val roll = raw[3].toInt() and 0xff
    val learnerState = raw[4].toInt() and 0xff
    require(overall <= 100 && forward <= 100 && roll <= 100) {
        "Invalid RegattaLink calibration confidence"
    }
    require(learnerState in 0..2) {
        "Unsupported RegattaLink learner state $learnerState"
    }
    val flags = raw[5].toInt() and 0xff

    return RegattaLinkCalibrationDiagnostics(
        overallConfidencePct = overall,
        forwardConfidencePct = forward,
        rollConfidencePct = roll,
        learnerState = learnerState,
        gyroBiasValid = flags and 0x01 != 0,
        boatFrameValid = flags and 0x02 != 0,
        sequence = buffer.getShort(6).toInt() and 0xffff,
        positiveManeuvers = buffer.getShort(8).toInt() and 0xffff,
        negativeManeuvers = buffer.getShort(10).toInt() and 0xffff,
        rollPairObservations = buffer.getShort(12).toInt() and 0xffff,
        contradictoryManeuvers = buffer.getShort(14).toInt() and 0xffff,
        mountingEpoch = buffer.getShort(16).toInt() and 0xffff,
        calibrationRevision = buffer.getShort(18).toInt() and 0xffff
    )
}

private fun telemetryBuffer(raw: ByteArray): ByteBuffer {
    require(raw.size == REGATTALINK_TELEMETRY_RECORD_SIZE) {
        "RegattaLink telemetry record must be $REGATTALINK_TELEMETRY_RECORD_SIZE bytes, got ${raw.size}"
    }
    val version = raw[0].toInt() and 0xff
    require(version == REGATTALINK_TELEMETRY_SCHEMA_VERSION) {
        "Unsupported RegattaLink telemetry schema $version"
    }
    return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
}

internal fun isRegattaLinkTelemetryFresh(
    receivedAtElapsedMs: Long?,
    maxAgeMs: Long,
    nowElapsedMs: Long
): Boolean {
    if (receivedAtElapsedMs == null) return false
    val age = nowElapsedMs - receivedAtElapsedMs
    return age in 0..maxAgeMs
}

internal fun buildRegattaLinkMeasurementsJson(
    state: RegattaLinkTelemetryState,
    nowElapsedMs: Long
): String? {
    if (!state.supported || state.pausedForOta) return null

    val measurements = JSONObject()

    fun put(key: String, value: Any, unit: String? = null) {
        val measurement = JSONObject()
            .put("value", value)
            .put("group", "regattalink")
        if (unit != null) {
            measurement.put("unit", unit)
        }
        measurements.put(key, measurement)
    }

    if (
        state.fast != null &&
        isRegattaLinkTelemetryFresh(
            state.fastReceivedAtElapsedMs,
            REGATTALINK_FAST_STALE_MS,
            nowElapsedMs
        )
    ) {
        val fast = state.fast
        put("regattalink.fast.confidence_pct", fast.confidencePct, "%")
        put("regattalink.fast.sequence", fast.sequence)
        put("regattalink.fast.timestamp_ms", fast.timestampMs, "ms")
        put("regattalink.fast.roll_deg", fast.rollDeg, "deg")
        put("regattalink.fast.pitch_deg", fast.pitchDeg, "deg")
        put("regattalink.fast.roll_rate_dps", fast.rollRateDps, "deg/s")
        put("regattalink.fast.pitch_rate_dps", fast.pitchRateDps, "deg/s")
        put("regattalink.fast.yaw_rate_dps", fast.yawRateDps, "deg/s")
        put("regattalink.fast.vertical_accel_g", fast.verticalAccelG, "g")
    }

    if (
        state.summary != null &&
        isRegattaLinkTelemetryFresh(
            state.summaryReceivedAtElapsedMs,
            REGATTALINK_SLOW_STALE_MS,
            nowElapsedMs
        )
    ) {
        val summary = state.summary
        put("regattalink.summary.confidence_pct", summary.confidencePct, "%")
        put("regattalink.summary.sequence", summary.sequence)
        put("regattalink.summary.timestamp_ms", summary.timestampMs, "ms")
        put("regattalink.summary.heel_filtered_deg", summary.heelFilteredDeg, "deg")
        put("regattalink.summary.trim_filtered_deg", summary.trimFilteredDeg, "deg")
        put("regattalink.summary.roll_rms_deg", summary.rollRmsDeg, "deg")
        put("regattalink.summary.pitch_rms_deg", summary.pitchRmsDeg, "deg")
        put(
            "regattalink.summary.vertical_accel_rms_g",
            summary.verticalAccelRmsG,
            "g"
        )
        put("regattalink.summary.motion_intensity", summary.motionIntensity)
    }

    if (
        state.calibration != null &&
        isRegattaLinkTelemetryFresh(
            state.calibrationReceivedAtElapsedMs,
            REGATTALINK_SLOW_STALE_MS,
            nowElapsedMs
        )
    ) {
        val calibration = state.calibration
        put(
            "regattalink.calibration.overall_confidence_pct",
            calibration.overallConfidencePct,
            "%"
        )
        put(
            "regattalink.calibration.forward_confidence_pct",
            calibration.forwardConfidencePct,
            "%"
        )
        put(
            "regattalink.calibration.roll_confidence_pct",
            calibration.rollConfidencePct,
            "%"
        )
        put("regattalink.calibration.learner_state", calibration.learnerState)
        put("regattalink.calibration.gyro_bias_valid", calibration.gyroBiasValid)
        put("regattalink.calibration.boat_frame_valid", calibration.boatFrameValid)
        put("regattalink.calibration.sequence", calibration.sequence)
        put(
            "regattalink.calibration.positive_maneuvers",
            calibration.positiveManeuvers
        )
        put(
            "regattalink.calibration.negative_maneuvers",
            calibration.negativeManeuvers
        )
        put(
            "regattalink.calibration.roll_pair_observations",
            calibration.rollPairObservations
        )
        put(
            "regattalink.calibration.contradictory_maneuvers",
            calibration.contradictoryManeuvers
        )
        put("regattalink.calibration.mounting_epoch", calibration.mountingEpoch)
        put(
            "regattalink.calibration.calibration_revision",
            calibration.calibrationRevision
        )
    }

    return if (measurements.length() == 0) null else measurements.toString()
}

internal object RegattaLinkTelemetrySnapshotStore {
    @Volatile
    private var latest = RegattaLinkTelemetryState()

    fun update(state: RegattaLinkTelemetryState) {
        latest = state
    }

    fun clear() {
        latest = RegattaLinkTelemetryState()
    }

    fun current(): RegattaLinkTelemetryState = latest

    fun measurementsJson(
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): String? = buildRegattaLinkMeasurementsJson(latest, nowElapsedMs)
}
