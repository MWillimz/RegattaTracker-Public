package de.williserv.regattaclient

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val REGATTALINK_DIAGNOSTIC_LOG_RECORD_SIZE = 22
internal const val REGATTALINK_DIAGNOSTIC_LOG_MAX_READS = 20
internal const val REGATTALINK_DEVICE_CONTROL_REQUEST_SIZE = 8
internal const val REGATTALINK_DEVICE_CONTROL_STATUS_SIZE = 20
internal const val REGATTALINK_DEVICE_CONTROL_VERSION = 1
internal const val REGATTALINK_DEVICE_CONTROL_POLL_MS = 100L
internal const val REGATTALINK_DEVICE_CONTROL_CLIENT_TIMEOUT_MS = 12_000L
internal const val REGATTALINK_FACTORY_RESET_FINALIZATION_TIMEOUT_MS = 10_000L
internal const val REGATTALINK_FACTORY_RESET_DISCONNECT_MARGIN_MS = 20_000L

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

enum class RegattaLinkTrimDirection {
    PORT,
    STARBOARD,
    FRONT,
    BACK
}

internal fun regattaLinkTrimDelta(
    opcode: RegattaLinkDeviceControlOpcode,
    direction: RegattaLinkTrimDirection
): Int = when (opcode) {
    RegattaLinkDeviceControlOpcode.ADJUST_FORWARD -> when (direction) {
        RegattaLinkTrimDirection.PORT -> -1
        RegattaLinkTrimDirection.STARBOARD -> 1
        else -> error("Forward trim only supports port/starboard")
    }
    RegattaLinkDeviceControlOpcode.ADJUST_HEEL -> when (direction) {
        RegattaLinkTrimDirection.PORT -> 1
        RegattaLinkTrimDirection.STARBOARD -> -1
        else -> error("Heel trim only supports port/starboard")
    }
    RegattaLinkDeviceControlOpcode.ADJUST_PITCH -> when (direction) {
        RegattaLinkTrimDirection.FRONT -> 1
        RegattaLinkTrimDirection.BACK -> -1
        else -> error("Pitch trim only supports front/back")
    }
    else -> error("Opcode $opcode is not a trim command")
}

internal fun formatRegattaLinkDirectionalTrim(
    valueDeg: Int?,
    positiveDirectionLabel: String,
    negativeDirectionLabel: String
): String = when {
    valueDeg == null -> "—"
    valueDeg == 0 -> "0°"
    valueDeg > 0 -> "${valueDeg}° $positiveDirectionLabel"
    else -> "${-valueDeg}° $negativeDirectionLabel"
}

internal fun roundRegattaLinkUserFacingDegrees(
    valueDeg: Double
): Int {
    val magnitude = abs(valueDeg).roundToInt()
    return if (valueDeg < 0.0) -magnitude else magnitude
}

internal fun formatRegattaLinkWholeDegreeAngle(
    valueDeg: Double
): String = "${roundRegattaLinkUserFacingDegrees(valueDeg)}°"

internal fun formatRegattaLinkDirectionalMeasurement(
    valueDeg: Double?,
    positiveDirectionLabel: String,
    negativeDirectionLabel: String
): String {
    if (valueDeg == null) return "—"

    val rounded = roundRegattaLinkUserFacingDegrees(valueDeg)
    if (rounded == 0) return "0°"

    val direction =
        if (rounded > 0) positiveDirectionLabel else negativeDirectionLabel

    return "${abs(rounded)}° $direction"
}

internal fun regattaLinkShouldShowManualOrientationControls(
    boatFramePresentation: RegattaLinkBoatFramePresentation
): Boolean =
    boatFramePresentation == RegattaLinkBoatFramePresentation.READY

internal enum class RegattaLinkBoatFramePresentation {
    UNKNOWN,
    PENDING,
    READY,
    NOT_SET
}

