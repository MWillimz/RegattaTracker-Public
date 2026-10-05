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
    val motionDampingSupported: Boolean = false,
    val motionDampingSeconds: Int? = null,
    val configWordSupported: Boolean = false,
    val configWord: UInt? = null,
    val configRestartRequired: Boolean = false,
    val headingTrimSupported: Boolean = false,
    val headingTrimDeg: Int? = null,
    val nmeaTxRuntimeStatusSupported: Boolean = false,
    val nmeaTxBootSelected: Boolean? = null,
    val nmeaTxActive: Boolean? = null,
    val nmeaBootOutputMask: Int? = null,
    val nmeaActiveOutputMask: Int? = null,
    val restartAwaitingDisconnect: Boolean = false,
    val diagnosticLogSupported: Boolean = false,
    val diagnosticLogLoading: Boolean = false,
    val diagnosticLogEntries: List<RegattaLinkDiagnosticLogEntry> = emptyList(),
    val diagnosticLogError: String = "",
    val deviceControlSupported: Boolean = false,
    val deviceControlBusy: Boolean = false,
    val deviceControlAcceptedOpcode: RegattaLinkDeviceControlOpcode? = null,
    val deviceControlAcceptedRequestId: UInt? = null,
    val factoryResetWriteAcceptedRequestId: UInt? = null,
    val factoryResetAwaitingDisconnect: Boolean = false,
    val deviceControlStatus: RegattaLinkDeviceControlStatus? = null,
    val deviceControlError: String = "",
    val busy: Boolean = false,
    val error: String = "",
    val userMessage: RegattaLinkUiMessage? = null
) {
    val loadPrecisionSupported: Boolean
        get() = configWordSupported

    val loadPrecisionX10: Boolean?
        get() = configWord?.let {
            it and REGATTALINK_CONFIG_LOAD_PRECISION_X10 != 0u
        }

    val nmeaTxSupported: Boolean
        get() = configWordSupported

    val nmeaTxEnabled: Boolean?
        get() = configWord?.let {
            it and REGATTALINK_CONFIG_TX_MASTER != 0u
        }

    val nmeaAttitudeTxSupported: Boolean
        get() = configWordSupported

    val nmeaAttitudeTxEnabled: Boolean?
        get() = configWord?.let {
            it and REGATTALINK_CONFIG_TX_IMU != 0u
        }

    val nmea0183TxEnabled: Boolean?
        get() = configWord?.let {
            it and REGATTALINK_CONFIG_TX_NMEA0183 != 0u
        }

    val phoneGpsTxEnabled: Boolean?
        get() = configWord?.let {
            it and REGATTALINK_CONFIG_TX_PHONE_GPS != 0u
        }

    val compassTxEnabled: Boolean?
        get() = configWord?.let {
            it and REGATTALINK_CONFIG_TX_COMPASS != 0u
        }

    val magBackgroundLearningEnabled: Boolean?
        get() = configWord?.let {
            it and REGATTALINK_CONFIG_MAG_BACKGROUND_LEARNING != 0u
        }

    val nmea0183Baud: RegattaLinkNmea0183Baud?
        get() = configWord?.let(RegattaLinkNmea0183Baud::fromConfigWord)

    val phoneGnssForwardingDesired: Boolean
        get() =
            configWord?.let {
                it and REGATTALINK_CONFIG_TX_MASTER != 0u &&
                    it and REGATTALINK_CONFIG_TX_PHONE_GPS != 0u
            } == true

    val imuSessionAvailable: Boolean?
        get() = regattaLinkSubsystemSessionAvailable(
            configWord,
            REGATTALINK_CONFIG_SESSION_IMU
        )

    val magSessionAvailable: Boolean?
        get() = regattaLinkSubsystemSessionAvailable(
            configWord,
            REGATTALINK_CONFIG_SESSION_MAG
        )

    val canSessionAvailable: Boolean?
        get() = regattaLinkSubsystemSessionAvailable(
            configWord,
            REGATTALINK_CONFIG_SESSION_CAN
        )

    val nmea0183SessionAvailable: Boolean?
        get() = regattaLinkSubsystemSessionAvailable(
            configWord,
            REGATTALINK_CONFIG_SESSION_NMEA0183
        )

    /*
     * Raw current-session bit used by the explicit next-boot enable switches.
     * Session bits are interpreted literally; an unset bit means unavailable.
     */
    fun subsystemSessionBit(subsystem: RegattaLinkSubsystem): Boolean? =
        configWord?.let { it and subsystem.configBit != 0u }

    val nmeaTxRestartRequired: Boolean
        get() =
            nmeaTxRuntimeStatusSupported &&
                nmeaTxEnabled != null &&
                nmeaTxBootSelected != null &&
                nmeaTxEnabled != nmeaTxBootSelected

    val nmeaAttitudeTxRestartRequired: Boolean
        get() {
            val desired = nmeaAttitudeTxEnabled ?: return false
            val bootMask = nmeaBootOutputMask ?: return false
            return nmeaTxRuntimeStatusSupported &&
                desired != (bootMask and REGATTALINK_TX_OUTPUT_IMU != 0)
        }
}

