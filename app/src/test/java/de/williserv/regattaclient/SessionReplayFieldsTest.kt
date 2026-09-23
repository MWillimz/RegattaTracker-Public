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
    fun discoveryIncludesInternalImuAndStoredMeasurements() {
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

        assertTrue(fields.any { it.id == "imu.accel_x" })
        assertTrue(fields.any { it.id == "imu.gyro_z" })

        val roll = fields.single { it.id == "measurement:regattalink.fast.roll_deg" }
        assertEquals("Fast Roll", roll.label)
        assertEquals("deg", roll.unit)
        assertEquals("regattalink", roll.measurementGroup)

        val stw = fields.single { it.id == "measurement:nmea.stw" }
        assertEquals("Stw", stw.label)
        assertEquals("kn", stw.unit)
    }

    @Test
    fun valuesUseSelectedSampleAndKeepMissingMeasurementsUnknown() {
        val sample = sample(
            accelX = 1.25f,
            gyroZ = -0.125f,
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

        assertEquals(
            "1.25 m/s²",
            replayExtraFieldValue(sample, fields.single { it.id == "imu.accel_x" })
        )
        assertEquals(
            "-0.125 rad/s",
            replayExtraFieldValue(sample, fields.single { it.id == "imu.gyro_z" })
        )

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
    fun malformedMeasurementsDoNotBreakReplayFieldDiscovery() {
        val fields = discoverReplayExtraFields(
            listOf(sample(measurementsJson = "{not-json"))
        )

        assertEquals(6, fields.size)
        assertTrue(fields.all { it.source == ReplayExtraFieldSource.INTERNAL_IMU })
    }

    private fun sample(
        accelX: Float = 0.1f,
        gyroZ: Float = 0.01f,
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
            accelX = accelX,
            accelY = 0.2f,
            accelZ = 9.8f,
            gyroX = 0.02f,
            gyroY = 0.03f,
            gyroZ = gyroZ,
            measurementsJson = measurementsJson
        )
    }
}
