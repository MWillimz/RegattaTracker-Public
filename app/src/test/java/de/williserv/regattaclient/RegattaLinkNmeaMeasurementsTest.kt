package de.williserv.regattaclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegattaLinkNmeaMeasurementsTest {

    @Test
    fun serializesValidBoatStateWithSemanticKeys() {
        val state = RegattaLinkNmeaState(
            boatStateSupported = true,
            boatState = RegattaLinkBoatState(
                sequence = 7,
                timestampMs = 1234,
                validityBitmap = 0xffffffffL,
                headingDeg = 123.4,
                headingReference = 1,
                headingDeviationDeg = -1.2,
                headingVariationDeg = 2.3,
                rateOfTurnDps = -0.4,
                yawDeg = 4.5,
                pitchDeg = -2.0,
                rollDeg = 11.0,
                speedThroughWaterMps = 3.2,
                depthM = 8.7,
                depthOffsetM = -0.4,
                depthRangeM = 50.0,
                waterTemperatureC = 17.6,
                latitudeDeg = 54.1,
                longitudeDeg = 10.2,
                cogDeg = 87.0,
                cogReference = 0,
                sogMps = 3.4,
                altitudeM = 2.0,
                windSpeedMps = 6.2,
                windAngleDeg = 42.0,
                windReference = 2
            ),
            boatStateReceivedAtElapsedMs = 1_000L
        )

        val json = requireNotNull(
            buildRegattaLinkNmeaMeasurementsJson(state, nowElapsedMs = 2_000L)
        )
        val measurements = JSONObject(json)

        assertMeasurement(measurements, "nmea.heading_magnetic_deg", 123.4, "deg")
        assertMeasurement(measurements, "nmea.heading_deviation_deg", -1.2, "deg")
        assertMeasurement(measurements, "nmea.heading_variation_deg", 2.3, "deg")
        assertMeasurement(measurements, "nmea.rate_of_turn_dps", -0.4, "deg/s")
        assertMeasurement(measurements, "nmea.stw_mps", 3.2, "m/s")
        assertMeasurement(measurements, "nmea.depth_m", 8.7, "m")
        assertMeasurement(measurements, "nmea.water_temperature_c", 17.6, "C")
        assertMeasurement(measurements, "nmea.cog_true_deg", 87.0, "deg")
        assertMeasurement(measurements, "nmea.sog_mps", 3.4, "m/s")
        assertMeasurement(measurements, "nmea.aws_mps", 6.2, "m/s")
        assertMeasurement(measurements, "nmea.awa_deg", 42.0, "deg")

        assertFalse(measurements.has("nmea.sequence"))
        assertFalse(measurements.has("nmea.timestamp_ms"))
        assertFalse(measurements.has("nmea.validity_bitmap"))
    }

    @Test
    fun mapsTrueHeadingAndTrueWindReferences() {
        for (windReference in listOf(3, 4)) {
            val state = RegattaLinkNmeaState(
                boatStateSupported = true,
                boatState = RegattaLinkBoatState(
                    sequence = 1,
                    timestampMs = 1,
                    validityBitmap = 1,
                    headingDeg = 201.0,
                    headingReference = 0,
                    windSpeedMps = 8.0,
                    windAngleDeg = 135.0,
                    windReference = windReference
                ),
                boatStateReceivedAtElapsedMs = 100L
            )

            val measurements = JSONObject(
                requireNotNull(
                    buildRegattaLinkNmeaMeasurementsJson(state, nowElapsedMs = 200L)
                )
            )

            assertMeasurement(measurements, "nmea.heading_true_deg", 201.0, "deg")
            assertMeasurement(measurements, "nmea.tws_mps", 8.0, "m/s")
            assertMeasurement(measurements, "nmea.twa_deg", 135.0, "deg")
            assertFalse(measurements.has("nmea.aws_mps"))
            assertFalse(measurements.has("nmea.awa_deg"))
        }
    }

    @Test
    fun keepsNorthReferencedWindSemanticallyDistinct() {
        val trueNorth = stateWithWind(reference = 0)
        val magneticNorth = stateWithWind(reference = 1)

        val trueJson = JSONObject(
            requireNotNull(buildRegattaLinkNmeaMeasurementsJson(trueNorth, 200L))
        )
        assertMeasurement(trueJson, "nmea.wind_speed_true_ground_mps", 7.5, "m/s")
        assertMeasurement(trueJson, "nmea.wind_direction_true_deg", 280.0, "deg")
        assertFalse(trueJson.has("nmea.twa_deg"))

        val magneticJson = JSONObject(
            requireNotNull(buildRegattaLinkNmeaMeasurementsJson(magneticNorth, 200L))
        )
        assertMeasurement(magneticJson, "nmea.wind_speed_true_ground_mps", 7.5, "m/s")
        assertMeasurement(magneticJson, "nmea.wind_direction_magnetic_deg", 280.0, "deg")
        assertFalse(magneticJson.has("nmea.twa_deg"))
    }

    @Test
    fun unsupportedReferencesAreNotMislabelled() {
        val state = RegattaLinkNmeaState(
            boatStateSupported = true,
            boatState = RegattaLinkBoatState(
                sequence = 1,
                timestampMs = 1,
                validityBitmap = 1,
                headingDeg = 55.0,
                headingReference = 9,
                windSpeedMps = 4.0,
                windAngleDeg = 90.0,
                windReference = 9
            ),
            boatStateReceivedAtElapsedMs = 100L
        )

        assertNull(buildRegattaLinkNmeaMeasurementsJson(state, nowElapsedMs = 200L))
    }

    @Test
    fun staleOrOtaPausedBoatStateIsNotPersisted() {
        val freshState = RegattaLinkNmeaState(
            boatStateSupported = true,
            boatState = RegattaLinkBoatState(
                sequence = 1,
                timestampMs = 1,
                validityBitmap = 1,
                speedThroughWaterMps = 2.5
            ),
            boatStateReceivedAtElapsedMs = 1_000L
        )

        assertTrue(
            buildRegattaLinkNmeaMeasurementsJson(
                freshState,
                nowElapsedMs = 1_000L + REGATTALINK_NMEA_STALE_MS
            ) != null
        )
        assertNull(
            buildRegattaLinkNmeaMeasurementsJson(
                freshState,
                nowElapsedMs = 1_001L + REGATTALINK_NMEA_STALE_MS
            )
        )
        assertNull(
            buildRegattaLinkNmeaMeasurementsJson(
                freshState.copy(pausedForOta = true),
                nowElapsedMs = 2_000L
            )
        )
    }

    @Test
    fun snapshotStoreClearPreventsStaleReuseAfterDisconnect() {
        val state = RegattaLinkNmeaState(
            boatStateSupported = true,
            boatState = RegattaLinkBoatState(
                sequence = 1,
                timestampMs = 1,
                validityBitmap = 1,
                speedThroughWaterMps = 2.5
            ),
            boatStateReceivedAtElapsedMs = 1_000L
        )

        RegattaLinkNmeaSnapshotStore.update(state)
        assertTrue(RegattaLinkNmeaSnapshotStore.measurementsJson(2_000L) != null)

        RegattaLinkNmeaSnapshotStore.clear()

        assertNull(RegattaLinkNmeaSnapshotStore.measurementsJson(2_000L))
        assertNull(RegattaLinkNmeaSnapshotStore.current().boatState)
    }

    @Test
    fun mergesNmeaWithoutOverwritingRegattaLinkMeasurements() {
        val imu = """
            {
              "regattalink.summary.heel_filtered_deg": {
                "value": -8.5,
                "unit": "deg",
                "group": "regattalink"
              }
            }
        """.trimIndent()
        val nmea = """
            {
              "nmea.stw_mps": {
                "value": 3.2,
                "unit": "m/s",
                "group": "nmea"
              }
            }
        """.trimIndent()

        val merged = JSONObject(requireNotNull(mergeMeasurementsJson(imu, nmea)))

        assertTrue(merged.has("regattalink.summary.heel_filtered_deg"))
        assertTrue(merged.has("nmea.stw_mps"))
        assertEquals(2, merged.length())
    }

    @Test
    fun emptyMeasurementInputsRemainNull() {
        assertNull(mergeMeasurementsJson(null, "", "{}"))
        assertNull(
            buildRegattaLinkNmeaMeasurementsJson(
                RegattaLinkNmeaState(),
                nowElapsedMs = 100L
            )
        )
    }

    private fun stateWithWind(reference: Int): RegattaLinkNmeaState =
        RegattaLinkNmeaState(
            boatStateSupported = true,
            boatState = RegattaLinkBoatState(
                sequence = 1,
                timestampMs = 1,
                validityBitmap = 1,
                windSpeedMps = 7.5,
                windAngleDeg = 280.0,
                windReference = reference
            ),
            boatStateReceivedAtElapsedMs = 100L
        )

    private fun assertMeasurement(
        measurements: JSONObject,
        key: String,
        expectedValue: Double,
        expectedUnit: String
    ) {
        val measurement = measurements.getJSONObject(key)
        assertEquals(expectedValue, measurement.getDouble("value"), 0.0001)
        assertEquals(expectedUnit, measurement.getString("unit"))
        assertEquals("nmea", measurement.getString("group"))
    }
}
