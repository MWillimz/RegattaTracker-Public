package de.williserv.regattaclient

import android.os.SystemClock
import org.json.JSONObject

internal const val REGATTALINK_NMEA_STALE_MS = 60_000L

private const val HEADING_REFERENCE_TRUE = 0
private const val HEADING_REFERENCE_MAGNETIC = 1

private const val WIND_REFERENCE_TRUE_NORTH = 0
private const val WIND_REFERENCE_MAGNETIC_NORTH = 1
private const val WIND_REFERENCE_APPARENT = 2
private const val WIND_REFERENCE_TRUE_BOAT = 3
private const val WIND_REFERENCE_TRUE_WATER = 4

internal fun buildRegattaLinkNmeaMeasurementsJson(
    state: RegattaLinkNmeaState,
    nowElapsedMs: Long
): String? {
    if (!state.boatStateSupported || state.pausedForOta) return null

    val boatState = state.boatState ?: return null
    val receivedAt = state.boatStateReceivedAtElapsedMs ?: return null
    val ageMs = nowElapsedMs - receivedAt
    if (ageMs !in 0..REGATTALINK_NMEA_STALE_MS) return null

    val measurements = JSONObject()

    fun put(key: String, value: Double?, unit: String? = null) {
        if (value == null || !value.isFinite()) return
        val measurement = JSONObject()
            .put("value", value)
            .put("group", "nmea")
        if (unit != null) {
            measurement.put("unit", unit)
        }
        measurements.put(key, measurement)
    }

    when (boatState.headingReference) {
        HEADING_REFERENCE_TRUE ->
            put("nmea.heading_true_deg", boatState.headingDeg, "deg")
        HEADING_REFERENCE_MAGNETIC ->
            put("nmea.heading_magnetic_deg", boatState.headingDeg, "deg")
    }

    put("nmea.heading_deviation_deg", boatState.headingDeviationDeg, "deg")
    put("nmea.heading_variation_deg", boatState.headingVariationDeg, "deg")
    put("nmea.rate_of_turn_dps", boatState.rateOfTurnDps, "deg/s")
    put("nmea.yaw_deg", boatState.yawDeg, "deg")
    put("nmea.pitch_deg", boatState.pitchDeg, "deg")
    put("nmea.roll_deg", boatState.rollDeg, "deg")
    put("nmea.stw_mps", boatState.speedThroughWaterMps, "m/s")
    put("nmea.depth_m", boatState.depthM, "m")
    put("nmea.depth_offset_m", boatState.depthOffsetM, "m")
    put("nmea.depth_range_m", boatState.depthRangeM, "m")
    put("nmea.water_temperature_c", boatState.waterTemperatureC, "C")
    put("nmea.latitude_deg", boatState.latitudeDeg, "deg")
    put("nmea.longitude_deg", boatState.longitudeDeg, "deg")

    when (boatState.cogReference) {
        HEADING_REFERENCE_TRUE ->
            put("nmea.cog_true_deg", boatState.cogDeg, "deg")
        HEADING_REFERENCE_MAGNETIC ->
            put("nmea.cog_magnetic_deg", boatState.cogDeg, "deg")
    }
    put("nmea.sog_mps", boatState.sogMps, "m/s")
    put("nmea.altitude_m", boatState.altitudeM, "m")

    when (boatState.windReference) {
        WIND_REFERENCE_TRUE_NORTH -> {
            put("nmea.wind_speed_true_ground_mps", boatState.windSpeedMps, "m/s")
            put("nmea.wind_direction_true_deg", boatState.windAngleDeg, "deg")
        }
        WIND_REFERENCE_MAGNETIC_NORTH -> {
            put("nmea.wind_speed_true_ground_mps", boatState.windSpeedMps, "m/s")
            put("nmea.wind_direction_magnetic_deg", boatState.windAngleDeg, "deg")
        }
        WIND_REFERENCE_APPARENT -> {
            put("nmea.aws_mps", boatState.windSpeedMps, "m/s")
            put("nmea.awa_deg", boatState.windAngleDeg, "deg")
        }
        WIND_REFERENCE_TRUE_BOAT,
        WIND_REFERENCE_TRUE_WATER -> {
            put("nmea.tws_mps", boatState.windSpeedMps, "m/s")
            put("nmea.twa_deg", boatState.windAngleDeg, "deg")
        }
    }

    return if (measurements.length() == 0) null else measurements.toString()
}

internal fun mergeMeasurementsJson(vararg payloads: String?): String? {
    val merged = JSONObject()

    payloads.forEach { raw ->
        if (raw.isNullOrBlank()) return@forEach
        val source = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            merged.put(key, source.get(key))
        }
    }

    return if (merged.length() == 0) null else merged.toString()
}

internal object RegattaLinkNmeaSnapshotStore {
    @Volatile
    private var latest = RegattaLinkNmeaState()

    fun update(state: RegattaLinkNmeaState) {
        latest = state
    }

    fun clear() {
        latest = RegattaLinkNmeaState()
    }

    fun current(): RegattaLinkNmeaState = latest

    fun measurementsJson(
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): String? = buildRegattaLinkNmeaMeasurementsJson(latest, nowElapsedMs)
}
