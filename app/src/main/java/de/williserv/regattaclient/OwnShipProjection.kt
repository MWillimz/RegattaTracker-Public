package de.williserv.regattaclient

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal const val OWN_SHIP_COURSE_VECTOR_SECONDS = 300.0
private const val METERS_PER_SECOND_TO_KNOTS = 1.9438444924406
private const val EARTH_RADIUS_METERS = 6_371_000.0

data class OwnShipGeoPoint(
    val lat: Double,
    val lon: Double
)

internal fun metersPerSecondToKnots(speedMps: Double): Double? {
    if (!speedMps.isFinite() || speedMps < 0.0) return null
    return speedMps * METERS_PER_SECOND_TO_KNOTS
}

internal fun normalizeBearingDegrees(bearingDegrees: Double): Double? {
    if (!bearingDegrees.isFinite()) return null
    val normalized = bearingDegrees % 360.0
    return if (normalized < 0.0) normalized + 360.0 else normalized
}

internal fun ownShipCourseVectorDistanceMeters(speedMps: Double): Double? {
    if (!speedMps.isFinite() || speedMps < 0.0) return null
    return speedMps * OWN_SHIP_COURSE_VECTOR_SECONDS
}

internal fun destinationPoint(
    lat: Double,
    lon: Double,
    bearingDegrees: Double,
    distanceMeters: Double
): OwnShipGeoPoint? {
    if (
        !lat.isFinite() ||
        !lon.isFinite() ||
        lat !in -90.0..90.0 ||
        !distanceMeters.isFinite() ||
        distanceMeters < 0.0
    ) {
        return null
    }

    val bearing = normalizeBearingDegrees(bearingDegrees) ?: return null
    if (distanceMeters == 0.0) {
        return OwnShipGeoPoint(lat = lat, lon = normalizeLongitudeDegrees(lon))
    }

    val lat1 = Math.toRadians(lat)
    val lon1 = Math.toRadians(lon)
    val bearingRad = Math.toRadians(bearing)
    val angularDistance = distanceMeters / EARTH_RADIUS_METERS

    val lat2 = asin(
        sin(lat1) * cos(angularDistance) +
            cos(lat1) * sin(angularDistance) * cos(bearingRad)
    )
    val lon2 = lon1 + atan2(
        sin(bearingRad) * sin(angularDistance) * cos(lat1),
        cos(angularDistance) - sin(lat1) * sin(lat2)
    )

    return OwnShipGeoPoint(
        lat = Math.toDegrees(lat2),
        lon = normalizeLongitudeDegrees(Math.toDegrees(lon2))
    )
}

internal fun ownShipCourseVectorEndpoint(
    lat: Double,
    lon: Double,
    speedMps: Double?,
    bearingDegrees: Double?
): OwnShipGeoPoint? {
    val speed = speedMps?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val bearing = bearingDegrees?.let(::normalizeBearingDegrees) ?: return null
    val distanceMeters = ownShipCourseVectorDistanceMeters(speed) ?: return null

    return destinationPoint(
        lat = lat,
        lon = lon,
        bearingDegrees = bearing,
        distanceMeters = distanceMeters
    )
}

private fun normalizeLongitudeDegrees(lon: Double): Double {
    val normalized = (lon + 180.0) % 360.0
    return if (normalized < 0.0) normalized + 180.0 else normalized - 180.0
}
