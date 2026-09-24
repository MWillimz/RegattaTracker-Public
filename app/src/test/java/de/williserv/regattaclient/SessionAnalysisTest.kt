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
    fun heelAndWindAreRecommendedForColorWhileDepthIsAllSensors() {
        val samples = listOf(
            sample(
                measurements = """
                    {
                      "regattalink.summary.heel_filtered_deg":{"value":-8.0,"unit":"deg","group":"regattalink"},
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
            it.id == "measurement:regattalink.summary.heel_filtered_deg"
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
                      "regattalink.summary.heel_filtered_deg":{"value":-8.0,"unit":"deg","group":"regattalink"}
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

    private fun sample(
        cog: Float = 90f,
        sog: Float = 4f,
        measurements: String? = null
    ) = SessionTrackingSample(
        localId = 1L,
        timestamp = "2026-09-24T12:00:00",
        utcOffsetMinutes = 0,
        lat = 54.0,
        lon = 10.0,
        accuracy = 3f,
        cog = cog,
        sog = sog,
        measurementsJson = measurements
    )
}
