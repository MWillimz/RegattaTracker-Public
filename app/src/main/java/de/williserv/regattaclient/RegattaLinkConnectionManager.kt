package de.williserv.regattaclient

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

internal data class RegattaLinkConfiguredDevice(
    val stableId: String,
    val deviceAddress: String,
    val deviceName: String
)

internal data class RegattaLinkDiscoveredDevice(
    val deviceAddress: String,
    val deviceName: String,
    val bonded: Boolean
)

internal data class RegattaLinkDiscoveryState(
    val scanning: Boolean = false,
    val devices: List<RegattaLinkDiscoveredDevice> = emptyList(),
    val userMessage: RegattaLinkUiMessage? = null
)

internal data class RegattaLinkDeviceSelectionState(
    val knownDevices: List<RegattaLinkConfiguredDevice> = emptyList(),
    val selectedStableId: String? = null,
    val discovery: RegattaLinkDiscoveryState = RegattaLinkDiscoveryState()
) {
    val selectedDevice: RegattaLinkConfiguredDevice?
        get() = knownDevices.firstOrNull { it.stableId == selectedStableId }
}

internal class RegattaLinkConfiguredDeviceStore(context: Context) {
    companion object {
        internal const val PREFS_NAME = "regattalink_connection"

        // Legacy single-device keys. Migrated once into the registry below.
        private const val KEY_STABLE_ID = "stable_id"
        private const val KEY_DEVICE_ADDRESS = "device_address"
        private const val KEY_DEVICE_NAME = "device_name"

        private const val KEY_KNOWN_STABLE_IDS = "known_stable_ids"
        private const val KEY_SELECTED_STABLE_ID = "selected_stable_id"
        private const val KEY_RESET_PENDING_PAIRING = "reset_pending_pairing"
        private const val KEY_RESET_PENDING_STABLE_ID = "reset_pending_stable_id"
        private const val DEVICE_KEY_PREFIX = "device."
    }

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    init {
        migrateLegacySingleDevice()
    }

    fun load(): RegattaLinkConfiguredDevice? = selected()

    fun all(): List<RegattaLinkConfiguredDevice> =
        knownStableIds()
            .mapNotNull(::device)
            .sortedWith(
                compareBy<RegattaLinkConfiguredDevice>(
                    { it.deviceName.lowercase(Locale.ROOT) },
                    { it.stableId }
                )
            )

    fun selected(): RegattaLinkConfiguredDevice? {
        val stableId = prefs.getString(KEY_SELECTED_STABLE_ID, "").orEmpty()
        if (stableId.isBlank()) return null
        return device(stableId)
    }

    fun save(device: RegattaLinkConfiguredDevice) {
        upsertAndSelect(device)
    }

    fun upsert(device: RegattaLinkConfiguredDevice) {
        require(device.stableId.isNotBlank())
        require(device.deviceAddress.isNotBlank())
        val ids = knownStableIds().toMutableSet().apply {
            add(device.stableId)
        }
        prefs.edit()
            .putStringSet(KEY_KNOWN_STABLE_IDS, ids)
            .putString(deviceAddressKey(device.stableId), device.deviceAddress)
            .putString(deviceNameKey(device.stableId), device.deviceName)
            .apply()
    }

    fun upsertAndSelect(device: RegattaLinkConfiguredDevice) {
        upsert(device)
        prefs.edit()
            .putString(KEY_SELECTED_STABLE_ID, device.stableId)
            .apply()
        clearResetRecoveryPending(device.stableId)
    }

    fun select(stableId: String): Boolean {
        if (device(stableId) == null) return false
        prefs.edit().putString(KEY_SELECTED_STABLE_ID, stableId).apply()
        return true
    }

    fun remove(stableId: String) {
        if (stableId.isBlank()) return
        val ids = knownStableIds().toMutableSet().apply { remove(stableId) }
        val editor = prefs.edit()
            .putStringSet(KEY_KNOWN_STABLE_IDS, ids)
            .remove(deviceAddressKey(stableId))
            .remove(deviceNameKey(stableId))
        if (prefs.getString(KEY_SELECTED_STABLE_ID, "") == stableId) {
            editor.remove(KEY_SELECTED_STABLE_ID)
        }
        editor.apply()
    }

    fun updateName(stableId: String, deviceName: String) {
        if (device(stableId) == null) return
        prefs.edit()
            .putString(deviceNameKey(stableId), deviceName)
            .apply()
    }

    fun markResetRecoveryPending(stableId: String? = selected()?.stableId) {
        val editor = prefs.edit()
            .putBoolean(KEY_RESET_PENDING_PAIRING, true)
        if (stableId.isNullOrBlank()) {
            editor.remove(KEY_RESET_PENDING_STABLE_ID)
        } else {
            editor.putString(KEY_RESET_PENDING_STABLE_ID, stableId)
        }
        editor.commit()
    }

    fun clearResetRecoveryPending(stableId: String? = null) {
        val pendingStableId =
            prefs.getString(KEY_RESET_PENDING_STABLE_ID, "").orEmpty()
        if (
            stableId != null &&
            pendingStableId.isNotBlank() &&
            pendingStableId != stableId
        ) {
            return
        }
        prefs.edit()
            .putBoolean(KEY_RESET_PENDING_PAIRING, false)
            .remove(KEY_RESET_PENDING_STABLE_ID)
            .commit()
    }

    fun clear() {
        prefs.edit().clear().putBoolean(KEY_RESET_PENDING_PAIRING, true).apply()
    }

    fun requiresNewPairing(): Boolean {
        if (!prefs.getBoolean(KEY_RESET_PENDING_PAIRING, false)) return false
        val pendingStableId =
            prefs.getString(KEY_RESET_PENDING_STABLE_ID, "").orEmpty()
        if (pendingStableId.isBlank()) return true
        return selected()?.stableId == pendingStableId
    }

