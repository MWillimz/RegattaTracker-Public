package de.williserv.regattaclient

import java.io.Closeable
import java.io.File
import java.util.Locale

internal const val REGATTALINK_RAW_CAPTURE_DURATION_MS = 30_000L
internal const val REGATTALINK_RAW_CAPTURE_EMPTY_POLL_MS = 100L
internal const val REGATTALINK_RAW_CAPTURE_FLUSH_READ_LIMIT = REGATTALINK_MAX_RAW_CAN_READS
internal const val REGATTALINK_RAW_CAPTURE_CSV_HEADER =
    "timestamp_us_low,can_id,pgn,priority,source,destination,dlc,data_hex"

internal enum class RegattaLinkRawCapturePhase {
    IDLE,
    FLUSHING,
    CAPTURING,
    COMPLETED,
    INTERRUPTED,
    ERROR
}

internal enum class RegattaLinkRawCaptureStopReason {
    USER,
    INTERRUPTED
}

internal enum class RegattaLinkRawCaptureEndReason {
    TIMEOUT,
    USER_STOP,
    INTERRUPTED,
    ERROR
}

internal data class RegattaLinkRawCaptureState(
    val phase: RegattaLinkRawCapturePhase = RegattaLinkRawCapturePhase.IDLE,
    val startedAtElapsedMs: Long? = null,
    val durationMs: Long = REGATTALINK_RAW_CAPTURE_DURATION_MS,
    val frameCount: Int = 0,
    val fileName: String = "",
    val filePath: String? = null,
    val error: String = ""
) {
    val isActive: Boolean
        get() = phase == RegattaLinkRawCapturePhase.FLUSHING ||
            phase == RegattaLinkRawCapturePhase.CAPTURING

    val hasFile: Boolean
        get() = !filePath.isNullOrBlank()
}

internal data class Nmea2000CanIdFields(
    val priority: Int,
    val pgn: Long,
    val source: Int,
    val destination: Int?
)

internal fun decodeNmea2000CanId(canId: Long): Nmea2000CanIdFields {
    require(canId in 0..0x1fffffffL) { "CAN identifier must be a 29-bit value" }

    val priority = ((canId shr 26) and 0x7L).toInt()
    val source = (canId and 0xffL).toInt()
    val pf = ((canId shr 16) and 0xffL).toInt()
    val ps = ((canId shr 8) and 0xffL).toInt()

    var pgn = (canId shr 8) and 0x3ffffL
    val destination = if (pf < 240) {
        pgn = pgn and 0x3ff00L
        ps
    } else {
        null
    }

    return Nmea2000CanIdFields(
        priority = priority,
        pgn = pgn,
        source = source,
        destination = destination
    )
}

internal fun regattaLinkRawCaptureCsvRow(
    frame: RegattaLinkRawCanFrame
): String {
    val fields = decodeNmea2000CanId(frame.canId)
    return String.format(
        Locale.ROOT,
        "%d,%d,%d,%d,%d,%s,%d,%s",
        frame.timestampUsLow,
        frame.canId,
        fields.pgn,
        fields.priority,
        fields.source,
        fields.destination?.toString().orEmpty(),
        frame.dlc,
        frame.dataHex
    )
}

internal fun shouldContinueRawCaptureFlush(
    readsCompleted: Int,
    result: RegattaLinkRawCanReadResult
): Boolean =
    readsCompleted < REGATTALINK_RAW_CAPTURE_FLUSH_READ_LIMIT &&
        result.frame != null &&
        result.remainingCount > 0

internal fun rawCapturePollDelayMs(
    result: RegattaLinkRawCanReadResult
): Long =
    if (result.frame == null || result.remainingCount == 0) {
        REGATTALINK_RAW_CAPTURE_EMPTY_POLL_MS
    } else {
        0L
    }

internal class RegattaLinkRawCaptureFileSession(
    val file: File
) : Closeable {
    private val writer = file.bufferedWriter(Charsets.UTF_8)
    private var closed = false

    init {
        writer.append(REGATTALINK_RAW_CAPTURE_CSV_HEADER)
        writer.newLine()
        writer.flush()
    }

    fun append(frame: RegattaLinkRawCanFrame) {
        check(!closed) { "Raw CAN capture file is already closed" }
        writer.append(regattaLinkRawCaptureCsvRow(frame))
        writer.newLine()
        writer.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        writer.close()
    }
}
