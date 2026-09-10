package de.williserv.regattaclient

internal data class EventAccessKey(
    val server: String,
    val event: String,
    val secret: String
)

internal data class EventCompatibilityContext(
    val access: EventAccessKey,
    val generation: Long
)

internal data class EventLegalDocumentContext(
    val compatibility: EventCompatibilityContext,
    val resolvedEventName: String,
    val legalHash: String
)

internal class EventLegalFlowState {
    var fetchContext: EventCompatibilityContext? = null
        private set
    var displayedDocument: EventLegalDocumentContext? = null
        private set
    var acceptDocument: EventLegalDocumentContext? = null
        private set

    fun invalidate() {
        fetchContext = null
        displayedDocument = null
        acceptDocument = null
    }

    fun tryStartFetch(context: EventCompatibilityContext): Boolean {
        if (fetchContext == context) return false
        fetchContext = context
        return true
    }

    fun finishFetch(context: EventCompatibilityContext) {
        if (fetchContext == context) {
            fetchContext = null
        }
    }

    fun display(document: EventLegalDocumentContext) {
        displayedDocument = document
    }

    fun clearDisplayedDocument() {
        displayedDocument = null
    }

    fun tryStartAccept(document: EventLegalDocumentContext): Boolean {
        if (acceptDocument == document) return false
        acceptDocument = document
        return true
    }

    fun finishAccept(document: EventLegalDocumentContext) {
        if (acceptDocument == document) {
            acceptDocument = null
        }
    }
}

internal fun eventAccessKey(
    server: String,
    event: String,
    secret: String
): EventAccessKey? {
    val normalizedServer = server.trim()
    val normalizedEvent = event.trim()
    val normalizedSecret = secret.trim()

    if (normalizedServer.isBlank() || normalizedEvent.isBlank() || normalizedSecret.isBlank()) {
        return null
    }

    return EventAccessKey(
        server = normalizedServer,
        event = normalizedEvent,
        secret = normalizedSecret
    )
}

internal enum class EventCompatibilityDecision {
    PROCEED,
    WARN,
    BLOCK
}

internal fun eventCompatibilityDecision(
    status: ClientVersionStatus?
): EventCompatibilityDecision = when (status?.policyState) {
    ClientVersionPolicyState.UPDATE_RECOMMENDED -> EventCompatibilityDecision.WARN
    ClientVersionPolicyState.UPDATE_REQUIRED -> EventCompatibilityDecision.BLOCK
    ClientVersionPolicyState.CURRENT,
    ClientVersionPolicyState.DEV_DEBUG,
    null -> EventCompatibilityDecision.PROCEED
}

internal fun shouldApplyEventCompatibilityResult(
    requestedAccess: EventAccessKey,
    requestedGeneration: Long,
    currentAccess: EventAccessKey?,
    currentGeneration: Long
): Boolean =
    requestedGeneration == currentGeneration && requestedAccess == currentAccess

internal fun shouldApplyEventLegalResult(
    requestedContext: EventCompatibilityContext,
    currentAccess: EventAccessKey?,
    currentGeneration: Long,
    allowedAccess: EventAccessKey?
): Boolean =
    requestedContext.generation == currentGeneration &&
        requestedContext.access == currentAccess &&
        requestedContext.access == allowedAccess

internal fun canAcceptEventLegal(
    displayedDocument: EventLegalDocumentContext?,
    currentAccess: EventAccessKey?,
    currentGeneration: Long,
    allowedAccess: EventAccessKey?,
    currentResolvedEventName: String,
    currentLegalHash: String
): Boolean {
    val document = displayedDocument ?: return false
    return shouldApplyEventLegalResult(
        requestedContext = document.compatibility,
        currentAccess = currentAccess,
        currentGeneration = currentGeneration,
        allowedAccess = allowedAccess
    ) &&
        document.resolvedEventName == currentResolvedEventName &&
        document.legalHash == currentLegalHash
}