    private fun knownStableIds(): Set<String> =
        prefs.getStringSet(KEY_KNOWN_STABLE_IDS, emptySet())
            ?.filterTo(linkedSetOf()) { it.isNotBlank() }
            ?: emptySet()

    private fun device(stableId: String): RegattaLinkConfiguredDevice? {
        if (stableId !in knownStableIds()) return null
        val address = prefs.getString(deviceAddressKey(stableId), "").orEmpty()
        if (address.isBlank()) return null
        return RegattaLinkConfiguredDevice(
            stableId = stableId,
            deviceAddress = address,
            deviceName = prefs.getString(deviceNameKey(stableId), "").orEmpty()
        )
    }

    private fun migrateLegacySingleDevice() {
        if (prefs.contains(KEY_KNOWN_STABLE_IDS)) return

        val stableId = prefs.getString(KEY_STABLE_ID, "").orEmpty()
        val address = prefs.getString(KEY_DEVICE_ADDRESS, "").orEmpty()
        val name = prefs.getString(KEY_DEVICE_NAME, "").orEmpty()
        val editor = prefs.edit()

        if (stableId.isNotBlank() && address.isNotBlank()) {
            editor
                .putStringSet(KEY_KNOWN_STABLE_IDS, setOf(stableId))
                .putString(KEY_SELECTED_STABLE_ID, stableId)
                .putString(deviceAddressKey(stableId), address)
                .putString(deviceNameKey(stableId), name)
        } else {
            editor.putStringSet(KEY_KNOWN_STABLE_IDS, emptySet())
        }

        editor
            .remove(KEY_STABLE_ID)
            .remove(KEY_DEVICE_ADDRESS)
            .remove(KEY_DEVICE_NAME)
            .commit()
    }

    private fun deviceAddressKey(stableId: String): String =
        "$DEVICE_KEY_PREFIX$stableId.address"

    private fun deviceNameKey(stableId: String): String =
        "$DEVICE_KEY_PREFIX$stableId.name"
}

internal interface RegattaLinkConnectionListener {
    fun onConnectionStateChanged(state: RegattaLinkClientState) {}
    fun onDeviceSelectionStateChanged(state: RegattaLinkDeviceSelectionState) {}
    fun onOtaStateChanged(state: RegattaLinkOtaUiState) {}
    fun onTelemetryStateChanged(state: RegattaLinkTelemetryState) {}
    fun onConfigurationStateChanged(state: RegattaLinkConfigurationState) {}
    fun onNmeaStateChanged(state: RegattaLinkNmeaState) {}
    fun onRawCaptureStateChanged(state: RegattaLinkRawCaptureState) {}
}

internal interface RegattaLinkConnectionClient {
    fun startKnownDeviceReconnect(
        deviceAddress: String,
        expectedStableId: String?,
        timeoutMs: Long
    ): Boolean

    fun startKnownDeviceAutoConnect(
        deviceAddress: String,
        expectedStableId: String?
    ): Boolean

    fun onBluetoothAdapterDisabled()
    fun onBluetoothAdapterEnabled()

    fun startDiscovery(): Boolean
    fun connectDiscoveredDevice(deviceAddress: String): Boolean = false
    fun disconnect()
    fun startOta(artifact: RegattaLinkFirmwareArtifact)
    fun cancelOta()
    fun resetOtaState()
    fun setDeviceName(name: String): Boolean
    fun setLedBrightness(percent: Int): Boolean
    fun setMotionDamping(seconds: Int): Boolean
    fun setLoadPrecisionX10(enabled: Boolean): Boolean = false
    fun setNmeaTxEnabled(enabled: Boolean): Boolean = false
    fun setNmeaAttitudeTxEnabled(enabled: Boolean): Boolean = false
    fun setNmea0183TxEnabled(enabled: Boolean): Boolean = false
    fun setPhoneGpsTxEnabled(enabled: Boolean): Boolean = false
    fun setCompassTxEnabled(enabled: Boolean): Boolean = false
    fun setNmea0183Baud(baudRate: Int): Boolean = false
    fun setMagBackgroundLearningEnabled(enabled: Boolean): Boolean = false
    fun setSubsystemEnabled(
        subsystem: RegattaLinkSubsystem,
        enabled: Boolean
    ): Boolean = false
    fun applyConfigBitsAndRestart(
        mask: UInt,
        encodedBits: UInt
    ): Boolean = false
    fun setHeadingTrimDeg(value: Int): Boolean = false
    fun offerPhoneGnss(sample: RegattaLinkPhoneGnssSample): Boolean = false
    fun clearPhoneGnss() = Unit
    fun drainDiagnosticLog(): Boolean = false
    fun executeDeviceControl(
        opcode: RegattaLinkDeviceControlOpcode,
        value: Int
    ): Boolean = false
    fun setImuRawPreviewEnabled(enabled: Boolean): Boolean = false
    fun refreshPgnInventory(): Boolean
    fun readRawCanFrames(): Boolean

    fun startRawCanCapture(
        onRecordingStarted: () -> Unit,
        onFrame: (RegattaLinkRawCanFrame) -> Unit,
        onFinished: (RegattaLinkRawCaptureEndReason, String) -> Unit
    ): Boolean = false

    fun stopRawCanCapture(
        reason: RegattaLinkRawCaptureStopReason
    ) = Unit
}

@SuppressLint("MissingPermission")
internal fun findUniqueLegacyBondedRegattaLinkAddress(context: Context): String? {
    val manager = context.applicationContext
        .getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter = manager.adapter ?: return null
    val candidates = runCatching {
        adapter.bondedDevices
            .asSequence()
            .filter { it.bondState == BluetoothDevice.BOND_BONDED }
            .filter {
                runCatching { it.name }
                    .getOrNull()
                    ?.startsWith("RegattaLink-", ignoreCase = true) == true
            }
            .map { it.address }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())
    return candidates.singleOrNull()
}

