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
        blockingFlow: Boolean = false,
        focused: Boolean = true
    ) = shouldOfferPlayStoreUpdate(
        isPlayUpdateAvailable = updateAvailable,
        screen = screen,
        raceTrackingActive = inRace,
        manualTrackingActive = manualTracking,
        blockingFlowActive = blockingFlow,
        hasWindowFocus = focused
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
    fun availableResultWaitsForWindowFocusWithoutAnotherPlayRequest() {
        val processGate = PlayStoreUpdateCheckGate()
        assertTrue(processGate.tryStart())
        // Play responds while the Android permission dialog obscures HOME.
        processGate.recordAvailable()
        assertTrue(processGate.hasPendingOffer)
        assertFalse(eligible(updateAvailable = processGate.hasPendingOffer, focused = false))

        // Permission dialog closes; the pending process result is still usable.
        assertTrue(eligible(updateAvailable = processGate.hasPendingOffer, focused = true))
        assertFalse(processGate.tryStart())
    }

    @Test
    fun activityRecreationDoesNotLoseInFlightOrCompletedPlayResult() {
        val processGate = PlayStoreUpdateCheckGate()
        assertTrue(processGate.tryStart())
        // Replacement Activity cannot start a second query before the first answers.
        assertFalse(processGate.tryStart())
        processGate.recordAvailable()
        assertTrue(processGate.hasPendingOffer)
        // Another recreation after the result keeps exactly the same offer.
        assertFalse(processGate.tryStart())
        assertTrue(eligible(updateAvailable = processGate.hasPendingOffer))
    }

    @Test
    fun higherPriorityFlowsDeferOfferInsteadOfDiscardingIt() {
        val processGate = PlayStoreUpdateCheckGate()
        assertTrue(processGate.tryStart())
        processGate.recordAvailable()
        assertFalse(eligible(updateAvailable = processGate.hasPendingOffer, inRace = true))
        assertFalse(eligible(updateAvailable = processGate.hasPendingOffer, manualTracking = true))
        assertFalse(eligible(updateAvailable = processGate.hasPendingOffer, blockingFlow = true))
        assertFalse(eligible(updateAvailable = processGate.hasPendingOffer, screen = Screen.RACE_LEGAL))
        assertTrue(processGate.hasPendingOffer)
        assertTrue(eligible(updateAvailable = processGate.hasPendingOffer))
    }

    @Test
    fun laterOrUpdateMarksOfferHandledForProcess() {
        val processGate = PlayStoreUpdateCheckGate()
        assertTrue(processGate.tryStart())
        processGate.recordAvailable()
        assertTrue(processGate.hasPendingOffer)
        processGate.markHandled()
        assertFalse(processGate.hasPendingOffer)
        assertFalse(eligible(updateAvailable = processGate.hasPendingOffer))
        // A late/duplicate callback cannot resurface a handled prompt.
        processGate.recordAvailable()
        assertFalse(processGate.hasPendingOffer)
        assertFalse(processGate.tryStart())
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
