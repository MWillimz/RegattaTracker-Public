package de.williserv.regattaclient

import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

internal enum class ReplayExtraFieldSource {
    INTERNAL_IMU,
    MEASUREMENT
}

internal enum class ReplayBuiltinField {
    ACCEL_X,
    ACCEL_Y,
    ACCEL_Z,
    GYRO_X,
    GYRO_Y,
    GYRO_Z
}

internal data class ReplayExtraField(
    val id: String,
    val source: ReplayExtraFieldSource,
    val label: String,
    val unit: String?,
    val builtin: ReplayBuiltinField? = null,
    val measurementKey: String? = null,
    val measurementGroup: String? = null
)

internal fun discoverReplayExtraFields(
    samples: List<SessionTrackingSample>
): List<ReplayExtraField> {
    if (samples.isEmpty()) return emptyList()

    val fields = mutableListOf(
        ReplayExtraField(
            id = "imu.accel_x",
            source = ReplayExtraFieldSource.INTERNAL_IMU,
            label = "Accel X",
            unit = "m/s²",
            builtin = ReplayBuiltinField.ACCEL_X
        ),
        ReplayExtraField(
            id = "imu.accel_y",
            source = ReplayExtraFieldSource.INTERNAL_IMU,
            label = "Accel Y",
            unit = "m/s²",
            builtin = ReplayBuiltinField.ACCEL_Y
        ),
        ReplayExtraField(
            id = "imu.accel_z",
            source = ReplayExtraFieldSource.INTERNAL_IMU,
            label = "Accel Z",
            unit = "m/s²",
            builtin = ReplayBuiltinField.ACCEL_Z
        ),
        ReplayExtraField(
            id = "imu.gyro_x",
            source = ReplayExtraFieldSource.INTERNAL_IMU,
            label = "Gyro X",
            unit = "rad/s",
            builtin = ReplayBuiltinField.GYRO_X
        ),
        ReplayExtraField(
            id = "imu.gyro_y",
            source = ReplayExtraFieldSource.INTERNAL_IMU,
            label = "Gyro Y",
            unit = "rad/s",
            builtin = ReplayBuiltinField.GYRO_Y
        ),
        ReplayExtraField(
            id = "imu.gyro_z",
            source = ReplayExtraFieldSource.INTERNAL_IMU,
            label = "Gyro Z",
            unit = "rad/s",
            builtin = ReplayBuiltinField.GYRO_Z
        )
    )

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

    fields += dynamic.values.sortedWith(
        compareBy<ReplayExtraField>(
            { it.measurementGroup.orEmpty() },
            { it.label },
            { it.id }
        )
    )
    return fields
}

internal fun replayExtraFieldValue(
    sample: SessionTrackingSample,
    field: ReplayExtraField
): String? {
    val builtinValue = when (field.builtin) {
        ReplayBuiltinField.ACCEL_X -> sample.accelX.toDouble()
        ReplayBuiltinField.ACCEL_Y -> sample.accelY.toDouble()
        ReplayBuiltinField.ACCEL_Z -> sample.accelZ.toDouble()
        ReplayBuiltinField.GYRO_X -> sample.gyroX.toDouble()
        ReplayBuiltinField.GYRO_Y -> sample.gyroY.toDouble()
        ReplayBuiltinField.GYRO_Z -> sample.gyroZ.toDouble()
        null -> null
    }
    if (builtinValue != null) {
        if (!builtinValue.isFinite()) return null
        val decimals = if (field.builtin?.name?.startsWith("GYRO") == true) 3 else 2
        return formatReplayNumber(builtinValue, decimals, field.unit)
    }

    val key = field.measurementKey ?: return null
    val json = parseMeasurements(sample.measurementsJson) ?: return null
    val measurement = json.optJSONObject(key) ?: return null
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

internal fun replayExtraFieldSourceLabel(field: ReplayExtraField): String {
    if (field.source == ReplayExtraFieldSource.INTERNAL_IMU) return "Internal IMU"
    val group = field.measurementGroup?.trim().orEmpty()
    if (group.equals("regattalink", ignoreCase = true)) return "RegattaLink"
    return group.takeIf { it.isNotBlank() }
        ?.let(::prettyIdentifier)
        ?: "Measurements"
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
    if (value.equals("regattalink", ignoreCase = true)) return "RegattaLink"
    if (value.length <= 4 && value.all { it.isUpperCase() || it.isDigit() }) return value
    return value.lowercase(Locale.ROOT)
        .replaceFirstChar { first ->
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
