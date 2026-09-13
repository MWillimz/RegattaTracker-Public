package de.williserv.regattaclient

import de.williserv.regattaclient.ui.theme.RegattaGreen
import de.williserv.regattaclient.ui.theme.RegattaOrange
import de.williserv.regattaclient.ui.theme.RegattaRed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUploadStatusTest {

    private fun shortUploadStatusEnglish(
    pendingUploadCount: Long,
    inRace: Boolean,
    noConnection: Boolean = false
): String = shortUploadStatus(
    pendingUploadCount = pendingUploadCount,
    inRace = inRace,
    pendingText = { "$it pending" },
    okText = "OK",
    noConnection = noConnection,
    noConnectionText = "No connection"
)

    private fun shortRaceStatusEnglish(
        raceStatusCode: String,
        raceStatusDisplayText: String = "",
        raceDataReady: Boolean = true,
        raceStartText: String,
        inRace: Boolean,
        racePrefix: String = "Race:",
        startPrefix: String = "Start:"
    ): String = shortRaceStatusText(
        raceStatusCode = raceStatusCode,
        raceStatusDisplayText = raceStatusDisplayText,
        raceDataReady = raceDataReady,
        raceStartText = raceStartText,
        inRace = inRace,
        racePrefix = racePrefix,
        startPrefix = startPrefix,
        activeText = "active",
        notActiveText = "not active",
        loadedText = "loaded",
        plannedText = "planned",
        racingText = "racing",
        startedText = "started",
        finishedText = "finished",
        postponedText = "postponed",
        cancelledText = "cancelled"
    )

    @Test
    fun connectedWithoutBacklog_isOkRegardlessOfRaceState() {
        assertEquals("OK", shortUploadStatusEnglish(0L, false))
        assertEquals("OK", shortUploadStatusEnglish(0L, true))
    }

    @Test
    fun pendingBacklogOutsideRace_isShownExplicitly() {
        assertEquals("37 pending", shortUploadStatusEnglish(37L, false))
        assertEquals("3 pending", shortUploadStatusEnglish(3L, false))
    }

    @Test
    fun noConnection_hasPriorityAndShowsPendingCount() {
        assertEquals("No connection · 7 pending", shortUploadStatusEnglish(7L, true, true))
        assertEquals("No connection", shortUploadStatusEnglish(0L, false, true))
    }

    @Test
    fun inRaceBacklog_preservesExistingCompactBehavior() {
        assertEquals("42", shortUploadStatusEnglish(42L, true))
        assertEquals("OK", shortUploadStatusEnglish(10L, true))
    }

    @Test
    fun uploadColor_reflectsConnectionAndBacklog() {
        assertEquals(RegattaRed, uploadStatusColor(0L, noConnection = true))
        assertEquals(RegattaGreen, uploadStatusColor(0L))
        assertEquals(RegattaGreen, uploadStatusColor(10L))
        assertEquals(RegattaOrange, uploadStatusColor(11L))
    }

    @Test
    fun unavailableRaceData_rendersLocalizedDisplayErrorInsteadOfStaleRawStatus() {
        assertEquals(
            "Fehler 503",
            shortRaceStatusEnglish(
                raceStatusCode = "racing",
                raceStatusDisplayText = "Regatta: Fehler 503",
                raceDataReady = false,
                raceStartText = "Start: 2026-09-01T12:00:00Z",
                inRace = false,
                racePrefix = "Regatta:"
            )
        )
    }

    @Test
    fun unavailableRaceData_stripsFrenchRacePrefix() {
        assertEquals(
            "erreur 503",
            shortRaceStatusEnglish(
                raceStatusCode = "racing",
                raceStatusDisplayText = "Course : erreur 503",
                raceDataReady = false,
                raceStartText = "Départ : --",
                inRace = false,
                racePrefix = "Course :",
                startPrefix = "Départ :"
            )
        )
    }

    @Test
    fun availableRaceData_usesRawStatusInsteadOfDisplayText() {
        assertEquals(
            "racing",
            shortRaceStatusEnglish(
                raceStatusCode = "racing",
                raceStatusDisplayText = "Race: stale display text",
                raceDataReady = true,
                raceStartText = "Start: --",
                inRace = false
            )
        )
    }

    @Test
    fun unavailableRaceData_doesNotExposeStaleFinishedState() {
        assertFalse(isRaceFinished("finished", raceDataReady = false))
        assertTrue(isRaceFinished("finished", raceDataReady = true))
        assertEquals(
            RegattaOrange,
            raceStatusColor(
                raceStatusCode = "finished",
                inRace = false,
                raceDataReady = false
            )
        )
    }

    @Test
    fun unavailableRaceData_keepsActiveRaceColorWhileTrackingContinues() {
        assertEquals(
            RegattaGreen,
            raceStatusColor(
                raceStatusCode = "finished",
                inRace = true,
                raceDataReady = false
            )
        )
    }
}
