package de.williserv.regattaclient

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class ServerMetadata(
    val operator: String?,
    val publicUrl: String?,
    val contactEmail: String?
) {
    fun hasAnyValue(): Boolean =
        operator != null || publicUrl != null || contactEmail != null
}

internal fun buildServerMetadataUrl(server: String): String? {
    val trimmed = server.trim()
    if (trimmed.isBlank()) return null

    val base = if (trimmed.endsWith("/ingest")) {
        trimmed.removeSuffix("/ingest")
    } else {
        trimmed
    }.trimEnd('/')

    if (base.isBlank()) return null

    val uri = runCatching { URI(base) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrBlank()) return null

    return "$base/server-metadata"
}

internal fun buildServerMetadataHeaders(
    eventName: String,
    sharedSecret: String
): Map<String, String>? {
    val normalizedEventName = eventName.trim()
    val normalizedSecret = sharedSecret.trim()

    if (normalizedEventName.isBlank() || normalizedSecret.isBlank()) return null

    return mapOf(
        "Accept" to "application/json",
        "x-api-version" to RegattaTrackingService.API_VERSION,
        "x-event-name" to normalizedEventName,
        "x-shared-secret" to normalizedSecret
    )
}

internal fun parseServerMetadata(body: String): ServerMetadata {
    val json = JSONObject(body)

    fun optionalString(key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.optString(key, "").trim()
        return value.takeIf { it.isNotBlank() }
    }

    return ServerMetadata(
        operator = optionalString("operator"),
        publicUrl = optionalString("public_url"),
        contactEmail = optionalString("contact_email")
    )
}

internal fun isHttpOrHttpsUrl(value: String): Boolean {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase()
    return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
}

internal fun fetchServerMetadata(
    server: String,
    eventName: String,
    sharedSecret: String
): ServerMetadata? {
    val endpoint = buildServerMetadataUrl(server) ?: return null
    val headers = buildServerMetadataHeaders(eventName, sharedSecret) ?: return null

    return try {
        val connection = URL(endpoint).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            headers.forEach { (name, value) ->
                connection.setRequestProperty(name, value)
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseServerMetadata(body).takeIf { it.hasAnyValue() }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
