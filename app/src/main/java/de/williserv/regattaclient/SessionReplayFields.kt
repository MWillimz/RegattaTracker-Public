package de.williserv.regattaclient

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
    val measurementGroup: String? = null,
    val recommended: Boolean = false
)

private data class ReplayMeasurementDefinition(
    val key: String,
    val label: String
)

private val REPLAY_RECOMMENDED_MEASUREMENTS = listOf(
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
    ReplayMeasurementDefinition(
        key = "nmea.heading_magnetic_deg",
        label = "Magnetic heading"
    ),
    ReplayMeasurementDefinition(
        key = "nmea.heading_true_deg",
        label = "True heading"
    ),
    ReplayMeasurementDefinition(
        key = "nmea.stw_mps",
        label = "STW"
    ),
    ReplayMeasurementDefinition(
        key = "nmea.depth_m",
        label = "Depth"
    ),
    ReplayMeasurementDefinition(
        key = "nmea.water_temperature_c",
        label = "Water temperature"
    ),
    ReplayMeasurementDefinition(
        key = "nmea.aws_mps",
        label = "AWS"
    ),
    ReplayMeasurementDefinition(
        key = "nmea.awa_deg",
        label = "AWA"
    ),
    ReplayMeasurementDefinition(
        key = "nmea.tws_mps",
        label = "TWS"
    ),
    ReplayMeasurementDefinition(
        key = "nmea.twa_deg",
        label = "TWA"
    )
)

private val REPLAY_RECOMMENDED_BY_KEY =
    REPLAY_RECOMMENDED_MEASUREMENTS.associateBy { it.key }

internal fun discoverReplayExtraFields(
    samples: List<SessionTrackingSample>
): List<ReplayExtraField> {
    val measurements = discoverSessionNumericMeasurements(samples)
    return measurements.map { measurement ->
        val recommended = REPLAY_RECOMMENDED_BY_KEY[measurement.key]
        ReplayExtraField(
            id = "measurement:${measurement.key}",
            source = ReplayExtraFieldSource.MEASUREMENT,
            label = recommended?.label ?: measurement.label,
            unit = measurement.unit,
            measurementKey = measurement.key,
            measurementGroup = measurement.group,
            recommended = recommended != null
        )
    }.sortedWith(
        compareByDescending<ReplayExtraField> { it.recommended }
            .thenBy { it.measurementGroup.orEmpty() }
            .thenBy { it.label }
            .thenBy { it.id }
    )
}

internal fun replayExtraFieldValues(
    sample: SessionTrackingSample,
    fields: List<ReplayExtraField>
): Map<String, String> = buildMap {
    fields.forEach { field ->
        replayExtraFieldValue(field, sample)?.let { value ->
            put(field.id, value)
        }
    }
}

internal fun replayExtraFieldValue(
    sample: SessionTrackingSample,
    field: ReplayExtraField
): String? = replayExtraFieldValue(field, sample)

private fun replayExtraFieldValue(
    field: ReplayExtraField,
    sample: SessionTrackingSample
): String? {
    val key = field.measurementKey ?: return null
    val number = sessionNumericMeasurementValue(sample, key) ?: return null
    val formatted = formatReplayNumber(
        number,
        decimalsForMeasurement(number),
        null
    )
    return field.unit?.let { "$formatted $it" } ?: formatted
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
