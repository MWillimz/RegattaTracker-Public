package de.williserv.regattaclient

import kotlin.math.PI
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseGateDetectionGeometryTest {

    private val origin = GeoPoint(lat = 54.0, lon = 10.0)

    @Test
    fun positiveDirectionCrossingCountsAndWrongDirectionDoesNot() {
        val geometry = requireNotNull(
            buildCourseGateDetectionGeometry(
                previousAnchor = localPoint(0.0, -200.0),
                gate = gate(direction = GateDirection.POSITIVE)
            )
        )

        val fraction = gateDetectionCrossingFraction(
            previousPosition = localPoint(500.0, -20.0),
            currentPosition = localPoint(500.0, 20.0),
            geometry = geometry
        )

        assertEquals(0.5, requireNotNull(fraction), 1e-6)
        assertNull(
            gateDetectionCrossingFraction(
                previousPosition = localPoint(500.0, 20.0),
                currentPosition = localPoint(500.0, -20.0),
                geometry = geometry
            )
        )
    }

    @Test
    fun negativeDirectionInvertsAllowedCrossing() {
        val geometry = requireNotNull(
            buildCourseGateDetectionGeometry(
                previousAnchor = localPoint(0.0, 200.0),
                gate = gate(direction = GateDirection.NEGATIVE)
            )
        )

        assertEquals(
            0.5,
            requireNotNull(
                gateDetectionCrossingFraction(
                    previousPosition = localPoint(0.0, 20.0),
                    currentPosition = localPoint(0.0, -20.0),
                    geometry = geometry
                )
            ),
            1e-6
        )
    }

    @Test
    fun offsetMovesDetectionLineTowardPreviousPosition() {
        val geometry = requireNotNull(
            buildCourseGateDetectionGeometry(
                previousAnchor = localPoint(0.0, -200.0),
                gate = gate(offsetM = 30.0)
            )
        )

        assertEquals(30.0, geometry.offsetM, 1e-9)
        assertEquals(
            30.0,
            StartLineMath.distanceBetweenMeters(origin, geometry.center),
            0.05
        )
        assertTrue(geometry.center.lat < origin.lat)
        assertEquals(
            0.0,
            distanceToGateDetectionLineM(localPoint(250.0, -30.0), geometry),
            0.05
        )
    }

    @Test
    fun positiveOffsetFailsClosedWhenPreviousSideIsUndefined() {
        assertNull(
            buildCourseGateDetectionGeometry(
                previousAnchor = localPoint(200.0, 0.0),
                gate = gate(offsetM = 30.0)
            )
        )
    }

    @Test
    fun zeroOffsetDoesNotRequirePreviousAnchor() {
        val geometry = requireNotNull(
            buildCourseGateDetectionGeometry(
                previousAnchor = null,
                gate = gate(offsetM = 0.0)
            )
        )

        assertEquals(origin.lat, geometry.center.lat, 1e-9)
        assertEquals(origin.lon, geometry.center.lon, 1e-9)
    }

    @Test
    fun degenerateGateFailsClosed() {
        val point = localPoint(0.0, 0.0)
        val gate = CoursePosition(
            order = 1,
            name = "Gate",
            kind = CoursePositionKind.GATE,
            omitWhenShortened = false,
            gateRef = point,
            gateMark = point,
            gateDirection = GateDirection.POSITIVE,
            gateOffsetM = 0.0
        )

        assertNull(buildCourseGateDetectionGeometry(null, gate))
    }

    @Test
    fun previousGateMidpointCanAnchorFollowingGateOffset() {
        val previousGate = CoursePosition(
            order = 1,
            name = "First",
            kind = CoursePositionKind.GATE,
            omitWhenShortened = false,
            gateRef = localPoint(-50.0, -150.0),
            gateMark = localPoint(50.0, -150.0),
            gateDirection = GateDirection.POSITIVE,
            gateOffsetM = 0.0
        )
        val secondGate = CoursePosition(
            order = 2,
            name = "Second",
            kind = CoursePositionKind.GATE,
            omitWhenShortened = false,
            gateRef = localPoint(-50.0, 0.0),
            gateMark = localPoint(50.0, 0.0),
            gateDirection = GateDirection.POSITIVE,
            gateOffsetM = 25.0
        )

        val geometry = requireNotNull(
            buildCourseGateDetectionGeometry(
                previousAnchor = previousGate.referencePoint(),
                gate = secondGate
            )
        )

        assertTrue(geometry.center.lat < origin.lat)
        assertEquals(25.0, StartLineMath.distanceBetweenMeters(origin, geometry.center), 0.05)
    }

    private fun gate(
        direction: GateDirection = GateDirection.POSITIVE,
        offsetM: Double = 0.0
    ): CoursePosition = CoursePosition(
        order = 1,
        name = "Gate",
        kind = CoursePositionKind.GATE,
        omitWhenShortened = false,
        gateRef = localPoint(-50.0, 0.0),
        gateMark = localPoint(50.0, 0.0),
        gateDirection = direction,
        gateOffsetM = offsetM
    )

    private fun localPoint(x: Double, y: Double): GeoPoint {
        val earthRadiusM = 6_371_000.0
        val lat = origin.lat + (y / earthRadiusM) * 180.0 / PI
        val lon = origin.lon +
            (x / (earthRadiusM * cos(origin.lat * PI / 180.0))) * 180.0 / PI

        return GeoPoint(lat = lat, lon = lon)
    }
}
