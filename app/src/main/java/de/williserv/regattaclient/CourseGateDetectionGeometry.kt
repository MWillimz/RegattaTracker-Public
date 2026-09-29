package de.williserv.regattaclient

import kotlin.math.abs

private const val GATE_OFFSET_SIDE_EPSILON_M = 0.01

internal data class CourseGateDetectionGeometry(
    val nominalLine: StartLine,
    val detectionLine: StartLine,
    val center: GeoPoint,
    val travelDirection: CourseDirection,
    val direction: GateDirection,
    val offsetM: Double,
    val previousAnchor: GeoPoint?
)

internal fun buildCourseGateDetectionGeometry(
    previousAnchor: GeoPoint?,
    gate: CoursePosition
): CourseGateDetectionGeometry? {
    if (gate.kind != CoursePositionKind.GATE) return null

    val ref = gate.gateRef ?: return null
    val mark = gate.gateMark ?: return null
    val direction = gate.gateDirection ?: return null
    val offsetM = gate.gateOffsetM ?: return null
    if (!offsetM.isFinite() || offsetM < 0.0) return null

    val nominalCenter = gateMidpoint(ref, mark) ?: return null
    val refLocal = projectToCourseLocal(ref, nominalCenter)
    val markLocal = projectToCourseLocal(mark, nominalCenter)

    val tangent = normalizeCourseDirection(
        x = markLocal.x - refLocal.x,
        y = markLocal.y - refLocal.y
    ) ?: return null

    val positiveNormal = CourseDirection(
        x = -tangent.y,
        y = tangent.x
    )
    val travelDirection = when (direction) {
        GateDirection.POSITIVE -> positiveNormal
        GateDirection.NEGATIVE -> CourseDirection(
            x = -positiveNormal.x,
            y = -positiveNormal.y
        )
    }

    var shiftX = 0.0
    var shiftY = 0.0

    if (offsetM > 0.0) {
        val anchor = previousAnchor ?: return null
        val previousLocal = projectToCourseLocal(anchor, nominalCenter)
        val signedPrevious =
            previousLocal.x * positiveNormal.x +
                previousLocal.y * positiveNormal.y

        if (abs(signedPrevious) <= GATE_OFFSET_SIDE_EPSILON_M) return null

        val side = if (signedPrevious > 0.0) 1.0 else -1.0
        shiftX = positiveNormal.x * offsetM * side
        shiftY = positiveNormal.y * offsetM * side
    }

    val detectionRef = localToCourseGeoPoint(
        point = LocalPoint(
            x = refLocal.x + shiftX,
            y = refLocal.y + shiftY
        ),
        origin = nominalCenter
    )
    val detectionMark = localToCourseGeoPoint(
        point = LocalPoint(
            x = markLocal.x + shiftX,
            y = markLocal.y + shiftY
        ),
        origin = nominalCenter
    )
    val detectionCenter = localToCourseGeoPoint(
        point = LocalPoint(x = shiftX, y = shiftY),
        origin = nominalCenter
    )

    return CourseGateDetectionGeometry(
        nominalLine = StartLine(ref = ref, mark = mark),
        detectionLine = StartLine(ref = detectionRef, mark = detectionMark),
        center = detectionCenter,
        travelDirection = travelDirection,
        direction = direction,
        offsetM = offsetM,
        previousAnchor = previousAnchor
    )
}

internal fun gateDetectionCrossingFraction(
    previousPosition: GeoPoint,
    currentPosition: GeoPoint,
    geometry: CourseGateDetectionGeometry
): Double? = directedInfiniteLineCrossingFraction(
    previousPosition = previousPosition,
    currentPosition = currentPosition,
    lineCenter = geometry.center,
    travelDirection = geometry.travelDirection
)

internal fun distanceToGateDetectionLineM(
    point: GeoPoint,
    geometry: CourseGateDetectionGeometry
): Double {
    val local = projectToCourseLocal(point, geometry.center)
    return abs(
        local.x * geometry.travelDirection.x +
            local.y * geometry.travelDirection.y
    )
}
