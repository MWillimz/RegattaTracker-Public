package de.williserv.regattaclient

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

internal enum class SamplingDistanceBand { NEAR, MEDIUM, FAR, VERY_FAR }

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
    private const val HYSTERESIS_FACTOR = 1.10

    fun decide(
        position: GeoPoint?,
        startLine: StartLine?,
        finishLine: StartLine?,
        coursePositions: List<CoursePosition>,
        trackingProfile: TrackingProfile,
        sailNumber: String,
        previousBand: SamplingDistanceBand?
    ): SamplingDecision {
        if (sailNumber.startsWith("MARK:", ignoreCase = false)) {
            return SamplingDecision(
                intervalMs = when (trackingProfile) {
                    TrackingProfile.NORMAL -> 30_000L
                    TrackingProfile.BATTERY_SAVER -> 60_000L
                    TrackingProfile.FIXED_1S -> 1_000L
                },
                band = null,
                nearestDistanceM = null
            )
        }
        val nearestDistanceM = position?.let {
            nearestRelevantCourseElementDistanceM(it, startLine, finishLine, coursePositions)
        }
        val nextBand = if (nearestDistanceM == null) SamplingDistanceBand.NEAR
        else resolveBandWithHysteresis(nearestDistanceM, previousBand)
        return SamplingDecision(intervalForBand(trackingProfile, nextBand), nextBand, nearestDistanceM)
    }

    fun nearestRelevantCourseElementDistanceM(
        position: GeoPoint,
        startLine: StartLine?,
        finishLine: StartLine?,
        coursePositions: List<CoursePosition>
    ): Double? {
        val distances = mutableListOf<Double>()
        startLine?.let { distances += distanceToSegmentMeters(position, it.ref, it.mark) }
        finishLine?.let { distances += distanceToSegmentMeters(position, it.ref, it.mark) }

        coursePositions.forEachIndexed { index, coursePosition ->
            when (coursePosition.kind) {
                CoursePositionKind.MARK -> {
                    val markPoint = coursePosition.markPoint ?: return null
                    distances += StartLineMath.distanceBetweenMeters(position, markPoint)
                }
                CoursePositionKind.GATE -> {
                    val previousAnchor = coursePositions.getOrNull(index - 1)?.referencePoint()
                        ?: startLine?.let(::lineMidpoint)
                    val geometry = buildCourseGateDetectionGeometry(previousAnchor, coursePosition)
                        ?: return null
                    distances += distanceToGateDetectionLineM(position, geometry)
                }
            }
        }
        return distances.minOrNull()
    }

    fun resolveBandWithHysteresis(distanceM: Double, previousBand: SamplingDistanceBand?): SamplingDistanceBand {
        require(distanceM >= 0.0)
        if (previousBand == null) return nominalBand(distanceM)
        return when (previousBand) {
            SamplingDistanceBand.NEAR -> if (distanceM < NEAR_LIMIT_M * HYSTERESIS_FACTOR) SamplingDistanceBand.NEAR else nominalBand(distanceM)
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
            SamplingDistanceBand.VERY_FAR -> if (distanceM < TEN_NAUTICAL_MILES_M) nominalBand(distanceM) else SamplingDistanceBand.VERY_FAR
        }
    }

    fun intervalForBand(trackingProfile: TrackingProfile, band: SamplingDistanceBand): Long = when (trackingProfile) {
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
        TrackingProfile.FIXED_1S -> 1_000L
    }

    private fun nominalBand(distanceM: Double): SamplingDistanceBand = when {
        distanceM < NEAR_LIMIT_M -> SamplingDistanceBand.NEAR
        distanceM < ONE_NAUTICAL_MILE_M -> SamplingDistanceBand.MEDIUM
        distanceM < TEN_NAUTICAL_MILES_M -> SamplingDistanceBand.FAR
        else -> SamplingDistanceBand.VERY_FAR
    }

    private fun distanceToSegmentMeters(point: GeoPoint, segmentA: GeoPoint, segmentB: GeoPoint): Double {
        val origin = segmentA
        val p = toLocalMeters(point, origin)
        val a = toLocalMeters(segmentA, origin)
        val b = toLocalMeters(segmentB, origin)
        val abX = b.x - a.x
        val abY = b.y - a.y
        val lengthSquared = abX * abX + abY * abY
        if (lengthSquared == 0.0) return hypot(p.x - a.x, p.y - a.y)
        val projection = (((p.x - a.x) * abX + (p.y - a.y) * abY) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + projection * abX), p.y - (a.y + projection * abY))
    }

    private fun toLocalMeters(point: GeoPoint, origin: GeoPoint): LocalPoint {
        val latRad = origin.lat * PI / 180.0
        val dLatRad = (point.lat - origin.lat) * PI / 180.0
        val dLonRad = (point.lon - origin.lon) * PI / 180.0
        return LocalPoint(dLonRad * cos(latRad) * EARTH_RADIUS_M, dLatRad * EARTH_RADIUS_M)
    }
}
