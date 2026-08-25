package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeUploadStatusTest {

    @Test
    fun pendingBacklogOutsideRace_isShownExplicitly() {
        assertEquals(
            "37 pending",
            shortUploadStatus(
                uploadStatusText = "Upload: 37 pending",
                inRace = false,
                raceStatusText = "Race: finished"
            )
        )
    }

    @Test
    fun smallPendingBacklogOutsideRace_isNotCollapsedToOk() {
        assertEquals(
            "3 pending",
            shortUploadStatus(
                uploadStatusText = "Upload: 3 pending",
                inRace = false,
                raceStatusText = "Race: finished"
            )
        )
    }

    @Test
    fun noBacklogOutsideRace_keepsExistingIdleState() {
        assertEquals(
            "off",
            shortUploadStatus(
                uploadStatusText = "Upload: all sent",
                inRace = false,
                raceStatusText = "Race: not loaded"
            )
        )
    }
}
