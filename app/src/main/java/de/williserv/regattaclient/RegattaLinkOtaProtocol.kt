package de.williserv.regattaclient

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID
import kotlin.math.min

internal val REGATTALINK_OTA_SERVICE_UUID: UUID =
    UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b710010")
internal val REGATTALINK_OTA_CONTROL_UUID: UUID =
    UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b710011")
internal val REGATTALINK_OTA_DATA_UUID: UUID =
    UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b710012")
internal val REGATTALINK_OTA_STATUS_UUID: UUID =
    UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b710013")

internal const val REGATTALINK_OTA_METADATA_SIZE = 48
internal const val REGATTALINK_OTA_STATUS_SIZE = 44
internal const val REGATTALINK_OTA_PROGRESS_SIZE = 20
internal const val REGATTALINK_OTA_MAX_VALUE_SIZE = 244
internal const val REGATTALINK_OTA_MAX_INFLIGHT_BLOCKS = 32
internal const val REGATTALINK_OTA_INITIAL_WINDOW = 4
internal const val REGATTALINK_OTA_SLOW_LINK_THROUGHPUT_KIB_S = 10.0
internal const val REGATTALINK_OTA_MIN_THROUGHPUT_KIB_S = 4.0
internal const val REGATTALINK_OTA_THROUGHPUT_SAMPLE_MS = 3_000L
internal const val REGATTALINK_OTA_THROUGHPUT_SAMPLE_BYTES = 64 * 1024

internal enum class RegattaLinkOtaDeviceState(val wireValue: Int) {
    IDLE(0),
    PREPARING(1),
    RECEIVING(2),
    VERIFYING(3),
    READY_TO_REBOOT(4),
    ERROR(5);

    companion object {
        fun fromWire(value: Int): RegattaLinkOtaDeviceState =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown OTA state $value")
    }
}

internal enum class RegattaLinkOtaBootResult(val wireValue: Int) {
    UNKNOWN(0),
    VALIDATED(1),
    PENDING_VERIFY(2),
    ROLLBACK(3);

    companion object {
        fun fromWire(value: Int): RegattaLinkOtaBootResult =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown OTA boot result $value")
    }
}

internal data class RegattaLinkOtaProgress(
    val revision: UInt,
    val session: UInt,
    val acceptedOffset: UInt,
    val totalSize: UInt,
    val state: RegattaLinkOtaDeviceState,
    val error: Int,
    val maxDataPayload: Int
)

internal data class RegattaLinkOtaStatus(
    val revision: UInt,
    val session: UInt,
    val acceptedOffset: UInt,
    val totalSize: UInt,
    val state: RegattaLinkOtaDeviceState,
    val error: Int,
    val maxDataPayload: Int,
    val requestId: UInt,
    val runningBuild: ULong,
    val targetBuild: ULong,
    val bootResult: RegattaLinkOtaBootResult,
    val assembling: Boolean,
    val assembledBytes: Int
)

internal fun regattaLinkOtaErrorName(code: Int): String = when (code) {
    0 -> "OK"
    1 -> "INVALID_LENGTH"
    2 -> "NOT_SUPPORTED"
    3 -> "BUSY"
    4 -> "BAD_STATE"
    5 -> "BAD_REQUEST"
    6 -> "BAD_OFFSET"
    7 -> "BAD_SESSION"
    8 -> "BAD_HARDWARE"
    9 -> "BAD_SIZE"
    10 -> "TIMEOUT"
    11 -> "HASH_MISMATCH"
    12 -> "IMAGE_MISMATCH"
    13 -> "SIGNATURE"
    14 -> "FLASH"
    15 -> "CANCELLED"
    16 -> "ROLLBACK"
    else -> "UNKNOWN($code)"
}

internal fun isRegattaLinkOtaRevisionNewer(candidate: UInt, baseline: UInt): Boolean {
    if (candidate == baseline) return false

    // Revisions are uint32 counters on the wire. Interpret the modular delta as
    // a signed value so the natural wrap from UInt.MAX_VALUE to 0 stays newer.
    // A legitimate observer cannot fall behind by half the uint32 range.
    return (candidate - baseline).toInt() > 0
}

