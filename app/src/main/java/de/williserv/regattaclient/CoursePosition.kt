package de.williserv.regattaclient

import org.json.JSONObject

internal enum class CoursePositionKind {
    MARK,
    GATE
}

internal enum class GateDirection {
    POSITIVE,
    NEGATIVE
}

internal data class CoursePosition(
    val order: Int,
    val name: String,
    val kind: CoursePositionKind,
    val omitWhenShortened: Boolean,
    val markPoint: GeoPoint? = null,
    val radiusM: Double? = null,
    val gateRef: GeoPoint? = null,
    val gateMark: GeoPoint? = null,
    val gateDirection: GateDirection? = null,
    val gateOffsetM: Double? = null
) {
    fun referencePoint(): GeoPoint? = when (kind) {
        CoursePositionKind.MARK -> markPoint
        CoursePositionKind.GATE -> gateMidpoint(gateRef, gateMark)
    }
}

internal fun parseCoursePositions(course: JSONObject?): List<CoursePosition> {
    val marks = course?.optJSONArray("marks") ?: return emptyList()
    val result = mutableListOf<CoursePosition>()

    for (index in 0 until marks.length()) {
        val item = marks.optJSONObject(index) ?: continue
        val order = item.optInt("order", index + 1)
        val isGate = item.optString("type") == "gate"
        val kind = if (isGate) CoursePositionKind.GATE else CoursePositionKind.MARK
        val defaultName = if (isGate) "Gate $order" else "Mark $order"

        if (isGate) {
            result += CoursePosition(
                order = order,
                name = item.optString("name", defaultName).ifBlank { defaultName },
                kind = CoursePositionKind.GATE,
                omitWhenShortened = item.optBoolean("omit_when_shortened", false),
                gateRef = item.optJSONObject("ref")?.toFiniteGeoPointOrNull(),
                gateMark = item.optJSONObject("mark")?.toFiniteGeoPointOrNull(),
                gateDirection = when (item.optString("direction")) {
                    "positive" -> GateDirection.POSITIVE
                    "negative" -> GateDirection.NEGATIVE
                    else -> null
                },
                gateOffsetM = item.finiteNonNegativeDoubleOrNull("offset_m")
            )
        } else {
            result += CoursePosition(
                order = order,
                name = item.optString("name", defaultName).ifBlank { defaultName },
                kind = CoursePositionKind.MARK,
                omitWhenShortened = item.optBoolean("omit_when_shortened", false),
                markPoint = item.toFiniteGeoPointOrNull(),
                radiusM = item.finitePositiveDoubleOrDefault("radius_m", 100.0)
            )
        }
    }

    return result.sortedBy { it.order }
}

internal fun List<CoursePosition>.activeCoursePositions(
    courseShortened: Boolean
): List<CoursePosition> = if (courseShortened) {
    filterNot { it.omitWhenShortened }
} else {
    this
}

internal fun gateMidpoint(
    ref: GeoPoint?,
    mark: GeoPoint?
): GeoPoint? {
    if (ref == null || mark == null) return null

    return GeoPoint(
        lat = (ref.lat + mark.lat) / 2.0,
        lon = (ref.lon + mark.lon) / 2.0
    )
}

private fun JSONObject.toFiniteGeoPointOrNull(): GeoPoint? {
    if (!has("lat") || !has("lon") || isNull("lat") || isNull("lon")) return null

    val lat = optDouble("lat", Double.NaN)
    val lon = optDouble("lon", Double.NaN)
    if (
        !lat.isFinite() ||
        !lon.isFinite() ||
        lat !in -90.0..90.0 ||
        lon !in -180.0..180.0
    ) {
        return null
    }

    return GeoPoint(lat = lat, lon = lon)
}

private fun JSONObject.finiteNonNegativeDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null

    val value = optDouble(key, Double.NaN)
    return value.takeIf { it.isFinite() && it >= 0.0 }
}

private fun JSONObject.finitePositiveDoubleOrDefault(
    key: String,
    defaultValue: Double
): Double {
    if (!has(key) || isNull(key)) return defaultValue

    val value = optDouble(key, Double.NaN)
    return value.takeIf { it.isFinite() && it > 0.0 } ?: defaultValue
}
