package de.williserv.regattaclient

internal enum class EnterRaceLegalGateDecision {
    CONTINUE,
    FETCH_LEGAL,
    SHOW_LEGAL,
    BLOCK
}

internal fun enterRaceLegalStartDecision(
    legalAccepted: Boolean
): EnterRaceLegalGateDecision =
    if (legalAccepted) {
        EnterRaceLegalGateDecision.CONTINUE
    } else {
        EnterRaceLegalGateDecision.FETCH_LEGAL
    }

internal fun enterRaceLegalFetchDecision(
    serverResponded: Boolean,
    responseSuccessful: Boolean,
    documentValid: Boolean,
    acceptancePreserved: Boolean
): EnterRaceLegalGateDecision = when {
    !serverResponded -> EnterRaceLegalGateDecision.CONTINUE
    !responseSuccessful || !documentValid -> EnterRaceLegalGateDecision.BLOCK
    acceptancePreserved -> EnterRaceLegalGateDecision.CONTINUE
    else -> EnterRaceLegalGateDecision.SHOW_LEGAL
}
