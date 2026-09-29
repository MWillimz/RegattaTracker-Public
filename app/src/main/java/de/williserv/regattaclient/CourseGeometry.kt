package de.williserv.regattaclient

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

internal const val COURSE_CROSSING_EPSILON_M = 1e-6
private const val COURSE_EARTH_RADIUS_M = 6_371_000.0

internal data class CourseDirection(
    val x: Double,
    val y: Double
)

internal fun projectToCourseLocal(
    point: GeoPoint,
    origin: GeoPoint
): LocalPoint {
    val latRad = origin.lat * PI / 180.0
    val x = (point.lon - origin.lon) * PI / 180.0 *
        COURSE_EARTH_RADIUS_M * cos(latRad)
    val y = (point.lat - origin.lat) * PI / 180.0 *
        COURSE_EARTH_RADIUS_M

    return LocalPoint(x = x, y = y)
}

internal fun localToCourseGeoPoint(
    point: LocalPoint,
    origin: GeoPoint
): GeoPoint {
    val lat = origin.lat +
        (point.y / COURSE_EARTH_RADIUS_M) * 180.0 / PI

    val cosLat = cos(origin.lat * PI / 180.0)
    val lon = if (abs(cosLat) < 1e-12) {
        origin.lon
    } else {
        origin.lon +
            (point.x / (COURSE_EARTH_RADIUS_M * cosLat)) * 180.0 / PI
    }

    return GeoPoint(lat = lat, lon = lon)
}

internal fun normalizeCourseDirection(
    x: Double,
    y: Double
): CourseDirection? {
    val length = hypot(x, y)
    if (!length.isFinite() || length < 1e-9) return null

    return CourseDirection(
        x = x / length,
        y = y / length
    )
}

internal fun directedInfiniteLineCrossingFraction(
    previousPosition: GeoPoint,
    currentPosition: GeoPoint,
    lineCenter: GeoPoint,
    travelDirection: CourseDirection
): Double? {
    val previous = projectToCourseLocal(previousPosition, lineCenter)
    val current = projectToCourseLocal(currentPosition, lineCenter)

    val previousProgress =
        previous.x * travelDirection.x + previous.y * travelDirection.y
    val currentProgress =
        current.x * travelDirection.x + current.y * travelDirection.y

    if (previousProgress > COURSE_CROSSING_EPSILON_M) return null
    if (currentProgress < -COURSE_CROSSING_EPSILON_M) return null

    val delta = currentProgress - previousProgress
    if (delta <= COURSE_CROSSING_EPSILON_M) return null

    val fraction = -previousProgress / delta
    if (
        fraction < -COURSE_CROSSING_EPSILON_M ||
        fraction > 1.0 + COURSE_CROSSING_EPSILON_M
    ) {
        return null
    }

    return fraction.coerceIn(0.0, 1.0)
}
