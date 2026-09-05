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
                trackingProfile = "battery_saver"
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
                trackingProfile = null
            ),
            client = client
        )

        assertFalse(payload.has("battery_percent"))
        assertFalse(payload.has("battery_charging"))
        assertFalse(payload.has("tracking_profile"))
    }

    private fun sample(
        batteryPercent: Int?,
        batteryCharging: Boolean?,
        trackingProfile: String?
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
        accelX = 0f,
        accelY = 0f,
        accelZ = 0f,
        gyroX = 0f,
        gyroY = 0f,
        gyroZ = 0f,
        batteryPercent = batteryPercent,
        batteryCharging = batteryCharging,
        trackingProfile = trackingProfile
    )
}
