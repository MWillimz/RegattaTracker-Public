package de.williserv.regattaclient

internal object RunContextPolicy {
    fun hasRunChanged(
        currentResolvedEventName: String?,
        nextResolvedEventName: String
    ): Boolean {
        val current = currentResolvedEventName?.trim().orEmpty()
        val next = nextResolvedEventName.trim()

        return current.isNotBlank() && next.isNotBlank() && current != next
    }

    fun canRestorePersistedState(
        savedResolvedEventName: String?,
        legacyEventName: String?,
        accessIdentifier: String,
        resolvedEventName: String,
        savedSailNumber: String,
        currentSailNumber: String
    ): Boolean {
        if (savedSailNumber != currentSailNumber) return false

        val savedResolved = savedResolvedEventName?.trim().orEmpty()
        val resolved = resolvedEventName.trim()

        if (savedResolved.isNotBlank()) {
            return savedResolved == resolved
        }

        val legacyEvent = legacyEventName?.trim().orEmpty()
        val access = accessIdentifier.trim()

        return legacyEvent == access && access == resolved
    }
}
