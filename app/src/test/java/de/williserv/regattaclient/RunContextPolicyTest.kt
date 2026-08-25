package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunContextPolicyTest {

    @Test
    fun firstResolvedEventIsNotTreatedAsRunChange() {
        assertFalse(
            RunContextPolicy.hasRunChanged(
                currentResolvedEventName = null,
                nextResolvedEventName = "Run A"
            )
        )
    }

    @Test
    fun sameResolvedEventDoesNotTriggerRunChange() {
        assertFalse(
            RunContextPolicy.hasRunChanged(
                currentResolvedEventName = "Run A",
                nextResolvedEventName = "Run A"
            )
        )
    }

    @Test
    fun differentResolvedEventTriggersRunChange() {
        assertTrue(
            RunContextPolicy.hasRunChanged(
                currentResolvedEventName = "Run A",
                nextResolvedEventName = "Run B"
            )
        )
    }

    @Test
    fun explicitPersistedStateRestoresOnlyForSameResolvedRun() {
        assertTrue(
            RunContextPolicy.canRestorePersistedState(
                savedResolvedEventName = "Run A",
                legacyEventName = "series-access",
                accessIdentifier = "series-access",
                resolvedEventName = "Run A",
                savedSailNumber = "GER 123",
                currentSailNumber = "GER 123"
            )
        )

        assertFalse(
            RunContextPolicy.canRestorePersistedState(
                savedResolvedEventName = "Run A",
                legacyEventName = "series-access",
                accessIdentifier = "series-access",
                resolvedEventName = "Run B",
                savedSailNumber = "GER 123",
                currentSailNumber = "GER 123"
            )
        )
    }

    @Test
    fun persistedStateDoesNotRestoreForDifferentBoat() {
        assertFalse(
            RunContextPolicy.canRestorePersistedState(
                savedResolvedEventName = "Run A",
                legacyEventName = "Run A",
                accessIdentifier = "Run A",
                resolvedEventName = "Run A",
                savedSailNumber = "GER 123",
                currentSailNumber = "GER 999"
            )
        )
    }

    @Test
    fun legacyStateRestoresForDirectSingleEvent() {
        assertTrue(
            RunContextPolicy.canRestorePersistedState(
                savedResolvedEventName = null,
                legacyEventName = "Run A",
                accessIdentifier = "Run A",
                resolvedEventName = "Run A",
                savedSailNumber = "GER 123",
                currentSailNumber = "GER 123"
            )
        )
    }

    @Test
    fun legacyStateDoesNotRestoreForSeriesAccess() {
        assertFalse(
            RunContextPolicy.canRestorePersistedState(
                savedResolvedEventName = null,
                legacyEventName = "series-access",
                accessIdentifier = "series-access",
                resolvedEventName = "Run A",
                savedSailNumber = "GER 123",
                currentSailNumber = "GER 123"
            )
        )
    }

    @Test
    fun policyNormalizesWhitespaceAroundEventNames() {
        assertFalse(
            RunContextPolicy.hasRunChanged(
                currentResolvedEventName = "  Run A ",
                nextResolvedEventName = "Run A  "
            )
        )

        assertTrue(
            RunContextPolicy.canRestorePersistedState(
                savedResolvedEventName = " Run A ",
                legacyEventName = null,
                accessIdentifier = "series-access",
                resolvedEventName = "Run A",
                savedSailNumber = "GER 123",
                currentSailNumber = "GER 123"
            )
        )
    }
}
