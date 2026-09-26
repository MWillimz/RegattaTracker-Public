package de.williserv.regattaclient

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val REGATTALINK_DIAGNOSTIC_LOG_RECORD_SIZE = 22
internal const val REGATTALINK_DIAGNOSTIC_LOG_MAX_READS = 20
internal const val REGATTALINK_DEVICE_CONTROL_REQUEST_SIZE = 8
internal const val REGATTALINK_DEVICE_CONTROL_STATUS_SIZE = 20
internal const val REGATTALINK_DEVICE_CONTROL_VERSION = 1
internal const val REGATTALINK_DEVICE_CONTROL_POLL_MS = 100L
internal const val REGATTALINK_DEVICE_CONTROL_CLIENT_TIMEOUT_MS = 12_000L

data class RegattaLinkDiagnosticLogEntry(
    val timestamp10ms: Int,
    val message: String
)

enum class RegattaLinkDeviceControlOpcode(val wireValue: Int) {
    SET_UPRIGHT(1),
    ADJUST_FORWARD(2),
    ADJUST_HEEL(3),
    ADJUST_PITCH(4),
    FACTORY_RESET(5);

    companion object {
        fun fromWire(value: Int): RegattaLinkDeviceControlOpcode? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class RegattaLinkDeviceControlPhase(val wireValue: Int) {
    IDLE(0),
    PENDING(1),
    CAPTURING(2),
    PERSISTING(3),
    SUCCESS(4),
    ERROR(5);

    val isTerminal: Boolean
        get() = this == SUCCESS || this == ERROR

    companion object {
        fun fromWire(value: Int): RegattaLinkDeviceControlPhase? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class RegattaLinkDeviceControlResult(val wireValue: Int) {
    NONE(0),
    OK(1),
    BUSY(2),
    INVALID(3),
    MOTION_REJECT(4),
    ORIENTATION_REJECT(5),
    PERSIST_ERROR(6),
    CONFIG_ERROR(7),
    BOND_RESET_ERROR(8),
    INTERNAL_ERROR(9),
    TIMEOUT(10);

    companion object {
        fun fromWire(value: Int): RegattaLinkDeviceControlResult? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class RegattaLinkDeviceControlStatus(
    val opcode: RegattaLinkDeviceControlOpcode?,
    val phase: RegattaLinkDeviceControlPhase,
    val result: RegattaLinkDeviceControlResult,
    val requestId: UInt,
    val forwardTrimDeg: Int,
    val heelTrimDeg: Int,
    val pitchTrimDeg: Int,
    val boatFrameValid: Boolean,
    val gyroBiasValid: Boolean,
    val mountingEpoch: UInt
)

internal enum class RegattaLinkDeviceControlPollDecision {
    IGNORE_OTHER_REQUEST,
    CONTINUE,
    SUCCESS,
    FAILURE
}

internal fun regattaLinkDeviceControlPollDecision(
    status: RegattaLinkDeviceControlStatus,
    requestId: UInt
): RegattaLinkDeviceControlPollDecision = when {
    status.requestId != requestId ->
        RegattaLinkDeviceControlPollDecision.IGNORE_OTHER_REQUEST
    !status.phase.isTerminal ->
        RegattaLinkDeviceControlPollDecision.CONTINUE
    status.phase == RegattaLinkDeviceControlPhase.SUCCESS &&
        status.result == RegattaLinkDeviceControlResult.OK ->
        RegattaLinkDeviceControlPollDecision.SUCCESS
    else ->
        RegattaLinkDeviceControlPollDecision.FAILURE
}

internal fun parseRegattaLinkDiagnosticLogEntry(
    raw: ByteArray
): RegattaLinkDiagnosticLogEntry? {
    if (raw.isEmpty()) return null
    require(raw.size == REGATTALINK_DIAGNOSTIC_LOG_RECORD_SIZE) {
        "RegattaLink diagnostic record must be $REGATTALINK_DIAGNOSTIC_LOG_RECORD_SIZE bytes"
    }
    val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    val timestamp = buffer.getShort(0).toInt() and 0xffff
    val messageBytes = raw.copyOfRange(2, raw.size)
    val zeroIndex = messageBytes.indexOf(0)
    val used = if (zeroIndex >= 0) {
        messageBytes.copyOfRange(0, zeroIndex)
    } else {
        messageBytes
    }
    require(used.all { byte ->
        val value = byte.toInt() and 0xff
        value in 0x20..0x7e
    }) {
        "RegattaLink diagnostic message contains non-ASCII bytes"
    }
    return RegattaLinkDiagnosticLogEntry(
        timestamp10ms = timestamp,
        message = used.toString(Charsets.US_ASCII)
    )
}

internal fun buildRegattaLinkDeviceControlRequest(
    opcode: RegattaLinkDeviceControlOpcode,
    requestId: UInt,
    value: Int
): ByteArray {
    require(requestId != 0u) { "RegattaLink Device Control request ID must be non-zero" }
    require(value in Short.MIN_VALUE..Short.MAX_VALUE) {
        "RegattaLink Device Control value is outside int16 range"
    }
    if (
        opcode == RegattaLinkDeviceControlOpcode.SET_UPRIGHT ||
        opcode == RegattaLinkDeviceControlOpcode.FACTORY_RESET
    ) {
        require(value == 0) { "$opcode requires value 0" }
    }

    return ByteBuffer.allocate(REGATTALINK_DEVICE_CONTROL_REQUEST_SIZE)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(REGATTALINK_DEVICE_CONTROL_VERSION.toByte())
        .put(opcode.wireValue.toByte())
        .putInt(requestId.toInt())
        .putShort(value.toShort())
        .array()
}

internal fun parseRegattaLinkDeviceControlStatus(
    raw: ByteArray
): RegattaLinkDeviceControlStatus {
    require(raw.size == REGATTALINK_DEVICE_CONTROL_STATUS_SIZE) {
        "RegattaLink Device Control status must be $REGATTALINK_DEVICE_CONTROL_STATUS_SIZE bytes"
    }
    val version = raw[0].toInt() and 0xff
    require(version == REGATTALINK_DEVICE_CONTROL_VERSION) {
        "Unsupported RegattaLink Device Control version $version"
    }

    val opcodeValue = raw[1].toInt() and 0xff
    val phaseValue = raw[2].toInt() and 0xff
    val resultValue = raw[3].toInt() and 0xff
    val phase = RegattaLinkDeviceControlPhase.fromWire(phaseValue)
        ?: throw IllegalArgumentException(
            "Unsupported RegattaLink Device Control state $phaseValue"
        )
    val result = RegattaLinkDeviceControlResult.fromWire(resultValue)
        ?: throw IllegalArgumentException(
            "Unsupported RegattaLink Device Control result $resultValue"
        )
    val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    val flags = raw[14].toInt() and 0xff
    require(flags and 0xfc == 0) {
        "Unsupported RegattaLink Device Control flags"
    }
    require(raw[15].toInt() == 0) {
        "Invalid RegattaLink Device Control reserved byte"
    }

    return RegattaLinkDeviceControlStatus(
        opcode = if (opcodeValue == 0) {
            null
        } else {
            RegattaLinkDeviceControlOpcode.fromWire(opcodeValue)
                ?: throw IllegalArgumentException(
                    "Unsupported RegattaLink Device Control opcode $opcodeValue"
                )
        },
        phase = phase,
        result = result,
        requestId = buffer.getInt(4).toUInt(),
        forwardTrimDeg = buffer.getShort(8).toInt(),
        heelTrimDeg = buffer.getShort(10).toInt(),
        pitchTrimDeg = buffer.getShort(12).toInt(),
        boatFrameValid = flags and 0x01 != 0,
        gyroBiasValid = flags and 0x02 != 0,
        mountingEpoch = buffer.getInt(16).toUInt()
    )
}

internal fun regattaLinkDeviceControlFailureText(
    result: RegattaLinkDeviceControlResult
): String = when (result) {
    RegattaLinkDeviceControlResult.NONE ->
        "RegattaLink Device Control ended without a result"
    RegattaLinkDeviceControlResult.OK ->
        ""
    RegattaLinkDeviceControlResult.BUSY ->
        "RegattaLink Device Control is busy"
    RegattaLinkDeviceControlResult.INVALID ->
        "RegattaLink rejected the Device Control request"
    RegattaLinkDeviceControlResult.MOTION_REJECT ->
        "RegattaLink calibration rejected because the boat moved"
    RegattaLinkDeviceControlResult.ORIENTATION_REJECT ->
        "RegattaLink calibration rejected because the orientation is invalid"
    RegattaLinkDeviceControlResult.PERSIST_ERROR ->
        "RegattaLink could not persist calibration"
    RegattaLinkDeviceControlResult.CONFIG_ERROR ->
        "RegattaLink could not reset configuration"
    RegattaLinkDeviceControlResult.BOND_RESET_ERROR ->
        "RegattaLink could not reset Bluetooth bonds"
    RegattaLinkDeviceControlResult.INTERNAL_ERROR ->
        "RegattaLink Device Control failed internally"
    RegattaLinkDeviceControlResult.TIMEOUT ->
        "RegattaLink calibration timed out"
}
