package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegattaLinkDiscoveryPolicyTest {

    @Test
    fun manualDiscoveryBudgetIsTwoMinutes() {
        assertEquals(120_000L, REGATTALINK_MANUAL_DISCOVERY_TIMEOUT_MS)
    }

    @Test
    fun remainingBudgetNeverGoesNegative() {
        assertEquals(40_000L, regattaLinkDiscoveryRemainingMs(60_000L, 20_000L))
        assertEquals(0L, regattaLinkDiscoveryRemainingMs(60_000L, 60_000L))
        assertEquals(0L, regattaLinkDiscoveryRemainingMs(60_000L, 61_000L))
    }

    @Test
    fun stageTimeoutIsCappedByOverallDiscoveryBudget() {
        assertEquals(
            20_000L,
            regattaLinkDiscoveryStageTimeoutMs(
                stageTimeoutMs = 20_000L,
                deadlineElapsedMs = 60_000L,
                nowElapsedMs = 5_000L
            )
        )
        assertEquals(
            4_000L,
            regattaLinkDiscoveryStageTimeoutMs(
                stageTimeoutMs = 20_000L,
                deadlineElapsedMs = 60_000L,
                nowElapsedMs = 56_000L
            )
        )
    }

    @Test
    fun rejectedBondIsSkippedOnlyAfterBondingWasObserved() {
        assertFalse(
            shouldSkipRejectedRegattaLinkDiscoveryCandidate(
                bondingObserved = false,
                currentlyUnbonded = true
            )
        )
        assertFalse(
            shouldSkipRejectedRegattaLinkDiscoveryCandidate(
                bondingObserved = true,
                currentlyUnbonded = false
            )
        )
        assertTrue(
            shouldSkipRejectedRegattaLinkDiscoveryCandidate(
                bondingObserved = true,
                currentlyUnbonded = true
            )
        )
    }

    @Test
    fun exhaustedDiscoveryExplainsStaleAndroidBondWhenObserved() {
        assertEquals(
            "No available RegattaLink found",
            regattaLinkManualDiscoveryExhaustedMessage(
                staleBondFailureObserved = false
            )
        )
        assertEquals(
            REGATTALINK_STALE_ANDROID_BOND_ERROR,
            regattaLinkManualDiscoveryExhaustedMessage(
                staleBondFailureObserved = true
            )
        )
        assertTrue(
            REGATTALINK_STALE_ANDROID_BOND_ERROR.contains(
                "Android Bluetooth settings"
            )
        )
        assertTrue(
            REGATTALINK_STALE_ANDROID_BOND_ERROR.contains("Search again")
        )
    }

    @Test
    fun tenPromptCandidateRejectionsStillLeaveFullGattAttemptBudget() {
        val startedAt = 1_000_000L
        val deadline = startedAt + REGATTALINK_MANUAL_DISCOVERY_TIMEOUT_MS
        var now = startedAt

        repeat(10) {
            assertTrue(
                shouldSkipRejectedRegattaLinkDiscoveryCandidate(
                    bondingObserved = true,
                    currentlyUnbonded = true
                )
            )
            now += 500L
        }

        assertEquals(
            20_000L,
            regattaLinkDiscoveryStageTimeoutMs(
                stageTimeoutMs = 20_000L,
                deadlineElapsedMs = deadline,
                nowElapsedMs = now
            )
        )
    }
}
