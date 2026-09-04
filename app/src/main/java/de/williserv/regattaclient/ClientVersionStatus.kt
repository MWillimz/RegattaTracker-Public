package de.williserv.regattaclient

internal const val DEV_DEBUG_VERSION_CODE = 42

internal data class ClientBuildIdentity(
    val versionCode: Int,
    val buildId: String
) {
    val isDevDebug: Boolean
        get() = versionCode == DEV_DEBUG_VERSION_CODE
}

internal fun currentClientBuildIdentity(): ClientBuildIdentity =
    ClientBuildIdentity(
        versionCode = BuildConfig.VERSION_CODE,
        buildId = BuildConfig.VERSION_NAME
    )

internal enum class ClientVersionPolicyState {
    DEV_DEBUG,
    CURRENT,
    UPDATE_RECOMMENDED,
    UPDATE_REQUIRED
}

internal data class ClientVersionStatus(
    val policyState: ClientVersionPolicyState,
    val installedVersionCode: Int,
    val recommendedVersionCode: Int?,
    val minimumVersionCode: Int?,
    val productionVersionCode: Int?,
    val directDownloadVersionCode: Int?,
    val newerProductionAvailable: Boolean,
    val newerDirectDownloadAvailable: Boolean
)

internal fun evaluateClientVersionStatus(
    client: ClientBuildIdentity,
    serverMetadata: ServerMetadata
): ClientVersionStatus {
    val productionVersionCode = serverMetadata.productionRelease?.versionCode
    val directDownloadVersionCode = serverMetadata.directDownloadRelease?.versionCode

    if (client.isDevDebug) {
        return ClientVersionStatus(
            policyState = ClientVersionPolicyState.DEV_DEBUG,
            installedVersionCode = client.versionCode,
            recommendedVersionCode = serverMetadata.recommendedClientVersionCode,
            minimumVersionCode = serverMetadata.minClientVersionCode,
            productionVersionCode = productionVersionCode,
            directDownloadVersionCode = directDownloadVersionCode,
            newerProductionAvailable = false,
            newerDirectDownloadAvailable = false
        )
    }

    val policyState = when {
        serverMetadata.minClientVersionCode?.let { client.versionCode < it } == true -> {
            ClientVersionPolicyState.UPDATE_REQUIRED
        }

        serverMetadata.recommendedClientVersionCode?.let { client.versionCode < it } == true -> {
            ClientVersionPolicyState.UPDATE_RECOMMENDED
        }

        else -> ClientVersionPolicyState.CURRENT
    }

    return ClientVersionStatus(
        policyState = policyState,
        installedVersionCode = client.versionCode,
        recommendedVersionCode = serverMetadata.recommendedClientVersionCode,
        minimumVersionCode = serverMetadata.minClientVersionCode,
        productionVersionCode = productionVersionCode,
        directDownloadVersionCode = directDownloadVersionCode,
        newerProductionAvailable = productionVersionCode?.let { client.versionCode < it } ?: false,
        newerDirectDownloadAvailable = directDownloadVersionCode?.let { client.versionCode < it } ?: false
    )
}
