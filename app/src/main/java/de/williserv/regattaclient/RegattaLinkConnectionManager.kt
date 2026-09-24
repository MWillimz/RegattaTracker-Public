package de.williserv.regattaclient

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
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
            .apply()
    }

    fun updateName(stableId: String, deviceName: String) {
        val current = load() ?: return
        if (current.stableId != stableId) return
        save(current.copy(deviceName = deviceName))
    }
}

internal interface RegattaLinkConnectionListener {
    fun onConnectionStateChanged(state: RegattaLinkClientState) {}
    fun onOtaStateChanged(state: RegattaLinkOtaUiState) {}
    fun onTelemetryStateChanged(state: RegattaLinkTelemetryState) {}
    fun onConfigurationStateChanged(state: RegattaLinkConfigurationState) {}
    fun onNmeaStateChanged(state: RegattaLinkNmeaState) {}
}

internal interface RegattaLinkConnectionClient {
    fun startKnownDeviceReconnect(
        deviceAddress: String,
        expectedStableId: String,
        timeoutMs: Long
    ): Boolean

    fun startDiscovery(): Boolean
    fun disconnect()
    fun startOta(artifact: RegattaLinkFirmwareArtifact)
    fun cancelOta()
    fun resetOtaState()
    fun setDeviceName(name: String): Boolean
    fun setLedBrightness(percent: Int): Boolean
    fun refreshPgnInventory(): Boolean
    fun readRawCanFrames(): Boolean
}

internal fun interface RegattaLinkConnectionClientFactory {
    fun create(
        context: Context,
        onStateChanged: (RegattaLinkClientState) -> Unit,
        onOtaStateChanged: (RegattaLinkOtaUiState) -> Unit,
        onTelemetryStateChanged: (RegattaLinkTelemetryState) -> Unit,
        onConfigurationStateChanged: (RegattaLinkConfigurationState) -> Unit,
        onNmeaStateChanged: (RegattaLinkNmeaState) -> Unit,
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
                onUnexpectedDisconnect ->
            RegattaLinkBleClient(
                context = clientContext,
                onStateChanged = onStateChanged,
                onOtaStateChanged = onOtaStateChanged,
                onTelemetryStateChanged = onTelemetryStateChanged,
                onConfigurationStateChanged = onConfigurationStateChanged,
                onNmeaStateChanged = onNmeaStateChanged,
                onUnexpectedDisconnect = onUnexpectedDisconnect
            )
        }
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
    private var explicitDiscoveryRequested = false

    private val client = clientFactory.create(
        context = appContext,
        onStateChanged = ::handleConnectionState,
        onOtaStateChanged = ::handleOtaState,
        onTelemetryStateChanged = ::handleTelemetryState,
        onConfigurationStateChanged = ::handleConfigurationState,
        onNmeaStateChanged = ::handleNmeaState,
        onUnexpectedDisconnect = {
            handler.post {
                if (!otaState.isActive) {
                    reconnectConfigured()
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
        if (otaState.isActive || explicitDiscoveryRequested) return false
        val configured = configuredDeviceStore.load() ?: return false
        return client.startKnownDeviceReconnect(
            deviceAddress = configured.deviceAddress,
            expectedStableId = configured.stableId,
            timeoutMs = NORMAL_RECONNECT_TIMEOUT_MS
        )
    }

    fun startDiscovery(): Boolean {
        if (otaState.isActive) return false
        val accepted = client.startDiscovery()
        if (accepted) {
            explicitDiscoveryRequested = true
        }
        return accepted
    }

    fun disconnect() {
        explicitDiscoveryRequested = false
        client.disconnect()
    }

    fun startOta(artifact: RegattaLinkFirmwareArtifact) {
        explicitDiscoveryRequested = false
        client.startOta(artifact)
    }

    fun cancelOta() {
        client.cancelOta()
    }

    fun resetOtaState() {
        client.resetOtaState()
    }

    fun setDeviceName(name: String): Boolean {
        if (otaState.isActive) return false
        return client.setDeviceName(name)
    }

    fun setLedBrightness(percent: Int): Boolean {
        if (otaState.isActive) return false
        return client.setLedBrightness(percent)
    }

    fun refreshPgnInventory(): Boolean {
        if (otaState.isActive) return false
        return client.refreshPgnInventory()
    }

    fun readRawCanFrames(): Boolean {
        if (otaState.isActive) return false
        return client.readRawCanFrames()
    }

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

        val info = state.deviceInfo
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

    private fun handleConfigurationState(state: RegattaLinkConfigurationState) {
        configurationState = state

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
}
