package de.williserv.regattaclient

import android.os.SystemClock
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val REGATTALINK_TELEMETRY_RECORD_SIZE = 20
internal const val REGATTALINK_TELEMETRY_SCHEMA_VERSION = 1
internal const val REGATTALINK_FAST_STALE_MS = 2_000L
internal const val REGATTALINK_SLOW_STALE_MS = 3_000L
internal const val REGATTALINK_MOTION_ONE_HZ_STALE_MS = 3_000L

private const val MOTION_ONE_HZ_ATTITUDE_VALID = 1 shl 0
private const val MOTION_ONE_HZ_YAW_RATE_VALID = 1 shl 1
private const val MOTION_ONE_HZ_PERIOD_VALID = 1 shl 2
private const val MOTION_ONE_HZ_PITCH_P2P_VALID = 1 shl 3
private const val MOTION_ONE_HZ_ROLL_P2P_VALID = 1 shl 4

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

data class RegattaLinkMotionOneHz(
    val validityFlags: Int,
    val sequence: Int,
    val timestampMs: Long,
    val heelDeg: Double?,
    val pitchDeg: Double?,
    val yawRateDps: Double?,
    val encounterPeriodS: Double?,
    val pitchPeakToPeakDeg: Double?,
    val rollPeakToPeakDeg: Double?
)

data class RegattaLinkTelemetryState(
    val supported: Boolean = false,
    val subscribed: Boolean = false,
    val motionOneHz: RegattaLinkMotionOneHz? = null,
    val motionOneHzReceivedAtElapsedMs: Long? = null,
    /*
     * Legacy protocol models remain available for explicit diagnostics and
     * historical fixtures. Normal Tracker setup does not subscribe to
     * 0021/0022/0023 anymore.
     */
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

internal fun parseRegattaLinkMotionOneHz(raw: ByteArray): RegattaLinkMotionOneHz {
    val buffer = telemetryBuffer(raw)
    val flags = raw[1].toInt() and 0xff
    fun valid(mask: Int): Boolean = flags and mask != 0

    return RegattaLinkMotionOneHz(
        validityFlags = flags,
        sequence = buffer.getShort(2).toInt() and 0xffff,
        timestampMs = buffer.getInt(4).toLong() and 0xffffffffL,
        heelDeg = if (valid(MOTION_ONE_HZ_ATTITUDE_VALID)) {
            buffer.getShort(8).toInt() / 100.0
        } else {
            null
        },
        pitchDeg = if (valid(MOTION_ONE_HZ_ATTITUDE_VALID)) {
            buffer.getShort(10).toInt() / 100.0
        } else {
            null
        },
        yawRateDps = if (valid(MOTION_ONE_HZ_YAW_RATE_VALID)) {
            buffer.getShort(12).toInt() / 100.0
        } else {
            null
        },
        encounterPeriodS = if (valid(MOTION_ONE_HZ_PERIOD_VALID)) {
            (buffer.getShort(14).toInt() and 0xffff) / 100.0
        } else {
            null
        },
        pitchPeakToPeakDeg = if (valid(MOTION_ONE_HZ_PITCH_P2P_VALID)) {
            (buffer.getShort(16).toInt() and 0xffff) / 100.0
        } else {
            null
        },
        rollPeakToPeakDeg = if (valid(MOTION_ONE_HZ_ROLL_P2P_VALID)) {
            (buffer.getShort(18).toInt() and 0xffff) / 100.0
        } else {
            null
        }
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

    val motion = state.motionOneHz
    if (
        motion == null ||
        !isRegattaLinkTelemetryFresh(
            state.motionOneHzReceivedAtElapsedMs,
            REGATTALINK_MOTION_ONE_HZ_STALE_MS,
            nowElapsedMs
        )
    ) {
        return null
    }

    val measurements = JSONObject()

    fun put(key: String, value: Any, unit: String) {
        measurements.put(
            key,
            JSONObject()
                .put("value", value)
                .put("group", "regattalink")
                .put("unit", unit)
        )
    }

    motion.heelDeg?.let {
        put("regattalink.summary.heel_filtered_deg", it, "deg")
    }
    motion.pitchDeg?.let {
        /*
         * Keep the historical storage key for session/#302 compatibility.
         * User-facing Tracker text calls this Pitch.
         */
        put("regattalink.summary.trim_filtered_deg", it, "deg")
    }
    motion.yawRateDps?.let {
        put("regattalink.motion.yaw_rate_dps", it, "deg/s")
    }
    motion.encounterPeriodS?.let {
        put("regattalink.motion.encounter_period_s", it, "s")
    }
    motion.pitchPeakToPeakDeg?.let {
        put("regattalink.motion.pitch_peak_to_peak_deg", it, "deg")
    }
    motion.rollPeakToPeakDeg?.let {
        put("regattalink.motion.roll_peak_to_peak_deg", it, "deg")
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
