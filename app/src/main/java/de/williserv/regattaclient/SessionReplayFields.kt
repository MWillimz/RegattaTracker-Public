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

internal fun discoverReplayExtraFields(
    samples: List<SessionTrackingSample>
): List<ReplayExtraField> {
    if (samples.isEmpty()) return emptyList()

    val dynamic = linkedMapOf<String, ReplayExtraField>()
    samples.forEach { sample ->
        val json = parseMeasurements(sample.measurementsJson) ?: return@forEach
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (dynamic.containsKey(key)) continue

            val measurement = json.optJSONObject(key) ?: continue
            if (!measurement.has("value") || measurement.isNull("value")) continue

            val unit = measurement.optString("unit")
                .takeIf { it.isNotBlank() }
            val group = measurement.optString("group")
                .takeIf { it.isNotBlank() }

            dynamic[key] = ReplayExtraField(
                id = "measurement:$key",
                source = ReplayExtraFieldSource.MEASUREMENT,
                label = prettyMeasurementLabel(key, group, unit),
                unit = unit,
                measurementKey = key,
                measurementGroup = group
            )
        }
    }

    return dynamic.values.sortedWith(
        compareBy<ReplayExtraField>(
            { it.measurementGroup.orEmpty() },
            { it.label },
            { it.id }
        )
    )
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

private fun prettyMeasurementLabel(
    key: String,
    group: String?,
    unit: String?
): String {
    var visibleKey = key
    if (!group.isNullOrBlank()) {
        val prefix = "$group."
        if (visibleKey.startsWith(prefix, ignoreCase = true)) {
            visibleKey = visibleKey.substring(prefix.length)
        }
    }

    val unitTokens = when (unit?.lowercase(Locale.ROOT)) {
        "deg" -> setOf("deg")
        "deg/s" -> setOf("dps")
        "g" -> setOf("g")
        "%" -> setOf("pct", "percent")
        "ms" -> setOf("ms")
        else -> emptySet()
    }

    val parts = visibleKey
        .split('.', '_')
        .filter { it.isNotBlank() && it.lowercase(Locale.ROOT) !in unitTokens }

    return parts.joinToString(" ") { prettyIdentifier(it) }
        .ifBlank { prettyIdentifier(key) }
}

private fun prettyIdentifier(value: String): String {
    val normalized = value.lowercase(Locale.ROOT)
    if (normalized == "regattalink") return "RegattaLink"
    if (normalized in DISPLAY_ACRONYMS) return normalized.uppercase(Locale.ROOT)
    if (value.length <= 4 && value.all { it.isUpperCase() || it.isDigit() }) return value
    return normalized.replaceFirstChar { first ->
        if (first.isLowerCase()) first.titlecase(Locale.ROOT) else first.toString()
    }
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

private val DISPLAY_ACRONYMS = setOf(
    "cog",
    "gps",
    "imu",
    "nmea",
    "pgn",
    "rms",
    "sog",
    "stw",
    "vmg"
)
