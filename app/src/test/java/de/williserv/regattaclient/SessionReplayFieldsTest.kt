package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionReplayFieldsTest {

    @Test
    fun discoveryIncludesOnlyReplayRelevantMeasurements() {
        val sample = sample(
            measurementsJson = """
                {
                  "regattalink.fast.roll_deg": {
                    "value": 12.34,
                    "unit": "deg",
                    "group": "regattalink"
                  },
                  "regattalink.summary.heel_filtered_deg": {
                    "value": -8.5,
                    "unit": "deg",
                    "group": "regattalink"
                  },
                  "nmea.stw": {
                    "value": 5.6,
                    "unit": "kn",
                    "group": "nmea"
                  },
                  "regattalink.fast.sequence": {
                    "value": 123,
                    "group": "regattalink"
                  },
                  "regattalink.fast.timestamp_ms": {
                    "value": 456789,
                    "unit": "ms",
                    "group": "regattalink"
                  },
                  "regattalink.fast.confidence_pct": {
                    "value": 92,
                    "unit": "%",
                    "group": "regattalink"
                  },
                  "regattalink.calibration.learner_state": {
                    "value": 1,
                    "group": "regattalink"
                  },
                  "regattalink.calibration.calibration_revision": {
                    "value": 7,
                    "group": "regattalink"
                  },
                  "internal.debug_counter": {
                    "value": 99,
                    "group": "debug"
                  }
                }
            """.trimIndent()
        )

        val fields = discoverReplayExtraFields(listOf(sample))

        assertEquals(3, fields.size)
        assertTrue(fields.all { it.source == ReplayExtraFieldSource.MEASUREMENT })
        assertTrue(fields.all { it.recommended })

        val heel = fields.single {
            it.id == "measurement:regattalink.summary.heel_filtered_deg"
        }
        assertEquals("Heel", heel.label)
        assertEquals("deg", heel.unit)

        val roll = fields.single { it.id == "measurement:regattalink.fast.roll_deg" }
        assertEquals("Roll", roll.label)
        assertEquals("deg", roll.unit)
        assertEquals("regattalink", roll.measurementGroup)

        val stw = fields.single { it.id == "measurement:nmea.stw" }
        assertEquals("STW", stw.label)
        assertEquals("kn", stw.unit)

        assertTrue(fields.none { it.id.contains("sequence") })
        assertTrue(fields.none { it.id.contains("timestamp") })
        assertTrue(fields.none { it.id.contains("confidence") })
        assertTrue(fields.none { it.id.contains("calibration") })
        assertTrue(fields.none { it.id.contains("debug") })
    }

    @Test
    fun valuesUseSelectedSampleAndKeepMissingMeasurementsUnknown() {
        val sample = sample(
            measurementsJson = """
                {
                  "regattalink.fast.roll_deg": {
                    "value": -14.5,
                    "unit": "deg",
                    "group": "regattalink"
                  }
                }
            """.trimIndent()
        )
        val fields = discoverReplayExtraFields(listOf(sample))

        val roll = fields.single { it.id == "measurement:regattalink.fast.roll_deg" }
        assertEquals("Roll", roll.label)
        assertTrue(replayExtraFieldValue(sample, roll)?.contains("-14.50") == true)

        assertNull(
            replayExtraFieldValue(
                sample.copy(measurementsJson = null),
                roll
            )
        )
    }

    @Test
    fun allSensorsIncludesUnknownUsefulMeasurementButBlacklistsDiagnostics() {
        val sample = sample(
            measurementsJson = """
                {
                  "nmea.foil_load": {
                    "value": 123.4,
                    "unit": "N",
                    "group": "nmea"
                  },
                  "regattalink.calibration.overall_confidence_pct": {
                    "value": 80,
                    "unit": "%",
                    "group": "regattalink"
                  },
                  "regattalink.fast.sequence": {
                    "value": 42,
                    "group": "regattalink"
                  },
                  "unknown.future.diagnostic": {
                    "value": 1,
                    "group": "diagnostic"
                  }
                }
            """.trimIndent()
        )

        val fields = discoverReplayExtraFields(listOf(sample))

        assertEquals(1, fields.size)
        val custom = fields.single()
        assertEquals("measurement:nmea.foil_load", custom.id)
        assertEquals("Foil Load", custom.label)
        assertEquals("N", custom.unit)
        assertEquals("nmea", custom.measurementGroup)
        assertTrue(!custom.recommended)
    }

    @Test
    fun sessionsWithoutMeasurementsOfferNoExtraFields() {
        assertTrue(discoverReplayExtraFields(listOf(sample(measurementsJson = null))).isEmpty())
    }

    @Test
    fun malformedMeasurementsDoNotBreakReplayFieldDiscovery() {
        assertTrue(
            discoverReplayExtraFields(
                listOf(sample(measurementsJson = "{not-json"))
            ).isEmpty()
        )
    }

    private fun sample(
        measurementsJson: String? = null
    ): SessionTrackingSample {
        return SessionTrackingSample(
            localId = 1L,
            timestamp = "2026-09-23T20:00:00",
            utcOffsetMinutes = 0,
            lat = 54.0,
            lon = 10.0,
            accuracy = 5f,
            cog = 90f,
            sog = 4f,
            measurementsJson = measurementsJson
        )
    }
}
