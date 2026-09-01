package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeUploadStatusTest {

    @Test
    fun pendingBacklogOutsideRace_isShownExplicitly() {
        assertEquals("37 pending", shortUploadStatus("Upload: 37 pending", false, "finished", true, true, true))
    }

    @Test
    fun smallPendingBacklogOutsideRace_isNotCollapsedToOk() {
        assertEquals("3 pending", shortUploadStatus("Upload: 3 pending", false, "finished", true, true, true))
    }

    @Test
    fun noRaceSetup_isOffWithoutParsingDisplayText() {
        assertEquals("off", shortUploadStatus("Übertragung: alles gesendet", false, "", false, false, false))
    }

    @Test
    fun loadedSetupWithoutLegalAcceptance_isBlocked() {
        assertEquals("blocked", shortUploadStatus("Envoi : tout envoyé", false, "planned", false, false, true))
    }

    @Test
    fun rawPlannedStatus_isWaiting() {
        assertEquals("waiting", shortUploadStatus("Carga: todo enviado", false, "planned", true, true, true))
    }

    @Test
    fun rawStartedStatus_isReady() {
        assertEquals("ready", shortUploadStatus("Invio: tutto inviato", false, "started", true, true, true))
    }
}
