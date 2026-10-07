package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticipantRetirementRunGuardTest {

    @Test
    fun `new Daily run rejects delayed retirement response from previous run`() {
        val guard = ParticipantRetirementRunGuard()
        val previousRunGeneration = guard.captureGeneration()
        var persistedReceipt: String? = "previous"

        guard.resetForNewRun {
            persistedReceipt = null
        }

        val staleWriteAccepted = guard.runIfCurrent(previousRunGeneration) {
            persistedReceipt = "delayed previous-run response"
        }

        assertFalse(staleWriteAccepted)
        assertNull(persistedReceipt)
        assertFalse(guard.isCurrent(previousRunGeneration))
    }

    @Test
    fun `current Daily run can still persist its retirement response`() {
        val guard = ParticipantRetirementRunGuard()
        guard.resetForNewRun {}
        val currentRunGeneration = guard.captureGeneration()
        var persistedReceipt: String? = null

        val writeAccepted = guard.runIfCurrent(currentRunGeneration) {
            persistedReceipt = "current-run response"
        }

        assertTrue(writeAccepted)
        assertEquals("current-run response", persistedReceipt)
        assertTrue(guard.isCurrent(currentRunGeneration))
    }
}
