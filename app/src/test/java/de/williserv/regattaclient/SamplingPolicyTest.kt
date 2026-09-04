package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplingPolicyTest {

    @Test
    fun `normal and battery saver use ticket intervals`() {
        assertEquals(1_000L, SamplingPolicy.intervalForBand(TrackingProfile.NORMAL, SamplingDistanceBand.NEAR))
        assertEquals(2_000L, SamplingPolicy.intervalForBand(TrackingProfile.NORMAL, SamplingDistanceBand.MEDIUM))
        assertEquals(5_000L, SamplingPolicy.intervalForBand(TrackingProfile.NORMAL, SamplingDistanceBand.FAR))
        assertEquals(10_000L, SamplingPolicy.intervalForBand(TrackingProfile.NORMAL, SamplingDistanceBand.VERY_FAR))

        assertEquals(2_000L, SamplingPolicy.intervalForBand(TrackingProfile.BATTERY_SAVER, SamplingDistanceBand.NEAR))
        assertEquals(10_000L, SamplingPolicy.intervalForBand(TrackingProfile.BATTERY_SAVER, SamplingDistanceBand.MEDIUM))
        assertEquals(30_000L, SamplingPolicy.intervalForBand(TrackingProfile.BATTERY_SAVER, SamplingDistanceBand.FAR))
        assertEquals(60_000L, SamplingPolicy.intervalForBand(TrackingProfile.BATTERY_SAVER, SamplingDistanceBand.VERY_FAR))
    }

    @Test
    fun `nearest later mark controls distance independent of course order`() {
        val position = GeoPoint(53.0000, 10.0000)
        val farFirstMark = CourseMark(
            order = 1,
            name = "First",
            point = GeoPoint(53.0200, 10.0000),
            radiusM = 100.0
        )
        val closeLaterMark = CourseMark(
            order = 5,
            name = "Later",
            point = GeoPoint(53.0010, 10.0000),
            radiusM = 100.0
        )

        val decision = SamplingPolicy.decide(
            position = position,
            startLine = null,
            finishLine = null,
            courseMarks = listOf(farFirstMark, closeLaterMark),
            trackingProfile = TrackingProfile.NORMAL,
            sailNumber = "GER 1234",
            previousBand = null
        )

        assertEquals(1_000L, decision.intervalMs)
        assertEquals(SamplingDistanceBand.NEAR, decision.band)
        assertTrue((decision.nearestDistanceM ?: Double.MAX_VALUE) < 250.0)
    }

    @Test
    fun `line distance uses segment endpoints not infinite line`() {
        val line = StartLine(
            ref = GeoPoint(53.0000, 10.0000),
            mark = GeoPoint(53.0000, 10.0010)
        )
        val pointBeyondEndpoint = GeoPoint(53.0000, 10.0100)

        val distance = SamplingPolicy.nearestRelevantCourseElementDistanceM(
            position = pointBeyondEndpoint,
            startLine = line,
            finishLine = null,
            courseMarks = emptyList()
        )

        assertTrue((distance ?: 0.0) > 500.0)
    }

    @Test
    fun `outward hysteresis prevents threshold flapping`() {
        assertEquals(
            SamplingDistanceBand.NEAR,
            SamplingPolicy.resolveBandWithHysteresis(260.0, SamplingDistanceBand.NEAR)
        )
        assertEquals(
            SamplingDistanceBand.MEDIUM,
            SamplingPolicy.resolveBandWithHysteresis(280.0, SamplingDistanceBand.NEAR)
        )
        assertEquals(
            SamplingDistanceBand.NEAR,
            SamplingPolicy.resolveBandWithHysteresis(240.0, SamplingDistanceBand.MEDIUM)
        )
    }

    @Test
    fun `mark senders use fixed profile intervals`() {
        val normal = SamplingPolicy.decide(
            position = GeoPoint(53.0, 10.0),
            startLine = null,
            finishLine = null,
            courseMarks = emptyList(),
            trackingProfile = TrackingProfile.NORMAL,
            sailNumber = "MARK:1",
            previousBand = SamplingDistanceBand.NEAR
        )
        val saver = SamplingPolicy.decide(
            position = GeoPoint(53.0, 10.0),
            startLine = null,
            finishLine = null,
            courseMarks = emptyList(),
            trackingProfile = TrackingProfile.BATTERY_SAVER,
            sailNumber = "MARK:1",
            previousBand = SamplingDistanceBand.NEAR
        )

        assertEquals(30_000L, normal.intervalMs)
        assertEquals(60_000L, saver.intervalMs)
        assertNull(normal.band)
        assertNull(saver.band)
    }

    @Test
    fun `missing geometry uses fastest selected profile band`() {
        val decision = SamplingPolicy.decide(
            position = GeoPoint(53.0, 10.0),
            startLine = null,
            finishLine = null,
            courseMarks = emptyList(),
            trackingProfile = TrackingProfile.NORMAL,
            sailNumber = "GER 1234",
            previousBand = SamplingDistanceBand.VERY_FAR
        )

        assertEquals(SamplingDistanceBand.NEAR, decision.band)
        assertEquals(1_000L, decision.intervalMs)
        assertNull(decision.nearestDistanceM)
    }
}
