package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TelemetryMetadataPayloadTest {

    private val accessContext = AccessContext(
        id = 1L,
        serverUrl = "https://raceoffice.example.org",
        accessIdentifier = "Event A",
        accessSecret = "secret",
        createdAt = 1L,
        lastUsedAt = 1L
    )

    private val client = ClientBuildIdentity(
        versionCode = 20_871_700,
        buildId = "test-build"
    )

    @Test
    fun `persisted battery and tracking profile are uploaded unchanged`() {
        val payload = buildTelemetryUploadPayload(
            sample = sample(
                batteryPercent = 73,
                batteryCharging = false,
                trackingProfile = "battery_saver",
                utcOffsetMinutes = null
            ),
            client = client
        )

        assertEquals(73, payload.getInt("battery_percent"))
        assertEquals(false, payload.getBoolean("battery_charging"))
        assertEquals("battery_saver", payload.getString("tracking_profile"))
    }

    @Test
    fun `optional metadata is omitted when sample did not contain it`() {
        val payload = buildTelemetryUploadPayload(
            sample = sample(
                batteryPercent = null,
                batteryCharging = null,
                trackingProfile = null,
                utcOffsetMinutes = null
            ),
            client = client
        )

        assertFalse(payload.has("battery_percent"))
        assertFalse(payload.has("battery_charging"))
        assertFalse(payload.has("tracking_profile"))
        assertFalse(payload.has("utc_offset_minutes"))
    }

    @Test
    fun `phone imu fields are absent from telemetry payload`() {
        val payload = buildTelemetryUploadPayload(
            sample = sample(
                batteryPercent = null,
                batteryCharging = null,
                trackingProfile = null,
                utcOffsetMinutes = null
            ),
            client = client
        )

        listOf(
            "accel_x",
            "accel_y",
            "accel_z",
            "gyro_x",
            "gyro_y",
            "gyro_z"
        ).forEach { key ->
            assertFalse(payload.has(key))
        }
    }

    @Test
    fun `persisted measurements are uploaded from the original sample`() {
        val measurements =
            """{"regattalink.fast.roll_deg":{"value":12.3,"unit":"deg","group":"regattalink"}}"""
        val payload = buildTelemetryUploadPayload(
            sample = sample(
                batteryPercent = null,
                batteryCharging = null,
                trackingProfile = null,
                utcOffsetMinutes = null
            ).copy(measurementsJson = measurements),
            client = client
        )

        assertEquals(
            12.3,
            payload.getJSONObject("measurements")
                .getJSONObject("regattalink.fast.roll_deg")
                .getDouble("value"),
            0.001
        )
    }

    @Test
    fun `persisted utc offset is uploaded unchanged`() {
        val payload = buildTelemetryUploadPayload(
            sample = sample(
                batteryPercent = null,
                batteryCharging = null,
                trackingProfile = null,
                utcOffsetMinutes = 120
            ),
            client = client
        )

        assertEquals(120, payload.getInt("utc_offset_minutes"))
        assertEquals("2026-09-05T00:00:00", payload.getString("timestamp"))
    }

    @Test
    fun `negative utc offset is uploaded unchanged`() {
        val payload = buildTelemetryUploadPayload(
            sample = sample(
                batteryPercent = null,
                batteryCharging = null,
                trackingProfile = null,
                utcOffsetMinutes = -300
            ),
            client = client
        )

        assertEquals(-300, payload.getInt("utc_offset_minutes"))
    }

    private fun sample(
        batteryPercent: Int?,
        batteryCharging: Boolean?,
        trackingProfile: String?,
        utcOffsetMinutes: Int?
    ) = PendingTrackingSample(
        localId = 1L,
        accessContext = accessContext,
        sequenceId = 1L,
        timestamp = "2026-09-05T00:00:00",
        boatName = "Test Boat",
        captainName = "Test Captain",
        hullColor = "white",
        sailNumber = "GER 1",
        yardstick = 100.0,
        boatType = "Test",
        lat = 53.0,
        lon = 10.0,
        accuracy = 5f,
        cog = 0f,
        sog = 0f,
        batteryPercent = batteryPercent,
        batteryCharging = batteryCharging,
        trackingProfile = trackingProfile,
        utcOffsetMinutes = utcOffsetMinutes
    )
}
