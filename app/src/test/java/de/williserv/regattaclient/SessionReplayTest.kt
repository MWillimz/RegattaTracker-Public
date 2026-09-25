package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SessionReplayTest {

    @Test
    fun initialReplaySelection_opensAtLatestSample() {
        assertEquals(0, replayInitialSampleIndex(0))
        assertEquals(0, replayInitialSampleIndex(1))
        assertEquals(2, replayInitialSampleIndex(3))
    }

    @Test
    fun sampleFractions_useElapsedTimeInsteadOfSampleIndex() {
        val samples = listOf(
            sample(id = 1, seconds = 0),
            sample(id = 2, seconds = 10),
            sample(id = 3, seconds = 100)
        )

        val fractions = replaySampleFractions(samples)

        assertEquals(0f, fractions[0], 0.0001f)
        assertEquals(0.1f, fractions[1], 0.0001f)
        assertEquals(1f, fractions[2], 0.0001f)
    }

    @Test
    fun sampleSelection_followsTimelinePosition() {
        val samples = listOf(
            sample(id = 1, seconds = 0),
            sample(id = 2, seconds = 90),
            sample(id = 3, seconds = 100)
        )
        val fractions = replaySampleFractions(samples)

        assertEquals(1, replaySampleIndexForFraction(fractions, 0.8f))
        assertEquals(2, replaySampleIndexForFraction(fractions, 0.98f))
    }

    @Test
    fun playbackOffsets_followRecordedSessionTime() {
        val samples = listOf(
            sample(id = 1, seconds = 0),
            sample(id = 2, seconds = 10),
            sample(id = 3, seconds = 100)
        )

        assertEquals(
            listOf(0L, 10_000L, 100_000L),
            replayPlaybackOffsetsMs(samples)
        )
    }

    @Test
    fun playbackIndex_usesLatestSampleAtOrBeforePlaybackTime() {
        val offsets = listOf(0L, 10_000L, 90_000L, 100_000L)

        assertEquals(0, replayPlaybackIndexForOffset(offsets, 0L))
        assertEquals(0, replayPlaybackIndexForOffset(offsets, 9_999L))
        assertEquals(1, replayPlaybackIndexForOffset(offsets, 10_000L))
        assertEquals(1, replayPlaybackIndexForOffset(offsets, 50_000L))
        assertEquals(2, replayPlaybackIndexForOffset(offsets, 99_999L))
        assertEquals(3, replayPlaybackIndexForOffset(offsets, 100_000L))
        assertEquals(3, replayPlaybackIndexForOffset(offsets, 999_000L))
    }

    @Test
    fun speedFraction_isRelativeToSessionMaximumAndClamped() {
        assertEquals(0f, replaySpeedFraction(0.0, 10.0), 0.0001f)
        assertEquals(0.5f, replaySpeedFraction(5.0, 10.0), 0.0001f)
        assertEquals(1f, replaySpeedFraction(20.0, 10.0), 0.0001f)
        assertEquals(0f, replaySpeedFraction(Double.NaN, 10.0), 0.0001f)
    }

    @Test
    fun replayCanvasSizing_keepsLogicalSizeStableAcrossDensities() {
        val mdpi = replayCanvasSizing(canvasScalePx = 600f, density = 1f)
        val xxhdpi = replayCanvasSizing(canvasScalePx = 1800f, density = 3f)

        assertEquals(mdpi.sailedTrackWidthPx, xxhdpi.sailedTrackWidthPx / 3f, 0.0001f)
        assertEquals(mdpi.futureTrackWidthPx, xxhdpi.futureTrackWidthPx / 3f, 0.0001f)
        assertEquals(mdpi.boatRadiusPx, xxhdpi.boatRadiusPx / 3f, 0.0001f)
        assertEquals(mdpi.boatOutlineWidthPx, xxhdpi.boatOutlineWidthPx / 3f, 0.0001f)
        assertEquals(mdpi.startFinishWidthPx, xxhdpi.startFinishWidthPx / 3f, 0.0001f)
        assertEquals(mdpi.referenceRouteWidthPx, xxhdpi.referenceRouteWidthPx / 3f, 0.0001f)
        assertEquals(mdpi.markRadiusPx, xxhdpi.markRadiusPx / 3f, 0.0001f)
        assertEquals(mdpi.markStrokeWidthPx, xxhdpi.markStrokeWidthPx / 3f, 0.0001f)
    }

    @Test
    fun replayCanvasSizing_appliesDpDerivedMinimumsOnSmallCanvas() {
        val sizing = replayCanvasSizing(canvasScalePx = 300f, density = 3f)

        assertEquals(7.5f, sizing.sailedTrackWidthPx, 0.0001f)
        assertEquals(4.5f, sizing.futureTrackWidthPx, 0.0001f)
        assertEquals(21f, sizing.boatRadiusPx, 0.0001f)
        assertEquals(6f, sizing.boatOutlineWidthPx, 0.0001f)
        assertEquals(9f, sizing.startFinishWidthPx, 0.0001f)
        assertEquals(6f, sizing.referenceRouteWidthPx, 0.0001f)
        assertEquals(21f, sizing.markRadiusPx, 0.0001f)
        assertEquals(6f, sizing.markStrokeWidthPx, 0.0001f)
    }

    @Test
    fun replayCanvasSizing_appliesDpDerivedMaximumsOnLargeCanvas() {
        val sizing = replayCanvasSizing(canvasScalePx = 10_000f, density = 2f)

        assertEquals(12f, sizing.sailedTrackWidthPx, 0.0001f)
        assertEquals(8f, sizing.futureTrackWidthPx, 0.0001f)
        assertEquals(32f, sizing.boatRadiusPx, 0.0001f)
        assertEquals(10f, sizing.boatOutlineWidthPx, 0.0001f)
        assertEquals(16f, sizing.startFinishWidthPx, 0.0001f)
        assertEquals(10f, sizing.referenceRouteWidthPx, 0.0001f)
        assertEquals(36f, sizing.markRadiusPx, 0.0001f)
        assertEquals(10f, sizing.markStrokeWidthPx, 0.0001f)
    }

    private fun sample(id: Long, seconds: Long): SessionTrackingSample {
        val timestamp = LocalDateTime.of(2026, 9, 22, 10, 0)
            .plusSeconds(seconds)
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        return SessionTrackingSample(
            localId = id,
            timestamp = timestamp,
            utcOffsetMinutes = 0,
            lat = 51.0 + id * 0.0001,
            lon = 12.0 + id * 0.0001,
            accuracy = 5f,
            cog = 90f,
            sog = 4f
        )
    }
}
