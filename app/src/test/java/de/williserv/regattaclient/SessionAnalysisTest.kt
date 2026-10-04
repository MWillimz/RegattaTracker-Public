package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionAnalysisTest {

    @Test
    fun gpsOnlyDefaultsToCogAndSog() {
        val samples = listOf(sample(cog = 45f, sog = 4f))
        val prepared = prepareAnalysisSamples(samples)
        val capabilities = discoverSessionAnalysisCapabilities(samples, prepared)

        assertEquals("gps.cog", capabilities.defaultAngleId)
        assertEquals("gps.sog", capabilities.defaultRadiusId)
        assertTrue(capabilities.angleMetrics.any { it.id == "gps.cog" })
        assertTrue(capabilities.radiusMetrics.any { it.id == "gps.sog" })
    }

    @Test
    fun timeRangeFilter_keepsOnlySelectedSessionWindow() {
        val samples = listOf(
            sample(timestamp = "2026-09-24T12:00:00"),
            sample(timestamp = "2026-09-24T12:00:10"),
            sample(timestamp = "2026-09-24T12:00:20")
        )
        val prepared = prepareAnalysisSamples(samples)
        val filter = analysisTimeFilterFromFraction(prepared, 0.25f..0.75f)

        val filtered = applyAnalysisTimeFilter(prepared, filter)

        assertEquals(listOf(1), filtered.map { it.sourceIndex })
    }

    @Test
    fun analysisRangeFilters_areReusableForPreparedSamples() {
        val prepared = prepareAnalysisSamples(
            listOf(
                sample(sog = 1f),
                sample(sog = 3f),
                sample(sog = 5f)
            )
        )
        val metric = AnalysisMetric(
            id = "gps.sog",
            label = "SOG",
            unit = "kn",
            source = AnalysisMetricSource.GPS_SOG,
            displayScale = MPS_TO_KNOTS
        )

        val filtered = applyAnalysisRangeFilters(
            samples = prepared,
            filters = listOf(
                AnalysisRangeFilter(
                    metricId = metric.id,
                    min = 2.0 * MPS_TO_KNOTS,
                    max = 4.0 * MPS_TO_KNOTS
                )
            ),
            metricsById = mapOf(metric.id to metric)
        )

        assertEquals(listOf(1), filtered.map { it.sourceIndex })
    }

    @Test
    fun trueWindAndStwArePreferredOverApparentWind() {
        val samples = listOf(
            sample(
                measurements = """
                    {
                      "nmea.stw_mps":{"value":3.0,"unit":"m/s","group":"nmea"},
                      "nmea.awa_deg":{"value":35.0,"unit":"deg","group":"nmea"},
                      "nmea.twa_deg":{"value":42.0,"unit":"deg","group":"nmea"}
                    }
                """.trimIndent()
            )
        )
        val prepared = prepareAnalysisSamples(samples)
        val capabilities = discoverSessionAnalysisCapabilities(samples, prepared)

        assertEquals("measurement:nmea.twa_deg", capabilities.defaultAngleId)
        assertEquals("measurement:nmea.stw_mps", capabilities.defaultRadiusId)
    }

    @Test
    fun apparentWindAndStwArePreferredWhenTrueWindIsMissing() {
        val samples = listOf(
            sample(
                measurements = """
                    {
                      "nmea.stw_mps":{"value":3.0,"unit":"m/s","group":"nmea"},
                      "nmea.awa_deg":{"value":35.0,"unit":"deg","group":"nmea"}
                    }
                """.trimIndent()
            )
        )
        val prepared = prepareAnalysisSamples(samples)
        val capabilities = discoverSessionAnalysisCapabilities(samples, prepared)

        assertEquals("measurement:nmea.awa_deg", capabilities.defaultAngleId)
        assertEquals("measurement:nmea.stw_mps", capabilities.defaultRadiusId)
    }

    @Test
    fun allSensorsIncludesUnknownSensorButNotDiagnostics() {
        val samples = listOf(
            sample(
                measurements = """
                    {
                      "nmea.foil_load":{"value":120.0,"unit":"N","group":"nmea"},
                      "regattalink.fast.sequence":{"value":9,"group":"regattalink"},
                      "unknown.diag":{"value":1,"group":"diagnostic"}
                    }
                """.trimIndent()
            )
        )
        val prepared = prepareAnalysisSamples(samples)
        val capabilities = discoverSessionAnalysisCapabilities(samples, prepared)

        assertTrue(capabilities.colorMetrics.any {
            it.id == "measurement:nmea.foil_load"
        })
        assertFalse(capabilities.metrics.any { it.id.contains("sequence") })
        assertFalse(capabilities.metrics.any { it.id.contains("diag") })
    }

    @Test
    fun missingRecordedCogFallsBackToAdjacentGpsTrack() {
        val samples = listOf(
            sample(
                timestamp = "2026-09-24T12:00:00",
                lat = 54.0,
                lon = 10.0,
                cog = 0f,
                cogValid = false
            ),
            sample(
                timestamp = "2026-09-24T12:00:01",
                lat = 54.0,
                lon = 10.001,
                cog = 90f,
                cogValid = true
            )
        )

        val prepared = prepareAnalysisSamples(samples)

        assertEquals(90.0, prepared[0].cogDeg, 0.1)
        assertTrue(hasGpsManeuverFilterData(prepared))
    }

    @Test
    fun missingRecordedCogWithoutAdjacentGpsTrackRemainsUnavailable() {
        val invalid = prepareAnalysisSamples(
            listOf(sample(cog = 0f, cogValid = false))
        ).single()
        val validNorth = prepareAnalysisSamples(
            listOf(sample(cog = 0f, cogValid = true))
        ).single()

        assertTrue(invalid.cogDeg.isNaN())
        assertEquals(0.0, validNorth.cogDeg, 0.001)
    }

    @Test
    fun heelAndWindAreRecommendedForColorWhileDepthIsAllSensors() {
        val samples = listOf(
            sample(
                measurements = """
                    {
                      "regattalink.motion.heel_deg":{"value":-8.0,"unit":"deg","group":"regattalink"},
                      "nmea.aws_mps":{"value":6.0,"unit":"m/s","group":"nmea"},
                      "nmea.depth_m":{"value":12.0,"unit":"m","group":"nmea"}
                    }
                """.trimIndent()
            )
        )
        val capabilities = discoverSessionAnalysisCapabilities(
            samples,
            prepareAnalysisSamples(samples)
        )

        val heel = capabilities.colorMetrics.single {
            it.id == "measurement:regattalink.motion.heel_deg"
        }
        val aws = capabilities.colorMetrics.single {
            it.id == "measurement:nmea.aws_mps"
        }
        val depth = capabilities.colorMetrics.single {
            it.id == "measurement:nmea.depth_m"
        }

        assertTrue(heel.isRecommendedFor(AnalysisMetricUse.COLOR))
        assertTrue(aws.isRecommendedFor(AnalysisMetricUse.COLOR))
        assertFalse(depth.isRecommendedFor(AnalysisMetricUse.COLOR))
    }

    @Test
    fun relativeAnglePreservesPortAndStarboardAcrossZero() {
        assertEquals(10.0, normalizeAnalysisAngle(10.0, AnalysisAngleKind.RELATIVE), 0.001)
        assertEquals(-10.0, normalizeAnalysisAngle(350.0, AnalysisAngleKind.RELATIVE), 0.001)

        val starboard = projectAnalysisAngleDegrees(45.0, AnalysisAngleKind.RELATIVE)
        val port = projectAnalysisAngleDegrees(315.0, AnalysisAngleKind.RELATIVE)

        assertTrue(starboard.first > 0.0)
        assertTrue(port.first < 0.0)
        assertEquals(starboard.second, port.second, 0.001)
    }

    @Test
    fun stwAndVmgAreConvertedToKnots() {
        val samples = listOf(
            sample(
                measurements = """
                    {
                      "nmea.stw_mps":{"value":4.0,"unit":"m/s","group":"nmea"},
                      "nmea.twa_deg":{"value":60.0,"unit":"deg","group":"nmea"}
                    }
                """.trimIndent()
            )
        )
        val prepared = prepareAnalysisSamples(samples)
        val capabilities = discoverSessionAnalysisCapabilities(samples, prepared)

        val stw = capabilities.radiusMetrics.single {
            it.id == "measurement:nmea.stw_mps"
        }
        val vmg = capabilities.radiusMetrics.single {
            it.id == "derived.vmg"
        }

        assertEquals(4.0 * MPS_TO_KNOTS, metricValue(stw, prepared.single())!!, 0.001)
        assertEquals(2.0 * MPS_TO_KNOTS, metricValue(vmg, prepared.single())!!, 0.001)
    }

    @Test
    fun filtersCombineWithAndAndMissingColorKeepsPoint() {
        val samples = listOf(
            sample(
                cog = 0f,
                sog = 3f,
                measurements = """
                    {
                      "nmea.aws_mps":{"value":5.0,"unit":"m/s","group":"nmea"},
                      "nmea.depth_m":{"value":10.0,"unit":"m","group":"nmea"}
                    }
                """.trimIndent()
            ),
            sample(
                cog = 90f,
                sog = 4f,
                measurements = """
                    {
                      "nmea.aws_mps":{"value":8.0,"unit":"m/s","group":"nmea"},
                      "nmea.depth_m":{"value":30.0,"unit":"m","group":"nmea"}
                    }
                """.trimIndent()
            )
        )
        val prepared = prepareAnalysisSamples(samples)
        val capabilities = discoverSessionAnalysisCapabilities(samples, prepared)
        val byId = capabilities.metrics.associateBy { it.id }
        val color = byId.getValue("measurement:nmea.depth_m")

        val dataset = buildSessionAnalysisDataset(
            samples = prepared,
            angleMetric = byId.getValue("gps.cog"),
            radiusMetric = byId.getValue("gps.sog"),
            colorMetric = color,
            filters = listOf(
                AnalysisRangeFilter(
                    "measurement:nmea.aws_mps",
                    5.0 * MPS_TO_KNOTS,
                    6.0 * MPS_TO_KNOTS
                ),
                AnalysisRangeFilter(
                    "measurement:nmea.depth_m",
                    5.0,
                    20.0
                )
            ),
            metricsById = byId
        )

        assertEquals(1, dataset.points.size)
        assertEquals(10.0, dataset.points.single().colorValue!!, 0.001)
    }

    @Test
    fun angleSelectorOnlyIncludesExplicitNavigationAndWindAngles() {
        val samples = listOf(
            sample(
                measurements = """
                    {
                      "nmea.heading_magnetic_deg":{"value":123.0,"unit":"deg","group":"nmea"},
                      "nmea.awa_deg":{"value":35.0,"unit":"deg","group":"nmea"},
                      "nmea.twa_deg":{"value":42.0,"unit":"deg","group":"nmea"},
                      "nmea.latitude_deg":{"value":54.0,"unit":"deg","group":"nmea"},
                      "nmea.longitude_deg":{"value":10.0,"unit":"deg","group":"nmea"},
                      "nmea.fancy_angle":{"value":1.57079632679,"unit":"rad","group":"nmea"},
                      "regattalink.motion.heel_deg":{"value":-8.0,"unit":"deg","group":"regattalink"}
                    }
                """.trimIndent()
            )
        )
        val capabilities = discoverSessionAnalysisCapabilities(
            samples,
            prepareAnalysisSamples(samples)
        )

        assertEquals(
            setOf(
                "gps.cog",
                "measurement:nmea.heading_magnetic_deg",
                "measurement:nmea.awa_deg",
                "measurement:nmea.twa_deg"
            ),
            capabilities.angleMetrics.mapTo(mutableSetOf()) { it.id }
        )
        assertTrue(capabilities.metrics.any { it.id == "measurement:nmea.latitude_deg" })
        assertTrue(capabilities.metrics.any { it.id == "measurement:nmea.longitude_deg" })
        assertTrue(capabilities.metrics.any { it.id == "measurement:nmea.fancy_angle" })
    }

    @Test
    fun shortestCourseDeltaHandlesNorthWraparound() {
        assertEquals(
            2.0,
            kotlin.math.abs(shortestAnalysisAngleDeltaDeg(1.0, 359.0)),
            0.001
        )
        assertEquals(
            2.0,
            kotlin.math.abs(shortestAnalysisAngleDeltaDeg(359.0, 1.0)),
            0.001
        )
    }

    @Test
    fun gpsManeuverFilterExcludesTransitionAndConfiguredRecovery() {
        val prepared = listOf(0.0, 0.0, 0.0, 30.0, 30.0, 30.0, 30.0, 30.0)
            .mapIndexed { index, cog ->
                PreparedAnalysisSample(
                    timestampMs = index * 1_000L,
                    cogDeg = cog,
                    sogMps = index.toDouble(),
                    measurements = emptyMap()
                )
            }

        val filtered = applyAnalysisSampleFilters(
            prepared,
            listOf(
                GpsManeuverAnalysisFilter(
                    smoothingSeconds = 0.1,
                    changeWindowSeconds = 2.0,
                    changeThresholdDeg = 20.0,
                    recoverySeconds = 2.0
                )
            )
        )

        assertEquals(listOf(0.0, 7.0), filtered.map { it.sogMps })
    }

    @Test
    fun canonicalPitchMeasurementIsPresentedAsPitch() {
        val source = listOf(
            sample(
                measurements = """
                    {
                      "regattalink.motion.pitch_deg":{"value":2.0,"unit":"deg","group":"regattalink"}
                    }
                """.trimIndent()
            )
        )

        val capabilities = discoverSessionAnalysisCapabilities(
            source,
            prepareAnalysisSamples(source)
        )

        assertEquals(
            "Pitch",
            capabilities.metrics.single {
                it.measurementKey ==
                    "regattalink.motion.pitch_deg"
            }.label
        )
    }

    @Test
    fun imuStabilityFilterExcludesRapidHeelChangeAndConfiguredRecovery() {
        val heel = listOf(10.0, 10.5, 16.0, 16.2, 16.4, 16.5, 16.6)
        val prepared = heel.mapIndexed { index, value ->
            PreparedAnalysisSample(
                timestampMs = index * 1_000L,
                cogDeg = 90.0,
                sogMps = index.toDouble(),
                measurements = mapOf(
                    "regattalink.motion.heel_deg" to value,
                    "regattalink.motion.pitch_deg" to 0.0
                )
            )
        }

        val filtered = applyAnalysisSampleFilters(
            prepared,
            listOf(
                ImuStabilityAnalysisFilter(
                    maxAttitudeRateDps = 2.0,
                    recoverySeconds = 2.0
                )
            )
        )

        assertEquals(
            listOf(0.0, 5.0, 6.0),
            filtered.map { it.sogMps }
        )
    }

    @Test
    fun imuStabilityFilterIsOnlyAvailableWithPersistedOneHertzHeel() {
        val withoutImu = listOf(sample(timestamp = "2026-09-24T12:00:00"))
        val withoutCapabilities = discoverSessionAnalysisCapabilities(
            withoutImu,
            prepareAnalysisSamples(withoutImu)
        )
        assertFalse(withoutCapabilities.imuStabilityFilterAvailable)

        val withImu = listOf(
            sample(
                timestamp = "2026-09-24T12:00:00",
                measurements = """
                    {
                      "regattalink.motion.heel_deg":{"value":4.0,"unit":"deg","group":"regattalink"}
                    }
                """.trimIndent()
            ),
            sample(
                timestamp = "2026-09-24T12:00:01",
                measurements = """
                    {
                      "regattalink.motion.heel_deg":{"value":4.4,"unit":"deg","group":"regattalink"}
                    }
                """.trimIndent()
            )
        )
        val withCapabilities = discoverSessionAnalysisCapabilities(
            withImu,
            prepareAnalysisSamples(withImu)
        )
        assertTrue(withCapabilities.imuStabilityFilterAvailable)
    }

    @Test
    fun activeSampleFiltersCombineByRejectingEitherFilter() {
        val prepared = (0..6).map { index ->
            PreparedAnalysisSample(
                timestampMs = index * 1_000L,
                cogDeg = if (index >= 3) 30.0 else 0.0,
                sogMps = index.toDouble(),
                measurements = mapOf(
                    "regattalink.motion.heel_deg" to
                        if (index == 6) 10.0 else 0.0,
                    "regattalink.motion.pitch_deg" to 0.0
                )
            )
        }

        val filtered = applyAnalysisSampleFilters(
            prepared,
            listOf(
                GpsManeuverAnalysisFilter(
                    smoothingSeconds = 0.1,
                    changeWindowSeconds = 2.0,
                    changeThresholdDeg = 20.0,
                    recoverySeconds = 0.0
                ),
                ImuStabilityAnalysisFilter(
                    maxAttitudeRateDps = 2.0,
                    recoverySeconds = 0.0
                )
            )
        )

        assertEquals(listOf(0.0), filtered.map { it.sogMps })
    }

    @Test
    fun accelerationFilterUsesMaximumGpsSpeedChangeFromPreviousFiveSeconds() {
        val samples = (0..11).map { second ->
            sample(
                sog = if (second < 6) 2f else 3f,
                timestamp = timestampAtSecond(second)
            )
        }
        val prepared = prepareAnalysisSamples(samples)
        val capabilities = discoverSessionAnalysisCapabilities(samples, prepared)
        val acceleration = capabilities.filterMetrics.single {
            it.id == "derived.acceleration_5s"
        }

        assertTrue(
            acceleration.isRecommendedFor(AnalysisMetricUse.FILTER)
        )
        assertFalse(capabilities.colorMetrics.any {
            it.id == acceleration.id
        })
        assertEquals(
            0.0,
            metricValue(acceleration, prepared[5])!!,
            0.001
        )
        assertEquals(
            1.0,
            metricValue(acceleration, prepared[6])!!,
            0.001
        )
        assertEquals(
            1.0,
            metricValue(acceleration, prepared[10])!!,
            0.001
        )
        assertEquals(
            0.0,
            metricValue(acceleration, prepared[11])!!,
            0.001
        )

        val byId = capabilities.metrics.associateBy { it.id }
        val filtered = buildSessionAnalysisDataset(
            samples = prepared,
            angleMetric = byId.getValue("gps.cog"),
            radiusMetric = byId.getValue("gps.sog"),
            colorMetric = null,
            filters = listOf(
                AnalysisRangeFilter(
                    metricId = acceleration.id,
                    min = 0.0,
                    max = 0.5
                )
            ),
            metricsById = byId
        )

        // The first five seconds have no complete lookback. The speed-change
        // event then stays excluded for the full configured five-second view.
        assertEquals(2, filtered.points.size)
    }

    @Test
    fun rawColorRangePreservesExtremesHiddenByAggregation() {
        val prepared = (0..10).map { index ->
            PreparedAnalysisSample(
                timestampMs = index * 1_000L,
                cogDeg = 90.0,
                sogMps = 4.0,
                measurements = mapOf(
                    "test.heel" to if (index % 2 == 0) -20.0 else 20.0
                ),
                sourceIndex = index
            )
        }
        val angle = AnalysisMetric(
            id = "gps.cog",
            label = "COG",
            unit = "deg",
            source = AnalysisMetricSource.GPS_COG,
            angleKind = AnalysisAngleKind.COMPASS
        )
        val radius = AnalysisMetric(
            id = "gps.sog",
            label = "SOG",
            unit = "m/s",
            source = AnalysisMetricSource.GPS_SOG
        )
        val color = AnalysisMetric(
            id = "measurement:test.heel",
            label = "Heel",
            unit = "deg",
            source = AnalysisMetricSource.MEASUREMENT,
            measurementKey = "test.heel"
        )
        val metrics = listOf(angle, radius, color).associateBy { it.id }

        val dataset = buildSessionAnalysisDataset(
            samples = prepared,
            angleMetric = angle,
            radiusMetric = radius,
            colorMetric = color,
            filters = emptyList(),
            metricsById = metrics,
            aggregationWindowMs = ANALYSIS_AGGREGATION_WINDOW_MS
        )
        val observed = sessionColorObservedRange(
            values = analysisEligibleColorValues(
                samples = prepared,
                angleMetric = angle,
                radiusMetric = radius,
                colorMetric = color
            ),
            useAbsoluteValue = false
        )

        assertEquals(0.0, dataset.colorMin!!, 0.0001)
        assertEquals(0.0, dataset.colorMax!!, 0.0001)
        assertEquals(-20.0, observed!!.start, 0.0001)
        assertEquals(20.0, observed.endInclusive, 0.0001)
    }

    @Test
    fun colorRangeExcludesSamplesThatCannotProducePlotPoints() {
        val angle = AnalysisMetric(
            id = "measurement:test.angle",
            label = "Angle",
            unit = "deg",
            source = AnalysisMetricSource.MEASUREMENT,
            measurementKey = "test.angle",
            angleKind = AnalysisAngleKind.RELATIVE
        )
        val radius = AnalysisMetric(
            id = "measurement:test.radius",
            label = "Radius",
            unit = "kn",
            source = AnalysisMetricSource.MEASUREMENT,
            measurementKey = "test.radius"
        )
        val color = AnalysisMetric(
            id = "measurement:test.color",
            label = "Color",
            unit = "deg",
            source = AnalysisMetricSource.MEASUREMENT,
            measurementKey = "test.color"
        )
        val prepared = listOf(
            PreparedAnalysisSample(
                timestampMs = 0L,
                cogDeg = 0.0,
                sogMps = 0.0,
                measurements = mapOf(
                    "test.angle" to 45.0,
                    "test.radius" to 2.0,
                    "test.color" to 5.0
                ),
                sourceIndex = 0
            ),
            PreparedAnalysisSample(
                timestampMs = 1_000L,
                cogDeg = 0.0,
                sogMps = 0.0,
                measurements = mapOf(
                    "test.radius" to 2.0,
                    "test.color" to -100.0
                ),
                sourceIndex = 1
            )
        )

        val values = analysisEligibleColorValues(
            samples = prepared,
            angleMetric = angle,
            radiusMetric = radius,
            colorMetric = color
        )
        val observed = sessionColorObservedRange(
            values = values,
            useAbsoluteValue = false
        )

        assertEquals(listOf(5.0), values)
        assertEquals(5.0, observed!!.start, 0.0001)
        assertEquals(5.0, observed.endInclusive, 0.0001)
        assertFalse(sessionColorHasNegativeValue(values))
    }

    @Test
    fun absoluteColoringTransformsValuesBeforeAggregation() {
        val prepared = (0..10).map { index ->
            PreparedAnalysisSample(
                timestampMs = index * 1_000L,
                cogDeg = 90.0,
                sogMps = 4.0,
                measurements = mapOf(
                    "test.heel" to if (index % 2 == 0) -20.0 else 20.0
                ),
                sourceIndex = index
            )
        }
        val angle = AnalysisMetric(
            id = "gps.cog",
            label = "COG",
            unit = "deg",
            source = AnalysisMetricSource.GPS_COG,
            angleKind = AnalysisAngleKind.COMPASS
        )
        val radius = AnalysisMetric(
            id = "gps.sog",
            label = "SOG",
            unit = "m/s",
            source = AnalysisMetricSource.GPS_SOG
        )
        val color = AnalysisMetric(
            id = "measurement:test.heel",
            label = "Heel",
            unit = "deg",
            source = AnalysisMetricSource.MEASUREMENT,
            measurementKey = "test.heel"
        )
        val metrics = listOf(angle, radius, color).associateBy { it.id }

        val dataset = buildSessionAnalysisDataset(
            samples = prepared,
            angleMetric = angle,
            radiusMetric = radius,
            colorMetric = color,
            filters = emptyList(),
            metricsById = metrics,
            colorUseAbsoluteValue = true,
            aggregationWindowMs = ANALYSIS_AGGREGATION_WINDOW_MS
        )

        assertEquals(1, dataset.points.size)
        assertEquals(20.0, dataset.points.single().colorValue!!, 0.0001)
        assertEquals(20.0, dataset.colorMin!!, 0.0001)
        assertEquals(20.0, dataset.colorMax!!, 0.0001)
    }

    @Test
    fun tenSecondAggregationUsesOnlyCompleteContinuousSegments() {
        val prepared = (0..20).map { index ->
            PreparedAnalysisSample(
                timestampMs = index * 1_000L,
                cogDeg = if (index % 2 == 0) 359.0 else 1.0,
                sogMps = index.toDouble(),
                measurements = emptyMap(),
                sourceIndex = index
            )
        }
        val angle = AnalysisMetric(
            id = "gps.cog",
            label = "COG",
            unit = "deg",
            source = AnalysisMetricSource.GPS_COG,
            angleKind = AnalysisAngleKind.COMPASS
        )
        val radius = AnalysisMetric(
            id = "gps.sog",
            label = "SOG",
            unit = "m/s",
            source = AnalysisMetricSource.GPS_SOG
        )
        val metrics = listOf(angle, radius).associateBy { it.id }

        val dataset = buildSessionAnalysisDataset(
            samples = prepared,
            angleMetric = angle,
            radiusMetric = radius,
            colorMetric = null,
            filters = emptyList(),
            metricsById = metrics,
            aggregationWindowMs = ANALYSIS_AGGREGATION_WINDOW_MS
        )

        assertEquals(2, dataset.points.size)
        assertEquals(0.0, dataset.points[0].angleDeg, 0.01)
        assertEquals(4.5, dataset.points[0].radius, 0.001)
        assertEquals(14.5, dataset.points[1].radius, 0.001)
    }

    @Test
    fun tenSecondAggregationDoesNotUseSparseBoundarySampleAsCoverage() {
        val timestamps = listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 35_000L)
        val prepared = timestamps.mapIndexed { index, timestamp ->
            PreparedAnalysisSample(
                timestampMs = timestamp,
                cogDeg = 90.0,
                sogMps = 4.0,
                measurements = emptyMap(),
                sourceIndex = index
            )
        }
        val angle = AnalysisMetric(
            id = "gps.cog",
            label = "COG",
            unit = "deg",
            source = AnalysisMetricSource.GPS_COG,
            angleKind = AnalysisAngleKind.COMPASS
        )
        val radius = AnalysisMetric(
            id = "gps.sog",
            label = "SOG",
            unit = "m/s",
            source = AnalysisMetricSource.GPS_SOG
        )

        val dataset = buildSessionAnalysisDataset(
            samples = prepared,
            angleMetric = angle,
            radiusMetric = radius,
            colorMetric = null,
            filters = emptyList(),
            metricsById = listOf(angle, radius).associateBy { it.id },
            aggregationWindowMs = ANALYSIS_AGGREGATION_WINDOW_MS
        )

        assertTrue(dataset.points.isEmpty())
    }

    @Test
    fun tenSecondAggregationDoesNotBridgeFilteredSamples() {
        val prepared = (0..20).map { index ->
            PreparedAnalysisSample(
                timestampMs = index * 1_000L,
                cogDeg = 90.0,
                sogMps = 4.0,
                measurements = mapOf(
                    "test.keep" to if (index == 5) 1.0 else 0.0
                ),
                sourceIndex = index
            )
        }
        val angle = AnalysisMetric(
            id = "gps.cog",
            label = "COG",
            unit = "deg",
            source = AnalysisMetricSource.GPS_COG,
            angleKind = AnalysisAngleKind.COMPASS
        )
        val radius = AnalysisMetric(
            id = "gps.sog",
            label = "SOG",
            unit = "m/s",
            source = AnalysisMetricSource.GPS_SOG
        )
        val gate = AnalysisMetric(
            id = "measurement:test.keep",
            label = "Gate",
            unit = null,
            source = AnalysisMetricSource.MEASUREMENT,
            measurementKey = "test.keep"
        )
        val metrics = listOf(angle, radius, gate).associateBy { it.id }

        val dataset = buildSessionAnalysisDataset(
            samples = prepared,
            angleMetric = angle,
            radiusMetric = radius,
            colorMetric = null,
            filters = listOf(
                AnalysisRangeFilter(
                    metricId = gate.id,
                    min = 0.0,
                    max = 0.0
                )
            ),
            metricsById = metrics,
            aggregationWindowMs = ANALYSIS_AGGREGATION_WINDOW_MS
        )

        assertEquals(1, dataset.points.size)
    }

    @Test
    fun shorterThanTenSecondsDoesNotProduceAggregate() {
        val prepared = (0..9).map { index ->
            PreparedAnalysisSample(
                timestampMs = index * 1_000L,
                cogDeg = 90.0,
                sogMps = 4.0,
                measurements = emptyMap(),
                sourceIndex = index
            )
        }
        val angle = AnalysisMetric(
            id = "gps.cog",
            label = "COG",
            unit = "deg",
            source = AnalysisMetricSource.GPS_COG,
            angleKind = AnalysisAngleKind.COMPASS
        )
        val radius = AnalysisMetric(
            id = "gps.sog",
            label = "SOG",
            unit = "m/s",
            source = AnalysisMetricSource.GPS_SOG
        )

        val dataset = buildSessionAnalysisDataset(
            samples = prepared,
            angleMetric = angle,
            radiusMetric = radius,
            colorMetric = null,
            filters = emptyList(),
            metricsById = listOf(angle, radius).associateBy { it.id },
            aggregationWindowMs = ANALYSIS_AGGREGATION_WINDOW_MS
        )

        assertTrue(dataset.points.isEmpty())
    }

    @Test
    fun genericNegativeRadiusSamplesAreRejected() {
        val samples = listOf(
            sample(
                measurements = """
                    {
                      "nmea.foil_load":{"value":-10.0,"unit":"N","group":"nmea"}
                    }
                """.trimIndent()
            )
        )
        val prepared = prepareAnalysisSamples(samples)
        val capabilities = discoverSessionAnalysisCapabilities(samples, prepared)
        val byId = capabilities.metrics.associateBy { it.id }

        val dataset = buildSessionAnalysisDataset(
            samples = prepared,
            angleMetric = byId.getValue("gps.cog"),
            radiusMetric = byId.getValue("measurement:nmea.foil_load"),
            colorMetric = null,
            filters = emptyList(),
            metricsById = byId
        )

        assertTrue(dataset.points.isEmpty())
    }

    private fun timestampAtSecond(second: Int): String =
        "2026-09-24T12:00:" + second.toString().padStart(2, '0')

    private fun sample(
        cog: Float = 90f,
        sog: Float = 4f,
        measurements: String? = null,
        timestamp: String = "2026-09-24T12:00:00",
        cogValid: Boolean? = null,
        lat: Double = 54.0,
        lon: Double = 10.0
    ) = SessionTrackingSample(
        localId = 1L,
        timestamp = timestamp,
        utcOffsetMinutes = 0,
        lat = lat,
        lon = lon,
        accuracy = 3f,
        cog = cog,
        sog = sog,
        cogValid = cogValid,
        measurementsJson = measurements
    )
}
