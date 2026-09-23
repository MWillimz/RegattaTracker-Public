package de.williserv.regattaclient

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class RegattaLinkFirmwareStatus {
    IDLE,
    LOADING,
    READY,
    ERROR
}

data class RegattaLinkFirmwareUiState(
    val status: RegattaLinkFirmwareStatus = RegattaLinkFirmwareStatus.IDLE,
    val availableBuild: String = "",
    val direction: RegattaLinkFirmwareDirection? = null,
    val signed: Boolean = false,
    val error: String = ""
)

internal fun versionedRegattaLinkFirmwareDownloadUrl(
    rawUrl: String,
    buildNumber: ULong
): String {
    val separator = if (rawUrl.contains("?")) "&" else "?"
    return "$rawUrl${separator}v=$buildNumber"
}

class RegattaLinkFirmwareClient {
    fun load(
        serverUrl: String,
        deviceInfo: RegattaLinkDeviceInfo
    ): RegattaLinkFirmwareArtifact {
        val baseUrl = normalizeFirmwareServerUrl(serverUrl)
        val metadataJson = getJson("$baseUrl/regattalink/firmware/metadata")
        val manifest = parseManifest(metadataJson)

        validateRegattaLinkFirmwareManifest(manifest)
        val firmwareUrl = versionedRegattaLinkFirmwareDownloadUrl(
            baseUrl + manifest.downloadUrl,
            manifest.buildNumber
        )
        val image = getFirmwareBytes(firmwareUrl, manifest.size)
        return validateRegattaLinkFirmwareArtifact(manifest, image, deviceInfo)
    }

    private fun getJson(url: String): JSONObject {
        val connection = openGet(url, "application/json")
        return try {
            val body = readResponse(connection)
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun getFirmwareBytes(url: String, expectedSize: Int): ByteArray {
        require(expectedSize in 1..REGATTALINK_FIRMWARE_MAX_BYTES) {
            "Firmware size is invalid"
        }

        val connection = openGet(url, "application/octet-stream")
        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                val suffix = if (errorBody.isBlank()) "" else ": $errorBody"
                throw IllegalStateException(
                    "Firmware download failed ($responseCode)$suffix"
                )
            }

            val advertisedLength = connection.contentLengthLong
            if (advertisedLength > REGATTALINK_FIRMWARE_MAX_BYTES) {
                throw IllegalStateException("Firmware download is too large")
            }

            connection.inputStream.use { input ->
                val buffer = ByteArray(expectedSize + 1)
                var total = 0
                while (total < buffer.size) {
                    val count = input.read(buffer, total, buffer.size - total)
                    if (count < 0) break
                    total += count
                }
                if (total != expectedSize || input.read() >= 0) {
                    throw IllegalStateException(
                        "Firmware download size does not match manifest"
                    )
                }
                buffer.copyOf(expectedSize)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openGet(url: String, accept: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", accept)
        connection.useCaches = false
        return connection
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val responseCode = connection.responseCode
        val body = if (responseCode in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }
        if (responseCode !in 200..299) {
            val suffix = if (body.isBlank()) "" else ": $body"
            throw IllegalStateException(
                "Firmware metadata request failed ($responseCode)$suffix"
            )
        }
        return body
    }

    private fun parseManifest(json: JSONObject): RegattaLinkFirmwareManifest {
        val buildNumber = requireUnsignedInteger(json, "build_number")
        val size = requirePositiveInt(json, "size")
        val signedValue = json.opt("signed")
        require(signedValue is Boolean) {
            "Firmware manifest signed must be boolean"
        }

        val signingKey = when {
            !json.has("signing_key_sha256") || json.isNull("signing_key_sha256") -> null
            else -> json.getString("signing_key_sha256").lowercase()
        }

        return RegattaLinkFirmwareManifest(
            schemaVersion = json.getInt("schema_version"),
            product = json.getString("product"),
            target = json.getString("target"),
            hardwareProfile = json.getString("hardware_profile"),
            buildNumber = buildNumber,
            filename = json.getString("filename"),
            size = size,
            sha256 = json.getString("sha256").lowercase(),
            signed = signedValue,
            signingKeySha256 = signingKey,
            downloadUrl = json.getString("download_url")
        )
    }

    private fun requireUnsignedInteger(json: JSONObject, key: String): ULong {
        val value = json.get(key)
        if (value !is Number) {
            throw IllegalArgumentException(
                "Firmware manifest $key must be an integer"
            )
        }
        val text = value.toString()
        if (!Regex("^[0-9]+$").matches(text)) {
            throw IllegalArgumentException(
                "Firmware manifest $key must be an integer"
            )
        }
        return text.toULongOrNull()
            ?.takeIf { it > 0uL }
            ?: throw IllegalArgumentException(
                "Firmware manifest $key must be a positive u64"
            )
    }

    private fun requirePositiveInt(json: JSONObject, key: String): Int {
        val value = json.get(key)
        val text = value.toString()
        val longValue = if (value is Number && Regex("^[0-9]+$").matches(text)) {
            text.toLongOrNull()
        } else {
            null
        } ?: throw IllegalArgumentException(
            "Firmware manifest $key must be an integer"
        )
        require(longValue in 1..Int.MAX_VALUE.toLong()) {
            "Firmware manifest $key is out of range"
        }
        return longValue.toInt()
    }

    private fun normalizeFirmwareServerUrl(serverUrl: String): String {
        val normalized = normalizeServerBaseUrl(serverUrl)
        require(normalized.startsWith("https://")) {
            "A configured HTTPS Regatta Server is required for firmware updates"
        }
        return normalized
    }
}
