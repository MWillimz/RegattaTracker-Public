package de.williserv.regattaclient

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal data class EventAccessCredentials(
    val server: String,
    val event: String,
    val secret: String
)

private val HTTPS_URL_CANDIDATE = Regex(
    pattern = "https://[^\\s<>\"']+",
    option = RegexOption.IGNORE_CASE
)

internal fun parseEventAccessUrl(rawText: String): EventAccessCredentials? {
    val text = rawText.trim()
    if (text.isEmpty()) return null

    parseStandaloneEventAccessUrl(text)?.let { return it }

    val validEventLinks = HTTPS_URL_CANDIDATE
        .findAll(text)
        .mapNotNull { match -> parseStandaloneEventAccessUrl(match.value) }
        .toList()

    return validEventLinks.singleOrNull()
}

private fun parseStandaloneEventAccessUrl(text: String): EventAccessCredentials? {
    return try {
        val uri = URI(text)

        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank()) return null
        if (uri.rawUserInfo != null || uri.rawFragment != null) return null

        val rawPath = uri.rawPath ?: return null
        val eventAccessSuffix = "/event-access"
        if (!rawPath.endsWith(eventAccessSuffix)) return null

        val rawQuery = uri.rawQuery ?: return null
        var eventName: String? = null
        var secret: String? = null

        rawQuery.split('&').forEach { part ->
            if (part.isEmpty()) return@forEach

            val separatorIndex = part.indexOf('=')
            val rawKey = if (separatorIndex >= 0) part.substring(0, separatorIndex) else part
            val rawValue = if (separatorIndex >= 0) part.substring(separatorIndex + 1) else ""
            val key = decodeQueryParameter(rawKey)
            val value = decodeQueryParameter(rawValue)

            when (key) {
                "event_name" -> {
                    if (eventName != null) return null
                    eventName = value.trim()
                }

                "secret" -> {
                    if (secret != null) return null
                    secret = value.trim()
                }
            }
        }

        val normalizedEventName = eventName?.takeIf { it.isNotBlank() } ?: return null
        val normalizedSecret = secret?.takeIf { it.isNotBlank() } ?: return null
        val basePath = rawPath.removeSuffix(eventAccessSuffix)
        val server = "${uri.scheme}://${uri.rawAuthority}$basePath"

        EventAccessCredentials(
            server = server,
            event = normalizedEventName,
            secret = normalizedSecret
        )
    } catch (_: Exception) {
        null
    }
}

internal fun shouldBlockSharedEventImport(
    server: String,
    event: String,
    secret: String,
    resolvedEventName: String,
    raceDataReady: Boolean,
    raceRegistered: Boolean,
    inRace: Boolean
): Boolean {
    return server.isNotBlank() ||
            event.isNotBlank() ||
            secret.isNotBlank() ||
            resolvedEventName.isNotBlank() ||
            raceDataReady ||
            raceRegistered ||
            inRace
}

private fun decodeQueryParameter(value: String): String {
    return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
