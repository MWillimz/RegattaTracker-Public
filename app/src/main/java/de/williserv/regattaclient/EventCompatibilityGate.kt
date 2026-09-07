package de.williserv.regattaclient

internal data class EventAccessKey(
    val server: String,
    val event: String,
    val secret: String
)

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
