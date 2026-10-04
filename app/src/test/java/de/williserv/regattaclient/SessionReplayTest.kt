package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun filteredReplaySelection_keepsSourceIdentityOrNearestSample() {
        val sourceIndices = listOf(10, 20, 30)

        assertEquals(1, replaySelectedFilteredIndex(sourceIndices, 20))
        assertEquals(1, replaySelectedFilteredIndex(sourceIndices, 24))
        assertEquals(2, replaySelectedFilteredIndex(sourceIndices, 29))
        assertEquals(0, replaySelectedFilteredIndex(emptyList(), 20))
    }

    @Test
    fun filteredReplaySourceIndices_doNotBridgeRemovedSamples() {
        assertEquals(true, replaySourceIndicesAreContiguous(listOf(4, 5, 6), 1))
        assertEquals(false, replaySourceIndicesAreContiguous(listOf(4, 6), 1))
    }

    @Test
    fun activeFilterCount_countsTimeRangesNumericAndStateFilters() {
        assertEquals(
            0,
            replayActiveFilterCount(
                timeFilterActive = false,
                rangeFilterCount = 0,
                sampleFilterCount = 0
            )
        )
        assertEquals(
            4,
            replayActiveFilterCount(
                timeFilterActive = true,
                rangeFilterCount = 1,
                sampleFilterCount = 2
            )
        )
    }

    @Test
    fun replayViewport_clampsZoomAndPanToVisibleBounds() {
        val zoomed = updateReplayViewport(
            viewport = ReplayViewport(),
            zoomChange = 2f,
            panX = 0f,
            panY = 0f,
            centroidX = 100f,
            centroidY = 50f,
            widthPx = 200f,
            heightPx = 100f
        )
        assertEquals(2f, zoomed.zoom, 0.0001f)
        assertEquals(0f, zoomed.panX, 0.0001f)
        assertEquals(0f, zoomed.panY, 0.0001f)

        val panned = updateReplayViewport(
            viewport = zoomed,
            zoomChange = 1f,
            panX = 500f,
            panY = -500f,
            centroidX = 100f,
            centroidY = 50f,
            widthPx = 200f,
            heightPx = 100f
        )
        assertEquals(100f, panned.panX, 0.0001f)
        assertEquals(-50f, panned.panY, 0.0001f)

        val reset = updateReplayViewport(
            viewport = panned,
            zoomChange = 0.1f,
            panX = 0f,
            panY = 0f,
            centroidX = 100f,
            centroidY = 50f,
            widthPx = 200f,
            heightPx = 100f
        )
        assertEquals(1f, reset.zoom, 0.0001f)
        assertEquals(0f, reset.panX, 0.0001f)
        assertEquals(0f, reset.panY, 0.0001f)
    }

    @Test
    fun speedFraction_isRelativeToSessionMaximumAndClamped() {
        assertEquals(0f, replaySpeedFraction(0.0, 10.0), 0.0001f)
        assertEquals(0.5f, replaySpeedFraction(5.0, 10.0), 0.0001f)
        assertEquals(1f, replaySpeedFraction(20.0, 10.0), 0.0001f)
        assertEquals(0f, replaySpeedFraction(Double.NaN, 10.0), 0.0001f)
    }

    @Test
    fun replayTrackColorData_usesMetricDisplayScaleAndSessionRange() {
        val metric = AnalysisMetric(
            id = "gps.sog",
            label = "SOG",
            unit = "kn",
            source = AnalysisMetricSource.GPS_SOG,
            displayScale = MPS_TO_KNOTS
        )
        val prepared = listOf(
            PreparedAnalysisSample(null, 90.0, 1.0, emptyMap()),
            PreparedAnalysisSample(null, 90.0, 2.0, emptyMap()),
            PreparedAnalysisSample(null, 90.0, 3.0, emptyMap())
        )

        val data = prepareReplayTrackColorData(prepared, metric)

        assertEquals(1.0 * MPS_TO_KNOTS, data.minValue!!, 0.000001)
        assertEquals(3.0 * MPS_TO_KNOTS, data.maxValue!!, 0.000001)
        assertEquals(0f, data.fractionAt(0)!!, 0.0001f)
        assertEquals(0.5f, data.fractionAt(1)!!, 0.0001f)
        assertEquals(1f, data.fractionAt(2)!!, 0.0001f)
    }

    @Test
    fun replayTrackColorData_supportsAbsoluteValuesAndCustomRange() {
        val metric = AnalysisMetric(
            id = "measurement:test.heel",
            label = "Heel",
            unit = "deg",
            source = AnalysisMetricSource.MEASUREMENT,
            measurementKey = "test.heel"
        )
        val prepared = listOf(
            PreparedAnalysisSample(
                null,
                90.0,
                2.0,
                mapOf("test.heel" to -20.0)
            ),
            PreparedAnalysisSample(
                null,
                90.0,
                2.0,
                mapOf("test.heel" to 5.0)
            ),
            PreparedAnalysisSample(
                null,
                90.0,
                2.0,
                mapOf("test.heel" to 20.0)
            )
        )

        val data = prepareReplayTrackColorData(
            samples = prepared,
            metric = metric,
            useAbsoluteValue = true
        )

        assertTrue(data.hasNegativeValues)
        assertEquals(5.0, data.minValue!!, 0.0001)
        assertEquals(20.0, data.maxValue!!, 0.0001)
        assertEquals(data.fractionAt(0), data.fractionAt(2))
        assertEquals(
            0f,
            data.fractionAt(
                index = 1,
                minValue = 10.0,
                maxValue = 15.0
            )!!,
            0.0001f
        )
        assertEquals(
            1f,
            data.fractionAt(
                index = 0,
                minValue = 10.0,
                maxValue = 15.0
            )!!,
            0.0001f
        )
    }

    @Test
    fun replayTrackColorData_keepsMissingMeasurementValuesMissing() {
        val metric = AnalysisMetric(
            id = "measurement:test.load",
            label = "Load",
            unit = "N",
            source = AnalysisMetricSource.MEASUREMENT,
            measurementKey = "test.load"
        )
        val prepared = listOf(
            PreparedAnalysisSample(null, 90.0, 2.0, mapOf("test.load" to 100.0)),
            PreparedAnalysisSample(null, 90.0, 2.0, emptyMap()),
            PreparedAnalysisSample(null, 90.0, 2.0, mapOf("test.load" to 300.0))
        )

        val data = prepareReplayTrackColorData(prepared, metric)

        assertEquals(100.0, data.minValue!!, 0.000001)
        assertEquals(300.0, data.maxValue!!, 0.000001)
        assertNull(data.values[1])
        assertNull(data.fractionAt(1))
    }

    @Test
    fun replayTrackColorData_constantMetricUsesMiddleOfScale() {
        val metric = AnalysisMetric(
            id = "measurement:test.constant",
            label = "Constant",
            unit = null,
            source = AnalysisMetricSource.MEASUREMENT,
            measurementKey = "test.constant"
        )
        val prepared = listOf(
            PreparedAnalysisSample(null, 90.0, 2.0, mapOf("test.constant" to 42.0)),
            PreparedAnalysisSample(null, 90.0, 2.0, mapOf("test.constant" to 42.0))
        )

        val data = prepareReplayTrackColorData(prepared, metric)

        assertEquals(0.5f, data.fractionAt(0)!!, 0.0001f)
        assertEquals(0.5f, data.fractionAt(1)!!, 0.0001f)
    }

    @Test
    fun replayBoatBearing_usesRecordedCogWhenAvailable() {
        val samples = listOf(
            sample(id = 1, seconds = 0).copy(cog = 123f),
            sample(id = 2, seconds = 1).copy(cog = 123f)
        )

        assertEquals(123f, replayBoatBearingDegrees(samples, 0), 0.0001f)
    }

    @Test
    fun replayBoatBearing_preservesValidZeroDegreeCog() {
        val samples = listOf(
            sample(id = 1, seconds = 0).copy(
                lat = 51.0,
                lon = 12.0,
                cog = 0f,
                cogValid = true
            ),
            sample(id = 2, seconds = 1).copy(
                lat = 51.0,
                lon = 12.001,
                cog = 0f,
                cogValid = true
            )
        )

        assertEquals(0f, replayBoatBearingDegrees(samples, 0), 0.0001f)
        assertEquals(0f, replayBoatBearingDegrees(samples, 1), 0.0001f)
    }

    @Test
    fun replayBoatBearing_fallsBackToTrackForExplicitlyInvalidBearing() {
        val samples = listOf(
            sample(id = 1, seconds = 0).copy(
                lat = 51.0,
                lon = 12.0,
                cog = 0f,
                cogValid = false
            ),
            sample(id = 2, seconds = 1).copy(
                lat = 51.0,
                lon = 12.001,
                cog = 0f,
                cogValid = false
            )
        )

        assertEquals(90f, replayBoatBearingDegrees(samples, 0), 0.5f)
        assertEquals(90f, replayBoatBearingDegrees(samples, 1), 0.5f)
    }

    @Test
    fun replayBoatBearing_preservesLegacyZeroDegreeCogWithoutValidityBit() {
        val samples = listOf(
            sample(id = 1, seconds = 0).copy(
                lat = 51.0,
                lon = 12.0,
                cog = 0f,
                cogValid = null
            ),
            sample(id = 2, seconds = 1).copy(
                lat = 51.0,
                lon = 12.001,
                cog = 0f,
                cogValid = null
            )
        )

        assertEquals(0f, replayBoatBearingDegrees(samples, 0), 0.0001f)
        assertEquals(0f, replayBoatBearingDegrees(samples, 1), 0.0001f)
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
