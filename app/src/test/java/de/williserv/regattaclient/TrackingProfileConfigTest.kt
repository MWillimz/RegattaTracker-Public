package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingProfileConfigTest {

    @Test
    fun `known persisted values map to profiles`() {
        assertEquals(
            TrackingProfile.NORMAL,
            TrackingProfile.fromPersistedValue("normal")
        )
        assertEquals(
            TrackingProfile.BATTERY_SAVER,
            TrackingProfile.fromPersistedValue("battery_saver")
        )
    }

    @Test
    fun `missing or unknown persisted values fall back to normal`() {
        assertEquals(
            TrackingProfile.NORMAL,
            TrackingProfile.fromPersistedValue(null)
        )
        assertEquals(
            TrackingProfile.NORMAL,
            TrackingProfile.fromPersistedValue("future_profile")
        )
    }
}
