package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventCompatibilityGateTest {

    @Test
    fun current_proceeds() {
        assertEquals(
            EventCompatibilityDecision.PROCEED,
            eventCompatibilityDecision(status(ClientVersionPolicyState.CURRENT))
        )
    }

    @Test
    fun devDebug_proceeds() {
        assertEquals(
            EventCompatibilityDecision.PROCEED,
            eventCompatibilityDecision(status(ClientVersionPolicyState.DEV_DEBUG))
        )
    }

    @Test
    fun missingMetadata_proceeds() {
        assertEquals(
            EventCompatibilityDecision.PROCEED,
            eventCompatibilityDecision(null)
        )
    }

    @Test
    fun updateRecommended_warns() {
        assertEquals(
            EventCompatibilityDecision.WARN,
            eventCompatibilityDecision(status(ClientVersionPolicyState.UPDATE_RECOMMENDED))
        )
    }

    @Test
    fun updateRequired_blocks() {
        assertEquals(
            EventCompatibilityDecision.BLOCK,
            eventCompatibilityDecision(status(ClientVersionPolicyState.UPDATE_REQUIRED))
        )
    }

    @Test
    fun accessKey_requiresCompleteContext() {
        assertNull(eventAccessKey("", "event", "secret"))
        assertNull(eventAccessKey("https://server", "", "secret"))
        assertNull(eventAccessKey("https://server", "event", ""))
    }

    @Test
    fun accessKeyNormalizesWhitespaceAndDetectsChangedAccess() {
        val first = eventAccessKey(" https://server ", " event ", " secret ")
        val same = eventAccessKey("https://server", "event", "secret")
        val changed = eventAccessKey("https://other", "event", "secret")

        assertEquals(first, same)
        assertNotEquals(first, changed)
    }

    @Test
    fun compatibilityResultAppliesOnlyToSameAccessAndGeneration() {
        val access = requireNotNull(eventAccessKey("https://server", "event", "secret"))
        val changedAccess = requireNotNull(eventAccessKey("https://other", "event", "secret"))

        assertTrue(
            shouldApplyEventCompatibilityResult(
                requestedAccess = access,
                requestedGeneration = 4,
                currentAccess = access,
                currentGeneration = 4
            )
        )
        assertFalse(
            shouldApplyEventCompatibilityResult(
                requestedAccess = access,
                requestedGeneration = 4,
                currentAccess = changedAccess,
                currentGeneration = 4
            )
        )
        assertFalse(
            shouldApplyEventCompatibilityResult(
                requestedAccess = access,
                requestedGeneration = 4,
                currentAccess = access,
                currentGeneration = 5
            )
        )
    }

    private fun status(policyState: ClientVersionPolicyState) = ClientVersionStatus(
        policyState = policyState,
        installedVersionCode = 100,
        recommendedVersionCode = null,
        minimumVersionCode = null
    )
}
