package de.williserv.regattaclient

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
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

internal class RegattaLinkConfiguredDeviceStore(context: Context) {
    companion object {
        internal const val PREFS_NAME = "regattalink_connection"
        private const val KEY_STABLE_ID = "stable_id"
        private const val KEY_DEVICE_ADDRESS = "device_address"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_RESET_PENDING_PAIRING = "reset_pending_pairing"
    }

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): RegattaLinkConfiguredDevice? {
        val stableId = prefs.getString(KEY_STABLE_ID, "").orEmpty()
        val address = prefs.getString(KEY_DEVICE_ADDRESS, "").orEmpty()
        if (stableId.isBlank() || address.isBlank()) return null
        return RegattaLinkConfiguredDevice(
            stableId = stableId,
            deviceAddress = address,
            deviceName = prefs.getString(KEY_DEVICE_NAME, "").orEmpty()
        )
    }

    fun save(device: RegattaLinkConfiguredDevice) {
        require(device.stableId.isNotBlank())
        require(device.deviceAddress.isNotBlank())
        prefs.edit()
            .putString(KEY_STABLE_ID, device.stableId)
            .putString(KEY_DEVICE_ADDRESS, device.deviceAddress)
            .putString(KEY_DEVICE_NAME, device.deviceName)
            .putBoolean(KEY_RESET_PENDING_PAIRING, false)
            .apply()
    }

    fun updateName(stableId: String, deviceName: String) {
        val current = load() ?: return
        if (current.stableId != stableId) return
        prefs.edit()
            .putString(KEY_DEVICE_NAME, deviceName)
            .apply()
    }

    fun markResetRecoveryPending() {
        prefs.edit()
            .putBoolean(KEY_RESET_PENDING_PAIRING, true)
            .apply()
    }

    fun clearResetRecoveryPending() {
        prefs.edit()
            .putBoolean(KEY_RESET_PENDING_PAIRING, false)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().putBoolean(KEY_RESET_PENDING_PAIRING, true).apply()
    }

    fun requiresNewPairing(): Boolean = prefs.getBoolean(KEY_RESET_PENDING_PAIRING, false)
}

internal interface RegattaLinkConnectionListener {
    fun onConnectionStateChanged(state: RegattaLinkClientState) {}
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

