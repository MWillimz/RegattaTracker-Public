package de.williserv.regattaclient

import java.net.URLEncoder

internal fun buildNormalApiGetUrl(
    baseUrl: String,
    path: String,
    eventName: String
): String {
    val normalizedBaseUrl = baseUrl.trimEnd('/')
    val normalizedPath = if (path.startsWith('/')) path else "/$path"
    val encodedEvent = URLEncoder.encode(eventName, "UTF-8")

    return "$normalizedBaseUrl$normalizedPath?event_name=$encodedEvent"
}