internal fun interface RegattaLinkConnectionClientFactory {
    fun create(
        context: Context,
        onStateChanged: (RegattaLinkClientState) -> Unit,
        onOtaStateChanged: (RegattaLinkOtaUiState) -> Unit,
        onTelemetryStateChanged: (RegattaLinkTelemetryState) -> Unit,
        onConfigurationStateChanged: (RegattaLinkConfigurationState) -> Unit,
        onNmeaStateChanged: (RegattaLinkNmeaState) -> Unit,
        onDiscoveryStateChanged: (RegattaLinkDiscoveryState) -> Unit,
        onFactoryResetRecoveryStateChanged: (Boolean) -> Unit,
        onUnexpectedDisconnect: () -> Unit
    ): RegattaLinkConnectionClient
}

internal class RegattaLinkConnectionManager(
    context: Context,
    clientFactory: RegattaLinkConnectionClientFactory =
        RegattaLinkConnectionClientFactory {
                clientContext,
                onStateChanged,
                onOtaStateChanged,
                onTelemetryStateChanged,
                onConfigurationStateChanged,
                onNmeaStateChanged,
                onDiscoveryStateChanged,
                onFactoryResetRecoveryStateChanged,
                onUnexpectedDisconnect ->
            RegattaLinkBleClient(
                context = clientContext,
                onStateChanged = onStateChanged,
                onOtaStateChanged = onOtaStateChanged,
                onTelemetryStateChanged = onTelemetryStateChanged,
                onConfigurationStateChanged = onConfigurationStateChanged,
                onNmeaStateChanged = onNmeaStateChanged,
                onDiscoveryStateChanged = onDiscoveryStateChanged,
                onFactoryResetRecoveryStateChanged =
                    onFactoryResetRecoveryStateChanged,
                onUnexpectedDisconnect = onUnexpectedDisconnect
            )
        },
    private val legacyBondedAddressProvider: (Context) -> String? =
        ::findUniqueLegacyBondedRegattaLinkAddress
) {
    companion object {
        private const val NORMAL_RECONNECT_TIMEOUT_MS = 60_000L
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<RegattaLinkConnectionListener>()
    private val configuredDeviceStore = RegattaLinkConfiguredDeviceStore(appContext)
    private val loadAliasStore = RegattaLinkLoadAliasStore(appContext)
    private val startupReconnectRequested = AtomicBoolean(false)

    @Volatile
    private var autoReconnectSuppressedByUser = false

    @Volatile
    private var deviceSelectionState = RegattaLinkDeviceSelectionState(
        knownDevices = configuredDeviceStore.all(),
        selectedStableId = configuredDeviceStore.selected()?.stableId
    )

    @Volatile
    private var pendingDiscoveredSelectionAddress: String? = null

    @Volatile
    private var pendingKnownSwitchStableId: String? = null

    @Volatile
    private var connectionState = RegattaLinkClientState()

    @Volatile
    private var otaState = RegattaLinkOtaUiState()

    @Volatile
    private var telemetryState = RegattaLinkTelemetryState()

    @Volatile
    private var configurationState = RegattaLinkConfigurationState()

    @Volatile
    private var nmeaState = RegattaLinkNmeaState()

    @Volatile
    private var rawCaptureState = RegattaLinkRawCaptureState()

    private val rawCaptureLock = Any()
    private var rawCaptureFileSession: RegattaLinkRawCaptureFileSession? = null
    private var rawCaptureFrameCount = 0
    private var rawCaptureLastUiEmitMs = 0L

    @Volatile
    private var explicitDiscoveryRequested = false

    @Volatile
    private var legacyBootstrapAddress: String? = null

    @Volatile
    private var factoryResetPending = false

    @Volatile
    private var factoryResetStableId: String? = null

    @Volatile
    private var lastPhoneGnssCogDeg: Double? = null

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (
                intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR
                )
            ) {
                BluetoothAdapter.STATE_OFF -> {
                    client.onBluetoothAdapterDisabled()
                }
                BluetoothAdapter.STATE_ON -> {
                    client.onBluetoothAdapterEnabled()
                    ensureBackgroundConnectedIfPermitted()
                }
            }
        }
    }

    private val client = clientFactory.create(
        context = appContext,
        onStateChanged = ::handleConnectionState,
        onOtaStateChanged = ::handleOtaState,
        onTelemetryStateChanged = ::handleTelemetryState,
        onConfigurationStateChanged = ::handleConfigurationState,
        onNmeaStateChanged = ::handleNmeaState,
        onDiscoveryStateChanged = ::handleDiscoveryState,
        onFactoryResetRecoveryStateChanged =
            ::handleFactoryResetRecoveryStateChanged,
        onUnexpectedDisconnect = {
            handler.post {
                if (
                    !otaState.isActive &&
                    !factoryResetPending &&
                    pendingKnownSwitchStableId == null &&
                    pendingDiscoveredSelectionAddress == null
                ) {
                    ensureConnectedIfPermitted()
                }
            }
        }
    )

    init {
        ContextCompat.registerReceiver(
            appContext,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun addListener(listener: RegattaLinkConnectionListener) {
        listeners.add(listener)
        handler.post {
            if (!listeners.contains(listener)) return@post
            listener.onConnectionStateChanged(connectionState)
            listener.onDeviceSelectionStateChanged(deviceSelectionState)
            listener.onOtaStateChanged(otaState)
            listener.onTelemetryStateChanged(telemetryState)
            listener.onConfigurationStateChanged(configurationState)
            listener.onNmeaStateChanged(nmeaState)
            listener.onRawCaptureStateChanged(rawCaptureState)
        }
    }

    fun removeListener(listener: RegattaLinkConnectionListener) {
        listeners.remove(listener)
    }

    fun requestForegroundStartupReconnectIfPermitted() {
        if (!startupReconnectRequested.compareAndSet(false, true)) return
        if (!hasRequiredPermissions()) return
        ensureBackgroundConnectedIfPermitted()
    }

    fun ensureConnectedIfPermitted() {
        ensureBackgroundConnectedIfPermitted()
    }

    private fun ensureBackgroundConnectedIfPermitted(): Boolean {
        if (
            autoReconnectSuppressedByUser ||
            otaState.isActive ||
            explicitDiscoveryRequested ||
            factoryResetPending ||
            configuredDeviceStore.requiresNewPairing() ||
            !hasRequiredPermissions()
        ) {
            return false
        }
        val configured = configuredDeviceStore.selected()
        if (configured != null) {
            legacyBootstrapAddress = null
            return client.startKnownDeviceAutoConnect(
                deviceAddress = configured.deviceAddress,
                expectedStableId = configured.stableId
            )
        }

        // Once a registry exists, absence of a selection is intentional:
        // never choose another known/bonded RLink implicitly.
        if (configuredDeviceStore.all().isNotEmpty()) return false

        val legacyAddress = legacyBondedAddressProvider(appContext) ?: return false
        legacyBootstrapAddress = legacyAddress
        val accepted = client.startKnownDeviceAutoConnect(
            deviceAddress = legacyAddress,
            expectedStableId = null
        )
        if (!accepted) {
            legacyBootstrapAddress = null
        }
        return accepted
    }

    fun reconnectConfigured(): Boolean {
        autoReconnectSuppressedByUser = false
        if (otaState.isActive || explicitDiscoveryRequested || factoryResetPending) return false
        if (configuredDeviceStore.requiresNewPairing()) return false

        val configured = configuredDeviceStore.selected()
        if (configured != null) {
            legacyBootstrapAddress = null
            return client.startKnownDeviceReconnect(
                deviceAddress = configured.deviceAddress,
                expectedStableId = configured.stableId,
                timeoutMs = NORMAL_RECONNECT_TIMEOUT_MS
            )
        }

        if (configuredDeviceStore.all().isNotEmpty()) return false

        val legacyAddress = legacyBondedAddressProvider(appContext) ?: return false
        legacyBootstrapAddress = legacyAddress
        val accepted = client.startKnownDeviceReconnect(
            deviceAddress = legacyAddress,
            expectedStableId = null,
            timeoutMs = NORMAL_RECONNECT_TIMEOUT_MS
        )
        if (!accepted) {
            legacyBootstrapAddress = null
        }
        return accepted
    }

    fun startDiscovery(): Boolean {
        if (otaState.isActive || factoryResetPending) return false
        autoReconnectSuppressedByUser = false
        legacyBootstrapAddress = null
        pendingDiscoveredSelectionAddress = null
        pendingKnownSwitchStableId = null
        val accepted = client.startDiscovery()
        if (accepted) {
            explicitDiscoveryRequested = true
        }
        return accepted
    }

    fun connectDiscoveredDevice(deviceAddress: String): Boolean {
        if (
            deviceAddress.isBlank() ||
            otaState.isActive ||
            factoryResetPending ||
            !hasRequiredPermissions()
        ) {
            return false
        }
        autoReconnectSuppressedByUser = false
        explicitDiscoveryRequested = false
        legacyBootstrapAddress = null
        pendingKnownSwitchStableId = null
        pendingDiscoveredSelectionAddress = deviceAddress
        val accepted = client.connectDiscoveredDevice(deviceAddress)
        if (!accepted) {
            pendingDiscoveredSelectionAddress = null
        }
        return accepted
    }

    fun selectKnownDevice(stableId: String): Boolean {
        val target = configuredDeviceStore.all().firstOrNull {
            it.stableId == stableId
        } ?: return false
        if (
            otaState.isActive ||
            factoryResetPending ||
            configurationState.deviceControlBusy ||
            configurationState.factoryResetAwaitingDisconnect ||
            !hasRequiredPermissions()
        ) {
            return false
        }

        if (!configuredDeviceStore.select(stableId)) return false
        refreshDeviceSelectionState()
        autoReconnectSuppressedByUser = false
        explicitDiscoveryRequested = false
        legacyBootstrapAddress = null
        pendingDiscoveredSelectionAddress = null

        if (
            connectionState.status == RegattaLinkConnectionStatus.CONNECTED &&
            connectionState.deviceInfo?.stableId == stableId
        ) {
            pendingKnownSwitchStableId = null
            return true
        }

        pendingKnownSwitchStableId = stableId
        client.disconnect()
        val accepted = client.startKnownDeviceAutoConnect(
            deviceAddress = target.deviceAddress,
            expectedStableId = target.stableId
        )
        if (!accepted) {
            pendingKnownSwitchStableId = null
        }
        return accepted
    }

    fun disconnect() {
        if (factoryResetPending) return
        autoReconnectSuppressedByUser = true
        explicitDiscoveryRequested = false
        legacyBootstrapAddress = null
        pendingDiscoveredSelectionAddress = null
        pendingKnownSwitchStableId = null
        stopRawCanCapture(interrupted = true)
        client.disconnect()
    }

    fun startOta(artifact: RegattaLinkFirmwareArtifact) {
        if (
            factoryResetPending ||
            configurationState.factoryResetAwaitingDisconnect ||
            configurationState.restartAwaitingDisconnect ||
            configurationState.deviceControlBusy ||
            configurationState.diagnosticLogLoading ||
            configurationState.busy
        ) {
            return
        }
        explicitDiscoveryRequested = false
        legacyBootstrapAddress = null
        if (rawCaptureState.isActive) {
            stopRawCanCapture(interrupted = true)
        }
        client.startOta(artifact)
    }

    fun cancelOta() {
        client.cancelOta()
    }

    fun resetOtaState() {
        client.resetOtaState()
    }

    fun setDeviceName(name: String): Boolean {
        if (
            otaState.isActive ||
            rawCaptureState.isActive ||
            regattaLinkConfigurationMutationBlocked(
                state = configurationState,
                factoryResetOwned = factoryResetPending
            )
        ) {
            return false
        }
        return client.setDeviceName(name)
    }

    fun setLedBrightness(percent: Int): Boolean {
        if (
            otaState.isActive ||
            rawCaptureState.isActive ||
            regattaLinkConfigurationMutationBlocked(
                state = configurationState,
                factoryResetOwned = factoryResetPending
            )
        ) {
            return false
        }
        return client.setLedBrightness(percent)
    }

    fun setMotionDamping(seconds: Int): Boolean {
        if (
            seconds !in 1..10 ||
            otaState.isActive ||
            rawCaptureState.isActive ||
            regattaLinkConfigurationMutationBlocked(
                state = configurationState,
                factoryResetOwned = factoryResetPending
            )
        ) {
            return false
        }
        return client.setMotionDamping(seconds)
    }

    fun setLoadPrecisionX10(enabled: Boolean): Boolean {
        if (
            otaState.isActive ||
            rawCaptureState.isActive ||
            regattaLinkConfigurationMutationBlocked(
                state = configurationState,
                factoryResetOwned = factoryResetPending
            )
        ) {
            return false
        }
        return client.setLoadPrecisionX10(enabled)
    }

    fun setNmeaTxEnabled(enabled: Boolean): Boolean {
        if (
            otaState.isActive ||
            rawCaptureState.isActive ||
            regattaLinkConfigurationMutationBlocked(
                state = configurationState,
                factoryResetOwned = factoryResetPending
            )
        ) {
            return false
        }
        return client.setNmeaTxEnabled(enabled)
    }

    fun setNmeaAttitudeTxEnabled(enabled: Boolean): Boolean {
        if (
            otaState.isActive ||
            rawCaptureState.isActive ||
            regattaLinkConfigurationMutationBlocked(
                state = configurationState,
                factoryResetOwned = factoryResetPending
            )
        ) {
            return false
        }
        return client.setNmeaAttitudeTxEnabled(enabled)
    }

    fun setNmea0183TxEnabled(enabled: Boolean): Boolean =
        withConfigMutationAllowed {
            client.setNmea0183TxEnabled(enabled)
        }

    fun setPhoneGpsTxEnabled(enabled: Boolean): Boolean =
        withConfigMutationAllowed {
            client.setPhoneGpsTxEnabled(enabled)
        }

    fun setCompassTxEnabled(enabled: Boolean): Boolean =
        withConfigMutationAllowed {
            client.setCompassTxEnabled(enabled)
        }

    fun setNmea0183Baud(baudRate: Int): Boolean {
        if (RegattaLinkNmea0183Baud.fromBaudRate(baudRate) == null) {
            return false
        }
        return withConfigMutationAllowed {
            client.setNmea0183Baud(baudRate)
        }
    }

    fun setMagBackgroundLearningEnabled(enabled: Boolean): Boolean =
        withConfigMutationAllowed {
            client.setMagBackgroundLearningEnabled(enabled)
        }

    fun setSubsystemEnabled(
        subsystem: RegattaLinkSubsystem,
        enabled: Boolean
    ): Boolean =
        withConfigMutationAllowed {
            client.setSubsystemEnabled(subsystem, enabled)
        }

    fun applyTxSelectionAndRestart(encodedBits: UInt): Boolean =
        applyConfigBitsAndRestart(
            mask = REGATTALINK_CONFIG_TX_SELECTION_MASK,
            encodedBits = encodedBits
        )

    fun applySubsystemSelectionAndRestart(encodedBits: UInt): Boolean =
        applyConfigBitsAndRestart(
            mask = REGATTALINK_CONFIG_SESSION_SUBSYSTEM_MASK,
            encodedBits = encodedBits
        )

    private fun applyConfigBitsAndRestart(
        mask: UInt,
        encodedBits: UInt
    ): Boolean {
        if (
            encodedBits and mask.inv() != 0u ||
            !configurationState.configWordSupported ||
            configurationState.configWord == null ||
            !configurationState.deviceControlSupported
        ) {
            return false
        }
        return withConfigMutationAllowed {
            client.applyConfigBitsAndRestart(mask, encodedBits)
        }
    }

    fun setHeadingTrimDeg(value: Int): Boolean {
        if (value !in -180..180) return false
        return withConfigMutationAllowed {
            client.setHeadingTrimDeg(value)
        }
    }

    fun isPhoneGnssForwardingEnabled(): Boolean =
        regattaLinkPhoneGnssForwardingGate(
            connected =
                connectionState.status == RegattaLinkConnectionStatus.CONNECTED,
            transportReady = connectionState.phoneGnssTransportReady,
            otaActive = otaState.isActive,
            configurationState = configurationState
        )

    fun offerPhoneGnss(location: Location): Boolean {
        if (!isPhoneGnssForwardingEnabled()) return false

        val sample = regattaLinkPhoneGnssSample(location)
        val retainedCogDeg = regattaLinkRetainedPhoneGnssCog(
            currentCogDeg = sample.cogDeg,
            previousCogDeg = lastPhoneGnssCogDeg
        )
        lastPhoneGnssCogDeg = retainedCogDeg

        return client.offerPhoneGnss(
            sample.copy(cogDeg = retainedCogDeg)
        )
    }

    fun stopPhoneGnssForwarding() {
        client.clearPhoneGnss()
    }

    private inline fun withConfigMutationAllowed(
        action: () -> Boolean
    ): Boolean {
        if (
            otaState.isActive ||
            rawCaptureState.isActive ||
            regattaLinkConfigurationMutationBlocked(
                state = configurationState,
                factoryResetOwned = factoryResetPending
            )
        ) {
            return false
        }
        return action()
    }

    fun setLoadSensorAlias(identityKey: String, alias: String): Boolean {
        val sensor = nmeaState.loadSensors.firstOrNull {
            it.identityKey == identityKey
        } ?: return false
        if (!sensor.stableIdentity) return false
        if (!loadAliasStore.set(identityKey, alias)) return false

        val persistedAlias = loadAliasStore.get(identityKey)
        val sensors = nmeaState.loadSensors.map {
            if (it.identityKey == identityKey) {
                it.copy(alias = persistedAlias)
            } else {
                it
            }
        }
        RegattaLinkLoadSnapshotStore.updateAlias(
            identityKey = identityKey,
            alias = persistedAlias
        )
        handleNmeaState(nmeaState.copy(loadSensors = sensors))
        return true
    }

    fun drainDiagnosticLog(): Boolean {
        if (
            !configurationState.diagnosticLogSupported ||
            otaState.isActive ||
            rawCaptureState.isActive ||
            configurationState.deviceControlBusy
        ) {
            return false
        }
        val accepted = client.drainDiagnosticLog()
        if (accepted) {
            handleConfigurationState(
                configurationState.copy(
                    diagnosticLogLoading = true,
                    diagnosticLogEntries = emptyList(),
                    diagnosticLogError = ""
                )
            )
        }
        return accepted
    }

    fun executeDeviceControl(
        opcode: RegattaLinkDeviceControlOpcode,
        value: Int
    ): Boolean {
        if (
            !configurationState.deviceControlSupported ||
            otaState.isActive ||
            rawCaptureState.isActive ||
            configurationState.diagnosticLogLoading
        ) {
            return false
        }
        if (
            opcode in setOf(
                RegattaLinkDeviceControlOpcode.ADJUST_FORWARD,
                RegattaLinkDeviceControlOpcode.ADJUST_HEEL,
                RegattaLinkDeviceControlOpcode.ADJUST_PITCH
            ) &&
            configurationState.deviceControlStatus?.boatFrameValid != true
        ) {
            return false
        }

        val accepted = client.executeDeviceControl(opcode, value)
        if (accepted) {
            handleConfigurationState(
                configurationState.copy(
                    deviceControlBusy = true,
                    deviceControlAcceptedOpcode = null,
                    deviceControlAcceptedRequestId = null,
                    factoryResetWriteAcceptedRequestId = null,
                    deviceControlError = ""
                )
            )
        }
        return accepted
    }

    fun setImuRawPreviewEnabled(enabled: Boolean): Boolean {
        if (
            otaState.isActive ||
            rawCaptureState.isActive ||
            configurationState.diagnosticLogLoading
        ) {
            return false
        }
        return client.setImuRawPreviewEnabled(enabled)
    }

    fun refreshPgnInventory(): Boolean {
        if (otaState.isActive || rawCaptureState.isActive) return false
        return client.refreshPgnInventory()
    }

    fun readRawCanFrames(): Boolean {
        if (otaState.isActive || rawCaptureState.isActive) return false
        return client.readRawCanFrames()
    }

    fun startRawCanCapture(): Boolean {
        if (
            !regattaLinkRawCaptureStartAllowed(
                connected =
                    connectionState.status == RegattaLinkConnectionStatus.CONNECTED,
                otaActive = otaState.isActive,
                configurationState = configurationState,
                nmeaState = nmeaState,
                rawCaptureState = rawCaptureState
            )
        ) {
            return false
        }

        val captureDir = File(appContext.cacheDir, "regattalink-captures")
        if (!captureDir.exists() && !captureDir.mkdirs()) {
            emitRawCapture(
                RegattaLinkRawCaptureState(
                    phase = RegattaLinkRawCapturePhase.ERROR,
                    userMessage = RegattaLinkUiMessage.RAW_CAPTURE_DIRECTORY_FAILED,
                    error = "Could not create raw CAN capture directory"
                )
            )
            return false
        }

        val timestamp = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .format(ZonedDateTime.now())
        val fileName = "regattalink-boat-data-capture-$timestamp.csv"
        val file = File(captureDir, fileName)
        val session = try {
            RegattaLinkRawCaptureFileSession(file)
        } catch (error: Exception) {
            emitRawCapture(
                RegattaLinkRawCaptureState(
                    phase = RegattaLinkRawCapturePhase.ERROR,
                    userMessage = RegattaLinkUiMessage.RAW_CAPTURE_FILE_FAILED,
                    error = error.message ?: "Could not create raw CAN capture file"
                )
            )
            return false
        }

        synchronized(rawCaptureLock) {
            rawCaptureFileSession = session
            rawCaptureFrameCount = 0
            rawCaptureLastUiEmitMs = 0L
        }

        val startedAt = SystemClock.elapsedRealtime()
        emitRawCapture(
            RegattaLinkRawCaptureState(
                phase = RegattaLinkRawCapturePhase.FLUSHING,
                startedAtElapsedMs = startedAt,
                fileName = fileName,
                filePath = file.absolutePath
            )
        )

        val accepted = client.startRawCanCapture(
            onRecordingStarted = {
                emitRawCapture(
                    rawCaptureState.copy(
                        phase = RegattaLinkRawCapturePhase.CAPTURING,
                        userMessage = null,
                        error = ""
                    )
                )
            },
            onFrame = { frame ->
                val frameCount: Int
                val shouldEmit: Boolean
                synchronized(rawCaptureLock) {
                    val activeSession = rawCaptureFileSession
                        ?: throw IllegalStateException(
                            "Raw CAN capture file session is unavailable"
                        )
                    activeSession.append(frame)
                    rawCaptureFrameCount += 1
                    frameCount = rawCaptureFrameCount
                    val now = SystemClock.elapsedRealtime()
                    shouldEmit =
                        now - rawCaptureLastUiEmitMs >= 250L ||
                            frameCount == 1
                    if (shouldEmit) {
                        rawCaptureLastUiEmitMs = now
                    }
                }
                if (shouldEmit) {
                    emitRawCapture(
                        rawCaptureState.copy(
                            phase = RegattaLinkRawCapturePhase.CAPTURING,
                            frameCount = frameCount,
                            userMessage = null,
                            error = ""
                        )
                    )
                }
            },
            onFinished = { reason, error ->
                val finalCount: Int
                synchronized(rawCaptureLock) {
                    finalCount = rawCaptureFrameCount
                    runCatching { rawCaptureFileSession?.close() }
                    rawCaptureFileSession = null
                }

                val phase = when (reason) {
                    RegattaLinkRawCaptureEndReason.TIMEOUT,
                    RegattaLinkRawCaptureEndReason.USER_STOP ->
                        RegattaLinkRawCapturePhase.COMPLETED
                    RegattaLinkRawCaptureEndReason.INTERRUPTED ->
                        RegattaLinkRawCapturePhase.INTERRUPTED
                    RegattaLinkRawCaptureEndReason.ERROR ->
                        RegattaLinkRawCapturePhase.ERROR
                }
                emitRawCapture(
                    rawCaptureState.copy(
                        phase = phase,
                        frameCount = finalCount,
                        userMessage =
                            RegattaLinkUiMessage.RAW_CAPTURE_FAILED
                                .takeIf { error.isNotBlank() },
                        error = error
                    )
                )
            }
        )

        if (!accepted) {
            synchronized(rawCaptureLock) {
                runCatching { rawCaptureFileSession?.close() }
                rawCaptureFileSession = null
            }
            file.delete()
            emitRawCapture(
                RegattaLinkRawCaptureState(
                    phase = RegattaLinkRawCapturePhase.ERROR,
                    userMessage = RegattaLinkUiMessage.RAW_CAPTURE_START_FAILED,
                    error = "Could not start raw CAN capture"
                )
            )
        }
        return accepted
    }

    fun stopRawCanCapture(interrupted: Boolean = false) {
        if (!rawCaptureState.isActive) return
        client.stopRawCanCapture(
            if (interrupted) {
                RegattaLinkRawCaptureStopReason.INTERRUPTED
            } else {
                RegattaLinkRawCaptureStopReason.USER
            }
        )
    }

    fun discardRawCanCapture(): Boolean {
        if (rawCaptureState.isActive) return false
        val path = rawCaptureState.filePath
        val deleted = path.isNullOrBlank() || File(path).let { file ->
            !file.exists() || file.delete()
        }
        if (deleted) {
            emitRawCapture(RegattaLinkRawCaptureState())
        }
        return deleted
    }

    fun exportRawCanCapture(uri: Uri): Boolean {
        if (rawCaptureState.isActive) return false
        val path = rawCaptureState.filePath ?: return false
        return try {
            File(path).inputStream().use { input ->
                appContext.contentResolver.openOutputStream(uri)?.use { output ->
                    input.copyTo(output)
                } ?: throw IllegalStateException("Could not open export destination")
            }
            emitRawCapture(
                rawCaptureState.copy(userMessage = null, error = "")
            )
            true
        } catch (error: Exception) {
            emitRawCapture(
                rawCaptureState.copy(
                    userMessage = RegattaLinkUiMessage.RAW_CAPTURE_EXPORT_FAILED,
                    error = error.message ?: "Could not export raw CAN capture"
                )
            )
            false
        }
    }

    internal fun currentRawCaptureState(): RegattaLinkRawCaptureState =
        rawCaptureState

    internal fun configuredDevice(): RegattaLinkConfiguredDevice? =
        configuredDeviceStore.selected()

    internal fun currentDeviceSelectionState(): RegattaLinkDeviceSelectionState =
        deviceSelectionState

    internal fun requiresNewPairing(): Boolean =
        configuredDeviceStore.requiresNewPairing()

    @SuppressLint("MissingPermission")
    fun refreshBluetoothAvailability() {
        if (!hasRequiredPermissions()) return
        val enabled = runCatching {
            val manager =
                appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            manager.adapter?.isEnabled == true
        }.getOrDefault(false)
        if (enabled) {
            client.onBluetoothAdapterEnabled()
        } else {
            client.onBluetoothAdapterDisabled()
        }
    }

    private fun hasRequiredPermissions(): Boolean =
        RegattaLinkBleClient.requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(
                appContext,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun handleConnectionState(state: RegattaLinkClientState) {
        connectionState = state

        if (
            factoryResetPending &&
            state.status == RegattaLinkConnectionStatus.IDLE
        ) {
            factoryResetPending = false
            removeFactoryResetDevice()
            legacyBootstrapAddress = null
            explicitDiscoveryRequested = false
        }

        if (
            rawCaptureState.isActive &&
            state.status != RegattaLinkConnectionStatus.CONNECTED
        ) {
            client.stopRawCanCapture(
                RegattaLinkRawCaptureStopReason.INTERRUPTED
            )
        }

        val info = state.deviceInfo
        val bootstrapAddress = legacyBootstrapAddress
        if (
            state.status == RegattaLinkConnectionStatus.CONNECTED &&
            info != null &&
            bootstrapAddress != null &&
            state.deviceAddress.equals(bootstrapAddress, ignoreCase = true)
        ) {
            configuredDeviceStore.upsertAndSelect(
                RegattaLinkConfiguredDevice(
                    stableId = info.stableId,
                    deviceAddress = state.deviceAddress,
                    deviceName = state.deviceName
                )
            )
            refreshDeviceSelectionState()
            legacyBootstrapAddress = null
        } else if (
            state.status == RegattaLinkConnectionStatus.ERROR &&
            bootstrapAddress != null
        ) {
            legacyBootstrapAddress = null
        }

        val discoveredAddress = pendingDiscoveredSelectionAddress
        if (
            state.status == RegattaLinkConnectionStatus.CONNECTED &&
            info != null &&
            discoveredAddress != null &&
            state.deviceAddress.equals(discoveredAddress, ignoreCase = true)
        ) {
            configuredDeviceStore.upsertAndSelect(
                RegattaLinkConfiguredDevice(
                    stableId = info.stableId,
                    deviceAddress = state.deviceAddress,
                    deviceName = state.deviceName
                )
            )
            pendingDiscoveredSelectionAddress = null
            refreshDeviceSelectionState()
        } else if (
            discoveredAddress != null &&
            state.status in setOf(
                RegattaLinkConnectionStatus.ERROR,
                RegattaLinkConnectionStatus.BLUETOOTH_OFF
            )
        ) {
            pendingDiscoveredSelectionAddress = null
            handler.post { ensureBackgroundConnectedIfPermitted() }
        }

        val pendingSwitch = pendingKnownSwitchStableId
        if (
            pendingSwitch != null &&
            state.status == RegattaLinkConnectionStatus.CONNECTED &&
            info?.stableId == pendingSwitch
        ) {
            pendingKnownSwitchStableId = null
        } else if (
            pendingSwitch != null &&
            state.status == RegattaLinkConnectionStatus.ERROR
        ) {
            pendingKnownSwitchStableId = null
            handler.post { ensureBackgroundConnectedIfPermitted() }
        }

        listeners.forEach { it.onConnectionStateChanged(state) }
    }

    private fun handleDiscoveryState(state: RegattaLinkDiscoveryState) {
        explicitDiscoveryRequested = state.scanning
        deviceSelectionState = deviceSelectionState.copy(discovery = state)
        listeners.forEach { it.onDeviceSelectionStateChanged(deviceSelectionState) }
        if (
            !state.scanning &&
            pendingDiscoveredSelectionAddress == null &&
            !autoReconnectSuppressedByUser
        ) {
            handler.post { ensureBackgroundConnectedIfPermitted() }
        }
    }

    private fun refreshDeviceSelectionState() {
        deviceSelectionState = deviceSelectionState.copy(
            knownDevices = configuredDeviceStore.all(),
            selectedStableId = configuredDeviceStore.selected()?.stableId
        )
        listeners.forEach { it.onDeviceSelectionStateChanged(deviceSelectionState) }
    }

    private fun handleOtaState(state: RegattaLinkOtaUiState) {
        otaState = state
        listeners.forEach { it.onOtaStateChanged(state) }
    }

    private fun handleTelemetryState(state: RegattaLinkTelemetryState) {
        telemetryState = state
        listeners.forEach { it.onTelemetryStateChanged(state) }
    }

    private fun handleFactoryResetRecoveryStateChanged(pending: Boolean) {
        if (pending) {
            factoryResetPending = true
            factoryResetStableId =
                connectionState.deviceInfo?.stableId
                    ?: configuredDeviceStore.selected()?.stableId
            configuredDeviceStore.markResetRecoveryPending(factoryResetStableId)
            return
        }

        val stableId = factoryResetStableId
        if (configuredDeviceStore.selected() != null) {
            factoryResetPending = false
            configuredDeviceStore.clearResetRecoveryPending(stableId)
            factoryResetStableId = null
        }
    }

    private fun handleConfigurationState(state: RegattaLinkConfigurationState) {
        configurationState = state

        if (
            state.deviceControlAcceptedOpcode ==
                RegattaLinkDeviceControlOpcode.FACTORY_RESET &&
            state.deviceControlAcceptedRequestId != null
        ) {
            factoryResetPending = true
            factoryResetStableId =
                connectionState.deviceInfo?.stableId
                    ?: configuredDeviceStore.selected()?.stableId
            configuredDeviceStore.markResetRecoveryPending(factoryResetStableId)
        }

        if (
            factoryResetPending &&
            state.deviceControlStatus?.opcode ==
                RegattaLinkDeviceControlOpcode.FACTORY_RESET &&
            state.deviceControlStatus.factoryResetBondsCleared
        ) {
            removeFactoryResetDevice()
            legacyBootstrapAddress = null
            explicitDiscoveryRequested = false
        }

        val resetStatus = state.deviceControlStatus
        if (
            factoryResetPending &&
            !state.deviceControlBusy &&
            !state.factoryResetAwaitingDisconnect &&
            state.deviceControlError.isNotBlank() &&
            connectionState.status == RegattaLinkConnectionStatus.CONNECTED &&
            resetStatus?.opcode == RegattaLinkDeviceControlOpcode.FACTORY_RESET &&
            resetStatus.phase.isTerminal &&
            !regattaLinkFactoryResetContinuesToBondReset(resetStatus) &&
            !resetStatus.factoryResetBondsCleared
        ) {
            factoryResetPending = false
            configuredDeviceStore.clearResetRecoveryPending(factoryResetStableId)
            factoryResetStableId = null
        }

        val stableId = connectionState.deviceInfo?.stableId
        if (
            connectionState.status == RegattaLinkConnectionStatus.CONNECTED &&
            stableId != null &&
            state.deviceNameSupported &&
            state.deviceName.isNotBlank()
        ) {
            configuredDeviceStore.updateName(stableId, state.deviceName)
            refreshDeviceSelectionState()
            if (connectionState.deviceName != state.deviceName) {
                connectionState = connectionState.copy(deviceName = state.deviceName)
                listeners.forEach { it.onConnectionStateChanged(connectionState) }
            }
        }

        listeners.forEach { it.onConfigurationStateChanged(state) }
    }

    private fun removeFactoryResetDevice() {
        val stableId =
            factoryResetStableId
                ?: connectionState.deviceInfo?.stableId
                ?: configuredDeviceStore.selected()?.stableId
        if (!stableId.isNullOrBlank()) {
            configuredDeviceStore.remove(stableId)
        }
        factoryResetStableId = null
        refreshDeviceSelectionState()
    }

    private fun handleNmeaState(state: RegattaLinkNmeaState) {
        nmeaState = state
        listeners.forEach { it.onNmeaStateChanged(state) }
    }

    private fun emitRawCapture(state: RegattaLinkRawCaptureState) {
        rawCaptureState = state
        handler.post {
            if (rawCaptureState != state && state.isActive) {
                return@post
            }
            listeners.forEach { it.onRawCaptureStateChanged(state) }
        }
    }
}