internal fun regattaLinkConfigurationMutationBlocked(
    state: RegattaLinkConfigurationState,
    factoryResetOwned: Boolean = false,
    deviceControlRunning: Boolean = false,
    diagnosticLogRunning: Boolean = false
): Boolean =
    factoryResetOwned ||
        deviceControlRunning ||
        diagnosticLogRunning ||
        state.deviceControlBusy ||
        state.diagnosticLogLoading ||
        state.factoryResetAwaitingDisconnect ||
        state.restartAwaitingDisconnect ||
        state.factoryResetWriteAcceptedRequestId != null

internal fun regattaLinkFirmwareInstallBlocked(
    state: RegattaLinkConfigurationState
): Boolean =
    state.busy ||
        state.deviceControlBusy ||
        state.diagnosticLogLoading ||
        state.factoryResetAwaitingDisconnect ||
        state.restartAwaitingDisconnect ||
        state.factoryResetWriteAcceptedRequestId != null

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
    val loadSupported: Boolean = false,
    val loadSubscribed: Boolean = false,
    val loadSensors: List<RegattaLinkLoadSensor> = emptyList(),
    val loadReceivedAtElapsedMs: Long? = null,
    val pausedForOta: Boolean = false,
    val error: String = "",
    val userMessage: RegattaLinkUiMessage? = null
)

internal enum class RegattaLinkDeviceNameValidationError {
    EMPTY,
    TOO_LONG_UTF8,
    UNSUPPORTED_CONTROL_CHARACTER
}

