package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OwnShipProjectionTest {

    @Test
    fun metersPerSecondAreConvertedToKnots() {
        assertEquals(1.9438444924406, metersPerSecondToKnots(1.0)!!, 1e-12)
        assertEquals(0.0, metersPerSecondToKnots(0.0)!!, 0.0)
    }

    @Test
    fun invalidSpeedsAreRejected() {
        assertNull(metersPerSecondToKnots(-0.1))
        assertNull(metersPerSecondToKnots(Double.NaN))
        assertNull(metersPerSecondToKnots(Double.POSITIVE_INFINITY))
    }

    @Test
    fun bearingIsNormalizedToFullCircle() {
        assertEquals(0.0, normalizeBearingDegrees(360.0)!!, 0.0)
        assertEquals(270.0, normalizeBearingDegrees(-90.0)!!, 0.0)
        assertEquals(45.0, normalizeBearingDegrees(765.0)!!, 0.0)
    }

    @Test
    fun fiveMinuteDistanceUsesExactlyThreeHundredSeconds() {
        assertEquals(1800.0, ownShipCourseVectorDistanceMeters(6.0)!!, 0.0)
    }

    @Test
    fun zeroDistanceKeepsStartPoint() {
        val point = destinationPoint(
            lat = 53.5,
            lon = 10.0,
            bearingDegrees = 123.0,
            distanceMeters = 0.0
        )

        assertNotNull(point)
        assertEquals(53.5, point!!.lat, 0.0)
        assertEquals(10.0, point.lon, 1e-12)
    }

    @Test
    fun cardinalDestinationPointsMatchSphericalNavigation() {
        val north = destinationPoint(0.0, 0.0, 0.0, 1000.0)!!
        val east = destinationPoint(0.0, 0.0, 90.0, 1000.0)!!
        val south = destinationPoint(0.0, 0.0, 180.0, 1000.0)!!
        val west = destinationPoint(0.0, 0.0, 270.0, 1000.0)!!

        assertEquals(0.008993216, north.lat, 1e-6)
        assertEquals(0.0, north.lon, 1e-6)

        assertEquals(0.0, east.lat, 1e-6)
        assertEquals(0.008993216, east.lon, 1e-6)

        assertEquals(-0.008993216, south.lat, 1e-6)
        assertEquals(0.0, south.lon, 1e-6)

        assertEquals(0.0, west.lat, 1e-6)
        assertEquals(-0.008993216, west.lon, 1e-6)
    }

    @Test
    fun invalidDestinationInputsAreRejected() {
        assertNull(destinationPoint(Double.NaN, 0.0, 0.0, 100.0))
        assertNull(destinationPoint(91.0, 0.0, 0.0, 100.0))
        assertNull(destinationPoint(0.0, 0.0, Double.NaN, 100.0))
        assertNull(destinationPoint(0.0, 0.0, 0.0, -1.0))
    }

    @Test
    fun vectorRequiresPositiveSpeedAndBearing() {
        assertNull(ownShipCourseVectorEndpoint(53.5, 10.0, null, 90.0))
        assertNull(ownShipCourseVectorEndpoint(53.5, 10.0, 2.0, null))
        assertNull(ownShipCourseVectorEndpoint(53.5, 10.0, 0.0, 90.0))
        assertNotNull(ownShipCourseVectorEndpoint(53.5, 10.0, 2.0, 90.0))
    }
}