internal fun newRegattaLinkOtaRequestId(random: SecureRandom = SecureRandom()): UInt {
    while (true) {
        val value = random.nextInt().toUInt()
        if (value != 0u) return value
    }
}

internal fun encodeRegattaLinkOtaMetadata(
    artifact: RegattaLinkFirmwareArtifact
): ByteArray {
    val manifest = artifact.manifest
    val sha = manifest.sha256.hexToByteArrayStrict()
    require(sha.size == 32)
    require(manifest.size > 0)
    require(manifest.buildNumber > 0uL)

    return ByteBuffer.allocate(REGATTALINK_OTA_METADATA_SIZE)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(REGATTALINK_PRODUCT_ID.toShort())
        .putShort(REGATTALINK_PROFILE_ID.toShort())
        .putInt(manifest.size)
        .putLong(manifest.buildNumber.toLong())
        .put(sha)
        .array()
}

internal fun regattaLinkOtaControlCapacity(mtu: Int): Int {
    require(mtu >= 23) { "Invalid ATT MTU $mtu" }
    return min(mtu - 3, REGATTALINK_OTA_MAX_VALUE_SIZE)
}

internal fun encodeRegattaLinkOtaStartBegin(requestId: UInt): ByteArray {
    require(requestId != 0u)
    return ByteBuffer.allocate(9)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(0x01)
        .putInt(requestId.toInt())
        .put(REGATTALINK_PROTOCOL_MAJOR.toByte())
        .put(0)
        .putShort(REGATTALINK_OTA_METADATA_SIZE.toShort())
        .array()
}

internal fun encodeRegattaLinkOtaStartFragments(
    requestId: UInt,
    metadata: ByteArray,
    mtu: Int
): List<ByteArray> {
    require(requestId != 0u)
    require(metadata.size == REGATTALINK_OTA_METADATA_SIZE)
    val payloadSize = regattaLinkOtaControlCapacity(mtu) - 7
    require(payloadSize > 0) { "ATT MTU is too small for START_FRAGMENT" }

    return buildList {
        var offset = 0
        while (offset < metadata.size) {
            val count = min(payloadSize, metadata.size - offset)
            val value = ByteBuffer.allocate(7 + count)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(0x02)
                .putInt(requestId.toInt())
                .putShort(offset.toShort())
                .put(metadata, offset, count)
                .array()
            add(value)
            offset += count
        }
    }
}

internal fun encodeRegattaLinkOtaStartCommit(requestId: UInt): ByteArray {
    require(requestId != 0u)
    return ByteBuffer.allocate(5)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(0x03)
        .putInt(requestId.toInt())
        .array()
}

internal fun encodeRegattaLinkOtaFinish(session: UInt): ByteArray {
    require(session != 0u)
    return ByteBuffer.allocate(5)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(0x04)
        .putInt(session.toInt())
        .array()
}

internal fun encodeRegattaLinkOtaAbort(requestId: UInt, session: UInt): ByteArray {
    require(requestId != 0u)
    return ByteBuffer.allocate(9)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(0x05)
        .putInt(requestId.toInt())
        .putInt(session.toInt())
        .array()
}

internal fun encodeRegattaLinkOtaSnapshot(): ByteArray = byteArrayOf(0x06)

