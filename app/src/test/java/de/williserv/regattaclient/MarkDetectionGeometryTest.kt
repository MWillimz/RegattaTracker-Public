package de.williserv.regattaclient

import kotlin.math.PI
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkDetectionGeometryTest {

    private val mark = GeoPoint(lat = 54.0, lon = 10.0)

    @Test
    fun entryAndExitCentersUseRadiusAsLongitudinalOffset() {
        val geometry = requireNotNull(
            buildMarkDetectionGeometry(
                previousAnchor = localPoint(-200.0, 0.0),
                mark = mark,
                nextAnchor = localPoint(200.0, 0.0),
                radiusM = 50.0
            )
        )

        assertEquals(
            50.0,
            StartLineMath.distanceBetweenMeters(mark, geometry.entry.center),
            0.05
        )
        assertEquals(
            50.0,
            StartLineMath.distanceBetweenMeters(mark, geometry.exit.center),
            0.05
        )
        assertTrue(geometry.entry.center.lon < mark.lon)
        assertTrue(geometry.exit.center.lon > mark.lon)
        assertFalse(geometry.limitedToIntersection)
    }

    @Test
    fun markCompletesOnlyAfterDirectedEntryAndExitCrossings() {
        val geometry = straightGeometry()

        val afterEntry = updateMarkDetectionProgress(
            previousPosition = localPoint(-80.0, 0.0),
            currentPosition = localPoint(-20.0, 0.0),
            geometry = geometry,
            previousProgress = null
        )

        assertTrue(afterEntry.entryCrossed)
        assertFalse(afterEntry.exitCrossed)
        assertFalse(afterEntry.completed)

        val afterExit = updateMarkDetectionProgress(
            previousPosition = localPoint(-20.0, 0.0),
            currentPosition = localPoint(80.0, 0.0),
            geometry = geometry,
            previousProgress = afterEntry
        )

        assertTrue(afterExit.entryCrossed)
        assertTrue(afterExit.exitCrossed)
        assertTrue(afterExit.completed)
    }

    @Test
    fun crossingInWrongDirectionDoesNotCount() {
        val geometry = straightGeometry()

        val progress = updateMarkDetectionProgress(
            previousPosition = localPoint(-20.0, 0.0),
            currentPosition = localPoint(-80.0, 0.0),
            geometry = geometry,
            previousProgress = null
        )

        assertFalse(progress.entryCrossed)
        assertFalse(progress.completed)
    }

    @Test
    fun intersectionLimitedGateRejectsCrossingBehindActiveRay() {
        val geometry = requireNotNull(
            buildMarkDetectionGeometry(
                previousAnchor = localPoint(-200.0, 0.0),
                mark = mark,
                nextAnchor = localPoint(0.0, 200.0),
                radiusM = 50.0
            )
        )

        assertTrue(geometry.limitedToIntersection)
        assertNotNull(geometry.entry.intersection)

        assertFalse(
            markDetectionGateCrossed(
                previousPosition = localPoint(-80.0, 100.0),
                currentPosition = localPoint(-20.0, 100.0),
                gate = geometry.entry,
                travelDirection = geometry.incoming
            )
        )
        assertTrue(
            markDetectionGateCrossed(
                previousPosition = localPoint(-80.0, 0.0),
                currentPosition = localPoint(-20.0, 0.0),
                gate = geometry.entry,
                travelDirection = geometry.incoming
            )
        )
    }

    @Test
    fun nearReversalKeepsBothDetectionLinesInfiniteLikeServer() {
        val geometry = requireNotNull(
            buildMarkDetectionGeometry(
                previousAnchor = localPoint(-200.0, 0.0),
                mark = mark,
                nextAnchor = localPoint(-200.0, 0.0),
                radiusM = 50.0
            )
        )

        assertEquals(180.0, geometry.turnAngleDeg, 0.001)
        assertFalse(geometry.limitedToIntersection)
        assertNull(geometry.entry.intersection)
        assertNull(geometry.exit.intersection)
    }

    @Test
    fun startAndFinishMidpointsAreFallbackNeighbors() {
        val startLine = StartLine(
            ref = localPoint(-300.0, -20.0),
            mark = localPoint(-300.0, 20.0)
        )
        val finishLine = StartLine(
            ref = localPoint(300.0, -20.0),
            mark = localPoint(300.0, 20.0)
        )
        val nextMark = localPoint(150.0, 0.0)
        val previousMark = localPoint(-150.0, 0.0)

        val firstMarkAnchors = requireNotNull(
            resolveMarkDetectionAnchors(
                previousCoursePosition = null,
                nextCoursePosition = nextMark,
                startLine = startLine,
                finishLine = finishLine
            )
        )
        val lastMarkAnchors = requireNotNull(
            resolveMarkDetectionAnchors(
                previousCoursePosition = previousMark,
                nextCoursePosition = null,
                startLine = startLine,
                finishLine = finishLine
            )
        )

        assertEquals(lineMidpoint(startLine), firstMarkAnchors.previous)
        assertEquals(nextMark, firstMarkAnchors.next)
        assertEquals(previousMark, lastMarkAnchors.previous)
        assertEquals(lineMidpoint(finishLine), lastMarkAnchors.next)
    }

    @Test
    fun suppliedNeighborPointCanRepresentFutureGateMidpoint() {
        val gateMidpoint = localPoint(-120.0, 40.0)
        val nextMark = localPoint(150.0, 0.0)
        val startLine = StartLine(
            ref = localPoint(-300.0, -20.0),
            mark = localPoint(-300.0, 20.0)
        )

        val anchors = requireNotNull(
            resolveMarkDetectionAnchors(
                previousCoursePosition = gateMidpoint,
                nextCoursePosition = nextMark,
                startLine = startLine,
                finishLine = null
            )
        )

        assertEquals(gateMidpoint, anchors.previous)
        assertEquals(nextMark, anchors.next)
    }

    private fun straightGeometry(): MarkDetectionGeometry =
        requireNotNull(
            buildMarkDetectionGeometry(
                previousAnchor = localPoint(-200.0, 0.0),
                mark = mark,
                nextAnchor = localPoint(200.0, 0.0),
                radiusM = 50.0
            )
        )

    private fun localPoint(x: Double, y: Double): GeoPoint {
        val earthRadiusM = 6_371_000.0
        val lat = mark.lat + (y / earthRadiusM) * 180.0 / PI
        val lon = mark.lon +
            (x / (earthRadiusM * cos(mark.lat * PI / 180.0))) * 180.0 / PI

        return GeoPoint(lat = lat, lon = lon)
    }
}
