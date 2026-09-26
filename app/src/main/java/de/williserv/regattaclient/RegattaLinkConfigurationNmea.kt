package de.williserv.regattaclient

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val REGATTALINK_RAW_CAN_RECORD_SIZE = 21
internal const val REGATTALINK_BOAT_STATE_RECORD_SIZE = 80
internal const val REGATTALINK_BOAT_STATE_NOTIFICATION_MTU = 83
internal const val REGATTALINK_MAX_RAW_CAN_READS = 128

data class RegattaLinkConfigurationState(
    val deviceNameSupported: Boolean = false,
    val deviceName: String = "",
    val ledBrightnessSupported: Boolean = false,
    val ledBrightnessPct: Int? = null,
    val diagnosticLogSupported: Boolean = false,
    val diagnosticLogLoading: Boolean = false,
    val diagnosticLogEntries: List<RegattaLinkDiagnosticLogEntry> = emptyList(),
    val diagnosticLogError: String = "",
    val deviceControlSupported: Boolean = false,
    val deviceControlBusy: Boolean = false,
    val deviceControlAcceptedOpcode: RegattaLinkDeviceControlOpcode? = null,
    val deviceControlAcceptedRequestId: UInt? = null,
    val deviceControlStatus: RegattaLinkDeviceControlStatus? = null,
    val deviceControlError: String = "",
    val busy: Boolean = false,
    val error: String = ""
)

data class RegattaLinkPgnInventoryEntry(
    val pgn: Long,
    val lastSeenMs: Long
)

data class RegattaLinkRawCanFrame(
    val timestampUsLow: Long,
    val canId: Long,
    val dlc: Int,
    val data: ByteArray
) {
    val dataHex: String
        get() = data.take(dlc).joinToString("") { byte ->
            "%02X".format(byte.toInt() and 0xff)
        }
}

data class RegattaLinkRawCanReadResult(
    val remainingCount: Int,
    val frame: RegattaLinkRawCanFrame?
)

data class RegattaLinkBoatState(
    val sequence: Int,
    val timestampMs: Long,
    val validityBitmap: Long,
    val headingDeg: Double? = null,
    val headingReference: Int? = null,
    val headingDeviationDeg: Double? = null,
    val headingVariationDeg: Double? = null,
    val rateOfTurnDps: Double? = null,
    val yawDeg: Double? = null,
    val pitchDeg: Double? = null,
    val rollDeg: Double? = null,
    val speedThroughWaterMps: Double? = null,
    val speedReference: Int? = null,
    val depthM: Double? = null,
    val depthOffsetM: Double? = null,
    val depthRangeM: Double? = null,
    val waterTemperatureC: Double? = null,
    val temperatureSource: Int? = null,
    val latitudeDeg: Double? = null,
    val longitudeDeg: Double? = null,
    val cogDeg: Double? = null,
    val sogMps: Double? = null,
    val cogReference: Int? = null,
    val gnssType: Int? = null,
    val gnssMethod: Int? = null,
    val satellites: Int? = null,
    val hdop: Double? = null,
    val pdop: Double? = null,
    val altitudeM: Double? = null,
    val windSpeedMps: Double? = null,
    val windAngleDeg: Double? = null,
    val windReference: Int? = null
) {
    val hasAnyValidData: Boolean
        get() = validityBitmap != 0L
}

data class RegattaLinkNmeaState(
    val pgnInventorySupported: Boolean = false,
    val pgnInventoryLoading: Boolean = false,
    val pgnInventory: List<RegattaLinkPgnInventoryEntry> = emptyList(),
    val rawCanSupported: Boolean = false,
    val rawCanReading: Boolean = false,
    val rawFrames: List<RegattaLinkRawCanFrame> = emptyList(),
    val boatStateSupported: Boolean = false,
    val boatStateSubscribed: Boolean = false,
    val boatStateLiveNotifications: Boolean = false,
    val boatState: RegattaLinkBoatState? = null,
    val boatStateReceivedAtElapsedMs: Long? = null,
    val pausedForOta: Boolean = false,
    val error: String = ""
)

internal fun validateRegattaLinkDeviceName(name: String): String? {
    val bytes = name.toByteArray(Charsets.UTF_8)
    if (bytes.isEmpty()) return "Name must not be empty"
    if (bytes.size > 24) return "Name must be at most 24 UTF-8 bytes"
    if (bytes.any { byte ->
            val value = byte.toInt() and 0xff
            value == 0 || value < 0x20 || value == 0x7f
        }
    ) {
        return "Name contains unsupported control characters"
    }
    return null
}

internal fun parseRegattaLinkDeviceName(raw: ByteArray): String {
    require(raw.isNotEmpty()) { "RegattaLink name must not be empty" }
    require(raw.size <= 24) { "RegattaLink name exceeds 24 bytes" }
    require(raw.none { byte ->
        val value = byte.toInt() and 0xff
        value == 0 || value < 0x20 || value == 0x7f
    }) { "RegattaLink name contains invalid control bytes" }
    return raw.toString(Charsets.UTF_8)
}

internal fun parseRegattaLinkLedBrightness(raw: ByteArray): Int {
    require(raw.size == 1) { "RegattaLink LED brightness must be exactly one byte" }
    val value = raw[0].toInt() and 0xff
    require(value <= 100) { "Invalid RegattaLink LED brightness $value" }
    return value
}

internal fun parseRegattaLinkPgnInventory(
    raw: ByteArray
): List<RegattaLinkPgnInventoryEntry> {
    require(raw.size % 8 == 0) {
        "RegattaLink PGN inventory length must be divisible by 8"
    }
    require(raw.size <= 64 * 8) {
        "RegattaLink PGN inventory exceeds 64 records"
    }
    val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    return List(raw.size / 8) { index ->
        val offset = index * 8
        RegattaLinkPgnInventoryEntry(
            pgn = buffer.getInt(offset).toLong() and 0xffffffffL,
            lastSeenMs = buffer.getInt(offset + 4).toLong() and 0xffffffffL
        )
    }
}

