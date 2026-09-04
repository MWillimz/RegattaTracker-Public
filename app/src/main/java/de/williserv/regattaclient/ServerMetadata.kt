package de.williserv.regattaclient

import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class ProductionReleaseMetadata(
    val versionCode: Int,
    val versionName: String,
    val sourceSha: String,
    val recordedAt: String?
)

data class DirectDownloadReleaseMetadata(
    val versionCode: Int,
    val versionName: String,
    val sourceSha: String,
    val uploadedAt: String?,
    val downloadUrl: String?
)

data class ServerMetadata(
    val operator: String?,
    val publicUrl: String?,
    val contactEmail: String?,
    val serverBuildId: String? = null,
    val serverBuildNumber: Int? = null,
    val serverBuildType: String? = null,
    val recommendedClientVersionCode: Int? = null,
    val minClientVersionCode: Int? = null,
    val productionRelease: ProductionReleaseMetadata? = null,
    val directDownloadRelease: DirectDownloadReleaseMetadata? = null
) {
    fun hasAnyValue(): Boolean =
        operator != null ||
            publicUrl != null ||
            contactEmail != null ||
            serverBuildId != null ||
            serverBuildNumber != null ||
            serverBuildType != null ||
            recommendedClientVersionCode != null ||
            minClientVersionCode != null ||
            productionRelease != null ||
            directDownloadRelease != null
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

private fun optionalString(json: JSONObject, key: String): String? {
    if (!json.has(key) || json.isNull(key)) return null
    val value = json.get(key)
    if (value !is String) throw JSONException("$key must be a string or null")
    return value.trim().takeIf { it.isNotBlank() }
}

private fun requiredString(json: JSONObject, key: String): String =
    optionalString(json, key) ?: throw JSONException("$key is required")

private fun optionalInt(json: JSONObject, key: String): Int? {
    if (!json.has(key) || json.isNull(key)) return null

    return when (val value = json.get(key)) {
        is Int -> value
        is Long -> {
            if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                throw JSONException("$key is outside the supported integer range")
            }
            value.toInt()
        }

        else -> throw JSONException("$key must be an integer or null")
    }
}

private fun requiredInt(json: JSONObject, key: String): Int =
    optionalInt(json, key) ?: throw JSONException("$key is required")

private fun optionalObject(json: JSONObject, key: String): JSONObject? {
    if (!json.has(key) || json.isNull(key)) return null
    val value = json.get(key)
    if (value !is JSONObject) throw JSONException("$key must be an object or null")
    return value
}

private fun parseProductionRelease(json: JSONObject?): ProductionReleaseMetadata? {
    if (json == null) return null

    return ProductionReleaseMetadata(
        versionCode = requiredInt(json, "version_code"),
        versionName = requiredString(json, "version_name"),
        sourceSha = requiredString(json, "source_sha"),
        recordedAt = optionalString(json, "recorded_at")
    )
}

private fun parseDirectDownloadRelease(json: JSONObject?): DirectDownloadReleaseMetadata? {
    if (json == null) return null

    return DirectDownloadReleaseMetadata(
        versionCode = requiredInt(json, "version_code"),
        versionName = requiredString(json, "version_name"),
        sourceSha = requiredString(json, "source_sha"),
        uploadedAt = optionalString(json, "uploaded_at"),
        downloadUrl = optionalString(json, "download_url")
    )
}

internal fun parseServerMetadata(body: String): ServerMetadata {
    val json = JSONObject(body)

    return ServerMetadata(
        operator = optionalString(json, "operator"),
        publicUrl = optionalString(json, "public_url"),
        contactEmail = optionalString(json, "contact_email"),
        serverBuildId = optionalString(json, "server_build_id"),
        serverBuildNumber = optionalInt(json, "server_build_number"),
        serverBuildType = optionalString(json, "server_build_type"),
        recommendedClientVersionCode = optionalInt(json, "recommended_client_version_code"),
        minClientVersionCode = optionalInt(json, "min_client_version_code"),
        productionRelease = parseProductionRelease(optionalObject(json, "production_release")),
        directDownloadRelease = parseDirectDownloadRelease(
            optionalObject(json, "direct_download_release")
        )
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
