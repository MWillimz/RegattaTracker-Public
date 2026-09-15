package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceLegalContextPolicyTest {

    @Test
    fun accessScopedSeriesLegalSurvivesResolvedRunChange() {
        assertFalse(
            RaceLegalContextPolicy.shouldClearForResolvedRunChange(
                accessIdentifier = "Mittwochsregatta 2026",
                legalEventIdentity = "Mittwochsregatta 2026",
                nextResolvedEventName = "internal-run-b"
            )
        )
    }

    @Test
    fun runScopedLegalIsClearedWhenResolvedRunChanges() {
        assertTrue(
            RaceLegalContextPolicy.shouldClearForResolvedRunChange(
                accessIdentifier = "recurring-access",
                legalEventIdentity = "internal-run-a",
                nextResolvedEventName = "internal-run-b"
            )
        )
    }

    @Test
    fun runScopedLegalStaysForSameResolvedRun() {
        assertFalse(
            RaceLegalContextPolicy.shouldClearForResolvedRunChange(
                accessIdentifier = "recurring-access",
                legalEventIdentity = "internal-run-a",
                nextResolvedEventName = "internal-run-a"
            )
        )
    }

    @Test
    fun acceptedLegalSurvivesReloadOnlyForSameIdentityAndHash() {
        assertTrue(
            RaceLegalContextPolicy.canPreserveAcceptanceAfterReload(
                currentlyAccepted = true,
                currentLegalEventIdentity = "Mittwochsregatta 2026",
                currentLegalHash = "hash-a",
                nextLegalEventIdentity = "Mittwochsregatta 2026",
                nextLegalHash = "hash-a"
            )
        )

        assertFalse(
            RaceLegalContextPolicy.canPreserveAcceptanceAfterReload(
                currentlyAccepted = true,
                currentLegalEventIdentity = "Mittwochsregatta 2026",
                currentLegalHash = "hash-a",
                nextLegalEventIdentity = "Mittwochsregatta 2026",
                nextLegalHash = "hash-b"
            )
        )

        assertFalse(
            RaceLegalContextPolicy.canPreserveAcceptanceAfterReload(
                currentlyAccepted = true,
                currentLegalEventIdentity = "Event A",
                currentLegalHash = "hash-a",
                nextLegalEventIdentity = "Event B",
                nextLegalHash = "hash-a"
            )
        )
    }
}
