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
    fun discoveryIncludesStoredMeasurements() {
        val sample = sample(
            measurementsJson = """
                {
                  "regattalink.fast.roll_deg": {
                    "value": 12.34,
                    "unit": "deg",
                    "group": "regattalink"
                  },
                  "nmea.stw": {
                    "value": 5.6,
                    "unit": "kn",
                    "group": "nmea"
                  }
                }
            """.trimIndent()
        )

        val fields = discoverReplayExtraFields(listOf(sample))

        assertEquals(2, fields.size)
        assertTrue(fields.all { it.source == ReplayExtraFieldSource.MEASUREMENT })

        val roll = fields.single { it.id == "measurement:regattalink.fast.roll_deg" }
        assertEquals("Fast Roll", roll.label)
        assertEquals("deg", roll.unit)
        assertEquals("regattalink", roll.measurementGroup)

        val stw = fields.single { it.id == "measurement:nmea.stw" }
        assertEquals("STW", stw.label)
        assertEquals("kn", stw.unit)
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
        assertTrue(replayExtraFieldValue(sample, roll)?.contains("-14.50") == true)

        assertNull(
            replayExtraFieldValue(
                sample.copy(measurementsJson = null),
                roll
            )
        )
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
