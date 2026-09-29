package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStatisticsTest {

    @Test
    fun timeWeightedAverage_respectsVariableSamplingIntervals() {
        val session = session(startedAt = 0L, endedAt = 30_000L)
        val samples = listOf(
            sample(id = 1L, second = 0, sog = 2f),
            sample(id = 2L, second = 10, sog = 4f),
            sample(id = 3L, second = 30, sog = 10f)
        )

        val stats = calculateSessionStatistics(session, samples)

        assertEquals(3L, stats.sampleCount)
        assertEquals(30_000L, stats.durationMs)
        assertEquals(10.0, stats.maxSogMps!!, 1e-9)
        assertEquals(17.0 / 3.0, stats.averageSogMps!!, 1e-9)
    }

    @Test
    fun invalidGpsPoint_breaksDistanceChain() {
        val session = session(startedAt = 0L, endedAt = 10_000L)
        val a = sample(id = 1L, second = 0, lat = 54.0, lon = 10.0)
        val b = sample(id = 2L, second = 1, lat = 54.0001, lon = 10.0)
        val invalid = sample(
            id = 3L,
            second = 2,
            lat = 54.0002,
            lon = 10.0,
            accuracy = 100f
        )
        val c = sample(id = 4L, second = 3, lat = 54.0010, lon = 10.0)
        val d = sample(id = 5L, second = 4, lat = 54.0011, lon = 10.0)

        val stats = calculateSessionStatistics(
            session = session,
            samples = listOf(a, b, invalid, c, d)
        )

        val expected =
            StartLineMath.distanceBetweenMeters(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon)) +
                StartLineMath.distanceBetweenMeters(GeoPoint(c.lat, c.lon), GeoPoint(d.lat, d.lon))
        assertEquals(expected, stats.distanceM, 0.01)
    }

    @Test
    fun longRecordingGap_breaksDistanceAndAverageSogContinuity() {
        val session = session(startedAt = 0L, endedAt = 600_000L)
        val first = sample(
            id = 1L,
            second = 0,
            lat = 54.0,
            lon = 10.0,
            sog = 2f,
            timestamp = "2026-09-22T10:00:00"
        )
        val second = sample(
            id = 2L,
            second = 0,
            lat = 54.01,
            lon = 10.0,
            sog = 8f,
            timestamp = "2026-09-22T10:05:00"
        )

        val stats = calculateSessionStatistics(session, listOf(first, second))

        assertEquals(0.0, stats.distanceM, 0.0)
        assertNull(stats.averageSogMps)
        assertEquals(8.0, stats.maxSogMps!!, 0.0)
    }

    @Test
    fun maximumRegularGap_isStillContinuous() {
        val session = session(startedAt = 0L, endedAt = SESSION_CONTINUITY_MAX_GAP_MS)
        val first = sample(
            id = 1L,
            second = 0,
            lat = 54.0,
            lon = 10.0,
            sog = 2f,
            timestamp = "2026-09-22T10:00:00"
        )
        val second = sample(
            id = 2L,
            second = 0,
            lat = 54.0001,
            lon = 10.0,
            sog = 4f,
            timestamp = "2026-09-22T10:02:00"
        )

        val stats = calculateSessionStatistics(session, listOf(first, second))

        assertTrue(stats.distanceM > 0.0)
        assertEquals(3.0, stats.averageSogMps!!, 1e-9)
    }

    @Test
    fun invalidSogAndNonMonotonicTime_areIgnored() {
        val session = session(startedAt = 1_000L, endedAt = 5_000L)
        val samples = listOf(
            sample(id = 1L, second = 0, sog = 3f),
            sample(id = 2L, second = 10, sog = Float.NaN),
            sample(id = 3L, second = 5, sog = 6f),
            sample(id = 4L, second = 20, sog = -1f)
        )

        val stats = calculateSessionStatistics(session, samples)

        assertEquals(6.0, stats.maxSogMps!!, 1e-9)
        assertNull(stats.averageSogMps)
    }

    @Test
    fun emptySession_hasZeroDistanceAndUnknownSpeeds() {
        val session = session(startedAt = 1_000L, endedAt = 61_000L)

        val stats = calculateSessionStatistics(session, emptyList())

        assertEquals(0L, stats.sampleCount)
        assertEquals(60_000L, stats.durationMs)
        assertEquals(0.0, stats.distanceM, 0.0)
        assertNull(stats.averageSogMps)
        assertNull(stats.maxSogMps)
    }

    @Test
    fun runningSession_usesProvidedNowAndClampsNegativeDuration() {
        val running = session(startedAt = 10_000L, endedAt = null)

        assertEquals(
            5_000L,
            calculateSessionStatistics(running, emptyList(), nowMillis = 15_000L).durationMs
        )
        assertEquals(
            0L,
            calculateSessionStatistics(running, emptyList(), nowMillis = 5_000L).durationMs
        )
    }

    private fun session(startedAt: Long, endedAt: Long?): TrackingSession =
        TrackingSession(
            id = 1L,
            startedAt = startedAt,
            endedAt = endedAt,
            mode = "manual",
            accessContextId = null,
            displayName = "Session"
        )

    private fun sample(
        id: Long,
        second: Int,
        lat: Double = 54.0,
        lon: Double = 10.0,
        accuracy: Float = 5f,
        sog: Float = 1f,
        timestamp: String = "2026-09-22T10:00:%02d".format(second),
        utcOffsetMinutes: Int? = 120
    ): SessionTrackingSample =
        SessionTrackingSample(
            localId = id,
            timestamp = timestamp,
            utcOffsetMinutes = utcOffsetMinutes,
            lat = lat,
            lon = lon,
            accuracy = accuracy,
            cog = 90f,
            sog = sog
        )
}
