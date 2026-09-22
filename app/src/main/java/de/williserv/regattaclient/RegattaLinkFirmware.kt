package de.williserv.regattaclient

import java.security.MessageDigest

internal const val REGATTALINK_FIRMWARE_MAX_BYTES = 4 * 1024 * 1024
private const val REGATTALINK_ESP_APP_DESC_OFFSET = 32
private const val REGATTALINK_ESP_APP_DESC_MAGIC = 0xABCD5432u
private const val REGATTALINK_PRODUCT = "RegattaLink"
private const val REGATTALINK_TARGET = "esp32c3"
private const val REGATTALINK_HARDWARE_PROFILE = "esp32c3-wroom02-4mb"
private const val REGATTALINK_FILENAME = "regattalink.bin"
internal const val REGATTALINK_DOWNLOAD_URL = "/regattalink/firmware"

data class RegattaLinkFirmwareManifest(
    val schemaVersion: Int,
    val product: String,
    val target: String,
    val hardwareProfile: String,
    val buildNumber: ULong,
    val filename: String,
    val size: Int,
    val sha256: String,
    val signed: Boolean,
    val signingKeySha256: String?,
    val downloadUrl: String
)

data class RegattaLinkFirmwareArtifact(
    val manifest: RegattaLinkFirmwareManifest,
    val image: ByteArray
)

enum class RegattaLinkFirmwareDirection {
    UPGRADE,
    DOWNGRADE,
    REINSTALL
}

internal fun validateRegattaLinkFirmwareManifest(manifest: RegattaLinkFirmwareManifest) {
    require(manifest.schemaVersion == 1) { "Unsupported firmware manifest schema" }
    require(manifest.product == REGATTALINK_PRODUCT) { "Firmware product mismatch" }
    require(manifest.target == REGATTALINK_TARGET) { "Firmware target mismatch" }
    require(manifest.hardwareProfile == REGATTALINK_HARDWARE_PROFILE) {
        "Unsupported firmware hardware profile"
    }
    require(manifest.buildNumber > 0uL) { "Firmware build number must be positive" }
    require(manifest.filename == REGATTALINK_FILENAME) { "Unexpected firmware filename" }
    require(manifest.size in 1..REGATTALINK_FIRMWARE_MAX_BYTES) {
        "Firmware size is invalid"
    }
    require(Regex("^[0-9a-f]{64}$").matches(manifest.sha256)) {
        "Firmware SHA-256 is invalid"
    }
    require(manifest.downloadUrl == REGATTALINK_DOWNLOAD_URL) {
        "Unexpected firmware download URL"
    }

    if (manifest.signed) {
        require(
            manifest.signingKeySha256 != null &&
                Regex("^[0-9a-f]{64}$").matches(manifest.signingKeySha256)
        ) {
            "Signed firmware requires a signing-key fingerprint"
        }
    } else {
        require(manifest.signingKeySha256 == null) {
            "Unsigned firmware must not declare a signing-key fingerprint"
        }
    }
}

internal fun validateRegattaLinkFirmwareImage(
    manifest: RegattaLinkFirmwareManifest,
    image: ByteArray
) {
    require(image.size == manifest.size) {
        "Firmware file size does not match manifest"
    }

    val actualSha256 = MessageDigest.getInstance("SHA-256")
        .digest(image)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    require(actualSha256 == manifest.sha256) {
        "Firmware SHA-256 does not match manifest"
    }

    require(image.size >= 144 && (image[0].toInt() and 0xff) == 0xE9) {
        "Firmware is not an ESP application image"
    }
    require(readUInt32Le(image, REGATTALINK_ESP_APP_DESC_OFFSET) == REGATTALINK_ESP_APP_DESC_MAGIC) {
        "Firmware has no ESP application descriptor"
    }

    val version = readAsciiCString(
        image,
        REGATTALINK_ESP_APP_DESC_OFFSET + 16,
        32
    )
    val project = readAsciiCString(
        image,
        REGATTALINK_ESP_APP_DESC_OFFSET + 48,
        32
    )

    require(project == "regattalink") {
        "Unexpected ESP project name"
    }
    require(version.toULongOrNull() == manifest.buildNumber) {
        "Embedded firmware build does not match manifest"
    }
}

internal fun validateRegattaLinkFirmwareForDevice(
    manifest: RegattaLinkFirmwareManifest,
    deviceInfo: RegattaLinkDeviceInfo
): RegattaLinkFirmwareDirection {
    require(deviceInfo.productId == REGATTALINK_PRODUCT_ID) {
        "Connected RegattaLink product is incompatible"
    }
    require(deviceInfo.profileId == REGATTALINK_PROFILE_ID) {
        "Connected RegattaLink hardware profile is incompatible"
    }
    require(deviceInfo.otaAvailable) {
        "Connected RegattaLink does not support OTA"
    }
    require(manifest.size.toULong() <= deviceInfo.otaSlotSize.toULong()) {
        "Firmware does not fit the RegattaLink OTA slot"
    }
    if (deviceInfo.signatureVerification) {
        require(manifest.signed) {
            "This RegattaLink requires signed firmware"
        }
    }

    return when {
        manifest.buildNumber > deviceInfo.runningBuild -> RegattaLinkFirmwareDirection.UPGRADE
        manifest.buildNumber < deviceInfo.runningBuild -> RegattaLinkFirmwareDirection.DOWNGRADE
        else -> RegattaLinkFirmwareDirection.REINSTALL
    }
}

internal fun validateRegattaLinkFirmwareArtifact(
    manifest: RegattaLinkFirmwareManifest,
    image: ByteArray,
    deviceInfo: RegattaLinkDeviceInfo
): RegattaLinkFirmwareArtifact {
    validateRegattaLinkFirmwareManifest(manifest)
    validateRegattaLinkFirmwareImage(manifest, image)
    validateRegattaLinkFirmwareForDevice(manifest, deviceInfo)
    return RegattaLinkFirmwareArtifact(manifest, image)
}

private fun readUInt32Le(bytes: ByteArray, offset: Int): UInt {
    require(offset >= 0 && offset + 4 <= bytes.size)
    return (
        (bytes[offset].toUInt() and 0xffu) or
            ((bytes[offset + 1].toUInt() and 0xffu) shl 8) or
            ((bytes[offset + 2].toUInt() and 0xffu) shl 16) or
            ((bytes[offset + 3].toUInt() and 0xffu) shl 24)
        )
}

private fun readAsciiCString(bytes: ByteArray, offset: Int, maxLength: Int): String {
    require(offset >= 0 && offset + maxLength <= bytes.size)
    val end = (offset until offset + maxLength)
        .firstOrNull { bytes[it] == 0.toByte() }
        ?: (offset + maxLength)
    val raw = bytes.copyOfRange(offset, end)
    require(raw.all { (it.toInt() and 0xff) < 0x80 }) {
        "ESP application descriptor contains non-ASCII text"
    }
    return raw.toString(Charsets.US_ASCII)
}