internal fun regattaLinkBoatFramePresentation(
    status: RegattaLinkDeviceControlStatus?,
    deviceControlBusy: Boolean
): RegattaLinkBoatFramePresentation = when {
    status?.boatFrameValid == true -> RegattaLinkBoatFramePresentation.READY
    status != null -> RegattaLinkBoatFramePresentation.NOT_SET
    deviceControlBusy -> RegattaLinkBoatFramePresentation.PENDING
    else -> RegattaLinkBoatFramePresentation.UNKNOWN
}

internal fun regattaLinkOrientationControlsEnabled(
    baseControlsEnabled: Boolean,
    boatFrameValid: Boolean,
    deviceControlBusy: Boolean
): Boolean =
    baseControlsEnabled &&
        boatFrameValid &&
        !deviceControlBusy

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
    val mountingEpoch: UInt,
    val factoryResetBondsCleared: Boolean = false,
    val applicationErrorCode: Int? = null
)

internal fun regattaLinkFactoryResetContinuesToBondReset(
    status: RegattaLinkDeviceControlStatus
): Boolean =
    status.opcode == RegattaLinkDeviceControlOpcode.FACTORY_RESET &&
        status.phase.isTerminal &&
        when (status.result) {
            RegattaLinkDeviceControlResult.OK,
            RegattaLinkDeviceControlResult.MOTION_REJECT,
            RegattaLinkDeviceControlResult.ORIENTATION_REJECT -> true
            else -> false
        }

internal fun <T> afterRegattaLinkGattSubmissionAccepted(
    onSubmitted: (() -> Unit)?,
    awaitResponse: () -> T
): T {
    onSubmitted?.invoke()
    return awaitResponse()
}

internal fun <T : Any> consumeRegattaLinkFactoryResetFallback(
    session: T,
    sessionStillCurrent: Boolean,
    tracker: RegattaLinkFactoryResetDisconnectTracker<T>
): Boolean =
    sessionStillCurrent && tracker.consumeDisconnect(session)

internal class RegattaLinkFactoryResetDisconnectTracker<T : Any>(
    private val nowElapsedMs: () -> Long,
    private val expectedDisconnectTimeoutMs: Long
) {
    private data class Expected<T : Any>(
        val session: T,
        val requestId: UInt,
        val deadlineElapsedMs: Long
    )

    private val expected = AtomicReference<Expected<T>?>(null)

    fun markAccepted(session: T, requestId: UInt) {
        require(requestId != 0u)
        expected.set(
            Expected(
                session = session,
                requestId = requestId,
                deadlineElapsedMs = nowElapsedMs() + expectedDisconnectTimeoutMs
            )
        )
    }

    fun clear(session: T, requestId: UInt) {
        while (true) {
            val current = expected.get() ?: return
            if (current.session !== session || current.requestId != requestId) return
            if (expected.compareAndSet(current, null)) return
        }
    }

    fun clearAll() {
        expected.set(null)
    }

    fun consumeDisconnect(session: T): Boolean {
        while (true) {
            val current = expected.get() ?: return false
            if (current.session !== session) return false
            if (nowElapsedMs() > current.deadlineElapsedMs) {
                if (expected.compareAndSet(current, null)) return false
                continue
            }
            if (expected.compareAndSet(current, null)) return true
        }
    }

    fun ownsLifecycle(session: T): Boolean {
        while (true) {
            val current = expected.get() ?: return false
            if (current.session !== session) return false
            if (nowElapsedMs() <= current.deadlineElapsedMs) return true
            if (expected.compareAndSet(current, null)) return false
        }
    }

    internal fun isExpected(session: T, requestId: UInt): Boolean {
        val current = expected.get() ?: return false
        return current.session === session &&
            current.requestId == requestId &&
            nowElapsedMs() <= current.deadlineElapsedMs
    }
}

