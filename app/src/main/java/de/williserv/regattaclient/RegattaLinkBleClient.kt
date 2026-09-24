package de.williserv.regattaclient

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class RegattaLinkConnectionStatus {
    IDLE,
    SCANNING,
    BONDING,
    CONNECTING,
    DISCOVERING,
    READING_DEVICE_INFO,
    CONNECTED,
    ERROR
}

data class RegattaLinkClientState(
    val status: RegattaLinkConnectionStatus = RegattaLinkConnectionStatus.IDLE,
    val deviceName: String = "",
    val deviceAddress: String = "",
    val deviceInfo: RegattaLinkDeviceInfo? = null,
    val error: String = ""
)

@SuppressLint("MissingPermission")
internal class RegattaLinkBleClient(
    context: Context,
    private val onStateChanged: (RegattaLinkClientState) -> Unit,
    private val onOtaStateChanged: (RegattaLinkOtaUiState) -> Unit = {},
    private val onTelemetryStateChanged: (RegattaLinkTelemetryState) -> Unit = {},
    private val onUnexpectedDisconnect: () -> Unit = {}
) : RegattaLinkOtaTransport, RegattaLinkConnectionClient {
    companion object {
        val CONFIG_SERVICE_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b710001")
        val DEVICE_NAME_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b710002")
        val DEVICE_INFO_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b710003")
        val NMEA_PGN_INVENTORY_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b710004")
        val NMEA_RAW_CAN_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b710005")
        val LED_BRIGHTNESS_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b710006")
        val TELEMETRY_SERVICE_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b710020")
        val TELEMETRY_FAST_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b710021")
        val TELEMETRY_SUMMARY_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b710022")
        val TELEMETRY_CALIBRATION_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b710023")
        val TELEMETRY_BOAT_STATE_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b710024")

        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val BOND_TIMEOUT_MS = 30_000L
        private const val GATT_TIMEOUT_MS = 20_000L
        private const val BOND_POLL_MS = 250L
        private const val GATT_OPERATION_TIMEOUT_MS = 10_000L
        private const val OTA_RECONNECT_SERVICE_SETTLE_MS = 500L
        private const val KNOWN_RECONNECT_SCAN_SLICE_MS = 6_000L
        private const val KNOWN_RECONNECT_PAUSE_MS = 4_000L
        private const val REQUESTED_OTA_MTU = 247
        private const val OTA_PHY_REQUEST_GRACE_MS = 300L
        private const val LOG_TAG = "RegattaLinkBLE"

        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }

    private enum class ScanPurpose {
        NORMAL,
        KNOWN_DEVICE_RECONNECT,
        OTA_RECONNECT
    }

    private sealed class PendingGattOperation {
        data class CharacteristicWrite(
            val uuid: UUID,
            val future: CompletableFuture<Unit>
        ) : PendingGattOperation()

        data class CharacteristicRead(
            val uuid: UUID,
            val future: CompletableFuture<ByteArray>
        ) : PendingGattOperation()

        data class DescriptorWrite(
            val uuid: UUID,
            val future: CompletableFuture<Unit>
        ) : PendingGattOperation()

        data class Mtu(
            val future: CompletableFuture<Int>
        ) : PendingGattOperation()
    }

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val handler = Handler(Looper.getMainLooper())
    private val otaExecutor = Executors.newSingleThreadExecutor()

    private var scanner: BluetoothLeScanner? = null
    @Volatile private var scanActive = false
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var connected = false
    private var currentDevice: BluetoothDevice? = null
    private var bondDeadline = 0L
    private var scanPurpose = ScanPurpose.NORMAL

    @Volatile
    override var mtu: Int = 23
        private set

    private val pendingGattLock = Any()
    private var pendingGattOperation: PendingGattOperation? = null

    private val otaRunning = AtomicBoolean(false)
    private val otaCancelled = AtomicBoolean(false)
    private val otaProgressQueue = LinkedBlockingQueue<RegattaLinkOtaProgress>()
    private val otaDataTransportError = AtomicReference<String?>(null)
    @Volatile private var lastState = RegattaLinkClientState()
    @Volatile private var lastOtaState = RegattaLinkOtaUiState()
    private val deferredTerminalOtaState =
        AtomicReference<RegattaLinkOtaUiState?>(null)
    @Volatile private var lastTelemetryState = RegattaLinkTelemetryState()
    private val telemetryLock = Any()
    private val serviceRediscoveryPending = AtomicBoolean(false)
    private val serviceRediscoveryRequested = AtomicBoolean(false)
    private val serviceRediscoveryDeferredForOta = AtomicBoolean(false)
    @Volatile private var serviceDiscoveryInProgress = false
    @Volatile private var deviceInfoReadInProgress = false
    @Volatile private var connectionSetupComplete = false
    @Volatile private var establishedConnection = false
    private var serviceRediscoveryGatt: BluetoothGatt? = null
    private var reconnectFuture: CompletableFuture<RegattaLinkDeviceInfo?>? = null
    private var selectedDeviceAddress: String? = null
    private val attemptedDiscoveryAddresses = mutableSetOf<String>()
    private var discoveryInProgress = false
    private var discoveryCandidateInProgress = false
    private var knownReconnectAddress: String? = null
    private var knownReconnectExpectedStableId: String? = null
    private var knownReconnectDeadlineMs = 0L
    private var knownReconnectLastError = ""

    private val knownReconnectRetry = Runnable {
        beginKnownDeviceReconnectScan()
    }

    private val scanTimeout = Runnable {
        stopScan()
        if (scanPurpose == ScanPurpose.OTA_RECONNECT) {
            reconnectFuture?.complete(null)
        } else if (scanPurpose == ScanPurpose.KNOWN_DEVICE_RECONNECT) {
            retryKnownDeviceReconnect("Configured RegattaLink was not found")
        } else {
            discoveryInProgress = false
            discoveryCandidateInProgress = false
            emit(
                RegattaLinkClientState(
                    status = RegattaLinkConnectionStatus.ERROR,
                    error = "No available RegattaLink found"
                )
            )
        }
    }

    private val gattTimeout = Runnable {
        val device = currentDevice
        val wasEstablishedConnection = establishedConnection
        closeGatt()
        if (scanPurpose == ScanPurpose.OTA_RECONNECT) {
            reconnectFuture?.complete(null)
        } else if (scanPurpose == ScanPurpose.KNOWN_DEVICE_RECONNECT) {
            retryKnownDeviceReconnect("Configured RegattaLink connection timed out")
        } else if (device != null) {
            if (discoveryInProgress) {
                retryDiscoveryAfterCandidateFailure()
            } else {
                emitError(device, "RegattaLink connection timed out")
                if (
                    shouldStartRegattaLinkOutageReconnect(
                        connectionWasReady = wasEstablishedConnection,
                        otaOwnsConnection = otaRunning.get(),
                        knownReconnectAlreadyActive = false
                    )
                ) {
                    handler.post { onUnexpectedDisconnect() }
                }
            }
        }
    }

    private val serviceRediscovery = object : Runnable {
        override fun run() {
            val activeGatt = serviceRediscoveryGatt
            if (
                activeGatt == null ||
                gatt !== activeGatt ||
                !connected
            ) {
                serviceRediscoveryGatt = null
                serviceRediscoveryPending.set(false)
                serviceRediscoveryRequested.set(false)
                return
            }

            if (
                otaRunning.get() &&
                connectionSetupComplete
            ) {
                serviceRediscoveryDeferredForOta.set(true)
                serviceRediscoveryPending.set(false)
                return
            }

            val localGattBusy = synchronized(pendingGattLock) {
                pendingGattOperation != null
            }
            if (
                localGattBusy ||
                serviceDiscoveryInProgress ||
                deviceInfoReadInProgress
            ) {
                handler.postDelayed(this, 100L)
                return
            }

            serviceRediscoveryRequested.set(false)
            if (beginServiceDiscovery(activeGatt, "GATT Service Changed")) {
                serviceRediscoveryGatt = null
                serviceRediscoveryPending.set(false)
                return
            }

            serviceRediscoveryRequested.set(true)
            handler.postDelayed(this, 250L)
        }
    }

    private fun beginServiceDiscovery(
        activeGatt: BluetoothGatt,
        reason: String
    ): Boolean {
        if (
            gatt !== activeGatt ||
            !connected ||
            serviceDiscoveryInProgress ||
            deviceInfoReadInProgress
        ) {
            return false
        }

        val localGattBusy = synchronized(pendingGattLock) {
            pendingGattOperation != null
        }
        if (localGattBusy) return false

        connectionSetupComplete = false
        emitForDevice(
            activeGatt.device,
            RegattaLinkConnectionStatus.DISCOVERING
        )
        if (!activeGatt.discoverServices()) {
            return false
        }

        serviceDiscoveryInProgress = true
        handler.removeCallbacks(gattTimeout)
        handler.postDelayed(gattTimeout, currentGattTimeoutMs())
        Log.i(LOG_TAG, "RegattaLink service discovery started: $reason")
        return true
    }

    private fun scheduleServiceRediscovery(
        activeGatt: BluetoothGatt,
        reason: String
    ) {
        if (gatt !== activeGatt || !connected) return
        serviceRediscoveryRequested.set(true)
        Log.i(LOG_TAG, "$reason; queued service rediscovery")
        serviceRediscoveryGatt = activeGatt
        if (serviceRediscoveryPending.compareAndSet(false, true)) {
            handler.post(serviceRediscovery)
        }
    }

    private val bondPoll = object : Runnable {
        override fun run() {
            val device = currentDevice ?: return
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> connectGatt(device)
                else -> {
                    if (SystemClock.elapsedRealtime() >= bondDeadline) {
                        if (scanPurpose == ScanPurpose.OTA_RECONNECT) {
                            reconnectFuture?.complete(null)
                        } else if (discoveryInProgress) {
                            retryDiscoveryAfterCandidateFailure()
                        } else {
                            emitError(device, "RegattaLink pairing timed out")
                        }
                    } else {
                        handler.postDelayed(this, BOND_POLL_MS)
                    }
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanActive) return
            val device = result.device
            if (scanPurpose == ScanPurpose.NORMAL && discoveryInProgress) {
                if (discoveryCandidateInProgress) return
                if (!attemptedDiscoveryAddresses.add(device.address)) return
                discoveryCandidateInProgress = true
            }
            if (
                scanPurpose == ScanPurpose.KNOWN_DEVICE_RECONNECT &&
                device.address != knownReconnectAddress
            ) {
                return
            }
            stopScan()
            prepareDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            if (!scanActive) return
            scanActive = false
            handler.removeCallbacks(scanTimeout)
            if (scanPurpose == ScanPurpose.OTA_RECONNECT) {
                reconnectFuture?.complete(null)
            } else if (scanPurpose == ScanPurpose.KNOWN_DEVICE_RECONNECT) {
                retryKnownDeviceReconnect("Bluetooth scan failed ($errorCode)")
            } else {
                discoveryInProgress = false
                discoveryCandidateInProgress = false
                emit(
                    RegattaLinkClientState(
                        status = RegattaLinkConnectionStatus.ERROR,
                        error = "Bluetooth scan failed ($errorCode)"
                    )
                )
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            callbackGatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (gatt !== callbackGatt) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    callbackGatt.close()
                }
                return
            }

            if (
                status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                connected = true
                connectionSetupComplete = false
                serviceDiscoveryInProgress = false
                deviceInfoReadInProgress = false
                serviceRediscoveryRequested.set(false)
                serviceRediscoveryDeferredForOta.set(false)
                if (!beginServiceDiscovery(callbackGatt, "initial connection")) {
                    closeGattWithError(
                        callbackGatt,
                        "Could not discover RegattaLink services"
                    )
                }
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasReadyConnection = establishedConnection
                connected = false
                establishedConnection = false
                resetServiceDiscoveryState()
                failPendingGattOperation(
                    RegattaLinkOtaTransportException(
                        "RegattaLink disconnected during GATT operation"
                    )
                )
                callbackGatt.close()
                if (gatt === callbackGatt) {
                    gatt = null
                }

                if (otaRunning.get()) {
                    updateTelemetry {
                        it.copy(
                            subscribed = false,
                            pausedForOta = true
                        )
                    }
                } else {
                    clearTelemetry()
                }

                if (scanPurpose == ScanPurpose.OTA_RECONNECT) {
                    reconnectFuture?.complete(null)
                } else if (scanPurpose == ScanPurpose.KNOWN_DEVICE_RECONNECT) {
                    retryKnownDeviceReconnect("Configured RegattaLink disconnected ($status)")
                } else if (!otaRunning.get()) {
                    if (discoveryInProgress) {
                        retryDiscoveryAfterCandidateFailure()
                    } else {
                        emitError(
                            callbackGatt.device,
                            "RegattaLink disconnected ($status)"
                        )
                        if (
                            shouldStartRegattaLinkOutageReconnect(
                                connectionWasReady = wasReadyConnection,
                                otaOwnsConnection = otaRunning.get(),
                                knownReconnectAlreadyActive = false
                            )
                        ) {
                            handler.post { onUnexpectedDisconnect() }
                        }
                    }
                }
            }
        }

        override fun onServiceChanged(callbackGatt: BluetoothGatt) {
            if (gatt !== callbackGatt) return

            /*
             * Never start a second discoverServices() in parallel. During
             * connection setup the current discovery / Device Info result is
             * discarded and one fresh discovery is serialized afterwards. If
             * OTA post-boot validation already owns a fully established
             * connection, defer the rediscovery until the OTA reaches a
             * terminal state so CCCD / SNAPSHOT operations cannot race it.
             */
            if (otaRunning.get() && connectionSetupComplete) {
                serviceRediscoveryRequested.set(true)
                serviceRediscoveryDeferredForOta.set(true)
                Log.i(
                    LOG_TAG,
                    "RegattaLink GATT Service Changed deferred until OTA completes"
                )
                return
            }

            scheduleServiceRediscovery(
                callbackGatt,
                "RegattaLink GATT Service Changed received"
            )
        }

        override fun onServicesDiscovered(
            callbackGatt: BluetoothGatt,
            status: Int
        ) {
            if (gatt !== callbackGatt) return
            serviceDiscoveryInProgress = false

            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGattWithError(
                    callbackGatt,
                    "RegattaLink service discovery failed ($status)"
                )
                return
            }

            if (serviceRediscoveryRequested.get()) {
                scheduleServiceRediscovery(
                    callbackGatt,
                    "Service Changed arrived during discovery"
                )
                return
            }

            val service: BluetoothGattService? =
                callbackGatt.getService(CONFIG_SERVICE_UUID)
            val characteristic: BluetoothGattCharacteristic? =
                service?.getCharacteristic(DEVICE_INFO_UUID)

            if (characteristic == null) {
                closeGattWithError(
                    callbackGatt,
                    "RegattaLink Device Info is unavailable"
                )
                return
            }

            emitForDevice(
                callbackGatt.device,
                RegattaLinkConnectionStatus.READING_DEVICE_INFO
            )
            deviceInfoReadInProgress = true
            if (!callbackGatt.readCharacteristic(characteristic)) {
                deviceInfoReadInProgress = false
                closeGattWithError(
                    callbackGatt,
                    "Could not read RegattaLink Device Info"
                )
            }
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicRead(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (gatt !== callbackGatt) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleCharacteristicRead(
                    callbackGatt,
                    characteristic.uuid,
                    characteristic.value ?: byteArrayOf(),
                    status
                )
            }
        }

        override fun onCharacteristicRead(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (gatt !== callbackGatt) return
            handleCharacteristicRead(
                callbackGatt,
                characteristic.uuid,
                value,
                status
            )
        }

        override fun onCharacteristicWrite(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (gatt !== callbackGatt) return
            val handled = completeCharacteristicWrite(
                characteristic.uuid,
                status
            )
            if (
                !handled &&
                characteristic.uuid == REGATTALINK_OTA_DATA_UUID &&
                status != BluetoothGatt.GATT_SUCCESS
            ) {
                otaDataTransportError.compareAndSet(
                    null,
                    "OTA DATA write callback failed ($status)"
                )
            }
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleCharacteristicChanged(
                    callbackGatt,
                    characteristic.uuid,
                    characteristic.value ?: byteArrayOf()
                )
            }
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (gatt !== callbackGatt) return
            handleCharacteristicChanged(callbackGatt, characteristic.uuid, value)
        }

        override fun onDescriptorWrite(
            callbackGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (gatt !== callbackGatt) return
            completeDescriptorWrite(descriptor.uuid, status)
        }

        override fun onMtuChanged(
            callbackGatt: BluetoothGatt,
            negotiatedMtu: Int,
            status: Int
        ) {
            if (gatt !== callbackGatt) return
            if (status == BluetoothGatt.GATT_SUCCESS && negotiatedMtu >= 23) {
                mtu = negotiatedMtu
            }
            completeMtu(mtu)
        }

        override fun onPhyUpdate(
            callbackGatt: BluetoothGatt,
            txPhy: Int,
            rxPhy: Int,
            status: Int
        ) {
            if (gatt !== callbackGatt) return
            Log.i(
                LOG_TAG,
                "PHY update status=${status} tx=${phyName(txPhy)} rx=${phyName(rxPhy)}"
            )
        }
    }

    override fun startDiscovery(): Boolean {
        if (otaRunning.get()) return false
        cancelKnownDeviceReconnect()
        clearTelemetry()
        selectedDeviceAddress = null
        attemptedDiscoveryAddresses.clear()
        discoveryInProgress = true
        discoveryCandidateInProgress = false
        stopScan()
        handler.removeCallbacks(bondPoll)
        closeGatt()
        currentDevice = null
        scanPurpose = ScanPurpose.NORMAL

        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            emit(
                RegattaLinkClientState(
                    status = RegattaLinkConnectionStatus.ERROR,
                    error = "Bluetooth is disabled"
                )
            )
            return true
        }

        scanner = adapter.bluetoothLeScanner
        val activeScanner = scanner
        if (activeScanner == null) {
            emit(
                RegattaLinkClientState(
                    status = RegattaLinkConnectionStatus.ERROR,
                    error = "Bluetooth LE is unavailable"
                )
            )
            return true
        }

        emit(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.SCANNING
            )
        )
        startFilteredScan(activeScanner)
        return true
    }

    override fun startOta(artifact: RegattaLinkFirmwareArtifact) {
        if (!otaRunning.compareAndSet(false, true)) return

        val info = lastState.deviceInfo
        if (
            lastState.status != RegattaLinkConnectionStatus.CONNECTED ||
            info == null ||
            !isConnected()
        ) {
            otaRunning.set(false)
            emitOta(
                RegattaLinkOtaUiState(
                    phase = RegattaLinkOtaPhase.ERROR,
                    error = "Connect RegattaLink before installing firmware"
                )
            )
            return
        }

        val validationFailure = runCatching {
            validateRegattaLinkFirmwareArtifact(
                artifact.manifest,
                artifact.image,
                info
            )
            validateRegattaLinkOtaDevice(info, artifact)
        }.exceptionOrNull()

        if (validationFailure != null) {
            otaRunning.set(false)
            emitOta(
                RegattaLinkOtaUiState(
                    phase = RegattaLinkOtaPhase.ERROR,
                    installedBuild = info.runningBuild.toString(),
                    targetBuild = artifact.manifest.buildNumber.toString(),
                    error = validationFailure.message
                        ?: "Firmware is not compatible with this RegattaLink"
                )
            )
            return
        }

        otaCancelled.set(false)
        otaProgressQueue.clear()
        otaDataTransportError.set(null)
        deferredTerminalOtaState.set(null)

        otaExecutor.execute {
            try {
                RegattaLinkOtaEngine(
                    artifact = artifact,
                    initialDeviceInfo = info,
                    transport = this,
                    cancelled = { otaCancelled.get() },
                    emit = ::emitOta,
                    onTerminalDisconnect = ::invalidateTerminalOtaConnection
                ).run()
            } finally {
                otaRunning.set(false)
                otaCancelled.set(false)
                scanPurpose = ScanPurpose.NORMAL

                if (serviceRediscoveryDeferredForOta.getAndSet(false)) {
                    val activeGatt = gatt
                    if (activeGatt != null && connected) {
                        serviceRediscoveryRequested.set(true)
                        handler.post {
                            scheduleServiceRediscovery(
                                activeGatt,
                                "Deferred RegattaLink GATT Service Changed"
                            )
                        }
                    }
                }

                flushDeferredTerminalOtaState()
            }
        }
    }

    override fun cancelOta() {
        if (!otaRunning.get()) return
        if (
            lastOtaState.phase !in setOf(
                RegattaLinkOtaPhase.PREPARING,
                RegattaLinkOtaPhase.STARTING,
                RegattaLinkOtaPhase.TRANSFERRING
            )
        ) {
            return
        }
        otaCancelled.set(true)
        emitOta(
            lastOtaState.copy(
                phase = RegattaLinkOtaPhase.CANCELLING,
                detail = "Cancelling firmware update"
            )
        )
    }

    override fun resetOtaState() {
        if (otaRunning.get()) return
        emitOta(RegattaLinkOtaUiState())
    }

    override fun disconnect() {
        if (otaRunning.get()) {
            cancelOta()
            return
        }
        cancelKnownDeviceReconnect()
        stopScan()
        handler.removeCallbacks(bondPoll)
        handler.removeCallbacks(gattTimeout)
        closeGatt()
        clearTelemetry()
        currentDevice = null
        discoveryInProgress = false
        discoveryCandidateInProgress = false
        attemptedDiscoveryAddresses.clear()
        emit(RegattaLinkClientState())
    }

    fun close() {
        otaCancelled.set(true)
        cancelKnownDeviceReconnect()
        stopScan()
        handler.removeCallbacks(bondPoll)
        handler.removeCallbacks(gattTimeout)
        closeGatt()
        clearTelemetry()
        currentDevice = null
        discoveryInProgress = false
        discoveryCandidateInProgress = false
        attemptedDiscoveryAddresses.clear()
        otaExecutor.shutdownNow()
    }

    override fun startKnownDeviceReconnect(
        deviceAddress: String,
        expectedStableId: String,
        timeoutMs: Long
    ): Boolean {
        if (
            otaRunning.get() ||
            deviceAddress.isBlank() ||
            expectedStableId.isBlank() ||
            timeoutMs <= 0L
        ) {
            return false
        }

        if (
            isConnected() &&
            lastState.status == RegattaLinkConnectionStatus.CONNECTED &&
            lastState.deviceInfo?.stableId == expectedStableId
        ) {
            return true
        }

        if (
            scanPurpose == ScanPurpose.KNOWN_DEVICE_RECONNECT &&
            knownReconnectAddress == deviceAddress &&
            knownReconnectExpectedStableId == expectedStableId &&
            SystemClock.elapsedRealtime() < knownReconnectDeadlineMs
        ) {
            return true
        }

        stopScan()
        handler.removeCallbacks(knownReconnectRetry)
        handler.removeCallbacks(bondPoll)
        handler.removeCallbacks(gattTimeout)
        closeGatt()
        clearTelemetry()
        currentDevice = null
        discoveryInProgress = false
        discoveryCandidateInProgress = false
        attemptedDiscoveryAddresses.clear()
        scanPurpose = ScanPurpose.KNOWN_DEVICE_RECONNECT
        knownReconnectAddress = deviceAddress
        knownReconnectExpectedStableId = expectedStableId
        knownReconnectDeadlineMs = SystemClock.elapsedRealtime() + timeoutMs
        knownReconnectLastError = "Configured RegattaLink was not found"

        handler.post(knownReconnectRetry)
        return true
    }

    private fun beginKnownDeviceReconnectScan() {
        if (
            scanPurpose != ScanPurpose.KNOWN_DEVICE_RECONNECT ||
            otaRunning.get()
        ) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        val remaining = regattaLinkReconnectRemainingMs(
            knownReconnectDeadlineMs,
            now
        )
        if (remaining <= 0L) {
            finishKnownDeviceReconnect(knownReconnectLastError)
            return
        }

        val address = knownReconnectAddress
        if (address.isNullOrBlank()) {
            finishKnownDeviceReconnect("Configured RegattaLink address is unavailable")
            return
        }

        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            retryKnownDeviceReconnect("Bluetooth is disabled")
            return
        }

        val activeScanner = adapter.bluetoothLeScanner
        if (activeScanner == null) {
            retryKnownDeviceReconnect("Bluetooth LE is unavailable")
            return
        }

        scanner = activeScanner
        emit(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.SCANNING,
                deviceAddress = address
            )
        )
        try {
            startFilteredScan(
                activeScanner = activeScanner,
                deviceAddress = address,
                scanMode = ScanSettings.SCAN_MODE_LOW_POWER,
                timeoutMs = minOf(KNOWN_RECONNECT_SCAN_SLICE_MS, remaining)
            )
        } catch (error: RuntimeException) {
            retryKnownDeviceReconnect(
                error.message ?: "Could not scan for configured RegattaLink"
            )
        }
    }

    private fun retryKnownDeviceReconnect(message: String) {
        if (scanPurpose != ScanPurpose.KNOWN_DEVICE_RECONNECT) return

        knownReconnectLastError = message
        stopScan()
        handler.removeCallbacks(bondPoll)
        handler.removeCallbacks(gattTimeout)
        closeGatt()
        currentDevice = null

        val remaining = regattaLinkReconnectRemainingMs(
            knownReconnectDeadlineMs,
            SystemClock.elapsedRealtime()
        )
        if (remaining <= 0L) {
            finishKnownDeviceReconnect(message)
            return
        }

        handler.removeCallbacks(knownReconnectRetry)
        handler.postDelayed(
            knownReconnectRetry,
            minOf(KNOWN_RECONNECT_PAUSE_MS, remaining)
        )
    }

    private fun finishKnownDeviceReconnect(message: String) {
        val address = knownReconnectAddress.orEmpty()
        stopScan()
        handler.removeCallbacks(knownReconnectRetry)
        handler.removeCallbacks(bondPoll)
        handler.removeCallbacks(gattTimeout)
        closeGatt()
        currentDevice = null
        clearKnownDeviceReconnectState()
        emit(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.ERROR,
                deviceAddress = address,
                error = message
            )
        )
    }

    private fun completeKnownDeviceReconnect() {
        handler.removeCallbacks(knownReconnectRetry)
        clearKnownDeviceReconnectState()
    }

    private fun cancelKnownDeviceReconnect() {
        if (scanPurpose != ScanPurpose.KNOWN_DEVICE_RECONNECT) return
        stopScan()
        handler.removeCallbacks(knownReconnectRetry)
        clearKnownDeviceReconnectState()
    }

    private fun clearKnownDeviceReconnectState() {
        knownReconnectAddress = null
        knownReconnectExpectedStableId = null
        knownReconnectDeadlineMs = 0L
        knownReconnectLastError = ""
        if (scanPurpose == ScanPurpose.KNOWN_DEVICE_RECONNECT) {
            scanPurpose = ScanPurpose.NORMAL
        }
    }

    private fun retryDiscoveryAfterCandidateFailure() {
        if (
            scanPurpose != ScanPurpose.NORMAL ||
            !discoveryInProgress ||
            !discoveryCandidateInProgress
        ) {
            return
        }

        discoveryCandidateInProgress = false
        handler.removeCallbacks(bondPoll)
        handler.removeCallbacks(gattTimeout)
        currentDevice = null

        handler.post {
            if (
                scanPurpose != ScanPurpose.NORMAL ||
                !discoveryInProgress ||
                discoveryCandidateInProgress
            ) {
                return@post
            }

            val adapter = bluetoothManager.adapter
            val activeScanner =
                if (adapter != null && adapter.isEnabled) {
                    adapter.bluetoothLeScanner
                } else {
                    null
                }
            if (activeScanner == null) {
                discoveryInProgress = false
                emit(
                    RegattaLinkClientState(
                        status = RegattaLinkConnectionStatus.ERROR,
                        error = "Bluetooth LE is unavailable"
                    )
                )
                return@post
            }

            scanner = activeScanner
            emit(
                RegattaLinkClientState(
                    status = RegattaLinkConnectionStatus.SCANNING
                )
            )
            startFilteredScan(activeScanner)
        }
    }

    private fun startFilteredScan(
        activeScanner: BluetoothLeScanner,
        deviceAddress: String? = null,
        scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY,
        timeoutMs: Long = SCAN_TIMEOUT_MS
    ) {
        val filterBuilder = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(CONFIG_SERVICE_UUID))
        if (deviceAddress != null) {
            filterBuilder.setDeviceAddress(deviceAddress)
        }
        val filters = listOf(filterBuilder.build())
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .build()

        scanActive = true
        try {
            activeScanner.startScan(filters, settings, scanCallback)
        } catch (error: RuntimeException) {
            scanActive = false
            throw error
        }
        handler.postDelayed(scanTimeout, timeoutMs)
    }

    private fun prepareDevice(device: BluetoothDevice) {
        currentDevice = device
        if (
            scanPurpose == ScanPurpose.KNOWN_DEVICE_RECONNECT &&
            device.bondState != BluetoothDevice.BOND_BONDED
        ) {
            finishKnownDeviceReconnect(
                "Configured RegattaLink is no longer bonded; use Search in RegattaLink setup"
            )
            return
        }
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            connectGatt(device)
            return
        }

        emitForDevice(device, RegattaLinkConnectionStatus.BONDING)
        bondDeadline = SystemClock.elapsedRealtime() + BOND_TIMEOUT_MS
        if (!device.createBond()) {
            if (scanPurpose == ScanPurpose.OTA_RECONNECT) {
                reconnectFuture?.complete(null)
            } else if (discoveryInProgress) {
                retryDiscoveryAfterCandidateFailure()
            } else {
                emitError(device, "Could not start RegattaLink pairing")
            }
            return
        }
        handler.post(bondPoll)
    }

    private fun connectGatt(device: BluetoothDevice) {
        handler.removeCallbacks(bondPoll)
        closeGatt()
        currentDevice = device
        emitForDevice(device, RegattaLinkConnectionStatus.CONNECTING)
        gatt = device.connectGatt(
            appContext,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
        if (gatt == null) {
            if (scanPurpose == ScanPurpose.OTA_RECONNECT) {
                reconnectFuture?.complete(null)
            } else if (scanPurpose == ScanPurpose.KNOWN_DEVICE_RECONNECT) {
                retryKnownDeviceReconnect("Could not open configured RegattaLink connection")
            } else if (discoveryInProgress) {
                retryDiscoveryAfterCandidateFailure()
            } else {
                emitError(device, "Could not open RegattaLink connection")
            }
        } else {
            handler.postDelayed(gattTimeout, currentGattTimeoutMs())
        }
    }

    private fun currentGattTimeoutMs(): Long =
        if (scanPurpose == ScanPurpose.KNOWN_DEVICE_RECONNECT) {
            minOf(
                GATT_TIMEOUT_MS,
                regattaLinkReconnectRemainingMs(
                    knownReconnectDeadlineMs,
                    SystemClock.elapsedRealtime()
                ).coerceAtLeast(1L)
            )
        } else {
            GATT_TIMEOUT_MS
        }

    private fun handleCharacteristicRead(
        callbackGatt: BluetoothGatt,
        characteristicUuid: UUID,
        value: ByteArray,
        status: Int
    ) {
        if (completeCharacteristicRead(characteristicUuid, value, status)) {
            return
        }

        if (characteristicUuid != DEVICE_INFO_UUID) return
        deviceInfoReadInProgress = false

        if (serviceRediscoveryRequested.get()) {
            scheduleServiceRediscovery(
                callbackGatt,
                "Service Changed arrived during Device Info read"
            )
            return
        }

        if (status != BluetoothGatt.GATT_SUCCESS) {
            closeGattWithError(
                callbackGatt,
                "RegattaLink Device Info read failed ($status)"
            )
            return
        }

        val info = runCatching {
            parseRegattaLinkDeviceInfo(value)
        }.getOrElse { error ->
            closeGattWithError(
                callbackGatt,
                error.message ?: "Invalid RegattaLink Device Info"
            )
            return
        }

        val validationError = validateRegattaLinkDeviceInfo(info)
        if (validationError != null) {
            closeGattWithError(callbackGatt, validationError)
            return
        }

        val device = callbackGatt.device
        handler.removeCallbacks(gattTimeout)

        if (scanPurpose == ScanPurpose.KNOWN_DEVICE_RECONNECT) {
            val expectedStableId = knownReconnectExpectedStableId
            if (
                expectedStableId == null ||
                info.stableId != expectedStableId
            ) {
                finishKnownDeviceReconnect(
                    "Configured RegattaLink identity did not match the bonded device"
                )
                return
            }
            connectionSetupComplete = true
            establishedConnection = true
            selectedDeviceAddress = device.address
            completeKnownDeviceReconnect()
        } else if (scanPurpose == ScanPurpose.NORMAL) {
            connectionSetupComplete = true
            establishedConnection = true
            selectedDeviceAddress = device.address
            discoveryInProgress = false
            discoveryCandidateInProgress = false
            attemptedDiscoveryAddresses.clear()
        }

        emit(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                deviceName = deviceName(device),
                deviceAddress = device.address,
                deviceInfo = info
            )
        )

        if (info.telemetryAvailable) {
            emitTelemetry(
                RegattaLinkTelemetryState(
                    supported = true,
                    pausedForOta = otaRunning.get()
                )
            )
            otaExecutor.execute {
                setupTelemetry(callbackGatt)
            }
        } else {
            clearTelemetry()
        }

        if (scanPurpose == ScanPurpose.OTA_RECONNECT) {
            /*
             * A Service Changed indication is delivered only after the bonded
             * link has restored security and can race the first encrypted
             * Device Info read. Do not hand the connection to the OTA validator
             * until the GATT database has been quiet for a short bounded window.
             * If Service Changed arrives, the serialized rediscovery path will
             * produce another Device Info read and restart this guard.
             */
            connectionSetupComplete = false
            handler.postDelayed(
                {
                    if (
                        gatt === callbackGatt &&
                        connected &&
                        scanPurpose == ScanPurpose.OTA_RECONNECT &&
                        !serviceRediscoveryRequested.get() &&
                        !serviceRediscoveryPending.get() &&
                        !serviceDiscoveryInProgress &&
                        !deviceInfoReadInProgress
                    ) {
                        connectionSetupComplete = true
                        establishedConnection = true
                        reconnectFuture?.complete(info)
                    }
                },
                OTA_RECONNECT_SERVICE_SETTLE_MS
            )
        }
    }

    private fun setupTelemetry(activeGatt: BluetoothGatt) {
        if (gatt !== activeGatt || !connected) return
        if (
            !connectionSetupComplete ||
            deviceInfoReadInProgress ||
            serviceRediscoveryRequested.get() ||
            serviceDiscoveryInProgress ||
            serviceRediscoveryPending.get()
        ) {
            Log.i(
                LOG_TAG,
                "Skipping telemetry setup until fresh GATT discovery completes"
            )
            return
        }

        val service = activeGatt.getService(TELEMETRY_SERVICE_UUID)
        val fast = service?.getCharacteristic(TELEMETRY_FAST_UUID)
        val summary = service?.getCharacteristic(TELEMETRY_SUMMARY_UUID)
        val calibration = service?.getCharacteristic(TELEMETRY_CALIBRATION_UUID)

        if (service == null || fast == null || summary == null || calibration == null) {
            updateTelemetry {
                it.copy(
                    supported = true,
                    subscribed = false,
                    error = "RegattaLink telemetry service is incomplete"
                )
            }
            return
        }

        val characteristics = listOf(fast, summary, calibration)
        try {
            characteristics.forEach { characteristic ->
                if (gatt !== activeGatt || !connected) return
                if (!activeGatt.setCharacteristicNotification(characteristic, true)) {
                    throw RegattaLinkOtaTransportException(
                        "Could not enable RegattaLink telemetry notification " +
                            characteristic.uuid,
                        ambiguous = false
                    )
                }
                val descriptor = characteristic.getDescriptor(CCCD_UUID)
                    ?: throw RegattaLinkOtaTransportException(
                        "RegattaLink telemetry CCCD is unavailable for " +
                            characteristic.uuid,
                        ambiguous = false
                    )
                writeDescriptorBlocking(
                    activeGatt,
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
            }

            updateTelemetry {
                it.copy(
                    supported = true,
                    subscribed = true,
                    error = ""
                )
            }

            characteristics.forEach { characteristic ->
                if (gatt !== activeGatt || !connected) return
                val raw = readCharacteristicBlocking(activeGatt, characteristic)
                handleTelemetryRecord(
                    activeGatt,
                    characteristic.uuid,
                    raw,
                    initialOnly = true
                )
            }
        } catch (error: Exception) {
            if (gatt === activeGatt) {
                updateTelemetry {
                    it.copy(
                        supported = true,
                        subscribed = false,
                        error = error.message
                            ?: "RegattaLink telemetry subscription failed"
                    )
                }
            }
        }
    }

    private fun handleCharacteristicChanged(
        callbackGatt: BluetoothGatt,
        characteristicUuid: UUID,
        value: ByteArray
    ) {
        if (gatt !== callbackGatt) return

        if (characteristicUuid == REGATTALINK_OTA_STATUS_UUID) {
            runCatching {
                parseRegattaLinkOtaProgress(value)
            }.onSuccess { progress ->
                otaProgressQueue.offer(progress)
            }.onFailure { error ->
                otaDataTransportError.compareAndSet(
                    null,
                    error.message ?: "Invalid OTA status notification"
                )
            }
            return
        }

        handleTelemetryRecord(callbackGatt, characteristicUuid, value)
    }

    private fun handleTelemetryRecord(
        callbackGatt: BluetoothGatt,
        characteristicUuid: UUID,
        value: ByteArray,
        initialOnly: Boolean = false
    ) {
        if (gatt !== callbackGatt) return
        val receivedAt = SystemClock.elapsedRealtime()

        try {
            when (characteristicUuid) {
                TELEMETRY_FAST_UUID -> {
                    val parsed = parseRegattaLinkFastMotion(value)
                    updateTelemetry {
                        if (initialOnly && it.fast != null) {
                            it
                        } else {
                            it.copy(
                                supported = true,
                                fast = parsed,
                                fastReceivedAtElapsedMs = receivedAt,
                                error = ""
                            )
                        }
                    }
                }

                TELEMETRY_SUMMARY_UUID -> {
                    val parsed = parseRegattaLinkMotionSummary(value)
                    updateTelemetry {
                        if (initialOnly && it.summary != null) {
                            it
                        } else {
                            it.copy(
                                supported = true,
                                summary = parsed,
                                summaryReceivedAtElapsedMs = receivedAt,
                                error = ""
                            )
                        }
                    }
                }

                TELEMETRY_CALIBRATION_UUID -> {
                    val parsed = parseRegattaLinkCalibrationDiagnostics(value)
                    updateTelemetry {
                        if (initialOnly && it.calibration != null) {
                            it
                        } else {
                            it.copy(
                                supported = true,
                                calibration = parsed,
                                calibrationReceivedAtElapsedMs = receivedAt,
                                error = ""
                            )
                        }
                    }
                }
            }
        } catch (error: IllegalArgumentException) {
            updateTelemetry {
                it.copy(
                    supported = true,
                    error = error.message ?: "Invalid RegattaLink telemetry record"
                )
            }
        }
    }

    override fun isConnected(): Boolean = connected && gatt != null

    override fun tuneConnection(info: RegattaLinkDeviceInfo) {
        val activeGatt = requireGatt()
        requestMtuBestEffort(activeGatt)

        val priorityAccepted = runCatching {
            activeGatt.requestConnectionPriority(
                BluetoothGatt.CONNECTION_PRIORITY_HIGH
            )
        }.onFailure { error ->
            Log.w(LOG_TAG, "High-priority BLE request failed locally", error)
        }.getOrDefault(false)
        Log.i(
            LOG_TAG,
            "High-priority BLE request accepted=${priorityAccepted}"
        )

        if (info.otaPhy2m) {
            try {
                Thread.sleep(OTA_PHY_REQUEST_GRACE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }

            runCatching {
                activeGatt.setPreferredPhy(
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED
                )
                Log.i(LOG_TAG, "Requested BLE OTA 2M PHY preference")
            }.onFailure { error ->
                Log.w(LOG_TAG, "BLE OTA 2M PHY request failed locally", error)
            }
        }
    }

    override fun enableStatusNotifications() {
        val activeGatt = requireGatt()
        val statusCharacteristic = requireOtaCharacteristic(
            activeGatt,
            REGATTALINK_OTA_STATUS_UUID
        )
        val descriptor = statusCharacteristic.getDescriptor(CCCD_UUID)
            ?: throw RegattaLinkOtaTransportException(
                "RegattaLink OTA status CCCD is unavailable",
                ambiguous = false
            )

        otaProgressQueue.clear()
        if (!activeGatt.setCharacteristicNotification(statusCharacteristic, true)) {
            throw RegattaLinkOtaTransportException(
                "Could not enable RegattaLink OTA status notifications",
                ambiguous = false
            )
        }

        writeDescriptorBlocking(
            activeGatt,
            descriptor,
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        )
    }

    override fun snapshot(): RegattaLinkOtaStatus {
        writeControl(encodeRegattaLinkOtaSnapshot())
        val raw = readCharacteristicBlocking(
            requireGatt(),
            REGATTALINK_OTA_STATUS_UUID
        )
        return parseRegattaLinkOtaStatus(raw)
    }

    override fun writeControl(value: ByteArray) {
        writeCharacteristicBlocking(
            requireGatt(),
            REGATTALINK_OTA_CONTROL_UUID,
            value,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
    }

    override fun canWriteDataWithoutResponse(valueSize: Int): Boolean {
        val activeGatt = gatt ?: return false
        val characteristic = runCatching {
            requireOtaCharacteristic(activeGatt, REGATTALINK_OTA_DATA_UUID)
        }.getOrNull() ?: return false
        val hasProperty =
            characteristic.properties and
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        return hasProperty && valueSize <= (mtu - 3).coerceAtLeast(20)
    }

    override fun canWriteDataWithResponse(valueSize: Int): Boolean {
        val activeGatt = gatt ?: return false
        val characteristic = runCatching {
            requireOtaCharacteristic(activeGatt, REGATTALINK_OTA_DATA_UUID)
        }.getOrNull() ?: return false
        val hasProperty =
            characteristic.properties and
                BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        return hasProperty && valueSize <= (mtu - 3).coerceAtLeast(20)
    }

    override fun submitDataWithoutResponse(
        value: ByteArray
    ): RegattaLinkOtaSubmitResult {
        val activeGatt = requireGatt()
        val characteristic = requireOtaCharacteristic(
            activeGatt,
            REGATTALINK_OTA_DATA_UUID
        )

        var attempts = 0
        while (attempts < 4) {
            attempts += 1

            // Android exposes one local GATT write operation at a time on many
            // stacks, even for WRITE_TYPE_NO_RESPONSE. This local serialization
            // is independent of the RegattaLink receiver's DATA in-flight window.
            // Wait for Android's write-completion callback before admitting the
            // next command, while keeping already submitted blocks logically
            // in-flight until the device advances authoritative accepted_offset.
            val future = CompletableFuture<Unit>()
            val pending = PendingGattOperation.CharacteristicWrite(
                REGATTALINK_OTA_DATA_UUID,
                future
            )
            if (!setPendingGattOperation(pending)) {
                if (attempts < 4) {
                    Thread.sleep(5)
                    continue
                }
                return RegattaLinkOtaSubmitResult.LOCAL_QUEUE_BUSY
            }

            val submitResult = submitCharacteristicWrite(
                activeGatt,
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
            if (submitResult != RegattaLinkOtaSubmitResult.ACCEPTED) {
                clearPendingGattOperation(pending)
                if (
                    submitResult == RegattaLinkOtaSubmitResult.LOCAL_QUEUE_BUSY &&
                    attempts < 4
                ) {
                    Thread.sleep(5)
                    continue
                }
                return submitResult
            }

            return try {
                awaitUnitFuture(
                    future,
                    "OTA DATA write command"
                )
                RegattaLinkOtaSubmitResult.ACCEPTED
            } catch (error: RegattaLinkOtaTransportException) {
                // The command was accepted by Android before the callback became
                // ambiguous. Let the OTA engine reconcile against accepted_offset
                // instead of retransmitting blindly.
                otaDataTransportError.compareAndSet(
                    null,
                    error.message ?: "OTA DATA write command became ambiguous"
                )
                RegattaLinkOtaSubmitResult.ACCEPTED
            }
        }

        return RegattaLinkOtaSubmitResult.LOCAL_QUEUE_BUSY
    }

    override fun writeDataWithResponse(value: ByteArray) {
        writeCharacteristicBlocking(
            requireGatt(),
            REGATTALINK_OTA_DATA_UUID,
            value,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
    }

    override fun pollProgress(
        timeoutMs: Long
    ): RegattaLinkOtaProgress? =
        if (timeoutMs <= 0L) {
            otaProgressQueue.poll()
        } else {
            otaProgressQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        }

    override fun consumeDataTransportError(): String? =
        otaDataTransportError.getAndSet(null)

    override fun closeCurrentConnection() {
        closeGatt()
    }

    override fun reconnectCandidate(
        expectedStableId: String,
        timeoutMs: Long
    ): RegattaLinkDeviceInfo? {
        if (timeoutMs <= 0L) return null
        closeGatt()
        stopScan()
        handler.removeCallbacks(bondPoll)

        val adapter = bluetoothManager.adapter ?: return null
        if (!adapter.isEnabled) return null
        val activeScanner = adapter.bluetoothLeScanner ?: return null
        val targetAddress = selectedDeviceAddress ?: return null
        scanner = activeScanner

        val future = CompletableFuture<RegattaLinkDeviceInfo?>()
        reconnectFuture = future
        scanPurpose = ScanPurpose.OTA_RECONNECT

        handler.post {
            emit(
                RegattaLinkClientState(
                    status = RegattaLinkConnectionStatus.SCANNING
                )
            )
            startFilteredScan(activeScanner, targetAddress)
        }

        return try {
            val info = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            if (info != null && info.stableId != expectedStableId) {
                closeGatt()
                null
            } else {
                info
            }
        } catch (_: TimeoutException) {
            stopScan()
            closeGatt()
            null
        } catch (_: Exception) {
            closeGatt()
            null
        } finally {
            reconnectFuture = null
            handler.removeCallbacks(scanTimeout)
            scanPurpose = ScanPurpose.NORMAL
        }
    }

    private fun requestMtuBestEffort(activeGatt: BluetoothGatt) {
        if (mtu >= REQUESTED_OTA_MTU) return
        val future = CompletableFuture<Int>()
        val pending = PendingGattOperation.Mtu(future)
        if (!setPendingGattOperation(pending)) return

        val initiated = runCatching {
            activeGatt.requestMtu(REQUESTED_OTA_MTU)
        }.getOrDefault(false)

        if (!initiated) {
            clearPendingGattOperation(pending)
            return
        }

        try {
            future.get(GATT_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            clearPendingGattOperation(pending)
        }
    }

    private fun writeCharacteristicBlocking(
        activeGatt: BluetoothGatt,
        uuid: UUID,
        value: ByteArray,
        writeType: Int
    ) {
        var attempts = 0
        while (true) {
            attempts += 1
            val characteristic = requireOtaCharacteristic(activeGatt, uuid)
            val future = CompletableFuture<Unit>()
            val pending =
                PendingGattOperation.CharacteristicWrite(uuid, future)
            if (!setPendingGattOperation(pending)) {
                throw RegattaLinkOtaTransportException(
                    "Another GATT operation is active",
                    ambiguous = false
                )
            }

            val submitResult = submitCharacteristicWrite(
                activeGatt,
                characteristic,
                value,
                writeType
            )

            if (submitResult != RegattaLinkOtaSubmitResult.ACCEPTED) {
                clearPendingGattOperation(pending)
                if (
                    submitResult == RegattaLinkOtaSubmitResult.LOCAL_QUEUE_BUSY &&
                    attempts < 4
                ) {
                    Thread.sleep(10)
                    continue
                }
                throw RegattaLinkOtaTransportException(
                    "Android rejected GATT write before transmission",
                    ambiguous = false
                )
            }

            awaitUnitFuture(
                future,
                "GATT write " + uuid
            )
            return
        }
    }

    private fun readCharacteristicBlocking(
        activeGatt: BluetoothGatt,
        uuid: UUID
    ): ByteArray =
        readCharacteristicBlocking(
            activeGatt,
            requireOtaCharacteristic(activeGatt, uuid)
        )

    private fun readCharacteristicBlocking(
        activeGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): ByteArray {
        var attempts = 0
        while (true) {
            attempts += 1
            val future = CompletableFuture<ByteArray>()
            val pending =
                PendingGattOperation.CharacteristicRead(characteristic.uuid, future)
            if (!setPendingGattOperation(pending)) {
                throw RegattaLinkOtaTransportException(
                    "Another GATT operation is active",
                    ambiguous = false
                )
            }

            if (!activeGatt.readCharacteristic(characteristic)) {
                clearPendingGattOperation(pending)
                if (attempts < 4) {
                    Thread.sleep(10)
                    continue
                }
                throw RegattaLinkOtaTransportException(
                    "Android rejected GATT read before transmission",
                    ambiguous = false
                )
            }

            return awaitByteArrayFuture(
                future,
                "GATT read " + characteristic.uuid
            )
        }
    }

    private fun writeDescriptorBlocking(
        activeGatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ) {
        var attempts = 0
        while (true) {
            attempts += 1
            val future = CompletableFuture<Unit>()
            val pending =
                PendingGattOperation.DescriptorWrite(
                    descriptor.uuid,
                    future
                )
            if (!setPendingGattOperation(pending)) {
                throw RegattaLinkOtaTransportException(
                    "Another GATT operation is active",
                    ambiguous = false
                )
            }

            val accepted =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activeGatt.writeDescriptor(
                        descriptor,
                        value
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    descriptor.value = value
                    activeGatt.writeDescriptor(descriptor)
                }

            if (!accepted) {
                clearPendingGattOperation(pending)
                if (attempts < 4) {
                    Thread.sleep(10)
                    continue
                }
                throw RegattaLinkOtaTransportException(
                    "Android rejected GATT descriptor write",
                    ambiguous = false
                )
            }

            awaitUnitFuture(
                future,
                "GATT descriptor write"
            )
            return
        }
    }

    private fun submitCharacteristicWrite(
        activeGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int
    ): RegattaLinkOtaSubmitResult {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (
                activeGatt.writeCharacteristic(
                    characteristic,
                    value,
                    writeType
                )
            ) {
                BluetoothStatusCodes.SUCCESS ->
                    RegattaLinkOtaSubmitResult.ACCEPTED
                BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY ->
                    RegattaLinkOtaSubmitResult.LOCAL_QUEUE_BUSY
                else ->
                    RegattaLinkOtaSubmitResult.REJECTED
            }
        } else {
            characteristic.writeType = writeType
            characteristic.value = value
            if (activeGatt.writeCharacteristic(characteristic)) {
                RegattaLinkOtaSubmitResult.ACCEPTED
            } else {
                RegattaLinkOtaSubmitResult.LOCAL_QUEUE_BUSY
            }
        }
    }

    private fun requireGatt(): BluetoothGatt =
        gatt?.takeIf { connected }
            ?: throw RegattaLinkOtaTransportException(
                "RegattaLink is not connected"
            )

    private fun requireOtaCharacteristic(
        activeGatt: BluetoothGatt,
        uuid: UUID
    ): BluetoothGattCharacteristic {
        val service = activeGatt.getService(REGATTALINK_OTA_SERVICE_UUID)
            ?: throw RegattaLinkOtaTransportException(
                "RegattaLink OTA service is unavailable",
                ambiguous = false
            )
        return service.getCharacteristic(uuid)
            ?: throw RegattaLinkOtaTransportException(
                "RegattaLink OTA characteristic $uuid is unavailable",
                ambiguous = false
            )
    }

    private fun setPendingGattOperation(
        operation: PendingGattOperation
    ): Boolean =
        synchronized(pendingGattLock) {
            if (pendingGattOperation != null) {
                false
            } else {
                pendingGattOperation = operation
                true
            }
        }

    private fun clearPendingGattOperation(
        operation: PendingGattOperation
    ) {
        synchronized(pendingGattLock) {
            if (pendingGattOperation === operation) {
                pendingGattOperation = null
            }
        }
    }

    private fun completeCharacteristicWrite(
        uuid: UUID,
        status: Int
    ): Boolean {
        val pending = synchronized(pendingGattLock) {
            val current = pendingGattOperation
            if (
                current is PendingGattOperation.CharacteristicWrite &&
                current.uuid == uuid
            ) {
                pendingGattOperation = null
                current
            } else {
                null
            }
        } ?: return false

        if (status == BluetoothGatt.GATT_SUCCESS) {
            pending.future.complete(Unit)
        } else {
            pending.future.completeExceptionally(
                RegattaLinkOtaTransportException(
                    "GATT write $uuid failed ($status)",
                    ambiguous = true
                )
            )
        }
        return true
    }

    private fun completeCharacteristicRead(
        uuid: UUID,
        value: ByteArray,
        status: Int
    ): Boolean {
        val pending = synchronized(pendingGattLock) {
            val current = pendingGattOperation
            if (
                current is PendingGattOperation.CharacteristicRead &&
                current.uuid == uuid
            ) {
                pendingGattOperation = null
                current
            } else {
                null
            }
        } ?: return false

        if (status == BluetoothGatt.GATT_SUCCESS) {
            pending.future.complete(value.copyOf())
        } else {
            pending.future.completeExceptionally(
                RegattaLinkOtaTransportException(
                    "GATT read $uuid failed ($status)",
                    ambiguous = true
                )
            )
        }
        return true
    }

    private fun completeDescriptorWrite(
        uuid: UUID,
        status: Int
    ) {
        val pending = synchronized(pendingGattLock) {
            val current = pendingGattOperation
            if (
                current is PendingGattOperation.DescriptorWrite &&
                current.uuid == uuid
            ) {
                pendingGattOperation = null
                current
            } else {
                null
            }
        } ?: return

        if (status == BluetoothGatt.GATT_SUCCESS) {
            pending.future.complete(Unit)
        } else {
            pending.future.completeExceptionally(
                RegattaLinkOtaTransportException(
                    "GATT descriptor write failed ($status)",
                    ambiguous = true
                )
            )
        }
    }

    private fun completeMtu(value: Int) {
        val pending = synchronized(pendingGattLock) {
            val current = pendingGattOperation
            if (current is PendingGattOperation.Mtu) {
                pendingGattOperation = null
                current
            } else {
                null
            }
        } ?: return
        pending.future.complete(value)
    }

    private fun failPendingGattOperation(error: Exception) {
        val pending = synchronized(pendingGattLock) {
            val current = pendingGattOperation
            pendingGattOperation = null
            current
        } ?: return

        when (pending) {
            is PendingGattOperation.CharacteristicWrite ->
                pending.future.completeExceptionally(error)
            is PendingGattOperation.CharacteristicRead ->
                pending.future.completeExceptionally(error)
            is PendingGattOperation.DescriptorWrite ->
                pending.future.completeExceptionally(error)
            is PendingGattOperation.Mtu ->
                pending.future.completeExceptionally(error)
        }
    }

    private fun awaitUnitFuture(
        future: CompletableFuture<Unit>,
        description: String
    ) {
        try {
            future.get(
                GATT_OPERATION_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
        } catch (error: TimeoutException) {
            clearPendingFuture(future)
            throw RegattaLinkOtaTransportException(
                "$description timed out",
                ambiguous = true,
                cause = error
            )
        } catch (error: ExecutionException) {
            throw unwrapTransportException(error, description)
        }
    }

    private fun awaitByteArrayFuture(
        future: CompletableFuture<ByteArray>,
        description: String
    ): ByteArray =
        try {
            future.get(
                GATT_OPERATION_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
        } catch (error: TimeoutException) {
            clearPendingFuture(future)
            throw RegattaLinkOtaTransportException(
                "$description timed out",
                ambiguous = true,
                cause = error
            )
        } catch (error: ExecutionException) {
            throw unwrapTransportException(error, description)
        }

    private fun clearPendingFuture(future: CompletableFuture<*>) {
        synchronized(pendingGattLock) {
            val current = pendingGattOperation
            val matches = when (current) {
                is PendingGattOperation.CharacteristicWrite ->
                    current.future === future
                is PendingGattOperation.CharacteristicRead ->
                    current.future === future
                is PendingGattOperation.DescriptorWrite ->
                    current.future === future
                is PendingGattOperation.Mtu ->
                    current.future === future
                null -> false
            }
            if (matches) pendingGattOperation = null
        }
    }

    private fun unwrapTransportException(
        error: ExecutionException,
        description: String
    ): RegattaLinkOtaTransportException {
        val cause = error.cause
        return if (cause is RegattaLinkOtaTransportException) {
            cause
        } else {
            RegattaLinkOtaTransportException(
                "$description failed",
                ambiguous = true,
                cause = cause ?: error
            )
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        scanActive = false
        runCatching { scanner?.stopScan(scanCallback) }
    }

    private fun resetServiceDiscoveryState() {
        handler.removeCallbacks(serviceRediscovery)
        serviceRediscoveryGatt = null
        serviceRediscoveryPending.set(false)
        serviceRediscoveryRequested.set(false)
        serviceRediscoveryDeferredForOta.set(false)
        serviceDiscoveryInProgress = false
        deviceInfoReadInProgress = false
        connectionSetupComplete = false
    }

    private fun closeGatt() {
        handler.removeCallbacks(gattTimeout)
        resetServiceDiscoveryState()
        connected = false
        establishedConnection = false
        val existing = gatt
        gatt = null
        failPendingGattOperation(
            RegattaLinkOtaTransportException(
                "GATT connection closed"
            )
        )
        if (existing != null) {
            runCatching { existing.disconnect() }
            existing.close()
        }
        mtu = 23
    }

    private fun closeGattWithError(
        callbackGatt: BluetoothGatt,
        message: String
    ) {
        val wasReadyConnection = establishedConnection
        handler.removeCallbacks(gattTimeout)
        resetServiceDiscoveryState()
        connected = false
        establishedConnection = false
        callbackGatt.disconnect()
        callbackGatt.close()
        if (gatt === callbackGatt) {
            gatt = null
        }
        failPendingGattOperation(
            RegattaLinkOtaTransportException(message)
        )
        if (!otaRunning.get()) {
            clearTelemetry()
        }

        if (scanPurpose == ScanPurpose.OTA_RECONNECT) {
            reconnectFuture?.complete(null)
        } else if (scanPurpose == ScanPurpose.KNOWN_DEVICE_RECONNECT) {
            retryKnownDeviceReconnect(message)
        } else if (!otaRunning.get()) {
            if (discoveryInProgress) {
                retryDiscoveryAfterCandidateFailure()
            } else {
                emitError(callbackGatt.device, message)
                if (
                    shouldStartRegattaLinkOutageReconnect(
                        connectionWasReady = wasReadyConnection,
                        otaOwnsConnection = otaRunning.get(),
                        knownReconnectAlreadyActive = false
                    )
                ) {
                    handler.post { onUnexpectedDisconnect() }
                }
            }
        }
    }

    private fun emitForDevice(
        device: BluetoothDevice,
        status: RegattaLinkConnectionStatus
    ) {
        emit(
            RegattaLinkClientState(
                status = status,
                deviceName = deviceName(device),
                deviceAddress = device.address
            )
        )
    }

    private fun emitError(
        device: BluetoothDevice,
        message: String
    ) {
        emit(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.ERROR,
                deviceName = deviceName(device),
                deviceAddress = device.address,
                error = message
            )
        )
    }

    private fun deviceName(device: BluetoothDevice): String =
        runCatching { device.name }.getOrNull().orEmpty()

    private fun emit(state: RegattaLinkClientState) {
        lastState = state
        handler.post {
            onStateChanged(state)
        }
    }

    private fun phyName(phy: Int): String = when (phy) {
        BluetoothDevice.PHY_LE_1M -> "1M"
        BluetoothDevice.PHY_LE_2M -> "2M"
        BluetoothDevice.PHY_LE_CODED -> "coded"
        else -> phy.toString()
    }

    private fun updateTelemetry(
        transform: (RegattaLinkTelemetryState) -> RegattaLinkTelemetryState
    ) {
        val next = synchronized(telemetryLock) {
            transform(lastTelemetryState).also {
                lastTelemetryState = it
                RegattaLinkTelemetrySnapshotStore.update(it)
            }
        }
        handler.post {
            onTelemetryStateChanged(next)
        }
    }

    private fun emitTelemetry(state: RegattaLinkTelemetryState) {
        synchronized(telemetryLock) {
            lastTelemetryState = state
            RegattaLinkTelemetrySnapshotStore.update(state)
        }
        handler.post {
            onTelemetryStateChanged(state)
        }
    }

    private fun clearTelemetry() {
        emitTelemetry(RegattaLinkTelemetryState())
    }

    private fun invalidateTerminalOtaConnection() {
        val previousState = lastState

        // The OTA engine has already closed the GATT connection here. Keep
        // telemetry paused while replacing the snapshot so the terminal
        // CANCELLED/ERROR state can never expose pre-OTA measurements again.
        emitTelemetry(
            RegattaLinkTelemetryState(
                pausedForOta = true
            )
        )
        emit(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.IDLE,
                deviceName = previousState.deviceName,
                deviceAddress = previousState.deviceAddress
            )
        )
    }

    private fun emitOta(state: RegattaLinkOtaUiState) {
        lastOtaState = state
        if (
            shouldDeferRegattaLinkTerminalOtaState(
                otaOwnsConnection = otaRunning.get(),
                phase = state.phase
            )
        ) {
            deferredTerminalOtaState.set(state)
            return
        }
        publishOtaState(state)
    }

    private fun flushDeferredTerminalOtaState() {
        deferredTerminalOtaState.getAndSet(null)?.let(::publishOtaState)
    }

    private fun publishOtaState(state: RegattaLinkOtaUiState) {
        val paused = state.isActive
        if (lastTelemetryState.pausedForOta != paused) {
            updateTelemetry { it.copy(pausedForOta = paused) }
        }
        handler.post {
            onOtaStateChanged(state)
        }
    }
}
