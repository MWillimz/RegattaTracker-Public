package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test

class EnterRaceLegalGateTest {

    @Test
    fun acceptedLegalContinuesWithoutFetch() {
        assertEquals(
            EnterRaceLegalGateDecision.CONTINUE,
            enterRaceLegalStartDecision(legalAccepted = true)
        )
    }

    @Test
    fun missingAcceptanceRequiresLegalFetch() {
        assertEquals(
            EnterRaceLegalGateDecision.FETCH_LEGAL,
            enterRaceLegalStartDecision(legalAccepted = false)
        )
    }

    @Test
    fun noServerResponseFailsOpen() {
        assertEquals(
            EnterRaceLegalGateDecision.CONTINUE,
            enterRaceLegalFetchDecision(
                serverResponded = false,
                responseSuccessful = false,
                documentValid = false,
                acceptancePreserved = false
            )
        )
    }

    @Test
    fun reachableHttpErrorBlocks() {
        assertEquals(
            EnterRaceLegalGateDecision.BLOCK,
            enterRaceLegalFetchDecision(
                serverResponded = true,
                responseSuccessful = false,
                documentValid = false,
                acceptancePreserved = false
            )
        )
    }

    @Test
    fun invalidDocumentFromReachableServerBlocks() {
        assertEquals(
            EnterRaceLegalGateDecision.BLOCK,
            enterRaceLegalFetchDecision(
                serverResponded = true,
                responseSuccessful = true,
                documentValid = false,
                acceptancePreserved = false
            )
        )
    }

    @Test
    fun validUnacceptedDocumentShowsLegal() {
        assertEquals(
            EnterRaceLegalGateDecision.SHOW_LEGAL,
            enterRaceLegalFetchDecision(
                serverResponded = true,
                responseSuccessful = true,
                documentValid = true,
                acceptancePreserved = false
            )
        )
    }

    @Test
    fun validAlreadyAcceptedDocumentContinues() {
        assertEquals(
            EnterRaceLegalGateDecision.CONTINUE,
            enterRaceLegalFetchDecision(
                serverResponded = true,
                responseSuccessful = true,
                documentValid = true,
                acceptancePreserved = true
            )
        )
    }
}
