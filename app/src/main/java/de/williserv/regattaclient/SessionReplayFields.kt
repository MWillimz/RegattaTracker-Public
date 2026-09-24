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
    if (samples.isEmpty()) return emptyList()

    val discovered = linkedMapOf<String, ReplayExtraField>()

    samples.forEach { sample ->
        val json = parseMeasurements(sample.measurementsJson) ?: return@forEach
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (discovered.containsKey(key)) continue

            val measurement = json.optJSONObject(key) ?: continue
            val value = measurement.opt("value")
            if (value !is Number || !value.toDouble().isFinite()) continue

            val group = measurement.optString("group")
                .takeIf { it.isNotBlank() }

            if (isReplayMeasurementBlacklisted(key, group)) continue

            val unit = measurement.optString("unit")
                .takeIf { it.isNotBlank() }
            val recommended = REPLAY_RECOMMENDED_BY_KEY[key]

            discovered[key] = ReplayExtraField(
                id = "measurement:$key",
                source = ReplayExtraFieldSource.MEASUREMENT,
                label = recommended?.label ?: prettyMeasurementLabel(key, group, unit),
                unit = unit,
                measurementKey = key,
                measurementGroup = group,
                recommended = recommended != null
            )
        }
    }

    return discovered.values.sortedWith(
        compareByDescending<ReplayExtraField> { it.recommended }
            .thenBy { it.measurementGroup.orEmpty() }
            .thenBy { it.label }
            .thenBy { it.id }
    )
}

private fun isReplayMeasurementBlacklisted(
    key: String,
    group: String?
): Boolean {
    val normalizedKey = key.lowercase(Locale.ROOT)
    val normalizedGroup = group?.lowercase(Locale.ROOT).orEmpty()

    if (
        normalizedGroup in setOf(
            "debug",
            "diagnostic",
            "diagnostics",
            "metadata",
            "protocol",
            "transport"
        )
    ) {
        return true
    }

    return REPLAY_BLACKLIST_KEY_TOKENS.any { token ->
        normalizedKey.contains(token)
    }
}

private val REPLAY_BLACKLIST_KEY_TOKENS = setOf(
    ".sequence",
    ".timestamp",
    ".confidence",
    ".calibration.",
    ".learner",
    ".gyro_bias",
    ".boat_frame_valid",
    ".positive_maneuvers",
    ".negative_maneuvers",
    ".roll_pair_observations",
    ".contradictory_maneuvers",
    ".mounting_epoch",
    ".calibration_revision",
    ".build",
    ".schema",
    ".protocol",
    ".transport",
    ".debug",
    ".diagnostic"
)

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

private val DISPLAY_ACRONYMS = setOf(
    "awa",
    "aws",
    "cog",
    "gps",
    "imu",
    "mag",
    "nmea",
    "pgn",
    "rms",
    "sog",
    "stw",
    "twa",
    "tws",
    "vmg"
)

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
