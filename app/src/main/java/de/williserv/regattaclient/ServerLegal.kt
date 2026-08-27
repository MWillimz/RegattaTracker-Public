package de.williserv.regattaclient

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class ServerLegalKind(
    val path: String,
    val fallbackTitle: String
) {
    IMPRESSUM("impressum", "Server Impressum"),
    DATENSCHUTZ("datenschutz", "Server Datenschutz")
}

data class ServerLegalDocument(
    val title: String,
    val content: String
)

internal fun buildServerLegalUrl(
    server: String,
    kind: ServerLegalKind
): String? {
    val metadataUrl = buildServerMetadataUrl(server) ?: return null
    val base = metadataUrl.removeSuffix("/server-metadata")
    return "$base/server-legal/${kind.path}"
}

internal fun parseServerLegalDocument(body: String): ServerLegalDocument? {
    val json = JSONObject(body)
    val title = json.optString("title", "").trim()
    val content = json.optString("content", "").trim()

    if (content.isBlank()) return null

    return ServerLegalDocument(
        title = title,
        content = content
    )
}

internal fun fetchServerLegalDocument(
    server: String,
    eventName: String,
    sharedSecret: String,
    kind: ServerLegalKind
): ServerLegalDocument? {
    val endpoint = buildServerLegalUrl(server, kind) ?: return null
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
            parseServerLegalDocument(body)
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
