package de.williserv.regattaclient

internal enum class ClientVersionNotice {
    NONE,
    UPDATE_RECOMMENDED,
    UPDATE_REQUIRED
}

internal fun clientVersionNotice(
    persistedHardBlock: Boolean,
    status: ClientVersionStatus?
): ClientVersionNotice {
    if (persistedHardBlock) {
        return ClientVersionNotice.UPDATE_REQUIRED
    }

    return when (status?.policyState) {
        ClientVersionPolicyState.UPDATE_RECOMMENDED -> ClientVersionNotice.UPDATE_RECOMMENDED
        ClientVersionPolicyState.UPDATE_REQUIRED -> ClientVersionNotice.UPDATE_REQUIRED
        ClientVersionPolicyState.CURRENT,
        ClientVersionPolicyState.DEV_DEBUG,
        null -> ClientVersionNotice.NONE
    }
}
