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

    @Test
    fun legalResultAppliesOnlyToCurrentCompatibilityAllowedContext() {
        val access = requireNotNull(eventAccessKey("https://server", "event", "secret"))
        val otherAccess = requireNotNull(eventAccessKey("https://other", "event", "secret"))
        val context = EventCompatibilityContext(access = access, generation = 4)

        assertTrue(
            shouldApplyEventLegalResult(
                requestedContext = context,
                currentAccess = access,
                currentGeneration = 4,
                allowedAccess = access
            )
        )
        assertFalse(
            shouldApplyEventLegalResult(
                requestedContext = context,
                currentAccess = otherAccess,
                currentGeneration = 4,
                allowedAccess = otherAccess
            )
        )
        assertFalse(
            shouldApplyEventLegalResult(
                requestedContext = context,
                currentAccess = access,
                currentGeneration = 5,
                allowedAccess = access
            )
        )
        assertFalse(
            shouldApplyEventLegalResult(
                requestedContext = context,
                currentAccess = access,
                currentGeneration = 4,
                allowedAccess = null
            )
        )
    }

    @Test
    fun delayedOldFetchCompletion_doesNotClearNewContextFetch() {
        val accessA = requireNotNull(eventAccessKey("https://server-a", "event-a", "secret-a"))
        val accessB = requireNotNull(eventAccessKey("https://server-b", "event-b", "secret-b"))
        val contextA = EventCompatibilityContext(access = accessA, generation = 1)
        val contextB = EventCompatibilityContext(access = accessB, generation = 2)
        val state = EventLegalFlowState()

        assertTrue(state.tryStartFetch(contextA))

        state.invalidate()
        assertTrue(state.tryStartFetch(contextB))
        assertEquals(contextB, state.fetchContext)

        state.finishFetch(contextA)

        assertEquals(contextB, state.fetchContext)
        assertFalse(
            shouldApplyEventLegalResult(
                requestedContext = contextA,
                currentAccess = accessB,
                currentGeneration = 2,
                allowedAccess = null
            )
        )
    }

    @Test
    fun staleLegalDocumentCannotBeAcceptedAfterContextSwitchOrHardBlock() {
        val accessA = requireNotNull(eventAccessKey("https://server-a", "event-a", "secret-a"))
        val accessB = requireNotNull(eventAccessKey("https://server-b", "event-b", "secret-b"))
        val contextA = EventCompatibilityContext(access = accessA, generation = 1)
        val documentA = EventLegalDocumentContext(
            compatibility = contextA,
            resolvedEventName = "event-a",
            legalHash = "hash-a"
        )

        assertFalse(
            canAcceptEventLegal(
                displayedDocument = documentA,
                currentAccess = accessB,
                currentGeneration = 2,
                allowedAccess = null,
                currentResolvedEventName = "event-a",
                currentLegalHash = "hash-a"
            )
        )
    }

    @Test
    fun legalAcceptanceRequiresTheDisplayedDocumentHashAndIdentity() {
        val access = requireNotNull(eventAccessKey("https://server", "event", "secret"))
        val context = EventCompatibilityContext(access = access, generation = 7)
        val document = EventLegalDocumentContext(
            compatibility = context,
            resolvedEventName = "resolved-event",
            legalHash = "hash-v1"
        )

        assertTrue(
            canAcceptEventLegal(
                displayedDocument = document,
                currentAccess = access,
                currentGeneration = 7,
                allowedAccess = access,
                currentResolvedEventName = "resolved-event",
                currentLegalHash = "hash-v1"
            )
        )
        assertFalse(
            canAcceptEventLegal(
                displayedDocument = document,
                currentAccess = access,
                currentGeneration = 7,
                allowedAccess = access,
                currentResolvedEventName = "resolved-event",
                currentLegalHash = "hash-v2"
            )
        )
        assertFalse(
            canAcceptEventLegal(
                displayedDocument = document,
                currentAccess = access,
                currentGeneration = 7,
                allowedAccess = access,
                currentResolvedEventName = "other-event",
                currentLegalHash = "hash-v1"
            )
        )
    }

    @Test
    fun invalidationMakesDisplayedLegalNonActionableAndAllowsNextFetch() {
        val accessA = requireNotNull(eventAccessKey("https://server-a", "event-a", "secret-a"))
        val accessB = requireNotNull(eventAccessKey("https://server-b", "event-b", "secret-b"))
        val contextA = EventCompatibilityContext(access = accessA, generation = 10)
        val contextB = EventCompatibilityContext(access = accessB, generation = 11)
        val documentA = EventLegalDocumentContext(
            compatibility = contextA,
            resolvedEventName = "event-a",
            legalHash = "hash-a"
        )
        val state = EventLegalFlowState()

        assertTrue(state.tryStartFetch(contextA))
        state.display(documentA)
        assertTrue(state.tryStartAccept(documentA))

        state.invalidate()

        assertNull(state.fetchContext)
        assertNull(state.displayedDocument)
        assertNull(state.acceptDocument)
        assertTrue(state.tryStartFetch(contextB))
    }

    @Test
    fun staleAcceptCompletion_doesNotClearNewDocumentAccept() {
        val access = requireNotNull(eventAccessKey("https://server", "event", "secret"))
        val oldContext = EventCompatibilityContext(access = access, generation = 20)
        val newContext = EventCompatibilityContext(access = access, generation = 21)
        val oldDocument = EventLegalDocumentContext(oldContext, "event", "old-hash")
        val newDocument = EventLegalDocumentContext(newContext, "event", "new-hash")
        val state = EventLegalFlowState()

        assertTrue(state.tryStartAccept(oldDocument))
        state.invalidate()
        state.display(newDocument)
        assertTrue(state.tryStartAccept(newDocument))

        state.finishAccept(oldDocument)

        assertEquals(newDocument, state.acceptDocument)
    }

    private fun status(policyState: ClientVersionPolicyState) = ClientVersionStatus(
        policyState = policyState,
        installedVersionCode = 100,
        recommendedVersionCode = null,
        minimumVersionCode = null
    )
}