internal fun parseRegattaLinkRawCanRead(
    raw: ByteArray
): RegattaLinkRawCanReadResult {
    require(raw.size == REGATTALINK_RAW_CAN_RECORD_SIZE) {
        "RegattaLink raw CAN record must be $REGATTALINK_RAW_CAN_RECORD_SIZE bytes"
    }
    val version = raw[0].toInt() and 0xff
    require(version == 1) { "Unsupported RegattaLink raw CAN format $version" }

    val remaining = raw[1].toInt() and 0xff
    require(remaining <= REGATTALINK_MAX_RAW_CAN_READS) {
        "Invalid RegattaLink raw CAN remaining count $remaining"
    }

    val present = raw[2].toInt() and 0xff
    require(present in 0..1) { "Invalid RegattaLink raw CAN frame flag $present" }
    require(raw[3].toInt() == 0) { "Invalid RegattaLink raw CAN reserved byte" }

    if (present == 0) {
        return RegattaLinkRawCanReadResult(
            remainingCount = remaining,
            frame = null
        )
    }

    val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    val timestamp = buffer.getInt(4).toLong() and 0xffffffffL
    val canId = buffer.getInt(8).toLong() and 0xffffffffL
    require(canId <= 0x1fffffffL) { "Invalid 29-bit RegattaLink CAN identifier" }

    val dlc = raw[12].toInt() and 0xff
    require(dlc in 0..8) { "Invalid RegattaLink CAN DLC $dlc" }

    return RegattaLinkRawCanReadResult(
        remainingCount = remaining,
        frame = RegattaLinkRawCanFrame(
            timestampUsLow = timestamp,
            canId = canId,
            dlc = dlc,
            data = raw.copyOfRange(13, 21)
        )
    )
}

internal fun parseRegattaLinkBoatState(raw: ByteArray): RegattaLinkBoatState {
    require(raw.size == REGATTALINK_BOAT_STATE_RECORD_SIZE) {
        "RegattaLink Boat State must be $REGATTALINK_BOAT_STATE_RECORD_SIZE bytes"
    }
    val version = raw[0].toInt() and 0xff
    val recordSize = raw[1].toInt() and 0xff
    require(version == 1) { "Unsupported RegattaLink Boat State schema $version" }
    require(recordSize == REGATTALINK_BOAT_STATE_RECORD_SIZE) {
        "Unsupported RegattaLink Boat State size $recordSize"
    }

    val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    val validity = buffer.getInt(8).toLong() and 0xffffffffL
    fun valid(bit: Int): Boolean = validity and (1L shl bit) != 0L
    fun u16(offset: Int): Int = buffer.getShort(offset).toInt() and 0xffff
    fun u32(offset: Int): Long = buffer.getInt(offset).toLong() and 0xffffffffL
    fun ref(offset: Int): Int? =
        (raw[offset].toInt() and 0xff).takeUnless { it == 0xff }

    return RegattaLinkBoatState(
        sequence = u16(2),
        timestampMs = u32(4),
        validityBitmap = validity,
        headingDeg = if (valid(0)) u16(12) / 100.0 else null,
        headingReference = if (valid(0)) ref(14) else null,
        headingDeviationDeg = if (valid(1)) buffer.getShort(16).toInt() / 100.0 else null,
        headingVariationDeg = if (valid(2)) buffer.getShort(18).toInt() / 100.0 else null,
        rateOfTurnDps = if (valid(3)) buffer.getShort(20).toInt() / 100.0 else null,
        yawDeg = if (valid(4)) buffer.getShort(22).toInt() / 100.0 else null,
        pitchDeg = if (valid(5)) buffer.getShort(24).toInt() / 100.0 else null,
        rollDeg = if (valid(6)) buffer.getShort(26).toInt() / 100.0 else null,
        speedThroughWaterMps = if (valid(7)) u16(28) / 100.0 else null,
        speedReference = if (valid(7)) ref(30) else null,
        depthM = if (valid(8)) u32(32) / 100.0 else null,
        depthOffsetM = if (valid(9)) buffer.getInt(36) / 100.0 else null,
        depthRangeM = if (valid(10)) u32(40) / 100.0 else null,
        waterTemperatureC = if (valid(11)) buffer.getShort(44).toInt() / 100.0 else null,
        temperatureSource = if (valid(11)) ref(46) else null,
        latitudeDeg = if (valid(12)) buffer.getInt(48) / 10_000_000.0 else null,
        longitudeDeg = if (valid(12)) buffer.getInt(52) / 10_000_000.0 else null,
        cogDeg = if (valid(13)) u16(56) / 100.0 else null,
        sogMps = if (valid(14)) u16(58) / 100.0 else null,
        cogReference = if (valid(13)) ref(60) else null,
        gnssType = if (valid(15)) ref(62) else null,
        gnssMethod = if (valid(15)) ref(63) else null,
        satellites = if (valid(15)) ref(64) else null,
        hdop = if (valid(15)) u16(66) / 100.0 else null,
        pdop = if (valid(15)) u16(68) / 100.0 else null,
        altitudeM = if (valid(16)) buffer.getInt(70) / 100.0 else null,
        windSpeedMps = if (valid(17)) u16(74) / 100.0 else null,
        windAngleDeg = if (valid(18)) u16(76) / 100.0 else null,
        windReference = if (valid(17) || valid(18)) ref(78) else null
    )
}
