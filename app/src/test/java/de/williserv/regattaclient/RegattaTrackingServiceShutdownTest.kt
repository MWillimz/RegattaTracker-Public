package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegattaTrackingServiceShutdownTest {

    @Test
    fun stopHandoff_ignoresEveryCommandExceptFreshStart() {
        assertTrue(
            RegattaTrackingService.shouldIgnoreCommandDuringStopHandoff(
                stopHandoffInProgress = true,
                action = null
            )
        )
        assertTrue(
            RegattaTrackingService.shouldIgnoreCommandDuringStopHandoff(
                stopHandoffInProgress = true,
                action = RegattaTrackingService.ACTION_STOP
            )
        )
        assertTrue(
            RegattaTrackingService.shouldIgnoreCommandDuringStopHandoff(
                stopHandoffInProgress = true,
                action = RegattaTrackingService.ACTION_CONTINUE_AFTER_FINISH
            )
        )
        assertTrue(
            RegattaTrackingService.shouldIgnoreCommandDuringStopHandoff(
                stopHandoffInProgress = true,
                action = RegattaTrackingService.ACTION_SET_COURSE_PROGRESS
            )
        )
        assertFalse(
            RegattaTrackingService.shouldIgnoreCommandDuringStopHandoff(
                stopHandoffInProgress = true,
                action = RegattaTrackingService.ACTION_START
            )
        )
        assertFalse(
            RegattaTrackingService.shouldIgnoreCommandDuringStopHandoff(
                stopHandoffInProgress = false,
                action = RegattaTrackingService.ACTION_STOP
            )
        )
    }

    @Test
    fun shutdownCompletion_onlyStopsMatchingQuiescedServiceGeneration() {
        assertTrue(
            shouldFinishTrackingServiceStop(
                handoffGeneration = 7L,
                currentGeneration = 7L,
                serviceRunning = false
            )
        )

        assertFalse(
            shouldFinishTrackingServiceStop(
                handoffGeneration = 7L,
                currentGeneration = 8L,
                serviceRunning = false
            )
        )

        assertFalse(
            shouldFinishTrackingServiceStop(
                handoffGeneration = 7L,
                currentGeneration = 7L,
                serviceRunning = true
            )
        )
    }
}