internal fun encodeRegattaLinkOtaData(
    session: UInt,
    offset: Int,
    payload: ByteArray
): ByteArray {
    require(session != 0u)
    require(offset >= 0)
    require(payload.isNotEmpty())
    return ByteBuffer.allocate(8 + payload.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(session.toInt())
        .putInt(offset)
        .put(payload)
        .array()
}

internal fun parseRegattaLinkOtaProgress(raw: ByteArray): RegattaLinkOtaProgress {
    require(raw.size == REGATTALINK_OTA_PROGRESS_SIZE) {
        "OTA status notification must be $REGATTALINK_OTA_PROGRESS_SIZE bytes, got " + raw.size
    }
    val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    return RegattaLinkOtaProgress(
        revision = buffer.getInt(0).toUInt(),
        session = buffer.getInt(4).toUInt(),
        acceptedOffset = buffer.getInt(8).toUInt(),
        totalSize = buffer.getInt(12).toUInt(),
        state = RegattaLinkOtaDeviceState.fromWire(raw[16].toInt() and 0xff),
        error = raw[17].toInt() and 0xff,
        maxDataPayload = buffer.getShort(18).toInt() and 0xffff
    )
}

internal fun isRegattaLinkPostBootValidated(
    status: RegattaLinkOtaStatus,
    targetBuild: ULong
): Boolean =
    status.runningBuild == targetBuild &&
        status.bootResult == RegattaLinkOtaBootResult.VALIDATED

internal fun parseRegattaLinkOtaStatus(raw: ByteArray): RegattaLinkOtaStatus {
    require(raw.size == REGATTALINK_OTA_STATUS_SIZE) {
        "Full OTA status must be $REGATTALINK_OTA_STATUS_SIZE bytes, got " + raw.size
    }
    val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    return RegattaLinkOtaStatus(
        revision = buffer.getInt(0).toUInt(),
        session = buffer.getInt(4).toUInt(),
        acceptedOffset = buffer.getInt(8).toUInt(),
        totalSize = buffer.getInt(12).toUInt(),
        state = RegattaLinkOtaDeviceState.fromWire(raw[16].toInt() and 0xff),
        error = raw[17].toInt() and 0xff,
        maxDataPayload = buffer.getShort(18).toInt() and 0xffff,
        requestId = buffer.getInt(20).toUInt(),
        runningBuild = buffer.getLong(24).toULong(),
        targetBuild = buffer.getLong(32).toULong(),
        bootResult = RegattaLinkOtaBootResult.fromWire(raw[40].toInt() and 0xff),
        assembling = raw[41].toInt() != 0,
        assembledBytes = buffer.getShort(42).toInt() and 0xffff
    )
}

internal fun validateRegattaLinkOtaDevice(
    info: RegattaLinkDeviceInfo,
    artifact: RegattaLinkFirmwareArtifact
) {
    validateRegattaLinkFirmwareForDevice(artifact.manifest, info)
    require(info.otaPipelinedData) {
        "RegattaLink firmware does not support required pipelined OTA DATA"
    }
    require(info.maxInflightBlocks in 2..REGATTALINK_OTA_MAX_INFLIGHT_BLOCKS) {
        "Invalid RegattaLink OTA receive window " + info.maxInflightBlocks
    }
    require(info.otaStatusSnapshot) {
        "RegattaLink firmware does not support required OTA status snapshots"
    }
    require(info.otaDataWriteWithoutResponse || info.otaDataWriteWithResponse) {
        "RegattaLink firmware advertises no supported OTA DATA transport"
    }
}

internal fun validateRegattaLinkCommittedOffset(
    previousOffset: Int,
    candidateOffset: Int,
    lastAdmittedOffset: Int,
    admittedBoundaries: Collection<Int>
) {
    require(candidateOffset >= previousOffset) {
        "Non-monotonic OTA committed offset $candidateOffset after $previousOffset"
    }
    require(candidateOffset <= lastAdmittedOffset) {
        "OTA committed offset $candidateOffset exceeds admitted host data $lastAdmittedOffset"
    }
    if (candidateOffset != previousOffset && candidateOffset != 0) {
        require(candidateOffset in admittedBoundaries) {
            "OTA committed offset $candidateOffset is not an admitted block boundary"
        }
    }
}

private fun String.hexToByteArrayStrict(): ByteArray {
    require(length % 2 == 0)
    require(Regex("^[0-9a-fA-F]+$").matches(this))
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
