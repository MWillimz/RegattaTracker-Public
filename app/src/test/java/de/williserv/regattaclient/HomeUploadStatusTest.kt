package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeUploadStatusTest {

    @Test
    fun pendingBacklogOutsideRace_isShownExplicitly() {
        assertEquals("37 pending", shortUploadStatus(37L, false, "finished", true, true, true))
    }

    @Test
    fun smallPendingBacklogOutsideRace_isNotCollapsedToOk() {
        assertEquals("3 pending", shortUploadStatus(3L, false, "finished", true, true, true))
    }

    @Test
    fun noRaceSetup_isOffWithoutParsingDisplayText() {
        assertEquals("off", shortUploadStatus(0L, false, "", false, false, false))
    }

    @Test
    fun loadedSetupWithoutLegalAcceptance_isBlocked() {
        assertEquals("blocked", shortUploadStatus(0L, false, "planned", false, false, true))
    }

    @Test
    fun rawPlannedStatus_isWaiting() {
        assertEquals("waiting", shortUploadStatus(0L, false, "planned", true, true, true))
    }

    @Test
    fun rawStartedStatus_isReady() {
        assertEquals("ready", shortUploadStatus(0L, false, "started", true, true, true))
    }

    @Test
    fun inRaceBacklog_usesRawPendingCount() {
        assertEquals("42", shortUploadStatus(42L, true, "racing", true, true, true))
        assertEquals("OK", shortUploadStatus(10L, true, "racing", true, true, true))
    }
}
