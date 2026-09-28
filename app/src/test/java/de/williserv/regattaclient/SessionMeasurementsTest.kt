package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionMeasurementsTest {
    @Test
    fun explicitMeasurementLabelWinsOverGeneratedLabel() {
        val sample = SessionTrackingSample(
            localId = 1,
            timestamp = "2026-09-28T12:00:00",
            utcOffsetMinutes = 0,
            lat = 0.0,
            lon = 0.0,
            accuracy = 1f,
            cog = 0f,
            sog = 0f,
            measurementsJson = """
                {
                  "nmea.load.0123456789abcdef.0": {
                    "value": 70.5,
                    "unit": "kg",
                    "group": "load",
                    "label": "Vorstag"
                  }
                }
            """.trimIndent()
        )

        val measurement =
            discoverSessionNumericMeasurements(listOf(sample)).single()
        assertEquals(
            "nmea.load.0123456789abcdef.0",
            measurement.key
        )
        assertEquals("Vorstag", measurement.label)
        assertEquals("kg", measurement.unit)
        assertEquals("load", measurement.group)
    }

    @Test
    fun latestExplicitMeasurementLabelWinsWithinSession() {
        fun sample(localId: Long, label: String) = SessionTrackingSample(
            localId = localId,
            timestamp = "2026-09-28T12:00:0" + localId,
            utcOffsetMinutes = 0,
            lat = 0.0,
            lon = 0.0,
            accuracy = 1f,
            cog = 0f,
            sog = 0f,
            measurementsJson = """
                {
                  "nmea.load.0123456789abcdef.0": {
                    "value": 70.5,
                    "unit": "kg",
                    "group": "load",
                    "label": "$label"
                  }
                }
            """.trimIndent()
        )

        val measurement = discoverSessionNumericMeasurements(
            listOf(
                sample(1, "Load 0"),
                sample(2, "Vorstag")
            )
        ).single()

        assertEquals("Vorstag", measurement.label)
        assertEquals("nmea.load.0123456789abcdef.0", measurement.key)
    }
}
