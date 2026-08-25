package de.williserv.regattaclient

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

data class GeoPoint(
    val lat: Double,
    val lon: Double
)

data class LocalPoint(
    val x: Double,
    val y: Double
)

data class StartLine(
    val ref: GeoPoint,
    val mark: GeoPoint
)

data class LineMetrics(
    val previousSignedDistanceM: Double?,
    val signedDistanceM: Double,
    val distanceM: Double,
    val ttlSeconds: Double?,
    val crossed: Boolean
)

object StartLineMath {

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun calculateLineMetrics(
        previousPosition: GeoPoint?,
        currentPosition: GeoPoint,
        previousTimestampMillis: Long?,
        currentTimestampMillis: Long,
        startLine: StartLine
    ): LineMetrics {
        val origin = startLine.ref

        val a = toLocalMeters(startLine.ref, origin)
        val b = toLocalMeters(startLine.mark, origin)
        val p = toLocalMeters(currentPosition, origin)

        val signedDistance = signedDistanceToInfiniteLineM(p, a, b)
        val distance = abs(signedDistance)

        val previousSignedDistance = previousPosition?.let {
            val prev = toLocalMeters(it, origin)
            signedDistanceToInfiniteLineM(prev, a, b)
        }

        val crossed = previousSignedDistance != null &&
                hasCrossedLine(previousSignedDistance, signedDistance)

        val ttl = if (
            previousPosition != null &&
            previousTimestampMillis != null &&
            currentTimestampMillis > previousTimestampMillis
        ) {
            val prev = toLocalMeters(previousPosition, origin)
            calculateTimeToLineSeconds(
                previous = prev,
                current = p,
                previousTimeMillis = previousTimestampMillis,
                currentTimeMillis = currentTimestampMillis,
                lineA = a,
                lineB = b
            )
        } else {
            null
        }

        return LineMetrics(
            previousSignedDistanceM = previousSignedDistance,
            signedDistanceM = signedDistance,
            distanceM = distance,
            ttlSeconds = ttl,
            crossed = crossed
        )
    }

    fun crossedWithTolerance(
        previousSignedDistanceM: Double?,
        currentSignedDistanceM: Double,
        toleranceM: Double
    ): Boolean {
        if (previousSignedDistanceM == null) return false

        val previousOutside = abs(previousSignedDistanceM) > toleranceM
        val currentOutside = abs(currentSignedDistanceM) > toleranceM

        if (!previousOutside || !currentOutside) return false

        return previousSignedDistanceM < 0.0 && currentSignedDistanceM > 0.0 ||
                previousSignedDistanceM > 0.0 && currentSignedDistanceM < 0.0
    }

    fun signedDistanceToStartLineM(
        point: GeoPoint,
        startLine: StartLine
    ): Double {
        val origin = startLine.ref

        val a = toLocalMeters(startLine.ref, origin)
        val b = toLocalMeters(startLine.mark, origin)
        val p = toLocalMeters(point, origin)

        return signedDistanceToInfiniteLineM(p, a, b)
    }

    fun distanceBetweenMeters(
        a: GeoPoint,
        b: GeoPoint
    ): Double {
        val origin = a
        val localA = toLocalMeters(a, origin)
        val localB = toLocalMeters(b, origin)

        return hypot(
            localB.x - localA.x,
            localB.y - localA.y
        )
    }

    private fun toLocalMeters(point: GeoPoint, origin: GeoPoint): LocalPoint {
        val latRad = origin.lat * PI / 180.0

        val dLatRad = (point.lat - origin.lat) * PI / 180.0
        val dLonRad = (point.lon - origin.lon) * PI / 180.0

        val x = dLonRad * cos(latRad) * EARTH_RADIUS_M
        val y = dLatRad * EARTH_RADIUS_M

        return LocalPoint(x = x, y = y)
    }

    private fun signedDistanceToInfiniteLineM(
        p: LocalPoint,
        a: LocalPoint,
        b: LocalPoint
    ): Double {
        val abX = b.x - a.x
        val abY = b.y - a.y

        val apX = p.x - a.x
        val apY = p.y - a.y

        val lineLength = hypot(abX, abY)

        if (lineLength == 0.0) {
            return 0.0
        }

        return (abX * apY - abY * apX) / lineLength
    }


    private fun hasCrossedLine(
        previousSignedDistanceM: Double,
        currentSignedDistanceM: Double
    ): Boolean {
        if (previousSignedDistanceM == 0.0 || currentSignedDistanceM == 0.0) {
            return true
        }

        return previousSignedDistanceM < 0.0 && currentSignedDistanceM > 0.0 ||
                previousSignedDistanceM > 0.0 && currentSignedDistanceM < 0.0
    }

    private fun calculateTimeToLineSeconds(
        previous: LocalPoint,
        current: LocalPoint,
        previousTimeMillis: Long,
        currentTimeMillis: Long,
        lineA: LocalPoint,
        lineB: LocalPoint
    ): Double? {
        val dtSeconds = (currentTimeMillis - previousTimeMillis) / 1000.0

        if (dtSeconds <= 0.0) {
            return null
        }

        val previousDistance = signedDistanceToInfiniteLineM(previous, lineA, lineB)
        val currentDistance = signedDistanceToInfiniteLineM(current, lineA, lineB)

        val distanceChangePerSecond = (currentDistance - previousDistance) / dtSeconds

        if (distanceChangePerSecond == 0.0) {
            return null
        }

        val ttl = -currentDistance / distanceChangePerSecond

        return if (ttl >= 0.0) ttl else null
    }
}