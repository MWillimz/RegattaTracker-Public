package de.williserv.regattaclient

import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

enum class ReplayExtraFieldSource {
    MEASUREMENT
}

data class ReplayExtraField(
    val id: String,
    val source: ReplayExtraFieldSource,
    val label: String,
    val unit: String?,
    val measurementKey: String? = null,
    val measurementGroup: String? = null
)

private data class ReplayMeasurementDefinition(
    val key: String,
    val label: String
)

private val REPLAY_MEASUREMENT_CATALOG = listOf(
    // RegattaLink motion values that are useful when reviewing a sailed track.
    ReplayMeasurementDefinition(
        key = "regattalink.summary.heel_filtered_deg",
        label = "Heel"
    ),
    ReplayMeasurementDefinition(
        key = "regattalink.summary.trim_filtered_deg",
        label = "Trim"
    ),
    ReplayMeasurementDefinition(
        key = "regattalink.fast.roll_deg",
        label = "Roll"
    ),
    ReplayMeasurementDefinition(
        key = "regattalink.fast.pitch_deg",
        label = "Pitch"
    ),

    // NMEA values are intentionally explicit as well. They only appear once
    // the corresponding measurement is actually persisted in a session.
    ReplayMeasurementDefinition(
        key = "nmea.heading",
        label = "Heading"
    ),
    ReplayMeasurementDefinition(
        key = "nmea.stw",
        label = "STW"
    ),
    ReplayMeasurementDefinition(
        key = "nmea.depth",
        label = "Depth"
    ),
    ReplayMeasurementDefinition(
        key = "nmea.water_temperature",
        label = "Water temperature"
    ),
    ReplayMeasurementDefinition(
        key = "nmea.wind_speed",
        label = "Wind speed"
    ),
    ReplayMeasurementDefinition(
        key = "nmea.wind_angle",
        label = "Wind angle"
    )
)

internal fun discoverReplayExtraFields(
    samples: List<SessionTrackingSample>
): List<ReplayExtraField> {
    if (samples.isEmpty()) return emptyList()

    return REPLAY_MEASUREMENT_CATALOG.mapNotNull { definition ->
        samples.asSequence()
            .mapNotNull { sample ->
                parseMeasurements(sample.measurementsJson)
                    ?.optJSONObject(definition.key)
            }
            .firstOrNull { measurement ->
                measurement.has("value") && !measurement.isNull("value")
            }
            ?.let { measurement ->
                val unit = measurement.optString("unit")
                    .takeIf { it.isNotBlank() }
                val group = measurement.optString("group")
                    .takeIf { it.isNotBlank() }

                ReplayExtraField(
                    id = "measurement:${definition.key}",
                    source = ReplayExtraFieldSource.MEASUREMENT,
                    label = definition.label,
                    unit = unit,
                    measurementKey = definition.key,
                    measurementGroup = group
                )
            }
    }
}

internal fun replayExtraFieldValues(
    sample: SessionTrackingSample,
    fields: List<ReplayExtraField>
): Map<String, String> {
    val measurements = parseMeasurements(sample.measurementsJson)
    return buildMap {
        fields.forEach { field ->
            replayExtraFieldValue(field, measurements)?.let { value ->
                put(field.id, value)
            }
        }
    }
}

internal fun replayExtraFieldValue(
    sample: SessionTrackingSample,
    field: ReplayExtraField
): String? = replayExtraFieldValue(
    field = field,
    measurements = parseMeasurements(sample.measurementsJson)
)

private fun replayExtraFieldValue(
    field: ReplayExtraField,
    measurements: JSONObject?
): String? {
    val key = field.measurementKey ?: return null
    val measurement = measurements?.optJSONObject(key) ?: return null
    if (!measurement.has("value") || measurement.isNull("value")) return null

    val value = measurement.opt("value")
    val formatted = when (value) {
        is Number -> {
            val number = value.toDouble()
            if (!number.isFinite()) return null
            formatReplayNumber(number, decimalsForMeasurement(number), null)
        }
        is Boolean -> value.toString()
        is String -> value.takeIf { it.isNotBlank() }
        else -> null
    } ?: return null

    return field.unit?.let { "$formatted $it" } ?: formatted
}

private fun parseMeasurements(raw: String?): JSONObject? {
    if (raw.isNullOrBlank()) return null
    return runCatching { JSONObject(raw) }.getOrNull()
}

private fun formatReplayNumber(
    value: Double,
    decimals: Int,
    unit: String?
): String {
    val formatted = String.format(Locale.getDefault(), "%.${decimals}f", value)
    return unit?.let { "$formatted $it" } ?: formatted
}

private fun decimalsForMeasurement(value: Double): Int {
    val nearestInteger = value.roundToLong().toDouble()
    if (abs(value - nearestInteger) < 0.000_001) return 0
    return when {
        abs(value) >= 100.0 -> 1
        abs(value) >= 10.0 -> 2
        else -> 3
    }
}