    fun startDiscovery(): Boolean
    fun disconnect()
    fun startOta(artifact: RegattaLinkFirmwareArtifact)
    fun cancelOta()
    fun resetOtaState()
    fun setDeviceName(name: String): Boolean
    fun setLedBrightness(percent: Int): Boolean
    fun drainDiagnosticLog(): Boolean = false
    fun executeDeviceControl(
        opcode: RegattaLinkDeviceControlOpcode,
        value: Int
    ): Boolean = false
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
                onFactoryResetRecoveryStateChanged,
                onUnexpectedDisconnect ->
            RegattaLinkBleClient(
                context = clientContext,
                onStateChanged = onStateChanged,
                onOtaStateChanged = onOtaStateChanged,
                onTelemetryStateChanged = onTelemetryStateChanged,
                onConfigurationStateChanged = onConfigurationStateChanged,
                onNmeaStateChanged = onNmeaStateChanged,
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
    private val startupReconnectRequested = AtomicBoolean(false)

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

    private val client = clientFactory.create(
        context = appContext,
        onStateChanged = ::handleConnectionState,
        onOtaStateChanged = ::handleOtaState,
        onTelemetryStateChanged = ::handleTelemetryState,
        onConfigurationStateChanged = ::handleConfigurationState,
        onNmeaStateChanged = ::handleNmeaState,
        onFactoryResetRecoveryStateChanged =
            ::handleFactoryResetRecoveryStateChanged,
        onUnexpectedDisconnect = {
            handler.post {
                if (!otaState.isActive && !factoryResetPending) {
                    ensureConnectedIfPermitted()
                }
            }
        }
    )

    fun addListener(listener: RegattaLinkConnectionListener) {
        listeners.add(listener)
        handler.post {
            if (!listeners.contains(listener)) return@post
            listener.onConnectionStateChanged(connectionState)
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
        ensureConnectedIfPermitted()
    }

    fun ensureConnectedIfPermitted() {
        if (!hasRequiredPermissions()) return
        reconnectConfigured()
    }

    fun reconnectConfigured(): Boolean {
        if (otaState.isActive || explicitDiscoveryRequested || factoryResetPending) return false
        if (configuredDeviceStore.requiresNewPairing()) return false

        val configured = configuredDeviceStore.load()
        if (configured != null) {
            legacyBootstrapAddress = null
            return client.startKnownDeviceReconnect(
                deviceAddress = configured.deviceAddress,
                expectedStableId = configured.stableId,
                timeoutMs = NORMAL_RECONNECT_TIMEOUT_MS
            )
        }

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
        legacyBootstrapAddress = null
        val accepted = client.startDiscovery()
        if (accepted) {
            explicitDiscoveryRequested = true
        }
        return accepted
    }

    fun disconnect() {
        if (factoryResetPending) return
        explicitDiscoveryRequested = false
        legacyBootstrapAddress = null
        stopRawCanCapture(interrupted = true)
        client.disconnect()
    }

    fun startOta(artifact: RegattaLinkFirmwareArtifact) {
        if (
            factoryResetPending ||
            configurationState.factoryResetAwaitingDisconnect ||
            configurationState.deviceControlBusy ||
            configurationState.diagnosticLogLoading
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
        if (otaState.isActive || rawCaptureState.isActive) return false
        return client.setDeviceName(name)
    }

    fun setLedBrightness(percent: Int): Boolean {
        if (otaState.isActive || rawCaptureState.isActive) return false
        return client.setLedBrightness(percent)
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
        return client.drainDiagnosticLog()
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
        return client.executeDeviceControl(opcode, value)
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
            otaState.isActive ||
            rawCaptureState.isActive ||
            rawCaptureState.hasFile ||
            configurationState.diagnosticLogLoading ||
            configurationState.deviceControlBusy ||
            connectionState.status != RegattaLinkConnectionStatus.CONNECTED ||
            !nmeaState.rawCanSupported
        ) {
            return false
        }

        val captureDir = File(appContext.cacheDir, "regattalink-captures")
        if (!captureDir.exists() && !captureDir.mkdirs()) {
            emitRawCapture(
                RegattaLinkRawCaptureState(
                    phase = RegattaLinkRawCapturePhase.ERROR,
                    error = "Could not create raw CAN capture directory"
                )
            )
            return false
        }

        val timestamp = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .format(ZonedDateTime.now())
        val fileName = "regattalink-nmea-capture-$timestamp.csv"
        val file = File(captureDir, fileName)
        val session = try {
            RegattaLinkRawCaptureFileSession(file)
        } catch (error: Exception) {
            emitRawCapture(
                RegattaLinkRawCaptureState(
                    phase = RegattaLinkRawCapturePhase.ERROR,
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
            emitRawCapture(rawCaptureState.copy(error = ""))
            true
        } catch (error: Exception) {
            emitRawCapture(
                rawCaptureState.copy(
                    error = error.message ?: "Could not export raw CAN capture"
                )
            )
            false
        }
    }

    internal fun currentRawCaptureState(): RegattaLinkRawCaptureState =
        rawCaptureState

    internal fun configuredDevice(): RegattaLinkConfiguredDevice? =
        configuredDeviceStore.load()

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
            configuredDeviceStore.clear()
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
            configuredDeviceStore.save(
                RegattaLinkConfiguredDevice(
                    stableId = info.stableId,
                    deviceAddress = state.deviceAddress,
                    deviceName = state.deviceName
                )
            )
            legacyBootstrapAddress = null
        } else if (
            state.status == RegattaLinkConnectionStatus.ERROR &&
            bootstrapAddress != null
        ) {
            legacyBootstrapAddress = null
        }

        if (
            state.status == RegattaLinkConnectionStatus.CONNECTED &&
            info != null &&
            explicitDiscoveryRequested
        ) {
            configuredDeviceStore.save(
                RegattaLinkConfiguredDevice(
                    stableId = info.stableId,
                    deviceAddress = state.deviceAddress,
                    deviceName = state.deviceName
                )
            )
            explicitDiscoveryRequested = false
        } else if (
            state.status == RegattaLinkConnectionStatus.ERROR &&
            explicitDiscoveryRequested
        ) {
            explicitDiscoveryRequested = false
        }

        listeners.forEach { it.onConnectionStateChanged(state) }
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
            configuredDeviceStore.markResetRecoveryPending()
            return
        }

        if (configuredDeviceStore.load() != null) {
            factoryResetPending = false
            configuredDeviceStore.clearResetRecoveryPending()
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
            configuredDeviceStore.markResetRecoveryPending()
        }

        if (
            factoryResetPending &&
            state.deviceControlStatus?.opcode ==
                RegattaLinkDeviceControlOpcode.FACTORY_RESET &&
            state.deviceControlStatus.factoryResetBondsCleared
        ) {
            configuredDeviceStore.clear()
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
            configuredDeviceStore.clearResetRecoveryPending()
        }

        val stableId = connectionState.deviceInfo?.stableId
        if (
            connectionState.status == RegattaLinkConnectionStatus.CONNECTED &&
            stableId != null &&
            state.deviceNameSupported &&
            state.deviceName.isNotBlank()
        ) {
            configuredDeviceStore.updateName(stableId, state.deviceName)
            if (connectionState.deviceName != state.deviceName) {
                connectionState = connectionState.copy(deviceName = state.deviceName)
                listeners.forEach { it.onConnectionStateChanged(connectionState) }
            }
        }

        listeners.forEach { it.onConfigurationStateChanged(state) }
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
