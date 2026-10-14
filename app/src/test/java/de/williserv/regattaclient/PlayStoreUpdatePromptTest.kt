package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayStoreUpdatePromptTest {
    private fun eligible(
        updateAvailable: Boolean = true,
        screen: Screen = Screen.HOME,
        inRace: Boolean = false,
        manualTracking: Boolean = false,
        blockingFlow: Boolean = false
    ) = shouldOfferPlayStoreUpdate(
        isPlayUpdateAvailable = updateAvailable,
        screen = screen,
        raceTrackingActive = inRace,
        manualTrackingActive = manualTracking,
        blockingFlowActive = blockingFlow
    )

    @Test
    fun processGateChecksOnlyOnceEvenAcrossActivityRecreation() {
        val gate = PlayStoreUpdateCheckGate()
        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        // Only a fresh app process/application instance gets a fresh check.
        assertTrue(PlayStoreUpdateCheckGate().tryStart())
    }

    @Test
    fun availabilityMustComeFromPlay() {
        assertTrue(eligible())
        assertFalse(eligible(updateAvailable = false))
    }

    @Test
    fun noUpdatePromptsInRaceOrDuringManualTracking() {
        assertFalse(eligible(inRace = true))
        assertFalse(eligible(manualTracking = true))
        assertFalse(eligible(inRace = true, manualTracking = true))
    }

    @Test
    fun legalAndOtherScreensNeverReceiveTheOptionalPrompt() {
        assertFalse(eligible(screen = Screen.RACE_LEGAL))
        assertFalse(eligible(screen = Screen.RACE))
        assertFalse(eligible(screen = Screen.QR_SCANNER))
        assertFalse(eligible(screen = Screen.REGATTALINK))
    }

    @Test
    fun importantDialogsOrPendingActionsHavePriority() {
        assertFalse(eligible(blockingFlow = true))
    }
}
