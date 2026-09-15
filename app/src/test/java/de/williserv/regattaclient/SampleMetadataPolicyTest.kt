package de.williserv.regattaclient

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleMetadataPolicyTest {

    @Test
    fun `battery percentage is normalized safely`() {
        assertEquals(73, BatteryTelemetry.normalizePercent(73, 100))
        assertEquals(50, BatteryTelemetry.normalizePercent(1, 2))
        assertNull(BatteryTelemetry.normalizePercent(-1, 100))
        assertNull(BatteryTelemetry.normalizePercent(50, 0))
    }

    @Test
    fun `charging state follows Android battery status`() {
        assertEquals(true, BatteryTelemetry.chargingFromStatus(BatteryManager.BATTERY_STATUS_CHARGING))
        assertEquals(true, BatteryTelemetry.chargingFromStatus(BatteryManager.BATTERY_STATUS_FULL))
        assertEquals(false, BatteryTelemetry.chargingFromStatus(BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertEquals(false, BatteryTelemetry.chargingFromStatus(BatteryManager.BATTERY_STATUS_NOT_CHARGING))
        assertNull(BatteryTelemetry.chargingFromStatus(BatteryManager.BATTERY_STATUS_UNKNOWN))
    }

    @Test
    fun `battery read is due on first sample and after sixty seconds`() {
        assertTrue(SampleMetadataPolicy.shouldReadBattery(null, 1_000L))
        assertFalse(SampleMetadataPolicy.shouldReadBattery(1_000L, 60_999L))
        assertTrue(SampleMetadataPolicy.shouldReadBattery(1_000L, 61_000L))
    }

    @Test
    fun `tracking profile is emitted on start change and heartbeat`() {
        assertTrue(
            SampleMetadataPolicy.shouldEmitTrackingProfile(
                lastEmittedProfile = null,
                lastEmittedAtMs = null,
                currentProfile = "normal",
                nowMs = 1_000L
            )
        )
        assertFalse(
            SampleMetadataPolicy.shouldEmitTrackingProfile(
                lastEmittedProfile = "normal",
                lastEmittedAtMs = 1_000L,
                currentProfile = "normal",
                nowMs = 60_999L
            )
        )
        assertTrue(
            SampleMetadataPolicy.shouldEmitTrackingProfile(
                lastEmittedProfile = "normal",
                lastEmittedAtMs = 1_000L,
                currentProfile = "battery_saver",
                nowMs = 2_000L
            )
        )
        assertTrue(
            SampleMetadataPolicy.shouldEmitTrackingProfile(
                lastEmittedProfile = "normal",
                lastEmittedAtMs = 1_000L,
                currentProfile = "normal",
                nowMs = 61_000L
            )
        )
    }
}
