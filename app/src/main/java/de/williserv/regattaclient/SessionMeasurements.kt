package de.williserv.regattaclient

import org.json.JSONObject
import java.util.Locale

data class SessionNumericMeasurement(
    val key: String,
    val label: String,
    val unit: String?,
    val group: String?
)

internal fun discoverSessionNumericMeasurements(
    samples: List<SessionTrackingSample>
): List<SessionNumericMeasurement> {
    if (samples.isEmpty()) return emptyList()

    val discovered = linkedMapOf<String, SessionNumericMeasurement>()

    samples.forEach { sample ->
        val measurements = parseSessionMeasurements(sample.measurementsJson)
            ?: return@forEach
        val keys = measurements.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (discovered.containsKey(key)) continue

            val measurement = measurements.optJSONObject(key) ?: continue
            val value = measurement.opt("value")
            if (value !is Number || !value.toDouble().isFinite()) continue

            val group = measurement.optString("group")
                .takeIf { it.isNotBlank() }
            if (isSessionMeasurementBlacklisted(key, group)) continue

            val unit = measurement.optString("unit")
                .takeIf { it.isNotBlank() }

            discovered[key] = SessionNumericMeasurement(
                key = key,
                label = prettySessionMeasurementLabel(key, group, unit),
                unit = unit,
                group = group
            )
        }
    }

    return discovered.values.sortedWith(
        compareBy<SessionNumericMeasurement> { it.group.orEmpty() }
            .thenBy { it.label }
            .thenBy { it.key }
    )
}

internal fun sessionNumericMeasurementValues(
    sample: SessionTrackingSample
): Map<String, Double> {
    val measurements = parseSessionMeasurements(sample.measurementsJson)
        ?: return emptyMap()
    return buildMap {
        val keys = measurements.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val measurement = measurements.optJSONObject(key) ?: continue
            val group = measurement.optString("group")
                .takeIf { it.isNotBlank() }
            if (isSessionMeasurementBlacklisted(key, group)) continue

            val value = measurement.opt("value")
            if (value is Number) {
                val numeric = value.toDouble()
                if (numeric.isFinite()) {
                    put(key, numeric)
                }
            }
        }
    }
}

internal fun sessionNumericMeasurementValue(
    sample: SessionTrackingSample,
    key: String
): Double? {
    val measurements = parseSessionMeasurements(sample.measurementsJson)
        ?: return null
    val measurement = measurements.optJSONObject(key) ?: return null
    val group = measurement.optString("group")
        .takeIf { it.isNotBlank() }
    if (isSessionMeasurementBlacklisted(key, group)) return null

    val value = measurement.opt("value")
    return (value as? Number)?.toDouble()?.takeIf { it.isFinite() }
}

internal fun isSessionMeasurementBlacklisted(
    key: String,
    group: String?
): Boolean {
    val normalizedKey = key.lowercase(Locale.ROOT)
    val normalizedGroup = group?.lowercase(Locale.ROOT).orEmpty()

    if (normalizedGroup in SESSION_MEASUREMENT_BLACKLIST_GROUPS) {
        return true
    }

    return SESSION_MEASUREMENT_BLACKLIST_KEY_TOKENS.any { token ->
        normalizedKey.contains(token)
    }
}

private val SESSION_MEASUREMENT_BLACKLIST_GROUPS = setOf(
    "debug",
    "diagnostic",
    "diagnostics",
    "metadata",
    "protocol",
    "transport"
)

private val SESSION_MEASUREMENT_BLACKLIST_KEY_TOKENS = setOf(
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

private fun parseSessionMeasurements(raw: String?): JSONObject? {
    if (raw.isNullOrBlank()) return null
    return runCatching { JSONObject(raw) }.getOrNull()
}

private fun prettySessionMeasurementLabel(
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
        "m/s" -> setOf("mps")
        "m" -> setOf("m")
        "c", "°c" -> setOf("c")
        else -> emptySet()
    }

    val parts = visibleKey
        .split('.', '_')
        .filter { it.isNotBlank() && it.lowercase(Locale.ROOT) !in unitTokens }

    return parts.joinToString(" ") { prettySessionIdentifier(it) }
        .ifBlank { prettySessionIdentifier(key) }
}

private fun prettySessionIdentifier(value: String): String {
    val normalized = value.lowercase(Locale.ROOT)
    if (normalized == "regattalink") return "RegattaLink"
    if (normalized in SESSION_DISPLAY_ACRONYMS) {
        return normalized.uppercase(Locale.ROOT)
    }
    if (value.length <= 4 && value.all { it.isUpperCase() || it.isDigit() }) {
        return value
    }
    return normalized.replaceFirstChar { first ->
        if (first.isLowerCase()) first.titlecase(Locale.ROOT) else first.toString()
    }
}

private val SESSION_DISPLAY_ACRONYMS = setOf(
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
