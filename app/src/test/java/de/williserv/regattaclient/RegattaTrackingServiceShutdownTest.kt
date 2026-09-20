package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegattaTrackingServiceShutdownTest {

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
