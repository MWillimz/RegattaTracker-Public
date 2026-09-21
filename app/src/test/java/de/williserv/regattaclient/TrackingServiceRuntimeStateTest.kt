package de.williserv.regattaclient

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingServiceRuntimeStateTest {

    @After
    fun tearDown() {
        TrackingServiceRuntimeState.markStopped()
    }

    @Test
    fun `stopped runtime does not expose stale persisted race state`() {
        val effective = effectiveTrackingRuntimeState(
            persistedInRace = true,
            persistedManual = false,
            runtimeStatus = TrackingServiceRuntimeStatus.STOPPED
        )

        assertFalse(effective.inRace)
        assertFalse(effective.manualTracking)
    }

    @Test
    fun `stopped runtime does not expose stale persisted manual state`() {
        val effective = effectiveTrackingRuntimeState(
            persistedInRace = false,
            persistedManual = true,
            runtimeStatus = TrackingServiceRuntimeStatus.STOPPED
        )

        assertFalse(effective.inRace)
        assertFalse(effective.manualTracking)
    }

    @Test
    fun `starting and active runtime keep persisted tracking mode`() {
        val starting = effectiveTrackingRuntimeState(
            persistedInRace = true,
            persistedManual = false,
            runtimeStatus = TrackingServiceRuntimeStatus.STARTING
        )
        val active = effectiveTrackingRuntimeState(
            persistedInRace = false,
            persistedManual = true,
            runtimeStatus = TrackingServiceRuntimeStatus.ACTIVE
        )

        assertTrue(starting.inRace)
        assertFalse(starting.manualTracking)
        assertFalse(active.inRace)
        assertTrue(active.manualTracking)
    }

    @Test
    fun `runtime state transitions are process local`() {
        TrackingServiceRuntimeState.markStopped()
        assertEquals(
            TrackingServiceRuntimeStatus.STOPPED,
            TrackingServiceRuntimeState.currentStatus()
        )

        TrackingServiceRuntimeState.markStarting()
        assertEquals(
            TrackingServiceRuntimeStatus.STARTING,
            TrackingServiceRuntimeState.currentStatus()
        )
        assertFalse(TrackingServiceRuntimeState.isActive())

        TrackingServiceRuntimeState.markActive()
        assertEquals(
            TrackingServiceRuntimeStatus.ACTIVE,
            TrackingServiceRuntimeState.currentStatus()
        )
        assertTrue(TrackingServiceRuntimeState.isActive())
    }
}