internal fun validateRegattaLinkDeviceName(
    name: String
): RegattaLinkDeviceNameValidationError? {
    val bytes = name.toByteArray(Charsets.UTF_8)
    if (bytes.isEmpty()) {
        return RegattaLinkDeviceNameValidationError.EMPTY
    }
    if (bytes.size > 24) {
        return RegattaLinkDeviceNameValidationError.TOO_LONG_UTF8
    }
    if (bytes.any { byte ->
            val value = byte.toInt() and 0xff
            value == 0 || value < 0x20 || value == 0x7f
        }
    ) {
        return RegattaLinkDeviceNameValidationError.UNSUPPORTED_CONTROL_CHARACTER
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

internal fun parseRegattaLinkMotionDamping(raw: ByteArray): Int {
    require(raw.size == 1) { "RegattaLink motion damping must be exactly one byte" }
    val value = raw[0].toInt() and 0xff
    require(value in 1..10) { "Invalid RegattaLink motion damping $value s" }
    return value
}

internal fun regattaLinkExpireLoadSensorsIfTransportStale(
    state: RegattaLinkNmeaState,
    nowElapsedMs: Long
): RegattaLinkNmeaState {
    val receivedAt = state.loadReceivedAtElapsedMs ?: return state
    val ageMs = nowElapsedMs - receivedAt
    if (ageMs < REGATTALINK_LOAD_TRANSPORT_STALE_MS) return state
    return state.copy(
        loadSensors = emptyList(),
        loadReceivedAtElapsedMs = null
    )
}

internal const val REGATTALINK_CONFIG_TX_MASTER: UInt = 0x00000001u
internal const val REGATTALINK_CONFIG_TX_IMU: UInt = 0x00000002u
internal const val REGATTALINK_CONFIG_TX_NMEA0183: UInt = 0x00000004u
internal const val REGATTALINK_CONFIG_TX_PHONE_GPS: UInt = 0x00000008u
internal const val REGATTALINK_CONFIG_TX_COMPASS: UInt = 0x00000010u
internal const val REGATTALINK_CONFIG_TX_LOAD: UInt = 0x00000020u
internal const val REGATTALINK_CONFIG_LOAD_PRECISION_X10: UInt = 0x00000100u
internal const val REGATTALINK_CONFIG_MAG_BACKGROUND_LEARNING: UInt = 0x00000200u
internal const val REGATTALINK_CONFIG_NMEA0183_BAUD_MASK: UInt = 0x0000c000u
internal const val REGATTALINK_CONFIG_SESSION_IMU: UInt = 0x00010000u
internal const val REGATTALINK_CONFIG_SESSION_MAG: UInt = 0x00020000u
internal const val REGATTALINK_CONFIG_SESSION_CAN: UInt = 0x00040000u
internal const val REGATTALINK_CONFIG_SESSION_NMEA0183: UInt = 0x00080000u
internal const val REGATTALINK_CONFIG_SESSION_SUBSYSTEM_MASK: UInt =
    0x000f0000u

enum class RegattaLinkSubsystem(
    val configBit: UInt
) {
    IMU(REGATTALINK_CONFIG_SESSION_IMU),
    MAG(REGATTALINK_CONFIG_SESSION_MAG),
    BOAT_DATA(REGATTALINK_CONFIG_SESSION_CAN),
    NMEA0183_RX(REGATTALINK_CONFIG_SESSION_NMEA0183)
}

internal fun regattaLinkSubsystemSessionAvailable(
    configWord: UInt?,
    bit: UInt
): Boolean? {
    require(
        bit == REGATTALINK_CONFIG_SESSION_IMU ||
            bit == REGATTALINK_CONFIG_SESSION_MAG ||
            bit == REGATTALINK_CONFIG_SESSION_CAN ||
            bit == REGATTALINK_CONFIG_SESSION_NMEA0183
    ) { "Unknown RegattaLink subsystem session bit" }

    val word = configWord ?: return null
    return word and bit != 0u
}

enum class RegattaLinkNmea0183Baud(
    val baudRate: Int,
    val encodedBits: UInt
) {
    BAUD_4800(4_800, 0x00000000u),
    BAUD_9600(9_600, 0x00004000u),
    BAUD_19200(19_200, 0x00008000u),
    BAUD_38400(38_400, 0x0000c000u);

    companion object {
        fun fromConfigWord(word: UInt): RegattaLinkNmea0183Baud =
            entries.first {
                it.encodedBits ==
                    word and REGATTALINK_CONFIG_NMEA0183_BAUD_MASK
            }

        fun fromBaudRate(baudRate: Int): RegattaLinkNmea0183Baud? =
            entries.firstOrNull { it.baudRate == baudRate }
    }
}

internal fun parseRegattaLinkConfigWord(raw: ByteArray): UInt {
    require(raw.size == 4) {
        "RegattaLink config word must be exactly four bytes"
    }
    return ByteBuffer.wrap(raw)
        .order(ByteOrder.LITTLE_ENDIAN)
        .int
        .toUInt()
}

internal fun encodeRegattaLinkConfigWord(value: UInt): ByteArray =
    ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value.toInt())
        .array()

internal fun regattaLinkConfigWordWithMask(
    current: UInt,
    mask: UInt,
    encodedBits: UInt
): UInt {
    require(mask != 0u) { "Config mutation mask must not be zero" }
    require(encodedBits and mask.inv() == 0u) {
        "Config mutation contains bits outside its mask"
    }
    return (current and mask.inv()) or encodedBits
}

internal fun regattaLinkConfigWordWithBit(
    current: UInt,
    bitMask: UInt,
    enabled: Boolean
): UInt {
    require(bitMask != 0u && (bitMask and (bitMask - 1u)) == 0u) {
        "Config mutation requires exactly one bit"
    }
    return regattaLinkConfigWordWithMask(
        current = current,
        mask = bitMask,
        encodedBits = bitMask.takeIf { enabled } ?: 0u
    )
}

internal fun regattaLinkApplyConfigWord(
    state: RegattaLinkConfigurationState,
    word: UInt
): RegattaLinkConfigurationState =
    state.copy(
        configWordSupported = true,
        configWord = word
    )

internal fun parseRegattaLinkHeadingTrim(raw: ByteArray): Int {
    require(raw.size == 2) {
        "RegattaLink heading trim must be exactly two bytes"
    }
    val value = ByteBuffer.wrap(raw)
        .order(ByteOrder.LITTLE_ENDIAN)
        .short
        .toInt()
    require(value in -180..180) {
        "RegattaLink heading trim must be between -180 and 180 degrees"
    }
    return value
}

internal fun encodeRegattaLinkHeadingTrim(value: Int): ByteArray {
    require(value in -180..180) {
        "RegattaLink heading trim must be between -180 and 180 degrees"
    }
    return ByteBuffer.allocate(2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(value.toShort())
        .array()
}

internal const val REGATTALINK_TX_OUTPUT_IMU = 1 shl 0
internal const val REGATTALINK_TX_OUTPUT_NMEA0183 = 1 shl 1
internal const val REGATTALINK_TX_OUTPUT_PHONE_GPS = 1 shl 2
internal const val REGATTALINK_TX_OUTPUT_COMPASS = 1 shl 3
internal const val REGATTALINK_TX_OUTPUT_LOAD = 1 shl 4

data class RegattaLinkNmeaTxRuntimeStatus(
    val bootMasterSelected: Boolean,
    val masterActive: Boolean,
    val bootOutputMask: Int,
    val activeOutputMask: Int
) {
    val bootAttitudeSelected: Boolean
        get() = bootOutputMask and REGATTALINK_TX_OUTPUT_IMU != 0

    val attitudeActive: Boolean
        get() = activeOutputMask and REGATTALINK_TX_OUTPUT_IMU != 0
}

internal fun parseRegattaLinkNmeaTxRuntimeStatus(
    raw: ByteArray
): RegattaLinkNmeaTxRuntimeStatus {
    require(raw.size == 4) {
        "RegattaLink Boat Data runtime TX status must be exactly four bytes"
    }
    val version = raw[0].toInt() and 0xff
    require(version == 2) {
        "Unsupported RegattaLink Boat Data runtime TX status version $version"
    }
    val masterFlags = raw[1].toInt() and 0xff
    return RegattaLinkNmeaTxRuntimeStatus(
        bootMasterSelected = masterFlags and 0x01 != 0,
        masterActive = masterFlags and 0x02 != 0,
        bootOutputMask = raw[2].toInt() and 0xff,
        activeOutputMask = raw[3].toInt() and 0xff
    )
}

internal fun regattaLinkApplyNmeaTxRuntimeStatus(
    state: RegattaLinkConfigurationState,
    runtime: RegattaLinkNmeaTxRuntimeStatus
): RegattaLinkConfigurationState =
    regattaLinkReconcileNmeaTxState(
        state.copy(
            nmeaTxRuntimeStatusSupported = true,
            nmeaTxBootSelected = runtime.bootMasterSelected,
            nmeaTxActive = runtime.masterActive,
            nmeaBootOutputMask = runtime.bootOutputMask,
            nmeaActiveOutputMask = runtime.activeOutputMask
        )
    )

internal fun regattaLinkReconcileNmeaTxState(
    state: RegattaLinkConfigurationState
): RegattaLinkConfigurationState = state


private fun regattaLinkOutputRestartRequired(
    state: RegattaLinkConfigurationState,
    configBit: UInt,
    runtimeBit: Int
): Boolean {
    if (!state.nmeaTxRuntimeStatusSupported) return false
    val desiredWord = state.configWord ?: return false
    val bootMask = state.nmeaBootOutputMask ?: return false
    val desired = desiredWord and configBit != 0u
    val bootSelected = bootMask and runtimeBit != 0
    return desired != bootSelected
}

internal fun regattaLinkNmeaRestartRequired(
    state: RegattaLinkConfigurationState
): Boolean =
    state.configRestartRequired ||
        state.nmeaTxRestartRequired ||
        regattaLinkOutputRestartRequired(
            state,
            REGATTALINK_CONFIG_TX_IMU,
            REGATTALINK_TX_OUTPUT_IMU
        ) ||
        regattaLinkOutputRestartRequired(
            state,
            REGATTALINK_CONFIG_TX_NMEA0183,
            REGATTALINK_TX_OUTPUT_NMEA0183
        ) ||
        regattaLinkOutputRestartRequired(
            state,
            REGATTALINK_CONFIG_TX_PHONE_GPS,
            REGATTALINK_TX_OUTPUT_PHONE_GPS
        ) ||
        regattaLinkOutputRestartRequired(
            state,
            REGATTALINK_CONFIG_TX_COMPASS,
            REGATTALINK_TX_OUTPUT_COMPASS
        )

internal fun regattaLinkNmeaAppliedStateUnknown(
    state: RegattaLinkConfigurationState
): Boolean {
    if (!state.configWordSupported) return false
    if (!state.nmeaTxRuntimeStatusSupported) return true
    if (state.nmeaTxBootSelected == null) return true
    return state.nmeaBootOutputMask == null
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
