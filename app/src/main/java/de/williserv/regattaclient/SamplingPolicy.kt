package de.williserv.regattaclient

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

internal enum class SamplingDistanceBand {
    NEAR,
    MEDIUM,
    FAR,
    VERY_FAR
}

internal data class SamplingDecision(
    val intervalMs: Long,
    val band: SamplingDistanceBand?,
    val nearestDistanceM: Double?
)

internal object SamplingPolicy {
    private const val EARTH_RADIUS_M = 6_371_000.0

    private const val NEAR_LIMIT_M = 250.0
    private const val ONE_NAUTICAL_MILE_M = 1_852.0
    private const val TEN_NAUTICAL_MILES_M = 18_520.0

    // Outward transitions use a small 10% margin so GPS noise cannot flap the interval.
    private const val HYSTERESIS_FACTOR = 1.10

    fun decide(
        position: GeoPoint?,
        startLine: StartLine?,
        finishLine: StartLine?,
        courseMarks: List<CourseMark>,
        trackingProfile: TrackingProfile,
        sailNumber: String,
        previousBand: SamplingDistanceBand?
    ): SamplingDecision {
        if (sailNumber.startsWith("MARK:", ignoreCase = false)) {
            return SamplingDecision(
                intervalMs = when (trackingProfile) {
                    TrackingProfile.NORMAL -> 30_000L
                    TrackingProfile.BATTERY_SAVER -> 60_000L
                },
                band = null,
                nearestDistanceM = null
            )
        }

        val nearestDistanceM = position?.let {
            nearestRelevantCourseElementDistanceM(
                position = it,
                startLine = startLine,
                finishLine = finishLine,
                courseMarks = courseMarks
            )
        }

        // Missing geometry must never reduce raw-data quality. Until geometry is available,
        // use the fastest band of the selected profile.
        val nextBand = if (nearestDistanceM == null) {
            SamplingDistanceBand.NEAR
        } else {
            resolveBandWithHysteresis(
                distanceM = nearestDistanceM,
                previousBand = previousBand
            )
        }

        return SamplingDecision(
            intervalMs = intervalForBand(trackingProfile, nextBand),
            band = nextBand,
            nearestDistanceM = nearestDistanceM
        )
    }

    fun nearestRelevantCourseElementDistanceM(
        position: GeoPoint,
        startLine: StartLine?,
        finishLine: StartLine?,
        courseMarks: List<CourseMark>
    ): Double? {
        val distances = mutableListOf<Double>()

        startLine?.let {
            distances += distanceToSegmentMeters(position, it.ref, it.mark)
        }

        finishLine?.let {
            distances += distanceToSegmentMeters(position, it.ref, it.mark)
        }

        courseMarks.forEach { mark ->
            distances += StartLineMath.distanceBetweenMeters(position, mark.point)
        }

        return distances.minOrNull()
    }

    fun resolveBandWithHysteresis(
        distanceM: Double,
        previousBand: SamplingDistanceBand?
    ): SamplingDistanceBand {
        require(distanceM >= 0.0)

        if (previousBand == null) {
            return nominalBand(distanceM)
        }

        return when (previousBand) {
            SamplingDistanceBand.NEAR -> {
                if (distanceM < NEAR_LIMIT_M * HYSTERESIS_FACTOR) {
                    SamplingDistanceBand.NEAR
                } else {
                    nominalBand(distanceM)
                }
            }

            SamplingDistanceBand.MEDIUM -> when {
                distanceM < NEAR_LIMIT_M -> SamplingDistanceBand.NEAR
                distanceM < ONE_NAUTICAL_MILE_M * HYSTERESIS_FACTOR -> SamplingDistanceBand.MEDIUM
                else -> nominalBand(distanceM)
            }

            SamplingDistanceBand.FAR -> when {
                distanceM < ONE_NAUTICAL_MILE_M -> nominalBand(distanceM)
                distanceM < TEN_NAUTICAL_MILES_M * HYSTERESIS_FACTOR -> SamplingDistanceBand.FAR
                else -> SamplingDistanceBand.VERY_FAR
            }

            SamplingDistanceBand.VERY_FAR -> {
                if (distanceM < TEN_NAUTICAL_MILES_M) {
                    nominalBand(distanceM)
                } else {
                    SamplingDistanceBand.VERY_FAR
                }
            }
        }
    }

    fun intervalForBand(
        trackingProfile: TrackingProfile,
        band: SamplingDistanceBand
    ): Long {
        return when (trackingProfile) {
            TrackingProfile.NORMAL -> when (band) {
                SamplingDistanceBand.NEAR -> 1_000L
                SamplingDistanceBand.MEDIUM -> 2_000L
                SamplingDistanceBand.FAR -> 5_000L
                SamplingDistanceBand.VERY_FAR -> 10_000L
            }

            TrackingProfile.BATTERY_SAVER -> when (band) {
                SamplingDistanceBand.NEAR -> 2_000L
                SamplingDistanceBand.MEDIUM -> 10_000L
                SamplingDistanceBand.FAR -> 30_000L
                SamplingDistanceBand.VERY_FAR -> 60_000L
            }
        }
    }

    private fun nominalBand(distanceM: Double): SamplingDistanceBand {
        return when {
            distanceM < NEAR_LIMIT_M -> SamplingDistanceBand.NEAR
            distanceM < ONE_NAUTICAL_MILE_M -> SamplingDistanceBand.MEDIUM
            distanceM < TEN_NAUTICAL_MILES_M -> SamplingDistanceBand.FAR
            else -> SamplingDistanceBand.VERY_FAR
        }
    }

    private fun distanceToSegmentMeters(
        point: GeoPoint,
        segmentA: GeoPoint,
        segmentB: GeoPoint
    ): Double {
        val origin = segmentA
        val p = toLocalMeters(point, origin)
        val a = toLocalMeters(segmentA, origin)
        val b = toLocalMeters(segmentB, origin)

        val abX = b.x - a.x
        val abY = b.y - a.y
        val lengthSquared = abX * abX + abY * abY

        if (lengthSquared == 0.0) {
            return hypot(p.x - a.x, p.y - a.y)
        }

        val apX = p.x - a.x
        val apY = p.y - a.y
        val projection = ((apX * abX + apY * abY) / lengthSquared).coerceIn(0.0, 1.0)
        val closestX = a.x + projection * abX
        val closestY = a.y + projection * abY

        return hypot(p.x - closestX, p.y - closestY)
    }

    private fun toLocalMeters(point: GeoPoint, origin: GeoPoint): LocalPoint {
        val latRad = origin.lat * PI / 180.0
        val dLatRad = (point.lat - origin.lat) * PI / 180.0
        val dLonRad = (point.lon - origin.lon) * PI / 180.0

        return LocalPoint(
            x = dLonRad * cos(latRad) * EARTH_RADIUS_M,
            y = dLatRad * EARTH_RADIUS_M
        )
    }
}