internal fun regattaLinkDeviceControlStatusConfirmsAcceptance(
    status: RegattaLinkDeviceControlStatus,
    requestId: UInt
): Boolean =
    status.requestId == requestId &&
        status.applicationErrorCode == null &&
        status.result != RegattaLinkDeviceControlResult.BUSY &&
        status.result != RegattaLinkDeviceControlResult.INVALID

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
    require(flags and 0xf8 == 0) {
        "Unsupported RegattaLink Device Control flags"
    }
    if (flags and 0x04 != 0) {
        require(
            opcodeValue == RegattaLinkDeviceControlOpcode.FACTORY_RESET.wireValue &&
                phase.isTerminal
        ) {
            "Factory Reset bonds-cleared flag requires terminal Factory Reset status"
        }
    }
    val applicationErrorCode =
        (raw[15].toInt() and 0xff).takeIf { it != 0 }
    if (applicationErrorCode != null) {
        require(
            phase == RegattaLinkDeviceControlPhase.ERROR &&
                result in setOf(
                    RegattaLinkDeviceControlResult.BUSY,
                    RegattaLinkDeviceControlResult.INVALID
                )
        ) {
            "Invalid RegattaLink Device Control rejection detail"
        }
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
        mountingEpoch = buffer.getInt(16).toUInt(),
        factoryResetBondsCleared = flags and 0x04 != 0,
        applicationErrorCode = applicationErrorCode
    )
}

internal fun regattaLinkDeviceControlFailureText(
    status: RegattaLinkDeviceControlStatus
): String = when (status.applicationErrorCode) {
    2 -> "RegattaLink does not support this Device Control request"
    3 -> "RegattaLink Device Control is busy"
    4 -> "RegattaLink is not ready for this Device Control request"
    5 -> "RegattaLink rejected the Device Control request"
    null -> regattaLinkDeviceControlFailureText(status.result)
    else -> "RegattaLink rejected Device Control (application error ${status.applicationErrorCode})"
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


internal fun regattaLinkDeviceControlUiMessage(
    status: RegattaLinkDeviceControlStatus?,
    hasError: Boolean
): RegattaLinkUiMessage? {
    if (!hasError) return null
    if (status?.phase != RegattaLinkDeviceControlPhase.ERROR) {
        return RegattaLinkUiMessage.CONFIGURATION_FAILED
    }

    return when (status.applicationErrorCode) {
        2 -> RegattaLinkUiMessage.DEVICE_CONTROL_UNSUPPORTED
        3 -> RegattaLinkUiMessage.DEVICE_CONTROL_BUSY
        4 -> RegattaLinkUiMessage.DEVICE_CONTROL_NOT_READY
        5 -> RegattaLinkUiMessage.DEVICE_CONTROL_REJECTED
        null -> when (status.result) {
            RegattaLinkDeviceControlResult.BUSY ->
                RegattaLinkUiMessage.DEVICE_CONTROL_BUSY
            RegattaLinkDeviceControlResult.INVALID ->
                RegattaLinkUiMessage.DEVICE_CONTROL_REJECTED
            RegattaLinkDeviceControlResult.MOTION_REJECT ->
                RegattaLinkUiMessage.CALIBRATION_MOTION_REJECTED
            RegattaLinkDeviceControlResult.ORIENTATION_REJECT ->
                RegattaLinkUiMessage.CALIBRATION_ORIENTATION_REJECTED
            RegattaLinkDeviceControlResult.PERSIST_ERROR ->
                RegattaLinkUiMessage.CALIBRATION_PERSIST_FAILED
            RegattaLinkDeviceControlResult.CONFIG_ERROR ->
                RegattaLinkUiMessage.FACTORY_RESET_CONFIG_FAILED
            RegattaLinkDeviceControlResult.BOND_RESET_ERROR ->
                RegattaLinkUiMessage.FACTORY_RESET_BOND_FAILED
            RegattaLinkDeviceControlResult.INTERNAL_ERROR ->
                RegattaLinkUiMessage.DEVICE_CONTROL_INTERNAL_FAILED
            RegattaLinkDeviceControlResult.TIMEOUT ->
                RegattaLinkUiMessage.CALIBRATION_TIMEOUT
            RegattaLinkDeviceControlResult.NONE,
            RegattaLinkDeviceControlResult.OK ->
                RegattaLinkUiMessage.CONFIGURATION_FAILED
        }
        else -> RegattaLinkUiMessage.DEVICE_CONTROL_REJECTED
    }
}
