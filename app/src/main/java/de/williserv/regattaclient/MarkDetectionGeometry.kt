package de.williserv.regattaclient

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

private const val MARK_INTERSECTION_DISABLED_FROM_TURN_DEG = 160.0
private const val MARK_GATE_RAY_TOLERANCE_M = 0.25

internal typealias MarkDetectionDirection = CourseDirection

internal data class MarkDetectionGate(
    val center: GeoPoint,
    val intersection: GeoPoint?
)

internal data class MarkDetectionGeometry(
    val entry: MarkDetectionGate,
    val exit: MarkDetectionGate,
    val incoming: MarkDetectionDirection,
    val outgoing: MarkDetectionDirection,
    val turnAngleDeg: Double
) {
    val limitedToIntersection: Boolean
        get() = entry.intersection != null && exit.intersection != null
}

internal data class MarkDetectionProgress(
    val geometry: MarkDetectionGeometry,
    val entryCrossed: Boolean = false,
    val exitCrossed: Boolean = false
) {
    val completed: Boolean
        get() = entryCrossed && exitCrossed
}

internal data class MarkDetectionAnchors(
    val previous: GeoPoint,
    val next: GeoPoint
)

internal fun resolveMarkDetectionAnchors(
    previousCoursePosition: GeoPoint?,
    nextCoursePosition: GeoPoint?,
    startLine: StartLine?,
    finishLine: StartLine?
): MarkDetectionAnchors? {
    val previous = previousCoursePosition ?: startLine?.let(::lineMidpoint)
    val next = nextCoursePosition ?: finishLine?.let(::lineMidpoint)

    if (previous == null || next == null) return null

    return MarkDetectionAnchors(previous = previous, next = next)
}

internal fun buildMarkDetectionGeometry(
    previousAnchor: GeoPoint,
    mark: GeoPoint,
    nextAnchor: GeoPoint,
    radiusM: Double
): MarkDetectionGeometry? {
    val previous = projectToCourseLocal(previousAnchor, mark)
    val next = projectToCourseLocal(nextAnchor, mark)

    val incoming = normalizeCourseDirection(-previous.x, -previous.y) ?: return null
    val outgoing = normalizeCourseDirection(next.x, next.y) ?: return null

    val dot = (incoming.x * outgoing.x + incoming.y * outgoing.y)
        .coerceIn(-1.0, 1.0)
    val turnAngleDeg = acos(dot) * 180.0 / PI
    val limitToIntersection = turnAngleDeg < MARK_INTERSECTION_DISABLED_FROM_TURN_DEG

    val entryCenterLocal = LocalPoint(
        x = -radiusM * incoming.x,
        y = -radiusM * incoming.y
    )
    val exitCenterLocal = LocalPoint(
        x = radiusM * outgoing.x,
        y = radiusM * outgoing.y
    )

    val entryDirection = MarkDetectionDirection(
        x = -incoming.y,
        y = incoming.x
    )
    val exitDirection = MarkDetectionDirection(
        x = -outgoing.y,
        y = outgoing.x
    )

    val intersectionLocal = if (limitToIntersection) {
        infiniteLinesIntersection(
            centerA = entryCenterLocal,
            directionA = entryDirection,
            centerB = exitCenterLocal,
            directionB = exitDirection
        )
    } else {
        null
    }

    val intersection = intersectionLocal?.let {
        localToCourseGeoPoint(it, mark)
    }

    return MarkDetectionGeometry(
        entry = MarkDetectionGate(
            center = localToCourseGeoPoint(entryCenterLocal, mark),
            intersection = intersection
        ),
        exit = MarkDetectionGate(
            center = localToCourseGeoPoint(exitCenterLocal, mark),
            intersection = intersection
        ),
        incoming = incoming,
        outgoing = outgoing,
        turnAngleDeg = turnAngleDeg
    )
}

internal fun updateMarkDetectionProgress(
    previousPosition: GeoPoint,
    currentPosition: GeoPoint,
    geometry: MarkDetectionGeometry,
    previousProgress: MarkDetectionProgress?
): MarkDetectionProgress {
    val base = previousProgress
        ?: MarkDetectionProgress(geometry = geometry)

    return base.copy(
        geometry = geometry,
        entryCrossed = base.entryCrossed || markDetectionGateCrossed(
            previousPosition = previousPosition,
            currentPosition = currentPosition,
            gate = geometry.entry,
            travelDirection = geometry.incoming
        ),
        exitCrossed = base.exitCrossed || markDetectionGateCrossed(
            previousPosition = previousPosition,
            currentPosition = currentPosition,
            gate = geometry.exit,
            travelDirection = geometry.outgoing
        )
    )
}

internal fun markDetectionGateCrossed(
    previousPosition: GeoPoint,
    currentPosition: GeoPoint,
    gate: MarkDetectionGate,
    travelDirection: MarkDetectionDirection
): Boolean {
    val fraction = directedInfiniteLineCrossingFraction(
        previousPosition = previousPosition,
        currentPosition = currentPosition,
        lineCenter = gate.center,
        travelDirection = travelDirection
    ) ?: return false

    val crossing = GeoPoint(
        lat = previousPosition.lat +
            (currentPosition.lat - previousPosition.lat) * fraction,
        lon = previousPosition.lon +
            (currentPosition.lon - previousPosition.lon) * fraction
    )

    return crossingIsOnActiveGateRay(crossing, gate)
}

private fun crossingIsOnActiveGateRay(
    crossing: GeoPoint,
    gate: MarkDetectionGate
): Boolean {
    val intersection = gate.intersection ?: return true
    val center = projectToCourseLocal(gate.center, intersection)
    val point = projectToCourseLocal(crossing, intersection)

    val centerDistance = hypot(center.x, center.y)
    if (centerDistance < COURSE_CROSSING_EPSILON_M) return true

    val distanceAlongActiveRay =
        (point.x * center.x + point.y * center.y) / centerDistance

    return distanceAlongActiveRay >= -MARK_GATE_RAY_TOLERANCE_M
}

private fun infiniteLinesIntersection(
    centerA: LocalPoint,
    directionA: MarkDetectionDirection,
    centerB: LocalPoint,
    directionB: MarkDetectionDirection
): LocalPoint? {
    val denominator = cross(directionA, directionB)
    if (abs(denominator) < 1e-9) return null

    val delta = LocalPoint(
        x = centerB.x - centerA.x,
        y = centerB.y - centerA.y
    )
    val factor = cross(delta, directionB) / denominator

    return LocalPoint(
        x = centerA.x + factor * directionA.x,
        y = centerA.y + factor * directionA.y
    )
}

private fun cross(
    a: MarkDetectionDirection,
    b: MarkDetectionDirection
): Double = a.x * b.y - a.y * b.x

private fun cross(
    a: LocalPoint,
    b: MarkDetectionDirection
): Double = a.x * b.y - a.y * b.x
