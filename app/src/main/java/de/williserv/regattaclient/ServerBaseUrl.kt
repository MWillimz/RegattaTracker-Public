package de.williserv.regattaclient

internal fun normalizeServerBaseUrl(serverUrl: String): String {
    val trimmed = serverUrl.trim().trimEnd('/')
    return trimmed.removeSuffix("/ingest").trimEnd('/')
}
