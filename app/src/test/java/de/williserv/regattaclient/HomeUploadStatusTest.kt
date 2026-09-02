package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeUploadStatusTest {

    private fun shortUploadStatusEnglish(
        pendingUploadCount: Long,
        inRace: Boolean,
        raceStatusCode: String,
        raceDataReady: Boolean,
        raceLegalAccepted: Boolean,
        hasRaceSetup: Boolean
    ): String = shortUploadStatus(
        pendingUploadCount = pendingUploadCount,
        inRace = inRace,
        raceStatusCode = raceStatusCode,
        raceDataReady = raceDataReady,
        raceLegalAccepted = raceLegalAccepted,
        hasRaceSetup = hasRaceSetup,
        pendingText = { "$it pending" },
        blockedText = "blocked",
        offText = "off",
        waitingText = "waiting",
        readyText = "ready",
        idleText = "idle",
        okText = "OK"
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
    fun pendingBacklogOutsideRace_isShownExplicitly() {
        assertEquals("37 pending", shortUploadStatusEnglish(37L, false, "finished", true, true, true))
    }

    @Test
    fun smallPendingBacklogOutsideRace_isNotCollapsedToOk() {
        assertEquals("3 pending", shortUploadStatusEnglish(3L, false, "finished", true, true, true))
    }

    @Test
    fun noRaceSetup_isOffWithoutParsingDisplayText() {
        assertEquals("off", shortUploadStatusEnglish(0L, false, "", false, false, false))
    }

    @Test
    fun loadedSetupWithoutLegalAcceptance_isBlocked() {
        assertEquals("blocked", shortUploadStatusEnglish(0L, false, "planned", false, false, true))
    }

    @Test
    fun rawPlannedStatus_isWaiting() {
        assertEquals("waiting", shortUploadStatusEnglish(0L, false, "planned", true, true, true))
    }

    @Test
    fun rawStartedStatus_isReady() {
        assertEquals("ready", shortUploadStatusEnglish(0L, false, "started", true, true, true))
    }

    @Test
    fun inRaceBacklog_usesRawPendingCount() {
        assertEquals("42", shortUploadStatusEnglish(42L, true, "racing", true, true, true))
        assertEquals("OK", shortUploadStatusEnglish(10L, true, "racing", true, true, true))
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
}
