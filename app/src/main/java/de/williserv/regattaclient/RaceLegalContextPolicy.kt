package de.williserv.regattaclient

internal object RaceLegalContextPolicy {
    fun shouldClearForResolvedRunChange(
        accessIdentifier: String,
        legalEventIdentity: String?,
        nextResolvedEventName: String
    ): Boolean {
        val access = accessIdentifier.trim()
        val legalIdentity = legalEventIdentity?.trim().orEmpty()
        val nextResolved = nextResolvedEventName.trim()

        if (legalIdentity.isBlank()) return false
        if (access.isNotBlank() && legalIdentity == access) return false

        return nextResolved.isNotBlank() && legalIdentity != nextResolved
    }

    fun canPreserveAcceptanceAfterReload(
        currentlyAccepted: Boolean,
        currentLegalEventIdentity: String?,
        currentLegalHash: String?,
        nextLegalEventIdentity: String,
        nextLegalHash: String
    ): Boolean {
        if (!currentlyAccepted) return false

        val currentIdentity = currentLegalEventIdentity?.trim().orEmpty()
        val currentHash = currentLegalHash?.trim().orEmpty()
        val nextIdentity = nextLegalEventIdentity.trim()
        val nextHash = nextLegalHash.trim()

        return currentIdentity.isNotBlank() &&
            currentHash.isNotBlank() &&
            currentIdentity == nextIdentity &&
            currentHash == nextHash
    }
}
