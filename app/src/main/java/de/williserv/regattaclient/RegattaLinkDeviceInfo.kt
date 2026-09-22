package de.williserv.regattaclient

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val REGATTALINK_DEVICE_INFO_SIZE = 32
internal const val REGATTALINK_PROTOCOL_MAJOR = 1
internal const val REGATTALINK_PRODUCT_ID = 1
internal const val REGATTALINK_PROFILE_ID = 1

private const val CAP_OTA_AVAILABLE: UInt = 0x00000002u

data class RegattaLinkDeviceInfo(
    val protocolMajor: Int,
    val protocolMinor: Int,
    val capabilities: UInt,
    val stableId: String,
    val productId: Int,
    val profileId: Int,
    val runningBuild: ULong,
    val otaSlotSize: UInt,
    val maxInflightBlocks: Int
) {
    val otaAvailable: Boolean
        get() = capabilities and CAP_OTA_AVAILABLE != 0u
}

internal fun parseRegattaLinkDeviceInfo(raw: ByteArray): RegattaLinkDeviceInfo {
    require(raw.size == REGATTALINK_DEVICE_INFO_SIZE) {
        "Device Info must be $REGATTALINK_DEVICE_INFO_SIZE bytes, got ${raw.size}"
    }

    val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    val protocolMajor = raw[0].toInt() and 0xff
    val protocolMinor = raw[1].toInt() and 0xff
    val recordSize = buffer.getShort(2).toInt() and 0xffff
    require(recordSize == REGATTALINK_DEVICE_INFO_SIZE) {
        "Unsupported Device Info record size $recordSize"
    }

    val stableId = raw.copyOfRange(8, 14)
        .joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    return RegattaLinkDeviceInfo(
        protocolMajor = protocolMajor,
        protocolMinor = protocolMinor,
        capabilities = buffer.getInt(4).toUInt(),
        stableId = stableId,
        productId = buffer.getShort(14).toInt() and 0xffff,
        profileId = buffer.getShort(16).toInt() and 0xffff,
        runningBuild = buffer.getLong(18).toULong(),
        otaSlotSize = buffer.getInt(26).toUInt(),
        maxInflightBlocks = buffer.getShort(30).toInt() and 0xffff
    )
}

internal fun validateRegattaLinkDeviceInfo(info: RegattaLinkDeviceInfo): String? {
    if (info.protocolMajor != REGATTALINK_PROTOCOL_MAJOR) {
        return "Unsupported RegattaLink protocol ${info.protocolMajor}.${info.protocolMinor}"
    }
    if (info.productId != REGATTALINK_PRODUCT_ID) {
        return "Unexpected RegattaLink product ${info.productId}"
    }
    if (info.profileId != REGATTALINK_PROFILE_ID) {
        return "Unsupported RegattaLink hardware profile ${info.profileId}"
    }
    return null
}
