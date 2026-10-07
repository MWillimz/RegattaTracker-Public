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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class RegattaLinkConnectionStatus {
    IDLE,
    WAITING,
    SCANNING,
    BONDING,
    CONNECTING,
    DISCOVERING,
    READING_DEVICE_INFO,
    CONNECTED,
    BLUETOOTH_OFF,
    ERROR
}

data class RegattaLinkClientState(
    val status: RegattaLinkConnectionStatus = RegattaLinkConnectionStatus.IDLE,
    val deviceName: String = "",
    val deviceAddress: String = "",
    val deviceInfo: RegattaLinkDeviceInfo? = null,
    val error: String = "",
    val userMessage: RegattaLinkUiMessage? = null,
    val phoneGnssTransportReady: Boolean = false
)

@SuppressLint(
    "MissingPermission",
    "DiscouragedPrivateApi",
    "SoonBlockedPrivateApi"
)
internal class RegattaLinkBleClient(
    context: Context,
    private val onStateChanged: (RegattaLinkClientState) -> Unit,
    private val onOtaStateChanged: (RegattaLinkOtaUiState) -> Unit = {},
    private val onTelemetryStateChanged: (RegattaLinkTelemetryState) -> Unit = {},
    private val onConfigurationStateChanged: (RegattaLinkConfigurationState) -> Unit = {},
    private val onNmeaStateChanged: (RegattaLinkNmeaState) -> Unit = {},
    private val onFactoryResetRecoveryStateChanged: (Boolean) -> Unit = {},
    private val onUnexpectedDisconnect: () -> Unit = {}
) : RegattaLinkOtaTransport, RegattaLinkConnectionClient {
    companion object {
        val CONFIG_SERVICE_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b720010")
        val EXTENSION_SERVICE_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b720030")
        val DEVICE_NAME_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b720011")
        val DEVICE_INFO_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b720012")
        val NMEA_PGN_INVENTORY_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b720013")
        val NMEA_RAW_CAN_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b720014")
        val LED_BRIGHTNESS_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b720015")
        val MOTION_DAMPING_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b720016")
        val CONFIG_WORD_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b720017")
        val HEADING_TRIM_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b720018")
        val DIAGNOSTIC_LOG_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b720019")
        val DEVICE_CONTROL_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b72001a")
        val NMEA_TX_RUNTIME_STATUS_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b72001b")
        val PHONE_GNSS_INPUT_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b72001c")
        val TELEMETRY_SERVICE_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b720020")
        val TELEMETRY_FAST_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b720021")
        val TELEMETRY_SUMMARY_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b720022")
        val TELEMETRY_CALIBRATION_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b720023")
        val TELEMETRY_BOAT_STATE_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b720024")
        val TELEMETRY_MOTION_ONE_HZ_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b720025")
        val TELEMETRY_LOAD_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b720026")

        internal val NORMAL_TELEMETRY_UUIDS: Set<UUID> =
            setOf(TELEMETRY_MOTION_ONE_HZ_UUID)

        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val BOND_TIMEOUT_MS = 30_000L
        private const val GATT_TIMEOUT_MS = 20_000L
        private const val BOND_POLL_MS = 250L
        private const val GATT_OPERATION_TIMEOUT_MS = 10_000L
        private const val FACTORY_RESET_LOCAL_DISCONNECT_FALLBACK_MS = 5_000L
        private const val RESTART_EXPECTED_DISCONNECT_TIMEOUT_MS = 10_000L
        private const val KNOWN_RECONNECT_SCAN_SLICE_MS = 6_000L
        private const val KNOWN_RECONNECT_PAUSE_MS = 4_000L
        private const val REQUESTED_GATT_MTU = 247
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
        KNOWN_DEVICE_AUTOCONNECT,
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
    private val loadAliasStore = RegattaLinkLoadAliasStore(appContext)
    private val loadPacketAssembler =
        RegattaLinkLoadPacketAssembler(
            aliasProvider = loadAliasStore::get,
            anonymousModeProvider = {
                lastConfigurationState.nmeaTxRuntimeStatusSupported &&
                    lastConfigurationState.nmeaTxActive == false
            }
        )
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val handler = Handler(Looper.getMainLooper())
    private val loadTelemetryStaleRunnable = Runnable {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        updateNmea { state ->
            regattaLinkExpireLoadSensorsIfTransportStale(
                state = state,
                nowElapsedMs = nowElapsedMs
            )
        }
    }
    private val otaExecutor = Executors.newSingleThreadExecutor()
    private val phoneGnssPending =
        AtomicReference<RegattaLinkPhoneGnssSample?>(null)
    private val phoneGnssDrainScheduled = AtomicBoolean(false)
    @Volatile private var phoneGnssLastSubmitElapsedMs = 0L
    private val phoneGnssDrainRunnable = Runnable {
        otaExecutor.execute(::drainPhoneGnssPending)
    }
    /*
     * OTA itself can block in reconnectCandidate(). GATT schema reconciliation
     * therefore needs a separate worker so its Device Info write can complete
     * while the OTA executor is waiting for the reconnect future.
     */
    private val gattSchemaExecutor = Executors.newSingleThreadExecutor()
    private val gattSchemaStore = RegattaLinkGattSchemaStore(appContext)
    /*
     * A persisted generation is only a hint. Every app process proves that the
     * actually cached Android handles work before trusting it. This catches the
     * exact failure where Service Changed/rediscovery completes but a CCCD write
     * still lands on a stale, non-writable ATT handle.
     */
    private val verifiedGattSchemaThisProcess =
        ConcurrentHashMap.newKeySet<String>()
    private val gattCacheRefreshAttemptsThisProcess =
        ConcurrentHashMap.newKeySet<String>()
    private val gattCacheRefreshPendingValidation =
        ConcurrentHashMap.newKeySet<String>()
    private val gattServiceChangedReconnectPendingValidation =
        ConcurrentHashMap.newKeySet<String>()

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
    private val mtuRequestAttempted = AtomicBoolean(false)

    private val pendingGattLock = Any()
    private var pendingGattOperation: PendingGattOperation? = null

    private val otaRunning = AtomicBoolean(false)
    private val otaCancelled = AtomicBoolean(false)
    private val rawCaptureRunning = AtomicBoolean(false)
    private val diagnosticLogRunning = AtomicBoolean(false)
    private val configurationMutationRunning = AtomicBoolean(false)
    private val deviceControlExecutionGuard =
        RegattaLinkDeviceControlExecutionGuard<BluetoothGatt>()
    private var nextDeviceControlRequestId = 1u
    private val rawCaptureStopReason =
        AtomicReference<RegattaLinkRawCaptureStopReason?>(null)
    private val otaProgressQueue = LinkedBlockingQueue<RegattaLinkOtaProgress>()
    private val otaDataTransportError = AtomicReference<String?>(null)
    @Volatile private var lastState = RegattaLinkClientState()
    @Volatile private var lastOtaState = RegattaLinkOtaUiState()
    private val deferredTerminalOtaState =
        AtomicReference<RegattaLinkOtaUiState?>(null)
    @Volatile private var lastTelemetryState = RegattaLinkTelemetryState()
    @Volatile private var lastConfigurationState = RegattaLinkConfigurationState()
    @Volatile private var lastNmeaState = RegattaLinkNmeaState()
    private val telemetryLock = Any()
    private val configurationLock = Any()
    private val nmeaLock = Any()
    private val serviceRediscoveryPending = AtomicBoolean(false)
    private val serviceRediscoveryRequested = AtomicBoolean(false)
    private val serviceRediscoveryDeferredForOta = AtomicBoolean(false)
    private val gattSchemaRefreshRequestRunning = AtomicBoolean(false)
    private val gattSchemaValidationRunning = AtomicBoolean(false)
    @Volatile private var currentConnectionStartedBonded = false
    @Volatile private var serviceChangedObservedThisConnection = false
    @Volatile private var serviceChangedRediscoveryCompletedThisConnection = false
    @Volatile private var gattSchemaReconciliationPending = false
    @Volatile private var pendingGattSchemaVersion = 0
    @Volatile private var pendingGattSchemaInfo: RegattaLinkDeviceInfo? = null
    @Volatile private var serviceDiscoveryInProgress = false
    @Volatile private var deviceInfoReadInProgress = false
    @Volatile private var connectionSetupComplete = false
    @Volatile private var establishedConnection = false
    private val factoryResetDisconnectTracker =
        RegattaLinkFactoryResetDisconnectTracker<BluetoothGatt>(
            nowElapsedMs = { SystemClock.elapsedRealtime() },
            expectedDisconnectTimeoutMs =
                REGATTALINK_DEVICE_CONTROL_CLIENT_TIMEOUT_MS +
                    REGATTALINK_FACTORY_RESET_FINALIZATION_TIMEOUT_MS +
                    REGATTALINK_FACTORY_RESET_DISCONNECT_MARGIN_MS
        )
    private val restartDisconnectTracker =
        RegattaLinkRestartDisconnectTracker<BluetoothGatt>(
            nowElapsedMs = { SystemClock.elapsedRealtime() },
            expectedDisconnectTimeoutMs = RESTART_EXPECTED_DISCONNECT_TIMEOUT_MS
        )
    private var serviceRediscoveryGatt: BluetoothGatt? = null
    @Volatile private var gattSchemaReconnectGatt: BluetoothGatt? = null
    @Volatile private var gattSchemaReconnectDevice: BluetoothDevice? = null
    @Volatile private var gattSchemaReconnectRefreshCache = false
    @Volatile private var gattSchemaReconnectRefreshKey: String? = null
    @Volatile private var gattSchemaReconnectRefreshReason: String? = null
    private var reconnectFuture: CompletableFuture<RegattaLinkDeviceInfo?>? = null
    @Volatile private var otaReconnectAllowOtaOnly = false
    @Volatile private var otaReconnectExpectedStableId: String? = null
    @Volatile private var otaOnlyPostBootConnection = false
    private var selectedDeviceAddress: String? = null
    private val attemptedDiscoveryAddresses = mutableSetOf<String>()
    private var discoveryInProgress = false
    private var discoveryCandidateInProgress = false
    private var discoveryCandidateBondingObserved = false
    private var discoveryCandidateStartedBonded = false
    private var discoveryStaleBondFailureObserved = false
    private var discoveryDeadlineMs = 0L
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
            finishManualDiscovery(
                regattaLinkManualDiscoveryExhaustedMessage(
                    discoveryStaleBondFailureObserved
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
        } else if (scanPurpose == ScanPurpose.KNOWN_DEVICE_AUTOCONNECT) {
            finishKnownDeviceAutoConnectError(
                "Configured RegattaLink connection setup timed out"
            )
        } else if (device != null) {
            if (discoveryInProgress) {
                retryDiscoveryAfterCandidateFailure()
            } else {
                emitError(
                    device,
                    "RegattaLink connection timed out",
                    RegattaLinkUiMessage.CONNECTION_TIMEOUT
                )
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

    private val gattSchemaReconcileTimeout = Runnable {
        val activeGatt = gatt ?: return@Runnable
        if (!connected || !gattSchemaReconciliationPending) return@Runnable
        val info = pendingGattSchemaInfo
        if (info != null) {
            recoverAndroidGattCacheOrFail(
                activeGatt,
                info,
                if (pendingGattSchemaVersion > 0) {
                    "Service Changed/rediscovery did not complete for GATT schema " +
                        pendingGattSchemaVersion
                } else {
                    "Legacy Service Changed was not delivered"
                }
            )
            return@Runnable
        }
        closeGattWithError(
            activeGatt,
            "Timed out reconciling RegattaLink GATT services"
        )
    }

    private val gattSchemaReconnectFallback = Runnable {
        val activeGatt = gattSchemaReconnectGatt ?: return@Runnable
        val device = gattSchemaReconnectDevice ?: activeGatt.device
        completePlannedGattSchemaDisconnect(
            activeGatt,
            device,
            disconnectConfirmed = false
        )
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
            val bondState = device.bondState

            if (
                scanPurpose == ScanPurpose.NORMAL &&
                discoveryInProgress &&
                bondState == BluetoothDevice.BOND_BONDING
            ) {
                discoveryCandidateBondingObserved = true
            }

            when {
                bondState == BluetoothDevice.BOND_BONDED -> connectGatt(device)
                scanPurpose == ScanPurpose.NORMAL &&
                    discoveryInProgress &&
                    shouldSkipRejectedRegattaLinkDiscoveryCandidate(
                        bondingObserved = discoveryCandidateBondingObserved,
                        currentlyUnbonded = bondState == BluetoothDevice.BOND_NONE
                    ) -> {
                    retryDiscoveryAfterCandidateFailure()
                }
                SystemClock.elapsedRealtime() >= bondDeadline -> {
                    if (scanPurpose == ScanPurpose.OTA_RECONNECT) {
                        reconnectFuture?.complete(null)
                    } else if (discoveryInProgress) {
                        retryDiscoveryAfterCandidateFailure()
                    } else {
                        emitError(device, "RegattaLink pairing timed out")
                    }
                }
                else -> handler.postDelayed(this, BOND_POLL_MS)
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
                finishManualDiscovery("Bluetooth scan failed ($errorCode)")
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
                resetConnectionTransportState()
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
                if (gattSchemaReconnectGatt === callbackGatt) {
                    completePlannedGattSchemaDisconnect(
                        callbackGatt,
                        gattSchemaReconnectDevice ?: callbackGatt.device,
                        disconnectConfirmed = true
                    )
                    return
                }
                if (factoryResetDisconnectTracker.consumeDisconnect(callbackGatt)) {
                    completeFactoryResetDisconnect(callbackGatt)
                    return
                }
                if (restartDisconnectTracker.consumeDisconnect(callbackGatt)) {
                    completeRestartDisconnect(callbackGatt)
                    return
                }
                val wasReadyConnection = establishedConnection
                connected = false
                establishedConnection = false
                resetConnectionTransportState()
                resetServiceDiscoveryState()
                failPendingGattOperation(
                    RegattaLinkOtaTransportException(
                        "RegattaLink disconnected during GATT operation"
                    )
                )

                if (scanPurpose == ScanPurpose.KNOWN_DEVICE_AUTOCONNECT) {
                    handler.removeCallbacks(gattTimeout)
                    if (isRegattaLinkStaleBondSecurityGattStatus(status)) {
                        callbackGatt.close()
                        if (gatt === callbackGatt) {
                            gatt = null
                        }
                        clearTelemetry()
                        clearConfiguration()
                        clearNmea()
                        clearKnownDeviceReconnectState()
                        emit(
                            RegattaLinkClientState(
                                status = RegattaLinkConnectionStatus.ERROR,
                                deviceName = deviceName(callbackGatt.device),
                                deviceAddress = callbackGatt.device.address,
                                userMessage = RegattaLinkUiMessage.PAIRING_REQUIRED,
                                error = REGATTALINK_STALE_ANDROID_BOND_ERROR
                            )
                        )
                    } else {
                        clearTelemetry()
                        clearConfiguration()
                        clearNmea()
                        emit(
                            RegattaLinkClientState(
                                status = RegattaLinkConnectionStatus.WAITING,
                                deviceName = deviceName(callbackGatt.device),
                                deviceAddress = callbackGatt.device.address
                            )
                        )
                    }
                    return
                }

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
                    handler.removeCallbacks(loadTelemetryStaleRunnable)
                    loadPacketAssembler.reset()
                    RegattaLinkLoadSnapshotStore.clear()
                    emitNmea(
                        RegattaLinkNmeaState(
                            pausedForOta = true
                        )
                    )
                } else {
                    clearTelemetry()
                    clearNmea()
                }

                if (scanPurpose == ScanPurpose.OTA_RECONNECT) {
                    reconnectFuture?.complete(null)
                } else if (scanPurpose == ScanPurpose.KNOWN_DEVICE_RECONNECT) {
                    retryKnownDeviceReconnect("Configured RegattaLink disconnected ($status)")
                } else if (!otaRunning.get()) {
                    if (discoveryInProgress) {
                        retryDiscoveryAfterCandidateFailure(
                            staleBondSecurityFailure =
                                isRegattaLinkStaleBondSecurityGattStatus(status)
                        )
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

            serviceChangedObservedThisConnection = true
            serviceChangedRediscoveryCompletedThisConnection = false

            val pendingSchemaInfo = pendingGattSchemaInfo
            if (
                gattSchemaReconciliationPending &&
                pendingSchemaInfo != null
            ) {
                /*
                 * Do not trust same-connection rediscovery for a schema
                 * migration. Android can deliver Service Changed yet keep
                 * downstream CCCD handles stale until the GATT connection is
                 * rebuilt. Close this GATT instance, reconnect, discover from
                 * scratch and prove the real OTA CCCD before accepting it.
                 */
                restartAfterGattServiceChanged(
                    callbackGatt,
                    pendingSchemaInfo
                )
                return
            }

            /*
             * During an active OTA transfer the current table is already in use,
             * so an unrelated Service Changed remains deferred. During setup,
             * serialize normal rediscovery.
             */
            if (
                otaRunning.get() &&
                scanPurpose != ScanPurpose.OTA_RECONNECT &&
                connectionSetupComplete
            ) {
                serviceRediscoveryRequested.set(true)
                serviceRediscoveryDeferredForOta.set(true)
                Log.i(
                    LOG_TAG,
                    "RegattaLink GATT Service Changed deferred until active OTA completes"
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
                    "RegattaLink service discovery failed ($status)",
                    gattStatus = status
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

            if (serviceChangedObservedThisConnection) {
                serviceChangedRediscoveryCompletedThisConnection = true
            }

            val service: BluetoothGattService? =
                callbackGatt.getService(CONFIG_SERVICE_UUID)
            val characteristic: BluetoothGattCharacteristic? =
                service?.getCharacteristic(DEVICE_INFO_UUID)

            if (characteristic == null) {
                if (
                    maybeCompleteOtaOnlyReconnect(
                        callbackGatt,
                        "RegattaLink Device Info is unavailable"
                    )
                ) {
                    return
                }
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
            if (gatt !== callbackGatt || !connected) return
            if (status == BluetoothGatt.GATT_SUCCESS && negotiatedMtu >= 23) {
                mtu = negotiatedMtu
                if (!regattaLinkPhoneGnssTransportReady(mtu)) {
                    Log.w(
                        LOG_TAG,
                        "Negotiated ATT MTU $mtu is below Phone GNSS minimum " +
                            REGATTALINK_PHONE_GNSS_REQUIRED_MTU
                    )
                }
                publishPhoneGnssTransportReadinessIfChanged()
            } else {
                Log.w(
                    LOG_TAG,
                    "Connection MTU negotiation failed status=$status mtu=$negotiatedMtu"
                )
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

    private fun completeFactoryResetDisconnect(activeGatt: BluetoothGatt) {
        connected = false
        establishedConnection = false
        resetConnectionTransportState()
        resetServiceDiscoveryState()
        failPendingGattOperation(
            RegattaLinkOtaTransportException(
                "Factory reset disconnected RegattaLink"
            )
        )
        activeGatt.close()
        if (gatt === activeGatt) gatt = null
        clearTelemetry()
        clearNmea()
        clearConfiguration()
        emit(RegattaLinkClientState())
    }

    private fun scheduleRestartDisconnectTimeout(activeGatt: BluetoothGatt) {
        handler.postDelayed(
            {
                if (!restartDisconnectTracker.consumeTimeout(activeGatt)) {
                    return@postDelayed
                }
                if (gatt !== activeGatt || !connected) {
                    return@postDelayed
                }

                Log.w(
                    LOG_TAG,
                    REGATTALINK_RESTART_DISCONNECT_TIMEOUT_ERROR
                )
                updateConfiguration(::regattaLinkRestartDisconnectTimedOutState)
            },
            RESTART_EXPECTED_DISCONNECT_TIMEOUT_MS
        )
    }

    private fun completeRestartDisconnect(activeGatt: BluetoothGatt) {
        val previousState = lastState
        connected = false
        establishedConnection = false
        resetConnectionTransportState()
        resetServiceDiscoveryState()
        failPendingGattOperation(
            RegattaLinkOtaTransportException(
                "RegattaLink restarted"
            )
        )
        activeGatt.close()
        if (gatt === activeGatt) gatt = null
        clearTelemetry()
        clearNmea()
        clearConfiguration()
        emit(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.IDLE,
                deviceName = previousState.deviceName,
                deviceAddress = previousState.deviceAddress
            )
        )
        handler.post { onUnexpectedDisconnect() }
    }

    private fun requestFactoryResetLocalDisconnect(activeGatt: BluetoothGatt) {
        runCatching { activeGatt.disconnect() }
        handler.postDelayed(
            {
                if (
                    !consumeRegattaLinkFactoryResetFallback(
                        session = activeGatt,
                        sessionStillCurrent = gatt === activeGatt,
                        tracker = factoryResetDisconnectTracker
                    )
                ) {
                    return@postDelayed
                }
                completeFactoryResetDisconnect(activeGatt)
            },
            FACTORY_RESET_LOCAL_DISCONNECT_FALLBACK_MS
        )
    }

    override fun startDiscovery(): Boolean {
        if (otaRunning.get()) return false
        factoryResetDisconnectTracker.clearAll()
        restartDisconnectTracker.clearAll()
        cancelKnownDeviceReconnect()
        clearTelemetry()
        clearConfiguration()
        clearNmea()
        diagnosticLogRunning.set(false)
        deviceControlExecutionGuard.clear()
        selectedDeviceAddress = null
        attemptedDiscoveryAddresses.clear()
        discoveryInProgress = true
        discoveryCandidateInProgress = false
        discoveryCandidateBondingObserved = false
        discoveryCandidateStartedBonded = false
        discoveryStaleBondFailureObserved = false
        discoveryDeadlineMs =
            SystemClock.elapsedRealtime() + REGATTALINK_MANUAL_DISCOVERY_TIMEOUT_MS
        stopScan()
        handler.removeCallbacks(bondPoll)
        closeGatt()
        currentDevice = null
        scanPurpose = ScanPurpose.NORMAL

        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            finishManualDiscovery(
                message = "Bluetooth is disabled",
                userMessage = RegattaLinkUiMessage.BLUETOOTH_DISABLED,
                status = RegattaLinkConnectionStatus.BLUETOOTH_OFF
            )
            return true
        }

        scanner = adapter.bluetoothLeScanner
        val activeScanner = scanner
        if (activeScanner == null) {
            finishManualDiscovery(
                "Bluetooth LE is unavailable",
                RegattaLinkUiMessage.BLUETOOTH_UNAVAILABLE
            )
            return true
        }

        emit(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.SCANNING
            )
        )
        startFilteredScan(
            activeScanner = activeScanner,
            timeoutMs = regattaLinkDiscoveryRemainingMs(
                discoveryDeadlineMs,
                SystemClock.elapsedRealtime()
            ).coerceAtLeast(1L)
        )
        return true
    }

    override fun startOta(artifact: RegattaLinkFirmwareArtifact) {
        clearPhoneGnssPending()
        val activeGatt = gatt
        if (
            activeGatt != null &&
            factoryResetDisconnectTracker.ownsLifecycle(activeGatt)
        ) {
            emitOta(
                RegattaLinkOtaUiState(
                    phase = RegattaLinkOtaPhase.ERROR,
                    userMessage = RegattaLinkUiMessage.OTA_WAIT_FACTORY_RESET,
                    error = "Wait for Factory Reset to finish before OTA"
                )
            )
            return
        }
        if (rawCaptureRunning.get()) {
            stopRawCanCapture(RegattaLinkRawCaptureStopReason.INTERRUPTED)
        }
        if (
            diagnosticLogRunning.get() ||
            configurationMutationRunning.get() ||
            deviceControlExecutionGuard.isActive()
        ) {
            emitOta(
                RegattaLinkOtaUiState(
                    phase = RegattaLinkOtaPhase.ERROR,
                    userMessage = RegattaLinkUiMessage.OTA_WAIT_CONFIGURATION,
                    error = "Wait for RegattaLink configuration work to finish before OTA"
                )
            )
            return
        }
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
                    userMessage = RegattaLinkUiMessage.OTA_CONNECT_FIRST,
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
                    userMessage = RegattaLinkUiMessage.OTA_FAILED,
                    error = validationFailure.message
                        ?: "Firmware is not compatible with this RegattaLink"
                )
            )
            return
        }

        otaCancelled.set(false)
        otaOnlyPostBootConnection = false
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
                val reconnectAfterOtaOnlySuccess =
                    otaOnlyPostBootConnection &&
                        lastOtaState.phase == RegattaLinkOtaPhase.SUCCESS

                if (reconnectAfterOtaOnlySuccess) {
                    closeGatt()
                    invalidateTerminalOtaConnection()
                }

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

                if (reconnectAfterOtaOnlySuccess) {
                    handler.post { onUnexpectedDisconnect() }
                }
                otaOnlyPostBootConnection = false
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
        clearPhoneGnssPending()
        val activeGatt = gatt
        if (
            activeGatt != null &&
            factoryResetDisconnectTracker.ownsLifecycle(activeGatt)
        ) {
            return
        }
        factoryResetDisconnectTracker.clearAll()
        stopRawCanCapture(RegattaLinkRawCaptureStopReason.INTERRUPTED)
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
        clearConfiguration()
        clearNmea()
        diagnosticLogRunning.set(false)
        deviceControlExecutionGuard.clear()
        currentDevice = null
        discoveryInProgress = false
        discoveryCandidateInProgress = false
        discoveryCandidateBondingObserved = false
        discoveryCandidateStartedBonded = false
        discoveryStaleBondFailureObserved = false
        discoveryDeadlineMs = 0L
        attemptedDiscoveryAddresses.clear()
        emit(RegattaLinkClientState())
    }

    fun close() {
        factoryResetDisconnectTracker.clearAll()
        stopRawCanCapture(RegattaLinkRawCaptureStopReason.INTERRUPTED)
        otaCancelled.set(true)
        cancelKnownDeviceReconnect()
        stopScan()
        handler.removeCallbacks(bondPoll)
        handler.removeCallbacks(gattTimeout)
        closeGatt()
        clearTelemetry()
        clearConfiguration()
        clearNmea()
        diagnosticLogRunning.set(false)
        deviceControlExecutionGuard.clear()
        currentDevice = null
        discoveryInProgress = false
        discoveryCandidateInProgress = false
        discoveryCandidateBondingObserved = false
        discoveryCandidateStartedBonded = false
        discoveryStaleBondFailureObserved = false
        discoveryDeadlineMs = 0L
        attemptedDiscoveryAddresses.clear()
        otaExecutor.shutdownNow()
    }

    override fun startKnownDeviceReconnect(
        deviceAddress: String,
        expectedStableId: String?,
        timeoutMs: Long
    ): Boolean {
        if (
            otaRunning.get() ||
            deviceAddress.isBlank() ||
            (expectedStableId != null && expectedStableId.isBlank()) ||
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
        clearConfiguration()
        clearNmea()
        currentDevice = null
        discoveryInProgress = false
        discoveryCandidateInProgress = false
        discoveryCandidateBondingObserved = false
        discoveryCandidateStartedBonded = false
        discoveryStaleBondFailureObserved = false
        discoveryDeadlineMs = 0L
        attemptedDiscoveryAddresses.clear()
        scanPurpose = ScanPurpose.KNOWN_DEVICE_RECONNECT
        knownReconnectAddress = deviceAddress
        knownReconnectExpectedStableId = expectedStableId
        knownReconnectDeadlineMs = SystemClock.elapsedRealtime() + timeoutMs
        knownReconnectLastError = "Configured RegattaLink was not found"

        handler.post { beginKnownDeviceReconnectDirect() }
        return true
    }

    override fun startKnownDeviceAutoConnect(
        deviceAddress: String,
        expectedStableId: String?
    ): Boolean {
        if (
            otaRunning.get() ||
            deviceAddress.isBlank() ||
            (expectedStableId != null && expectedStableId.isBlank())
        ) {
            return false
        }

        if (
            isConnected() &&
            lastState.status == RegattaLinkConnectionStatus.CONNECTED &&
            (expectedStableId == null || lastState.deviceInfo?.stableId == expectedStableId)
        ) {
            return true
        }

        if (
            scanPurpose == ScanPurpose.KNOWN_DEVICE_AUTOCONNECT &&
            knownReconnectAddress == deviceAddress &&
            knownReconnectExpectedStableId == expectedStableId &&
            gatt != null
        ) {
            return true
        }

        stopScan()
        handler.removeCallbacks(knownReconnectRetry)
        handler.removeCallbacks(bondPoll)
        handler.removeCallbacks(gattTimeout)
        closeGatt()
        clearTelemetry()
        clearConfiguration()
        clearNmea()
        currentDevice = null
        discoveryInProgress = false
        discoveryCandidateInProgress = false
        discoveryCandidateBondingObserved = false
        discoveryCandidateStartedBonded = false
        discoveryStaleBondFailureObserved = false
        discoveryDeadlineMs = 0L
        attemptedDiscoveryAddresses.clear()
        scanPurpose = ScanPurpose.KNOWN_DEVICE_AUTOCONNECT
        knownReconnectAddress = deviceAddress
        knownReconnectExpectedStableId = expectedStableId
        knownReconnectDeadlineMs = 0L
        knownReconnectLastError = ""

        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            onBluetoothAdapterDisabled()
            return true
        }

        val device = runCatching {
            adapter.getRemoteDevice(deviceAddress)
        }.getOrNull()
        if (device == null) {
            finishKnownDeviceAutoConnectError(
                "Configured RegattaLink address is invalid"
            )
            return true
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            finishKnownDeviceAutoConnectError(
                "Configured RegattaLink is no longer bonded; use Search in RegattaLink setup",
                RegattaLinkUiMessage.PAIRING_REQUIRED
            )
            return true
        }

        connectGatt(device, autoConnect = true)
        return true
    }

    override fun onBluetoothAdapterDisabled() {
        clearPhoneGnssPending()
        cancelKnownDeviceReconnect()
        stopScan()
        handler.removeCallbacks(bondPoll)
        handler.removeCallbacks(gattTimeout)
        closeGatt()
        clearTelemetry()
        clearConfiguration()
        clearNmea()
        diagnosticLogRunning.set(false)
        deviceControlExecutionGuard.clear()
        currentDevice = null
        discoveryInProgress = false
        discoveryCandidateInProgress = false
        discoveryCandidateBondingObserved = false
        discoveryCandidateStartedBonded = false
        discoveryStaleBondFailureObserved = false
        discoveryDeadlineMs = 0L
        attemptedDiscoveryAddresses.clear()
        emit(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.BLUETOOTH_OFF,
                userMessage = RegattaLinkUiMessage.BLUETOOTH_DISABLED
            )
        )
    }

    override fun onBluetoothAdapterEnabled() {
        if (lastState.status == RegattaLinkConnectionStatus.BLUETOOTH_OFF) {
            emit(RegattaLinkClientState())
        }
    }

    private fun beginKnownDeviceReconnectDirect() {
        if (
            scanPurpose != ScanPurpose.KNOWN_DEVICE_RECONNECT ||
            otaRunning.get()
        ) {
            return
        }

        val remaining = regattaLinkReconnectRemainingMs(
            knownReconnectDeadlineMs,
            SystemClock.elapsedRealtime()
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
            onBluetoothAdapterDisabled()
            return
        }

        val device = runCatching {
            adapter.getRemoteDevice(address)
        }.getOrNull()
        if (device == null) {
            finishKnownDeviceReconnect("Configured RegattaLink address is invalid")
            return
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            finishKnownDeviceReconnect(
                "Configured RegattaLink is no longer bonded; use Search in RegattaLink setup"
            )
            return
        }

        connectGatt(device)
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
            onBluetoothAdapterDisabled()
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
        if (
            scanPurpose != ScanPurpose.KNOWN_DEVICE_RECONNECT &&
            scanPurpose != ScanPurpose.KNOWN_DEVICE_AUTOCONNECT
        ) {
            return
        }
        stopScan()
        handler.removeCallbacks(knownReconnectRetry)
        clearKnownDeviceReconnectState()
    }

    private fun clearKnownDeviceReconnectState() {
        knownReconnectAddress = null
        knownReconnectExpectedStableId = null
        knownReconnectDeadlineMs = 0L
        knownReconnectLastError = ""
        if (
            scanPurpose == ScanPurpose.KNOWN_DEVICE_RECONNECT ||
            scanPurpose == ScanPurpose.KNOWN_DEVICE_AUTOCONNECT
        ) {
            scanPurpose = ScanPurpose.NORMAL
        }
    }

    private fun finishKnownDeviceAutoConnectError(
        message: String,
        userMessage: RegattaLinkUiMessage = RegattaLinkUiMessage.CONNECTION_FAILED
    ) {
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
                userMessage = userMessage,
                error = message
            )
        )
    }

    private fun finishManualDiscovery(
        message: String,
        userMessage: RegattaLinkUiMessage = RegattaLinkUiMessage.CONNECTION_FAILED,
        status: RegattaLinkConnectionStatus = RegattaLinkConnectionStatus.ERROR
    ) {
        stopScan()
        handler.removeCallbacks(bondPoll)
        handler.removeCallbacks(gattTimeout)
        closeGatt()
        currentDevice = null
        discoveryInProgress = false
        discoveryCandidateInProgress = false
        discoveryCandidateBondingObserved = false
        discoveryCandidateStartedBonded = false
        discoveryStaleBondFailureObserved = false
        discoveryDeadlineMs = 0L
        attemptedDiscoveryAddresses.clear()
        emit(
            RegattaLinkClientState(
                status = status,
                userMessage = userMessage,
                error = message
            )
        )
    }

    private fun retryDiscoveryAfterCandidateFailure(
        staleBondSecurityFailure: Boolean = false
    ) {
        if (
            scanPurpose != ScanPurpose.NORMAL ||
            !discoveryInProgress ||
            !discoveryCandidateInProgress
        ) {
            return
        }

        if (
            discoveryCandidateStartedBonded &&
            staleBondSecurityFailure
        ) {
            discoveryStaleBondFailureObserved = true
        }
        discoveryCandidateInProgress = false
        discoveryCandidateBondingObserved = false
        discoveryCandidateStartedBonded = false
        handler.removeCallbacks(bondPoll)
        handler.removeCallbacks(gattTimeout)
        currentDevice = null

        val remaining = regattaLinkDiscoveryRemainingMs(
            discoveryDeadlineMs,
            SystemClock.elapsedRealtime()
        )
        if (remaining <= 0L) {
            finishManualDiscovery(
                regattaLinkManualDiscoveryExhaustedMessage(
                    discoveryStaleBondFailureObserved
                )
            )
            return
        }

        handler.post {
            if (
                scanPurpose != ScanPurpose.NORMAL ||
                !discoveryInProgress ||
                discoveryCandidateInProgress
            ) {
                return@post
            }

            val retryRemaining = regattaLinkDiscoveryRemainingMs(
                discoveryDeadlineMs,
                SystemClock.elapsedRealtime()
            )
            if (retryRemaining <= 0L) {
                finishManualDiscovery(
                regattaLinkManualDiscoveryExhaustedMessage(
                    discoveryStaleBondFailureObserved
                )
            )
                return@post
            }

            val adapter = bluetoothManager.adapter
            if (adapter == null || !adapter.isEnabled) {
                finishManualDiscovery(
                    message = "Bluetooth is disabled",
                    userMessage = RegattaLinkUiMessage.BLUETOOTH_DISABLED,
                    status = RegattaLinkConnectionStatus.BLUETOOTH_OFF
                )
                return@post
            }
            val activeScanner = adapter.bluetoothLeScanner
            if (activeScanner == null) {
                finishManualDiscovery(
                    "Bluetooth LE is unavailable",
                    RegattaLinkUiMessage.BLUETOOTH_UNAVAILABLE
                )
                return@post
            }

            scanner = activeScanner
            emit(
                RegattaLinkClientState(
                    status = RegattaLinkConnectionStatus.SCANNING
                )
            )
            startFilteredScan(
                activeScanner = activeScanner,
                timeoutMs = retryRemaining
            )
        }
    }

    private fun startFilteredScan(
        activeScanner: BluetoothLeScanner,
        deviceAddress: String? = null,
        scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY,
        timeoutMs: Long = SCAN_TIMEOUT_MS
    ) {
        val filterBuilder = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(REGATTALINK_OTA_SERVICE_UUID))
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
        currentConnectionStartedBonded =
            device.bondState == BluetoothDevice.BOND_BONDED
        if (scanPurpose == ScanPurpose.NORMAL && discoveryInProgress) {
            discoveryCandidateStartedBonded =
                device.bondState == BluetoothDevice.BOND_BONDED
        }
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
        val now = SystemClock.elapsedRealtime()
        val bondTimeoutMs =
            if (scanPurpose == ScanPurpose.NORMAL && discoveryInProgress) {
                regattaLinkDiscoveryStageTimeoutMs(
                    stageTimeoutMs = BOND_TIMEOUT_MS,
                    deadlineElapsedMs = discoveryDeadlineMs,
                    nowElapsedMs = now
                )
            } else {
                BOND_TIMEOUT_MS
            }
        if (bondTimeoutMs <= 0L) {
            retryDiscoveryAfterCandidateFailure()
            return
        }
        bondDeadline = now + bondTimeoutMs
        discoveryCandidateBondingObserved =
            scanPurpose == ScanPurpose.NORMAL &&
                discoveryInProgress &&
                device.bondState == BluetoothDevice.BOND_BONDING
        if (!device.createBond()) {
            if (scanPurpose == ScanPurpose.OTA_RECONNECT) {
                reconnectFuture?.complete(null)
            } else if (discoveryInProgress) {
                retryDiscoveryAfterCandidateFailure()
            } else {
                emitError(
                    device,
                    "Could not start RegattaLink pairing",
                    RegattaLinkUiMessage.PAIRING_START_FAILED
                )
            }
            return
        }
        if (
            scanPurpose == ScanPurpose.NORMAL &&
            discoveryInProgress &&
            device.bondState == BluetoothDevice.BOND_BONDING
        ) {
            discoveryCandidateBondingObserved = true
        }
        handler.post(bondPoll)
    }

    private fun connectGatt(
        device: BluetoothDevice,
        autoConnect: Boolean = false
    ) {
        handler.removeCallbacks(bondPoll)
        closeGatt()
        resetGattSchemaReconciliationForNewConnection()
        currentDevice = device
        emitForDevice(
            device,
            if (autoConnect) {
                RegattaLinkConnectionStatus.WAITING
            } else {
                RegattaLinkConnectionStatus.CONNECTING
            }
        )
        gatt = device.connectGatt(
            appContext,
            autoConnect,
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
                emitError(
                    device,
                    "Could not open RegattaLink connection",
                    RegattaLinkUiMessage.CONNECTION_OPEN_FAILED
                )
            }
        } else if (!autoConnect) {
            handler.postDelayed(gattTimeout, currentGattTimeoutMs())
        }
    }

    private fun currentGattTimeoutMs(): Long =
        when {
            scanPurpose == ScanPurpose.KNOWN_DEVICE_RECONNECT -> {
                minOf(
                    GATT_TIMEOUT_MS,
                    regattaLinkReconnectRemainingMs(
                        knownReconnectDeadlineMs,
                        SystemClock.elapsedRealtime()
                    ).coerceAtLeast(1L)
                )
            }
            scanPurpose == ScanPurpose.NORMAL && discoveryInProgress -> {
                regattaLinkDiscoveryStageTimeoutMs(
                    stageTimeoutMs = GATT_TIMEOUT_MS,
                    deadlineElapsedMs = discoveryDeadlineMs,
                    nowElapsedMs = SystemClock.elapsedRealtime()
                ).coerceAtLeast(1L)
            }
            else -> GATT_TIMEOUT_MS
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
            if (isRegattaLinkStaleBondSecurityGattStatus(status)) {
                closeGattWithError(
                    callbackGatt,
                    REGATTALINK_STALE_ANDROID_BOND_ERROR,
                    gattStatus = status
                )
                return
            }
            if (
                maybeCompleteOtaOnlyReconnect(
                    callbackGatt,
                    "RegattaLink Device Info read failed ($status)"
                )
            ) {
                return
            }
            closeGattWithError(
                callbackGatt,
                "RegattaLink Device Info read failed ($status)",
                gattStatus = status
            )
            return
        }

        val info = try {
            parseRegattaLinkDeviceInfo(value)
        } catch (error: Exception) {
            if (
                maybeCompleteOtaOnlyReconnect(
                    callbackGatt,
                    error.message ?: "Invalid RegattaLink Device Info"
                )
            ) {
                return
            }
            closeGattWithError(
                callbackGatt,
                error.message ?: "Invalid RegattaLink Device Info"
            )
            return
        }

        val validationError = validateRegattaLinkDeviceInfo(info)
        if (validationError != null) {
            if (
                maybeCompleteOtaOnlyReconnect(
                    callbackGatt,
                    validationError
                )
            ) {
                return
            }
            closeGattWithError(callbackGatt, validationError)
            return
        }

        handler.removeCallbacks(gattTimeout)

        val schemaKey = gattSchemaKey(info)
        val validatingAfterLocalCacheRefresh =
            gattCacheRefreshPendingValidation.contains(schemaKey)
        val validatingAfterServiceChangedReconnect =
            gattServiceChangedReconnectPendingValidation.contains(schemaKey)
        val validatingAfterForcedRediscovery =
            validatingAfterLocalCacheRefresh ||
                validatingAfterServiceChangedReconnect
        val acceptedSchemaVersion =
            if (validatingAfterForcedRediscovery) {
                info.gattSchemaVersion
            } else {
                gattSchemaStore.acceptedVersion(info.stableId)
            }
        val schemaDecision = regattaLinkGattSchemaDecision(
            reportedVersion = info.gattSchemaVersion,
            acceptedVersion = acceptedSchemaVersion,
            connectionStartedBonded = currentConnectionStartedBonded,
            serviceChangedObserved = serviceChangedObservedThisConnection,
            serviceChangedRediscoveryCompleted =
                serviceChangedRediscoveryCompletedThisConnection
        )
        if (schemaDecision.waitForRediscovery) {
            beginGattSchemaReconciliation(
                callbackGatt,
                info,
                requestServiceChanged = schemaDecision.requestServiceChanged
            )
            return
        }

        /*
         * A generation match or Service Changed callback is not proof that
         * Android's cached ATT table is usable. Prove real critical
         * characteristic/CCCD I/O once per app process before persisting or
         * trusting the schema. OTA Status covers the historic stale-handle
         * failure; schema 11+ additionally proves the required Motion 1 Hz
         * surface because that append does not move the earlier OTA handles.
         */
        if (
            shouldValidateRegattaLinkGattLayout(
                schemaAlreadyVerifiedThisProcess =
                    verifiedGattSchemaThisProcess.contains(schemaKey),
                acceptReportedVersion =
                    schemaDecision.acceptReportedVersion,
                forcedRediscoveryPendingValidation =
                    validatingAfterForcedRediscovery
            )
        ) {
            validateGattSchemaThenComplete(callbackGatt, info)
            return
        }

        finishGattSchemaReconciliation()
        completeConnectionAfterDeviceInfo(callbackGatt, info)
    }

    private fun maybeCompleteOtaOnlyReconnect(
        callbackGatt: BluetoothGatt,
        deviceInfoFailure: String
    ): Boolean {
        if (
            scanPurpose != ScanPurpose.OTA_RECONNECT ||
            !otaReconnectAllowOtaOnly
        ) {
            return false
        }

        val expectedStableId = otaReconnectExpectedStableId ?: return false
        val otaService = callbackGatt.getService(REGATTALINK_OTA_SERVICE_UUID)
            ?: return false
        val controlCharacteristic =
            otaService.getCharacteristic(REGATTALINK_OTA_CONTROL_UUID)
                ?: return false
        val statusCharacteristic =
            otaService.getCharacteristic(REGATTALINK_OTA_STATUS_UUID)
                ?: return false

        deviceInfoReadInProgress = false
        emitForDevice(
            callbackGatt.device,
            RegattaLinkConnectionStatus.DISCOVERING
        )

        gattSchemaExecutor.execute {
            var recoveredInfo: RegattaLinkDeviceInfo? = null
            var failure: String? = null
            var failureGattStatus: Int? = null
            try {
                if (gatt !== callbackGatt || !connected) return@execute
                writeCharacteristicBlockingDirect(
                    callbackGatt,
                    controlCharacteristic,
                    encodeRegattaLinkOtaSnapshot()
                )
                val status = parseRegattaLinkOtaStatus(
                    readCharacteristicBlocking(
                        callbackGatt,
                        statusCharacteristic
                    )
                )
                recoveredInfo = RegattaLinkDeviceInfo(
                    protocolMajor = REGATTALINK_PROTOCOL_MAJOR,
                    protocolMinor = 0,
                    capabilities = 0u,
                    stableId = expectedStableId,
                    productId = REGATTALINK_PRODUCT_ID,
                    profileId = REGATTALINK_PROFILE_ID,
                    runningBuild = status.runningBuild,
                    otaSlotSize = 0u,
                    maxInflightBlocks = 0
                )
            } catch (error: Exception) {
                failure = error.message ?: "OTA Status validation failed"
                failureGattStatus =
                    (error as? RegattaLinkOtaTransportException)?.gattStatus
            }

            handler.post {
                if (gatt !== callbackGatt || !connected) return@post

                val info = recoveredInfo
                if (info == null) {
                    val securityStatus = failureGattStatus
                        ?.takeIf(::isRegattaLinkStaleBondSecurityGattStatus)
                    closeGattWithError(
                        callbackGatt,
                        if (securityStatus != null) {
                            REGATTALINK_STALE_ANDROID_BOND_ERROR
                        } else {
                            "OTA-only reconnect failed after $deviceInfoFailure: " +
                                (failure ?: "OTA core is unavailable")
                        },
                        gattStatus = securityStatus
                    )
                    return@post
                }

                handler.removeCallbacks(gattTimeout)
                connectionSetupComplete = true
                establishedConnection = true
                selectedDeviceAddress = callbackGatt.device.address
                otaOnlyPostBootConnection = true
                reconnectFuture?.complete(info)
            }
        }
        return true
    }

    private fun completeConnectionAfterDeviceInfo(
        callbackGatt: BluetoothGatt,
        info: RegattaLinkDeviceInfo
    ) {
        if (gatt !== callbackGatt || !connected) return
        val device = callbackGatt.device

        if (
            scanPurpose == ScanPurpose.KNOWN_DEVICE_RECONNECT ||
            scanPurpose == ScanPurpose.KNOWN_DEVICE_AUTOCONNECT
        ) {
            val autoConnect =
                scanPurpose == ScanPurpose.KNOWN_DEVICE_AUTOCONNECT
            val expectedStableId = knownReconnectExpectedStableId
            if (
                expectedStableId != null &&
                info.stableId != expectedStableId
            ) {
                if (autoConnect) {
                    finishKnownDeviceAutoConnectError(
                        "Configured RegattaLink identity did not match the bonded device",
                        RegattaLinkUiMessage.PAIRING_REQUIRED
                    )
                } else {
                    finishKnownDeviceReconnect(
                        "Configured RegattaLink identity did not match the bonded device"
                    )
                }
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
            discoveryCandidateBondingObserved = false
            discoveryCandidateStartedBonded = false
            discoveryStaleBondFailureObserved = false
            discoveryDeadlineMs = 0L
            attemptedDiscoveryAddresses.clear()
        }

        emit(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                deviceName = deviceName(device),
                deviceAddress = device.address,
                deviceInfo = info,
                phoneGnssTransportReady =
                    establishedConnection &&
                        regattaLinkPhoneGnssTransportReady(mtu)
            )
        )

        if (info.telemetryAvailable) {
            emitTelemetry(
                RegattaLinkTelemetryState(
                    supported = true,
                    pausedForOta = otaRunning.get()
                )
            )
        } else {
            clearTelemetry()
        }

        otaExecutor.execute {
            setupConnectedFeatures(callbackGatt, info)
        }

        if (scanPurpose == ScanPurpose.OTA_RECONNECT) {
            connectionSetupComplete = true
            establishedConnection = true
            reconnectFuture?.complete(info)
        }
    }

    private fun beginGattSchemaReconciliation(
        activeGatt: BluetoothGatt,
        info: RegattaLinkDeviceInfo,
        requestServiceChanged: Boolean
    ) {
        if (gatt !== activeGatt || !connected) return

        connectionSetupComplete = false
        gattSchemaReconciliationPending = true
        pendingGattSchemaVersion = info.gattSchemaVersion
        pendingGattSchemaInfo = info
        emitForDevice(
            activeGatt.device,
            RegattaLinkConnectionStatus.DISCOVERING
        )

        handler.removeCallbacks(gattSchemaReconcileTimeout)
        handler.postDelayed(
            gattSchemaReconcileTimeout,
            REGATTALINK_GATT_SCHEMA_RECONCILE_TIMEOUT_MS
        )

        if (
            !requestServiceChanged ||
            !gattSchemaRefreshRequestRunning.compareAndSet(false, true)
        ) {
            return
        }

        gattSchemaExecutor.execute {
            var errorMessage: String? = null
            try {
                if (
                    gatt !== activeGatt ||
                    !connected ||
                    !gattSchemaReconciliationPending
                ) {
                    return@execute
                }
                val characteristic = activeGatt
                    .getService(CONFIG_SERVICE_UUID)
                    ?.getCharacteristic(DEVICE_NAME_UUID)
                    ?: throw RegattaLinkOtaTransportException(
                        "RegattaLink stable configuration handle is unavailable for GATT refresh",
                        ambiguous = false
                    )
                writeCharacteristicBlockingDirect(
                    activeGatt,
                    characteristic,
                    byteArrayOf(
                        0,
                        info.gattSchemaVersion.toByte()
                    )
                )
            } catch (error: Exception) {
                errorMessage = error.message
                    ?: "Could not request RegattaLink GATT schema refresh"
            } finally {
                gattSchemaRefreshRequestRunning.set(false)
            }

            if (errorMessage != null) {
                handler.post {
                    if (
                        gatt === activeGatt &&
                        connected &&
                        gattSchemaReconciliationPending
                    ) {
                        recoverAndroidGattCacheOrFail(
                            activeGatt,
                            info,
                            errorMessage!!
                        )
                    }
                }
            }
        }
    }

    private fun gattSchemaKey(info: RegattaLinkDeviceInfo): String =
        info.stableId.lowercase() + ":" + info.gattSchemaVersion

    private fun restartAfterGattServiceChanged(
        activeGatt: BluetoothGatt,
        info: RegattaLinkDeviceInfo
    ) {
        if (gatt !== activeGatt || !connected) return
        if (gattSchemaReconnectGatt != null) return

        val key = gattSchemaKey(info)
        gattServiceChangedReconnectPendingValidation += key
        handler.removeCallbacks(gattSchemaReconcileTimeout)
        gattSchemaReconnectGatt = activeGatt
        gattSchemaReconnectDevice = activeGatt.device
        gattSchemaReconnectRefreshCache = false
        gattSchemaReconnectRefreshKey = null
        gattSchemaReconnectRefreshReason = null
        connectionSetupComplete = false
        Log.i(
            LOG_TAG,
            "Disconnecting after Service Changed before accepting GATT schema " +
                info.gattSchemaVersion
        )
        activeGatt.disconnect()
        handler.removeCallbacks(gattSchemaReconnectFallback)
        handler.postDelayed(gattSchemaReconnectFallback, 2_000L)
    }

    private fun clearGattSchemaReconnectState() {
        handler.removeCallbacks(gattSchemaReconnectFallback)
        gattSchemaReconnectGatt = null
        gattSchemaReconnectDevice = null
        gattSchemaReconnectRefreshCache = false
        gattSchemaReconnectRefreshKey = null
        gattSchemaReconnectRefreshReason = null
    }

    private fun completePlannedGattSchemaDisconnect(
        activeGatt: BluetoothGatt,
        device: BluetoothDevice,
        disconnectConfirmed: Boolean
    ) {
        if (gattSchemaReconnectGatt !== activeGatt) return

        val refreshCache = gattSchemaReconnectRefreshCache
        val refreshKey = gattSchemaReconnectRefreshKey
        val refreshReason = gattSchemaReconnectRefreshReason
        val reconnectAction = regattaLinkGattReconnectAction(
            cacheRefreshPlanned = refreshCache,
            disconnectConfirmed = disconnectConfirmed
        )
        clearGattSchemaReconnectState()

        if (reconnectAction == RegattaLinkGattReconnectAction.FAIL_CACHE_REFRESH) {
            val reason = refreshReason ?: "stale Android GATT cache"
            Log.e(
                LOG_TAG,
                "Timed out waiting for GATT disconnect before cache refresh; " +
                    "refusing to call BluetoothGatt.refresh() while connected"
            )
            closeGattWithError(
                activeGatt,
                "RegattaLink GATT cache could not be refreshed after disconnect. " +
                    "Forget/pair the RegattaLink once. Root cause: $reason"
            )
            return
        }

        if (!disconnectConfirmed) {
            Log.w(
                LOG_TAG,
                "Timed out waiting for planned GATT schema disconnect; forcing close"
            )
        }

        connected = false
        establishedConnection = false
        resetConnectionTransportState()
        resetServiceDiscoveryState()
        failPendingGattOperation(
            RegattaLinkOtaTransportException(
                if (refreshCache) {
                    "GATT connection intentionally disconnected before cache refresh"
                } else {
                    "GATT connection intentionally rebuilt after Service Changed"
                },
                ambiguous = false
            )
        )

        if (reconnectAction ==
            RegattaLinkGattReconnectAction.REFRESH_CACHE_AFTER_DISCONNECT
        ) {
            /*
             * Android's hidden BluetoothGatt.refresh() only reaches the path
             * which clears the cached attribute database once the GATT link is
             * disconnected. Calling it while connected can merely trigger an
             * in-place discovery and leave stale handles behind.
             *
             * This function is entered from STATE_DISCONNECTED for the normal
             * path. The timeout fallback explicitly refuses to refresh.
             */
            val refreshed = refreshAndroidGattCache(activeGatt)
            if (!refreshed) {
                refreshKey?.let(gattCacheRefreshPendingValidation::remove)
                closeGattWithError(
                    activeGatt,
                    "RegattaLink GATT cache refresh failed after disconnect. " +
                        "Forget/pair the RegattaLink once. Root cause: " +
                        (refreshReason ?: "stale Android GATT cache")
                )
                return
            }

            refreshKey?.let { gattCacheRefreshPendingValidation += it }
            Log.w(
                LOG_TAG,
                "Android GATT cache refresh completed while disconnected; " +
                    "closing stale GATT instance before reconnect"
            )
        }

        if (
            reconnectAction ==
            RegattaLinkGattReconnectAction.REFRESH_CACHE_AFTER_DISCONNECT
        ) {
            /*
             * refresh() has no completion callback. Keep the now-disconnected
             * BluetoothGatt alive briefly before close() so Android can process
             * the cache invalidation request before a new GATT client is built.
             */
            handler.postDelayed(
                {
                    activeGatt.close()
                    if (gatt === activeGatt) {
                        gatt = null
                        prepareDevice(device)
                    }
                },
                250L
            )
            return
        }

        if (!disconnectConfirmed) {
            runCatching { activeGatt.disconnect() }
        }
        activeGatt.close()
        if (gatt === activeGatt) {
            gatt = null
        }
        handler.post {
            if (gatt == null) {
                prepareDevice(device)
            }
        }
    }

    private fun validateGattSchemaThenComplete(
        activeGatt: BluetoothGatt,
        info: RegattaLinkDeviceInfo
    ) {
        if (gatt !== activeGatt || !connected) return
        if (!gattSchemaValidationRunning.compareAndSet(false, true)) {
            handler.postDelayed(
                {
                    if (gatt === activeGatt && connected) {
                        validateGattSchemaThenComplete(activeGatt, info)
                    }
                },
                50L
            )
            return
        }

        connectionSetupComplete = false
        gattSchemaReconciliationPending = true
        pendingGattSchemaVersion = info.gattSchemaVersion
        pendingGattSchemaInfo = info
        handler.removeCallbacks(gattSchemaReconcileTimeout)
        emitForDevice(
            activeGatt.device,
            RegattaLinkConnectionStatus.DISCOVERING
        )

        gattSchemaExecutor.execute {
            var failure: String? = null
            var securityGattStatus: Int? = null
            try {
                if (gatt !== activeGatt || !connected) return@execute
                probeCriticalGattLayout(activeGatt, info)
            } catch (error: Exception) {
                val transportError = error as? RegattaLinkOtaTransportException
                securityGattStatus = transportError
                    ?.gattStatus
                    ?.takeIf(::isRegattaLinkStaleBondSecurityGattStatus)
                failure = error.message
                    ?: "RegattaLink critical GATT layout validation failed"
            } finally {
                gattSchemaValidationRunning.set(false)
            }

            handler.post {
                if (gatt !== activeGatt || !connected) return@post

                if (failure != null) {
                    val securityStatus = securityGattStatus
                    when (
                        regattaLinkGattCoreFailureAction(
                            otaReconnect =
                                scanPurpose == ScanPurpose.OTA_RECONNECT,
                            allowOtaOnly = otaReconnectAllowOtaOnly,
                            securityFailure = securityStatus != null
                        )
                    ) {
                        RegattaLinkGattCoreFailureAction.SECURITY_FAILURE -> {
                            closeGattWithError(
                                activeGatt,
                                REGATTALINK_STALE_ANDROID_BOND_ERROR,
                                gattStatus = securityStatus
                            )
                        }

                        RegattaLinkGattCoreFailureAction.OTA_ONLY_RECOVERY -> {
                            if (
                                maybeCompleteOtaOnlyReconnect(
                                    activeGatt,
                                    failure!!
                                )
                            ) {
                                finishGattSchemaReconciliation()
                                return@post
                            }
                            recoverAndroidGattCacheOrFail(
                                activeGatt,
                                info,
                                failure!!
                            )
                        }

                        RegattaLinkGattCoreFailureAction.CACHE_RECOVERY -> {
                            recoverAndroidGattCacheOrFail(
                                activeGatt,
                                info,
                                failure!!
                            )
                        }
                    }
                    return@post
                }

                val key = gattSchemaKey(info)
                verifiedGattSchemaThisProcess += key
                gattCacheRefreshPendingValidation.remove(key)
                gattServiceChangedReconnectPendingValidation.remove(key)
                gattSchemaStore.accept(
                    info.stableId,
                    info.gattSchemaVersion
                )

                finishGattSchemaReconciliation()
                completeConnectionAfterDeviceInfo(activeGatt, info)
            }
        }
    }

    private fun requireRegattaLinkV2FrozenCore(
        activeGatt: BluetoothGatt
    ) {
        fun requireService(
            uuid: UUID,
            label: String
        ): BluetoothGattService {
            val matches = activeGatt.services.filter { it.uuid == uuid }
            if (matches.size != 1) {
                throw RegattaLinkOtaTransportException(
                    "RegattaLink v2 $label service count is ${matches.size}, expected 1",
                    ambiguous = false
                )
            }
            return matches.single()
        }

        fun requireCharacteristic(
            service: BluetoothGattService,
            uuid: UUID,
            label: String
        ) {
            val matches = service.characteristics.filter { it.uuid == uuid }
            if (matches.size != 1) {
                throw RegattaLinkOtaTransportException(
                    "RegattaLink v2 $label characteristic count is " +
                        "${matches.size}, expected 1",
                    ambiguous = false
                )
            }
        }

        val otaService = requireService(
            REGATTALINK_OTA_SERVICE_UUID,
            "OTA"
        )
        listOf(
            REGATTALINK_OTA_CONTROL_UUID to "OTA Control 0002",
            REGATTALINK_OTA_DATA_UUID to "OTA Data 0003",
            REGATTALINK_OTA_STATUS_UUID to "OTA Status 0004"
        ).forEach { (uuid, label) ->
            requireCharacteristic(otaService, uuid, label)
        }

        val configService = requireService(
            CONFIG_SERVICE_UUID,
            "Config/Control"
        )
        listOf(
            DEVICE_NAME_UUID to "Device Name 0011",
            DEVICE_INFO_UUID to "Device Info 0012",
            NMEA_PGN_INVENTORY_UUID to "Boat Data PGN Inventory 0013",
            NMEA_RAW_CAN_UUID to "Boat Data Raw FIFO 0014",
            LED_BRIGHTNESS_UUID to "LED Brightness 0015",
            MOTION_DAMPING_UUID to "Heel/Pitch Damping 0016",
            CONFIG_WORD_UUID to "Config Word 0017",
            HEADING_TRIM_UUID to "Heading Trim 0018",
            DIAGNOSTIC_LOG_UUID to "Diagnostic Log 0019",
            DEVICE_CONTROL_UUID to "Device Control 001A",
            NMEA_TX_RUNTIME_STATUS_UUID to "TX Runtime Status 001B",
            PHONE_GNSS_INPUT_UUID to "Phone GNSS Input 001C"
        ).forEach { (uuid, label) ->
            requireCharacteristic(configService, uuid, label)
        }

        val telemetryService = requireService(
            TELEMETRY_SERVICE_UUID,
            "Telemetry"
        )
        listOf(
            TELEMETRY_FAST_UUID to "Fast Motion 0021",
            TELEMETRY_SUMMARY_UUID to "Motion Summary 0022",
            TELEMETRY_CALIBRATION_UUID to "IMU Diagnostics 0023",
            TELEMETRY_BOAT_STATE_UUID to "Boat State 0024",
            TELEMETRY_MOTION_ONE_HZ_UUID to "Motion 1 Hz 0025",
            TELEMETRY_LOAD_UUID to "Load Telemetry 0026"
        ).forEach { (uuid, label) ->
            requireCharacteristic(telemetryService, uuid, label)
        }

        requireService(
            EXTENSION_SERVICE_UUID,
            "Extension"
        )
    }

    private fun probeCriticalGattLayout(
        activeGatt: BluetoothGatt,
        info: RegattaLinkDeviceInfo
    ) {
        requireRegattaLinkV2FrozenCore(activeGatt)
        var provedCriticalLayout = false

        if (info.otaAvailable) {
            val statusCharacteristic = requireOtaCharacteristic(
                activeGatt,
                REGATTALINK_OTA_STATUS_UUID
            )
            val descriptor = statusCharacteristic.getDescriptor(CCCD_UUID)
                ?: throw RegattaLinkOtaTransportException(
                    "RegattaLink OTA status CCCD is unavailable",
                    ambiguous = false
                )

            if (
                !activeGatt.setCharacteristicNotification(
                    statusCharacteristic,
                    true
                )
            ) {
                throw RegattaLinkOtaTransportException(
                    "Could not enable RegattaLink OTA status notification " +
                        "for GATT validation",
                    ambiguous = false
                )
            }

            try {
                writeDescriptorBlocking(
                    activeGatt,
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
                parseRegattaLinkOtaStatus(
                    readCharacteristicBlocking(
                        activeGatt,
                        statusCharacteristic
                    )
                )
            } finally {
                runCatching {
                    writeDescriptorBlocking(
                        activeGatt,
                        descriptor,
                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    )
                }
                runCatching {
                    activeGatt.setCharacteristicNotification(
                        statusCharacteristic,
                        false
                    )
                }
                otaProgressQueue.clear()
            }
            provedCriticalLayout = true
        }

        /*
         * Schema 11 appends 0025 after the previous telemetry prefix. OTA
         * handles do not move in that migration, so proving only OTA Status
         * could accept an Android cache that still exposes the old schema-10
         * telemetry table. Prove the actual required normal-motion
         * characteristic and its CCCD as well.
         */
        if (
            regattaLinkGattProofRequiresMotionOneHz(
                reportedVersion = info.gattSchemaVersion,
                telemetryAvailable = info.telemetryAvailable
            )
        ) {
            val motionCharacteristic = activeGatt
                .getService(TELEMETRY_SERVICE_UUID)
                ?.getCharacteristic(TELEMETRY_MOTION_ONE_HZ_UUID)
                ?: throw RegattaLinkOtaTransportException(
                    "RegattaLink Motion 1 Hz characteristic is missing " +
                        "from schema ${info.gattSchemaVersion}",
                    ambiguous = false
                )
            val descriptor = motionCharacteristic.getDescriptor(CCCD_UUID)
                ?: throw RegattaLinkOtaTransportException(
                    "RegattaLink Motion 1 Hz CCCD is unavailable",
                    ambiguous = false
                )

            if (
                !activeGatt.setCharacteristicNotification(
                    motionCharacteristic,
                    true
                )
            ) {
                throw RegattaLinkOtaTransportException(
                    "Could not enable RegattaLink Motion 1 Hz notification " +
                        "for GATT validation",
                    ambiguous = false
                )
            }

            try {
                writeDescriptorBlocking(
                    activeGatt,
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
                parseRegattaLinkMotionOneHz(
                    readCharacteristicBlocking(
                        activeGatt,
                        motionCharacteristic
                    )
                )
            } finally {
                runCatching {
                    writeDescriptorBlocking(
                        activeGatt,
                        descriptor,
                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    )
                }
                runCatching {
                    activeGatt.setCharacteristicNotification(
                        motionCharacteristic,
                        false
                    )
                }
            }
            provedCriticalLayout = true
        }

        if (!provedCriticalLayout) {
            val nameCharacteristic = activeGatt
                .getService(CONFIG_SERVICE_UUID)
                ?.getCharacteristic(DEVICE_NAME_UUID)
                ?: throw RegattaLinkOtaTransportException(
                    "RegattaLink configuration service is incomplete",
                    ambiguous = false
                )
            readCharacteristicBlocking(activeGatt, nameCharacteristic)
        }
    }

    private fun refreshAndroidGattCache(activeGatt: BluetoothGatt): Boolean {
        return runCatching {
            val refresh = activeGatt.javaClass.getMethod("refresh")
            (refresh.invoke(activeGatt) as? Boolean) == true
        }.onFailure { error ->
            Log.w(
                LOG_TAG,
                "Android BluetoothGatt.refresh() fallback unavailable",
                error
            )
        }.getOrDefault(false)
    }

    private fun recoverAndroidGattCacheOrFail(
        activeGatt: BluetoothGatt,
        info: RegattaLinkDeviceInfo,
        reason: String
    ) {
        if (gatt !== activeGatt || !connected) return

        val key = gattSchemaKey(info)
        handler.removeCallbacks(gattSchemaReconcileTimeout)
        gattSchemaStore.clear(info.stableId)
        verifiedGattSchemaThisProcess.remove(key)
        gattServiceChangedReconnectPendingValidation.remove(key)

        if (gattCacheRefreshAttemptsThisProcess.add(key)) {
            /*
             * refresh() must not run on the live connection. First request a
             * clean disconnect and wait for STATE_DISCONNECTED. Only then can
             * completePlannedGattSchemaDisconnect() clear Android's cached ATT
             * database, close this BluetoothGatt and create a fresh instance.
             */
            gattSchemaReconnectGatt = activeGatt
            gattSchemaReconnectDevice = activeGatt.device
            gattSchemaReconnectRefreshCache = true
            gattSchemaReconnectRefreshKey = key
            gattSchemaReconnectRefreshReason = reason
            connectionSetupComplete = false
            Log.w(
                LOG_TAG,
                "Disconnecting before Android GATT cache refresh for RegattaLink " +
                    info.stableId + " schema=" + info.gattSchemaVersion +
                    "; reason=" + reason
            )
            activeGatt.disconnect()
            handler.removeCallbacks(gattSchemaReconnectFallback)
            handler.postDelayed(gattSchemaReconnectFallback, 2_000L)
            return
        }

        gattCacheRefreshPendingValidation.remove(key)
        closeGattWithError(
            activeGatt,
            "RegattaLink GATT cache remained stale after one local refresh. " +
                "Forget/pair the RegattaLink once. Root cause: $reason"
        )
    }

    private fun finishGattSchemaReconciliation() {
        handler.removeCallbacks(gattSchemaReconcileTimeout)
        gattSchemaReconciliationPending = false
        pendingGattSchemaVersion = 0
        pendingGattSchemaInfo = null
    }

    private fun resetGattSchemaReconciliationForNewConnection() {
        handler.removeCallbacks(gattSchemaReconcileTimeout)
        gattSchemaReconciliationPending = false
        pendingGattSchemaVersion = 0
        pendingGattSchemaInfo = null
        serviceChangedObservedThisConnection = false
        serviceChangedRediscoveryCompletedThisConnection = false
        gattSchemaRefreshRequestRunning.set(false)
    }

    private fun optionalFeatureWorkAllowed(activeGatt: BluetoothGatt): Boolean =
        gatt === activeGatt &&
            connected &&
            connectionSetupComplete &&
            !deviceInfoReadInProgress &&
            !serviceRediscoveryRequested.get() &&
            !serviceDiscoveryInProgress &&
            !serviceRediscoveryPending.get() &&
            !otaRunning.get()

    private fun setupConnectedFeatures(
        activeGatt: BluetoothGatt,
        info: RegattaLinkDeviceInfo
    ) {
        if (!optionalFeatureWorkAllowed(activeGatt)) return

        requestConnectionMtuBestEffort(activeGatt)
        if (!optionalFeatureWorkAllowed(activeGatt)) return

        if (info.telemetryAvailable) {
            setupTelemetry(activeGatt)
        }
        if (!optionalFeatureWorkAllowed(activeGatt)) return

        setupConfiguration(activeGatt)
        if (!optionalFeatureWorkAllowed(activeGatt)) return

        setupNmea(activeGatt)
    }

    private fun setupConfiguration(activeGatt: BluetoothGatt) {
        if (!optionalFeatureWorkAllowed(activeGatt)) return

        val service = activeGatt.getService(CONFIG_SERVICE_UUID)
        val nameCharacteristic = service?.getCharacteristic(DEVICE_NAME_UUID)
        val brightnessCharacteristic = service?.getCharacteristic(LED_BRIGHTNESS_UUID)
        val dampingCharacteristic = service?.getCharacteristic(MOTION_DAMPING_UUID)
        val configWordCharacteristic = service?.getCharacteristic(CONFIG_WORD_UUID)
        val headingTrimCharacteristic = service?.getCharacteristic(HEADING_TRIM_UUID)
        val nmeaTxRuntimeStatusCharacteristic =
            service?.getCharacteristic(NMEA_TX_RUNTIME_STATUS_UUID)
        val diagnosticLogCharacteristic =
            service?.getCharacteristic(DIAGNOSTIC_LOG_UUID)
        val deviceControlCharacteristic =
            service?.getCharacteristic(DEVICE_CONTROL_UUID)

        var next = RegattaLinkConfigurationState(
            deviceNameSupported = nameCharacteristic != null,
            ledBrightnessSupported = brightnessCharacteristic != null,
            motionDampingSupported = dampingCharacteristic != null,
            configWordSupported = configWordCharacteristic != null,
            headingTrimSupported = headingTrimCharacteristic != null,
            nmeaTxRuntimeStatusSupported =
                nmeaTxRuntimeStatusCharacteristic != null,
            diagnosticLogSupported = diagnosticLogCharacteristic != null,
            deviceControlSupported = deviceControlCharacteristic != null
        )
        var errorMessage = ""

        if (nameCharacteristic != null && optionalFeatureWorkAllowed(activeGatt)) {
            runCatching {
                parseRegattaLinkDeviceName(
                    readCharacteristicBlocking(activeGatt, nameCharacteristic)
                )
            }.onSuccess { name ->
                next = next.copy(deviceName = name)
            }.onFailure { error ->
                errorMessage = error.message ?: "Could not read RegattaLink name"
            }
        }

        if (brightnessCharacteristic != null && optionalFeatureWorkAllowed(activeGatt)) {
            runCatching {
                parseRegattaLinkLedBrightness(
                    readCharacteristicBlocking(activeGatt, brightnessCharacteristic)
                )
            }.onSuccess { brightness ->
                next = next.copy(ledBrightnessPct = brightness)
            }.onFailure { error ->
                if (errorMessage.isBlank()) {
                    errorMessage = error.message
                        ?: "Could not read RegattaLink LED brightness"
                }
            }
        }

        if (dampingCharacteristic != null && optionalFeatureWorkAllowed(activeGatt)) {
            runCatching {
                parseRegattaLinkMotionDamping(
                    readCharacteristicBlocking(activeGatt, dampingCharacteristic)
                )
            }.onSuccess { damping ->
                next = next.copy(motionDampingSeconds = damping)
            }.onFailure { error ->
                if (errorMessage.isBlank()) {
                    errorMessage = error.message
                        ?: "Could not read RegattaLink motion damping"
                }
            }
        }

        if (
            configWordCharacteristic != null &&
            optionalFeatureWorkAllowed(activeGatt)
        ) {
            runCatching {
                parseRegattaLinkConfigWord(
                    readCharacteristicBlocking(
                        activeGatt,
                        configWordCharacteristic
                    )
                )
            }.onSuccess { word ->
                next = regattaLinkApplyConfigWord(next, word)
            }.onFailure { error ->
                if (errorMessage.isBlank()) {
                    errorMessage = error.message
                        ?: "Could not read RegattaLink config word"
                }
            }
        }

        if (
            headingTrimCharacteristic != null &&
            optionalFeatureWorkAllowed(activeGatt)
        ) {
            runCatching {
                parseRegattaLinkHeadingTrim(
                    readCharacteristicBlocking(
                        activeGatt,
                        headingTrimCharacteristic
                    )
                )
            }.onSuccess { trim ->
                next = next.copy(headingTrimDeg = trim)
            }.onFailure { error ->
                if (errorMessage.isBlank()) {
                    errorMessage = error.message
                        ?: "Could not read RegattaLink heading trim"
                }
            }
        }

        if (
            nmeaTxRuntimeStatusCharacteristic != null &&
            optionalFeatureWorkAllowed(activeGatt)
        ) {
            runCatching {
                parseRegattaLinkNmeaTxRuntimeStatus(
                    readCharacteristicBlocking(
                        activeGatt,
                        nmeaTxRuntimeStatusCharacteristic
                    )
                )
            }.onSuccess { runtime ->
                next = regattaLinkApplyNmeaTxRuntimeStatus(next, runtime)
            }.onFailure { error ->
                if (errorMessage.isBlank()) {
                    errorMessage = error.message
                        ?: "Could not read RegattaLink Boat Data runtime TX status"
                }
            }
        }

        if (
            deviceControlCharacteristic != null &&
            optionalFeatureWorkAllowed(activeGatt)
        ) {
            runCatching {
                parseRegattaLinkDeviceControlStatus(
                    readCharacteristicBlocking(
                        activeGatt,
                        deviceControlCharacteristic
                    )
                )
            }.onSuccess { status ->
                next = next.copy(deviceControlStatus = status)
            }.onFailure { error ->
                if (errorMessage.isBlank()) {
                    errorMessage = error.message
                        ?: "Could not read RegattaLink Device Control status"
                }
            }
        }

        if (gatt === activeGatt && connected) {
            emitConfiguration(
                next.copy(
                    userMessage =
                        RegattaLinkUiMessage.CONFIGURATION_FAILED
                            .takeIf { errorMessage.isNotBlank() },
                    error = errorMessage
                )
            )
        }
    }

    private fun setupNmea(activeGatt: BluetoothGatt) {
        if (!optionalFeatureWorkAllowed(activeGatt)) return

        val configService = activeGatt.getService(CONFIG_SERVICE_UUID)
        val pgnSupported =
            configService?.getCharacteristic(NMEA_PGN_INVENTORY_UUID) != null
        val rawSupported =
            configService?.getCharacteristic(NMEA_RAW_CAN_UUID) != null

        val telemetryService = activeGatt.getService(TELEMETRY_SERVICE_UUID)
        val boatStateCharacteristic =
            telemetryService?.getCharacteristic(TELEMETRY_BOAT_STATE_UUID)
        val loadCharacteristic =
            telemetryService?.getCharacteristic(TELEMETRY_LOAD_UUID)

        handler.removeCallbacks(loadTelemetryStaleRunnable)
        loadPacketAssembler.reset()
        RegattaLinkLoadSnapshotStore.clear()

        emitNmea(
            RegattaLinkNmeaState(
                pgnInventorySupported = pgnSupported,
                rawCanSupported = rawSupported,
                boatStateSupported = boatStateCharacteristic != null,
                loadSupported = loadCharacteristic != null,
                pausedForOta = otaRunning.get()
            )
        )

        if (
            boatStateCharacteristic != null &&
            optionalFeatureWorkAllowed(activeGatt)
        ) {
            if (mtu < REGATTALINK_BOAT_STATE_NOTIFICATION_MTU) {
                requestConnectionMtuBestEffort(activeGatt)
            }
            if (!optionalFeatureWorkAllowed(activeGatt)) return

            var subscribed = false
            var errorMessage = ""
            if (
                activeGatt.setCharacteristicNotification(
                    boatStateCharacteristic,
                    true
                )
            ) {
                val descriptor = boatStateCharacteristic.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    runCatching {
                        writeDescriptorBlocking(
                            activeGatt,
                            descriptor,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        )
                    }.onSuccess {
                        subscribed = true
                    }.onFailure { error ->
                        errorMessage = error.message
                            ?: "Could not subscribe to RegattaLink Boat State"
                    }
                } else {
                    errorMessage =
                        "RegattaLink Boat State CCCD is unavailable"
                }
            } else {
                errorMessage =
                    "Could not enable RegattaLink Boat State notifications"
            }

            if (!optionalFeatureWorkAllowed(activeGatt)) return

            var boatState: RegattaLinkBoatState? = null
            var boatStateReceivedAtElapsedMs: Long? = null
            runCatching {
                parseRegattaLinkBoatState(
                    readCharacteristicBlocking(
                        activeGatt,
                        boatStateCharacteristic
                    )
                )
            }.onSuccess {
                boatState = it
                boatStateReceivedAtElapsedMs =
                    SystemClock.elapsedRealtime()
            }.onFailure { error ->
                if (errorMessage.isBlank()) {
                    errorMessage = error.message
                        ?: "Could not read RegattaLink Boat State"
                }
            }

            if (gatt === activeGatt && connected) {
                updateNmea {
                    it.copy(
                        boatStateSupported = true,
                        boatStateSubscribed = subscribed,
                        boatStateLiveNotifications =
                            subscribed &&
                                mtu >= REGATTALINK_BOAT_STATE_NOTIFICATION_MTU,
                        boatState = boatState ?: it.boatState,
                        boatStateReceivedAtElapsedMs =
                            if (boatState != null) {
                                boatStateReceivedAtElapsedMs
                            } else {
                                it.boatStateReceivedAtElapsedMs
                            },
                        userMessage =
                            if (errorMessage.isBlank()) {
                                it.userMessage
                            } else if (!subscribed) {
                                RegattaLinkUiMessage.NMEA_NOTIFICATIONS_FAILED
                            } else {
                                RegattaLinkUiMessage.NMEA_BOAT_STATE_READ_FAILED
                            },
                        error =
                            listOf(it.error, errorMessage)
                                .filter { message -> message.isNotBlank() }
                                .joinToString("; ")
                    )
                }
            }
        }

        if (
            loadCharacteristic != null &&
            optionalFeatureWorkAllowed(activeGatt)
        ) {
            var subscribed = false
            var errorMessage = ""
            if (
                activeGatt.setCharacteristicNotification(
                    loadCharacteristic,
                    true
                )
            ) {
                val descriptor = loadCharacteristic.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    runCatching {
                        writeDescriptorBlocking(
                            activeGatt,
                            descriptor,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        )
                    }.onSuccess {
                        subscribed = true
                    }.onFailure { error ->
                        errorMessage = error.message
                            ?: "Could not subscribe to RegattaLink load telemetry"
                    }
                } else {
                    errorMessage =
                        "RegattaLink load telemetry CCCD is unavailable"
                }
            } else {
                errorMessage =
                    "Could not enable RegattaLink load telemetry notifications"
            }

            if (gatt === activeGatt && connected) {
                updateNmea {
                    it.copy(
                        loadSupported = true,
                        loadSubscribed = subscribed,
                        userMessage =
                            if (errorMessage.isBlank()) {
                                it.userMessage
                            } else {
                                RegattaLinkUiMessage.NMEA_NOTIFICATIONS_FAILED
                            },
                        error =
                            listOf(it.error, errorMessage)
                                .filter { message -> message.isNotBlank() }
                                .joinToString("; ")
                    )
                }
            }
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
        val motionOneHz = service?.getCharacteristic(
            NORMAL_TELEMETRY_UUIDS.single()
        )

        if (service == null || motionOneHz == null) {
            updateTelemetry {
                it.copy(
                    supported = true,
                    subscribed = false,
                    userMessage = RegattaLinkUiMessage.TELEMETRY_UNAVAILABLE,
                    error = "RegattaLink Motion 1 Hz telemetry is unavailable"
                )
            }
            return
        }

        try {
            if (!activeGatt.setCharacteristicNotification(motionOneHz, true)) {
                throw RegattaLinkOtaTransportException(
                    "Could not enable RegattaLink Motion 1 Hz notifications",
                    ambiguous = false
                )
            }
            val descriptor = motionOneHz.getDescriptor(CCCD_UUID)
                ?: throw RegattaLinkOtaTransportException(
                    "RegattaLink Motion 1 Hz CCCD is unavailable",
                    ambiguous = false
                )
            writeDescriptorBlocking(
                activeGatt,
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )

            updateTelemetry {
                it.copy(
                    supported = true,
                    subscribed = true,
                    userMessage = null,
                    error = ""
                )
            }

            if (gatt !== activeGatt || !connected) return
            val raw = readCharacteristicBlocking(activeGatt, motionOneHz)
            handleTelemetryRecord(
                activeGatt,
                motionOneHz.uuid,
                raw,
                initialOnly = true
            )
        } catch (error: Exception) {
            if (gatt === activeGatt) {
                updateTelemetry {
                    it.copy(
                        supported = true,
                        subscribed = false,
                        userMessage = RegattaLinkUiMessage.TELEMETRY_FAILED,
                        error = error.message
                            ?: "RegattaLink Motion 1 Hz subscription failed"
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

        if (characteristicUuid == TELEMETRY_LOAD_UUID) {
            if (otaRunning.get()) return
            runCatching {
                loadPacketAssembler.accept(value)
            }.onSuccess { sensors ->
                if (sensors != null) {
                    val receivedAtElapsedMs = SystemClock.elapsedRealtime()
                    RegattaLinkLoadSnapshotStore.update(
                        sensors,
                        receivedAtElapsedMs = receivedAtElapsedMs
                    )
                    updateNmea {
                        it.copy(
                            loadSupported = true,
                            loadSubscribed = true,
                            loadSensors = sensors,
                            loadReceivedAtElapsedMs =
                                receivedAtElapsedMs.takeIf {
                                    sensors.isNotEmpty()
                                },
                            userMessage = null,
                            error = ""
                        )
                    }
                    handler.removeCallbacks(loadTelemetryStaleRunnable)
                    if (sensors.isNotEmpty()) {
                        handler.postDelayed(
                            loadTelemetryStaleRunnable,
                            REGATTALINK_LOAD_TRANSPORT_STALE_MS
                        )
                    }
                }
            }.onFailure { error ->
                updateNmea {
                    it.copy(
                        loadSupported = true,
                        userMessage = RegattaLinkUiMessage.NMEA_FAILED,
                        error = error.message
                            ?: "Invalid RegattaLink load telemetry"
                    )
                }
            }
            return
        }

        if (characteristicUuid == TELEMETRY_BOAT_STATE_UUID) {
            if (otaRunning.get()) return
            runCatching {
                parseRegattaLinkBoatState(value)
            }.onSuccess { boatState ->
                val receivedAt = SystemClock.elapsedRealtime()
                updateNmea {
                    it.copy(
                        boatStateSupported = true,
                        boatStateSubscribed = true,
                        boatStateLiveNotifications =
                            mtu >= REGATTALINK_BOAT_STATE_NOTIFICATION_MTU,
                        boatState = boatState,
                        boatStateReceivedAtElapsedMs = receivedAt,
                        userMessage = null,
                        error = ""
                    )
                }
            }.onFailure { error ->
                updateNmea {
                    it.copy(
                        boatStateSupported = true,
                        userMessage = RegattaLinkUiMessage.NMEA_BOAT_STATE_READ_FAILED,
                        error = error.message ?: "Invalid RegattaLink Boat State record"
                    )
                }
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
                TELEMETRY_MOTION_ONE_HZ_UUID -> {
                    val parsed = parseRegattaLinkMotionOneHz(value)
                    updateTelemetry {
                        if (initialOnly && it.motionOneHz != null) {
                            it
                        } else {
                            it.copy(
                                supported = true,
                                motionOneHz = parsed,
                                motionOneHzReceivedAtElapsedMs = receivedAt,
                                userMessage = null,
                                error = ""
                            )
                        }
                    }
                }

                TELEMETRY_FAST_UUID -> {
                    val version =
                        value.firstOrNull()?.toInt()?.and(0xff) ?: -1
                    when (version) {
                        REGATTALINK_TELEMETRY_SCHEMA_VERSION -> {
                            val parsed = parseRegattaLinkFastMotion(value)
                            updateTelemetry {
                                if (initialOnly && it.fast != null) {
                                    it
                                } else {
                                    it.copy(
                                        supported = true,
                                        fast = parsed,
                                        fastReceivedAtElapsedMs = receivedAt,
                                        userMessage = null,
                                        error = ""
                                    )
                                }
                            }
                        }

                        REGATTALINK_RAW_IMU_SCHEMA_VERSION -> {
                            val parsed = parseRegattaLinkRawImu(value)
                            updateTelemetry {
                                it.copy(
                                    supported = true,
                                    rawImu = parsed,
                                    rawImuReceivedAtElapsedMs = receivedAt,
                                    userMessage = null,
                                    error = ""
                                )
                            }
                        }

                        else -> throw IllegalArgumentException(
                            "Unsupported RegattaLink Fast Motion schema $version"
                        )
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
                                userMessage = null,
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
                                userMessage = null,
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
                    userMessage = RegattaLinkUiMessage.TELEMETRY_INVALID,
                    error = error.message ?: "Invalid RegattaLink telemetry record"
                )
            }
        }
    }

    private fun configurationMutationBlocked(
        activeGatt: BluetoothGatt
    ): Boolean =
        regattaLinkConfigurationMutationBlocked(
            state = lastConfigurationState,
            factoryResetOwned =
                factoryResetDisconnectTracker.ownsLifecycle(activeGatt),
            deviceControlRunning = deviceControlExecutionGuard.isActive(),
            diagnosticLogRunning = diagnosticLogRunning.get()
        )

    override fun setDeviceName(name: String): Boolean {
        if (validateRegattaLinkDeviceName(name) != null) {
            return false
        }
        if (otaRunning.get() || !isConnected()) return false

        val activeGatt = gatt ?: return false
        if (
            configurationMutationBlocked(activeGatt) ||
            !configurationMutationRunning.compareAndSet(false, true)
        ) {
            return false
        }
        otaExecutor.execute {
            try {
                if (
                    !optionalFeatureWorkAllowed(activeGatt) ||
                    configurationMutationBlocked(activeGatt)
                ) {
                    return@execute
                }
                updateConfiguration {
                    it.copy(busy = true, userMessage = null, error = "")
                }
                try {
                    val characteristic = activeGatt
                        .getService(CONFIG_SERVICE_UUID)
                        ?.getCharacteristic(DEVICE_NAME_UUID)
                        ?: throw RegattaLinkOtaTransportException(
                            "RegattaLink name setting is unavailable",
                            ambiguous = false
                        )
                    writeCharacteristicBlockingDirect(
                        activeGatt,
                        characteristic,
                        name.toByteArray(Charsets.UTF_8)
                    )
                    updateConfiguration {
                        it.copy(
                            deviceNameSupported = true,
                            deviceName = name,
                            busy = false,
                            userMessage = null,
                            error = ""
                        )
                    }
                } catch (error: Exception) {
                    updateConfiguration {
                        it.copy(
                            busy = false,
                            userMessage = RegattaLinkUiMessage.NAME_CHANGE_FAILED,
                            error = error.message ?: "Could not change RegattaLink name"
                        )
                    }
                }
            } finally {
                configurationMutationRunning.set(false)
            }
        }
        return true
    }

    override fun setLedBrightness(percent: Int): Boolean {
        if (percent !in 0..100) {
            updateConfiguration {
                it.copy(
                    userMessage = RegattaLinkUiMessage.LED_BRIGHTNESS_RANGE,
                    error = "LED brightness must be between 0 and 100"
                )
            }
            return false
        }
        if (otaRunning.get() || !isConnected()) return false

        val activeGatt = gatt ?: return false
        if (
            configurationMutationBlocked(activeGatt) ||
            !configurationMutationRunning.compareAndSet(false, true)
        ) {
            return false
        }
        otaExecutor.execute {
            try {
                if (
                    !optionalFeatureWorkAllowed(activeGatt) ||
                    configurationMutationBlocked(activeGatt)
                ) {
                    return@execute
                }
                updateConfiguration { it.copy(busy = true, userMessage = null, error = "") }
                try {
                    val characteristic = activeGatt
                        .getService(CONFIG_SERVICE_UUID)
                        ?.getCharacteristic(LED_BRIGHTNESS_UUID)
                        ?: throw RegattaLinkOtaTransportException(
                            "RegattaLink LED brightness is unavailable",
                            ambiguous = false
                        )
                    writeCharacteristicBlockingDirect(
                        activeGatt,
                        characteristic,
                        byteArrayOf(percent.toByte())
                    )
                    updateConfiguration {
                        it.copy(
                            ledBrightnessSupported = true,
                            ledBrightnessPct = percent,
                            busy = false,
                            userMessage = null,
                            error = ""
                        )
                    }
                } catch (error: Exception) {
                    val reread =
                        if (optionalFeatureWorkAllowed(activeGatt)) {
                            runCatching {
                                val characteristic = activeGatt
                                    .getService(CONFIG_SERVICE_UUID)
                                    ?.getCharacteristic(LED_BRIGHTNESS_UUID)
                                    ?: return@runCatching null
                                parseRegattaLinkLedBrightness(
                                    readCharacteristicBlocking(activeGatt, characteristic)
                                )
                            }.getOrNull()
                        } else {
                            null
                        }
                    updateConfiguration {
                        it.copy(
                            ledBrightnessPct = reread ?: it.ledBrightnessPct,
                            busy = false,
                            userMessage = RegattaLinkUiMessage.CONFIGURATION_FAILED,
                            error = error.message
                                ?: "Could not change RegattaLink LED brightness"
                        )
                    }
                }
            } finally {
                configurationMutationRunning.set(false)
            }
        }
        return true
    }

    override fun setMotionDamping(seconds: Int): Boolean {
        if (seconds !in 1..10) {
            updateConfiguration {
                it.copy(
                    userMessage = RegattaLinkUiMessage.MOTION_DAMPING_RANGE,
                    error = "Motion damping must be between 1 and 10 seconds"
                )
            }
            return false
        }
        if (otaRunning.get() || !isConnected()) return false

        val activeGatt = gatt ?: return false
        if (
            configurationMutationBlocked(activeGatt) ||
            !configurationMutationRunning.compareAndSet(false, true)
        ) {
            return false
        }
        otaExecutor.execute {
            try {
                if (
                    !optionalFeatureWorkAllowed(activeGatt) ||
                    configurationMutationBlocked(activeGatt)
                ) {
                    return@execute
                }
                updateConfiguration { it.copy(busy = true, userMessage = null, error = "") }
                try {
                    val characteristic = activeGatt
                        .getService(CONFIG_SERVICE_UUID)
                        ?.getCharacteristic(MOTION_DAMPING_UUID)
                        ?: throw RegattaLinkOtaTransportException(
                            "RegattaLink motion damping setting is unavailable",
                            ambiguous = false
                        )
                    writeCharacteristicBlockingDirect(
                        activeGatt,
                        characteristic,
                        byteArrayOf(seconds.toByte())
                    )
                    updateConfiguration {
                        it.copy(
                            motionDampingSupported = true,
                            motionDampingSeconds = seconds,
                            busy = false,
                            userMessage = null,
                            error = ""
                        )
                    }
                } catch (error: Exception) {
                    val reread =
                        if (optionalFeatureWorkAllowed(activeGatt)) {
                            runCatching {
                                val characteristic = activeGatt
                                    .getService(CONFIG_SERVICE_UUID)
                                    ?.getCharacteristic(MOTION_DAMPING_UUID)
                                    ?: return@runCatching null
                                parseRegattaLinkMotionDamping(
                                    readCharacteristicBlocking(activeGatt, characteristic)
                                )
                            }.getOrNull()
                        } else {
                            null
                        }
                    updateConfiguration {
                        it.copy(
                            motionDampingSeconds =
                                reread ?: it.motionDampingSeconds,
                            busy = false,
                            userMessage = RegattaLinkUiMessage.CONFIGURATION_FAILED,
                            error = error.message
                                ?: "Could not change RegattaLink motion damping"
                        )
                    }
                }
            } finally {
                configurationMutationRunning.set(false)
            }
        }
        return true
    }

    override fun setNmeaTxEnabled(enabled: Boolean): Boolean =
        mutateConfigWord(
            mask = REGATTALINK_CONFIG_TX_MASTER,
            encodedBits =
                REGATTALINK_CONFIG_TX_MASTER.takeIf { enabled } ?: 0u,
            failureText = "Could not change RegattaLink Boat Data TX setting"
        )

    override fun setNmeaAttitudeTxEnabled(enabled: Boolean): Boolean =
        mutateConfigWord(
            mask = REGATTALINK_CONFIG_TX_IMU,
            encodedBits =
                REGATTALINK_CONFIG_TX_IMU.takeIf { enabled } ?: 0u,
            failureText = "Could not change RegattaLink Heel / Trim TX setting"
        )

    override fun setNmea0183TxEnabled(enabled: Boolean): Boolean =
        mutateConfigWord(
            mask = REGATTALINK_CONFIG_TX_NMEA0183,
            encodedBits =
                REGATTALINK_CONFIG_TX_NMEA0183.takeIf { enabled } ?: 0u,
            failureText = "Could not change RegattaLink NMEA 0183 TX setting"
        )

    override fun setPhoneGpsTxEnabled(enabled: Boolean): Boolean =
        mutateConfigWord(
            mask = REGATTALINK_CONFIG_TX_PHONE_GPS,
            encodedBits =
                REGATTALINK_CONFIG_TX_PHONE_GPS.takeIf { enabled } ?: 0u,
            failureText = "Could not change RegattaLink Phone GPS TX setting"
        )

    override fun setCompassTxEnabled(enabled: Boolean): Boolean =
        mutateConfigWord(
            mask = REGATTALINK_CONFIG_TX_COMPASS,
            encodedBits =
                REGATTALINK_CONFIG_TX_COMPASS.takeIf { enabled } ?: 0u,
            failureText = "Could not change RegattaLink compass TX setting"
        )

    override fun setLoadPrecisionX10(enabled: Boolean): Boolean =
        mutateConfigWord(
            mask = REGATTALINK_CONFIG_LOAD_PRECISION_X10,
            encodedBits =
                REGATTALINK_CONFIG_LOAD_PRECISION_X10
                    .takeIf { enabled } ?: 0u,
            failureText = "Could not change RegattaLink load precision"
        )

    override fun setMagBackgroundLearningEnabled(enabled: Boolean): Boolean =
        mutateConfigWord(
            mask = REGATTALINK_CONFIG_MAG_BACKGROUND_LEARNING,
            encodedBits =
                REGATTALINK_CONFIG_MAG_BACKGROUND_LEARNING
                    .takeIf { enabled } ?: 0u,
            failureText = "Could not change RegattaLink MAG background learning"
        )

    override fun setNmea0183Baud(baudRate: Int): Boolean {
        val baud = RegattaLinkNmea0183Baud.fromBaudRate(baudRate)
            ?: return false
        return mutateConfigWord(
            mask = REGATTALINK_CONFIG_NMEA0183_BAUD_MASK,
            encodedBits = baud.encodedBits,
            failureText = "Could not change RegattaLink NMEA 0183 baud"
        )
    }

    override fun setSubsystemEnabled(
        subsystem: RegattaLinkSubsystem,
        enabled: Boolean
    ): Boolean =
        mutateConfigWord(
            mask = subsystem.configBit,
            encodedBits = subsystem.configBit.takeIf { enabled } ?: 0u,
            failureText = "Could not change RegattaLink subsystem setting",
            rereadAfterWrite = true
        )

    override fun applyConfigBitsAndRestart(
        mask: UInt,
        encodedBits: UInt
    ): Boolean =
        mutateConfigWord(
            mask = mask,
            encodedBits = encodedBits,
            failureText = "Could not apply RegattaLink configuration",
            rereadAfterWrite = true,
            restartAfterWrite = true
        )

    private fun mutateConfigWord(
        mask: UInt,
        encodedBits: UInt,
        failureText: String,
        rereadAfterWrite: Boolean = false,
        restartAfterWrite: Boolean = false
    ): Boolean {
        if (encodedBits and mask.inv() != 0u) return false
        if (otaRunning.get() || !isConnected()) return false

        val activeGatt = gatt ?: return false
        if (
            configurationMutationBlocked(activeGatt) ||
            !configurationMutationRunning.compareAndSet(false, true)
        ) {
            return false
        }

        otaExecutor.execute {
            var wordBeforeWrite: UInt? = null
            var restartAfterMutation = false
            try {
                if (
                    !optionalFeatureWorkAllowed(activeGatt) ||
                    configurationMutationBlocked(activeGatt)
                ) {
                    return@execute
                }
                updateConfiguration {
                    it.copy(busy = true, userMessage = null, error = "")
                }

                val characteristic = activeGatt
                    .getService(CONFIG_SERVICE_UUID)
                    ?.getCharacteristic(CONFIG_WORD_UUID)

                if (characteristic == null) {
                    updateConfiguration {
                        it.copy(
                            busy = false,
                            userMessage = RegattaLinkUiMessage.CONFIGURATION_FAILED,
                            error = "RegattaLink config word is unavailable"
                        )
                    }
                    return@execute
                }

                try {
                    val currentWord = parseRegattaLinkConfigWord(
                        readCharacteristicBlocking(activeGatt, characteristic)
                    )
                    wordBeforeWrite = currentWord
                    val nextWord = regattaLinkConfigWordWithMask(
                        current = currentWord,
                        mask = mask,
                        encodedBits = encodedBits
                    )

                    if (nextWord != currentWord) {
                        writeCharacteristicBlockingDirect(
                            activeGatt,
                            characteristic,
                            encodeRegattaLinkConfigWord(nextWord)
                        )
                    }

                    /*
                     * Bits 16..19 read back current-session availability, not
                     * the desired next-boot subsystem state. Re-reading keeps
                     * current-session availability authoritative while the
                     * staged UI owns the user's next-boot selection.
                     */
                    val confirmedWord =
                        if (rereadAfterWrite && nextWord != currentWord) {
                            parseRegattaLinkConfigWord(
                                readCharacteristicBlocking(
                                    activeGatt,
                                    characteristic
                                )
                            )
                        } else {
                            nextWord
                        }

                    updateConfiguration { current ->
                        regattaLinkApplyConfigWord(
                            current,
                            confirmedWord
                        ).copy(
                            configRestartRequired =
                                current.configRestartRequired ||
                                    nextWord != currentWord,
                            busy = false,
                            userMessage = null,
                            error = ""
                        )
                    }
                    restartAfterMutation = restartAfterWrite
                } catch (error: Exception) {
                    val rereadWord =
                        if (optionalFeatureWorkAllowed(activeGatt)) {
                            runCatching {
                                parseRegattaLinkConfigWord(
                                    readCharacteristicBlocking(
                                        activeGatt,
                                        characteristic
                                    )
                                )
                            }.getOrNull()
                        } else {
                            null
                        }
                    updateConfiguration { current ->
                        val authoritative =
                            rereadWord?.let {
                                regattaLinkApplyConfigWord(current, it)
                            } ?: current
                        authoritative.copy(
                            configRestartRequired =
                                authoritative.configRestartRequired ||
                                    (
                                        rereadWord != null &&
                                            wordBeforeWrite != null &&
                                            rereadWord != wordBeforeWrite
                                        ),
                            busy = false,
                            userMessage =
                                RegattaLinkUiMessage.CONFIGURATION_FAILED,
                            error = error.message ?: failureText
                        )
                    }
                }
            } finally {
                configurationMutationRunning.set(false)
            }

            if (
                restartAfterMutation &&
                !executeDeviceControl(RegattaLinkDeviceControlOpcode.RESTART, 0)
            ) {
                updateConfiguration {
                    it.copy(
                        userMessage = RegattaLinkUiMessage.CONFIGURATION_FAILED,
                        error = "Configuration was saved, but RegattaLink restart could not be started"
                    )
                }
            }
        }
        return true
    }

    override fun setHeadingTrimDeg(value: Int): Boolean {
        if (value !in -180..180) return false
        if (otaRunning.get() || !isConnected()) return false

        val activeGatt = gatt ?: return false
        if (
            configurationMutationBlocked(activeGatt) ||
            !configurationMutationRunning.compareAndSet(false, true)
        ) {
            return false
        }

        otaExecutor.execute {
            try {
                if (
                    !optionalFeatureWorkAllowed(activeGatt) ||
                    configurationMutationBlocked(activeGatt)
                ) {
                    return@execute
                }
                updateConfiguration {
                    it.copy(busy = true, userMessage = null, error = "")
                }

                val characteristic = activeGatt
                    .getService(CONFIG_SERVICE_UUID)
                    ?.getCharacteristic(HEADING_TRIM_UUID)

                if (characteristic == null) {
                    updateConfiguration {
                        it.copy(
                            busy = false,
                            userMessage = RegattaLinkUiMessage.CONFIGURATION_FAILED,
                            error = "RegattaLink heading trim is unavailable"
                        )
                    }
                    return@execute
                }

                try {
                    writeCharacteristicBlockingDirect(
                        activeGatt,
                        characteristic,
                        encodeRegattaLinkHeadingTrim(value)
                    )
                    updateConfiguration {
                        it.copy(
                            headingTrimSupported = true,
                            headingTrimDeg = value,
                            busy = false,
                            userMessage = null,
                            error = ""
                        )
                    }
                } catch (error: Exception) {
                    val reread =
                        if (optionalFeatureWorkAllowed(activeGatt)) {
                            runCatching {
                                parseRegattaLinkHeadingTrim(
                                    readCharacteristicBlocking(
                                        activeGatt,
                                        characteristic
                                    )
                                )
                            }.getOrNull()
                        } else {
                            null
                        }
                    updateConfiguration {
                        it.copy(
                            headingTrimDeg = reread ?: it.headingTrimDeg,
                            busy = false,
                            userMessage =
                                RegattaLinkUiMessage.CONFIGURATION_FAILED,
                            error = error.message
                                ?: "Could not change RegattaLink heading trim"
                        )
                    }
                }
            } finally {
                configurationMutationRunning.set(false)
            }
        }
        return true
    }

    override fun offerPhoneGnss(sample: RegattaLinkPhoneGnssSample): Boolean {
        if (!phoneGnssForwardingAllowed()) {
            clearPhoneGnssPending()
            return false
        }
        phoneGnssPending.set(sample)
        schedulePhoneGnssDrain()
        return true
    }

    private fun phoneGnssForwardingAllowed(): Boolean =
        regattaLinkPhoneGnssForwardingGate(
            connected = connected,
            transportReady =
                establishedConnection &&
                    regattaLinkPhoneGnssTransportReady(mtu),
            otaActive = otaRunning.get(),
            configurationState = lastConfigurationState
        )

    private fun schedulePhoneGnssDrain() {
        if (!phoneGnssForwardingAllowed()) return
        if (!phoneGnssDrainScheduled.compareAndSet(false, true)) return

        val elapsedSinceLast =
            SystemClock.elapsedRealtime() - phoneGnssLastSubmitElapsedMs
        val delayMs =
            (REGATTALINK_PHONE_GNSS_MIN_INTERVAL_MS - elapsedSinceLast)
                .coerceAtLeast(0L)
        handler.postDelayed(phoneGnssDrainRunnable, delayMs)
    }

    private fun drainPhoneGnssPending() {
        try {
            if (!phoneGnssForwardingAllowed()) {
                phoneGnssPending.set(null)
                return
            }

            val sample = phoneGnssPending.getAndSet(null) ?: return
            val activeGatt = gatt ?: return
            if (!optionalFeatureWorkAllowed(activeGatt)) return
            if (!regattaLinkPhoneGnssTransportReady(mtu)) return

            val characteristic = activeGatt
                .getService(CONFIG_SERVICE_UUID)
                ?.getCharacteristic(PHONE_GNSS_INPUT_UUID)
                ?: return
            val sendElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            val encoded = encodeRegattaLinkPhoneGnss(
                sample = sample,
                sendElapsedRealtimeNanos = sendElapsedRealtimeNanos
            ) ?: return

            phoneGnssLastSubmitElapsedMs = SystemClock.elapsedRealtime()
            runCatching {
                writeCharacteristicBlockingDirect(
                    activeGatt,
                    characteristic,
                    encoded
                )
            }.onFailure { error ->
                Log.w(LOG_TAG, "Phone GNSS write failed", error)
            }
        } finally {
            phoneGnssDrainScheduled.set(false)
            if (
                phoneGnssPending.get() != null &&
                phoneGnssForwardingAllowed()
            ) {
                schedulePhoneGnssDrain()
            }
        }
    }

    override fun clearPhoneGnss() {
        clearPhoneGnssPending()
    }

    private fun clearPhoneGnssPending() {
        phoneGnssPending.set(null)
    }

    override fun drainDiagnosticLog(): Boolean {
        if (
            !lastConfigurationState.diagnosticLogSupported ||
            otaRunning.get() ||
            rawCaptureRunning.get() ||
            deviceControlExecutionGuard.isActive() ||
            configurationMutationRunning.get() ||
            !isConnected() ||
            !diagnosticLogRunning.compareAndSet(false, true)
        ) {
            return false
        }

        val activeGatt = gatt ?: run {
            diagnosticLogRunning.set(false)
            return false
        }

        updateConfiguration {
            it.copy(
                diagnosticLogLoading = true,
                diagnosticLogEntries = emptyList(),
                diagnosticLogError = ""
            )
        }

        otaExecutor.execute {
            if (!optionalFeatureWorkAllowed(activeGatt)) {
                diagnosticLogRunning.set(false)
                if (gatt === activeGatt && connected) {
                    updateConfiguration {
                        it.copy(diagnosticLogLoading = false)
                    }
                }
                return@execute
            }

            val entries = mutableListOf<RegattaLinkDiagnosticLogEntry>()
            var errorMessage = ""
            try {
                val characteristic = activeGatt.getService(CONFIG_SERVICE_UUID)
                    ?.getCharacteristic(DIAGNOSTIC_LOG_UUID)
                    ?: throw RegattaLinkOtaTransportException(
                        "RegattaLink diagnostic log is unavailable",
                        ambiguous = false
                    )

                for (readIndex in 0 until REGATTALINK_DIAGNOSTIC_LOG_MAX_READS) {
                    if (!optionalFeatureWorkAllowed(activeGatt)) break
                    val parsed = parseRegattaLinkDiagnosticLogEntry(
                        readCharacteristicBlocking(activeGatt, characteristic)
                    ) ?: break
                    entries += parsed
                }
            } catch (error: Exception) {
                errorMessage =
                    error.message ?: "Could not read RegattaLink diagnostic log"
            } finally {
                diagnosticLogRunning.set(false)
            }

            if (gatt === activeGatt && connected) {
                updateConfiguration {
                    it.copy(
                        diagnosticLogSupported = true,
                        diagnosticLogLoading = false,
                        diagnosticLogEntries = entries,
                        diagnosticLogError = errorMessage
                    )
                }
            }
        }
        return true
    }

    override fun setImuRawPreviewEnabled(enabled: Boolean): Boolean {
        if (
            otaRunning.get() ||
            rawCaptureRunning.get() ||
            diagnosticLogRunning.get() ||
            !isConnected()
        ) {
            return false
        }

        val activeGatt = gatt ?: return false
        otaExecutor.execute {
            if (!optionalFeatureWorkAllowed(activeGatt)) {
                return@execute
            }

            try {
                val characteristic = activeGatt
                    .getService(TELEMETRY_SERVICE_UUID)
                    ?.getCharacteristic(TELEMETRY_FAST_UUID)
                    ?: throw RegattaLinkOtaTransportException(
                        "RegattaLink Fast Motion telemetry is unavailable",
                        ambiguous = false
                    )
                val descriptor = characteristic.getDescriptor(CCCD_UUID)
                    ?: throw RegattaLinkOtaTransportException(
                        "RegattaLink Fast Motion CCCD is unavailable",
                        ambiguous = false
                    )

                if (enabled) {
                    updateTelemetry {
                        it.copy(
                            fast = null,
                            fastReceivedAtElapsedMs = null,
                            rawImu = null,
                            rawImuReceivedAtElapsedMs = null
                        )
                    }
                    if (
                        !activeGatt.setCharacteristicNotification(
                            characteristic,
                            true
                        )
                    ) {
                        throw RegattaLinkOtaTransportException(
                            "Could not enable RegattaLink Fast Motion notifications",
                            ambiguous = false
                        )
                    }
                    writeDescriptorBlocking(
                        activeGatt,
                        descriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    )
                } else {
                    runCatching {
                        writeDescriptorBlocking(
                            activeGatt,
                            descriptor,
                            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                        )
                    }
                    activeGatt.setCharacteristicNotification(
                        characteristic,
                        false
                    )
                }
            } catch (error: Exception) {
                if (enabled && gatt === activeGatt && connected) {
                    updateTelemetry {
                        it.copy(
                            userMessage = RegattaLinkUiMessage.TELEMETRY_FAILED,
                            error = error.message
                                ?: "Could not read RegattaLink installation orientation"
                        )
                    }
                }
            }
        }
        return true
    }

    override fun executeDeviceControl(
        opcode: RegattaLinkDeviceControlOpcode,
        value: Int
    ): Boolean {
        if (
            opcode == RegattaLinkDeviceControlOpcode.RESTART ||
            opcode == RegattaLinkDeviceControlOpcode.FACTORY_RESET
        ) {
            clearPhoneGnssPending()
        }
        if (
            !lastConfigurationState.deviceControlSupported ||
            otaRunning.get() ||
            rawCaptureRunning.get() ||
            diagnosticLogRunning.get() ||
            configurationMutationRunning.get() ||
            !isConnected()
        ) {
            return false
        }

        val activeGatt = gatt ?: return false
        val execution =
            deviceControlExecutionGuard.tryAcquire(activeGatt)
                ?: return false

        if (!isConnected() || gatt !== activeGatt) {
            deviceControlExecutionGuard.release(execution)
            return false
        }

        otaExecutor.execute {
            if (!optionalFeatureWorkAllowed(activeGatt)) {
                rejectDeviceControlBeforeStart(execution)
                return@execute
            }

            try {
                val requestId = nextDeviceControlRequestId()
            updateConfiguration {
                it.copy(
                    deviceControlBusy = true,
                    deviceControlAcceptedOpcode = null,
                    deviceControlAcceptedRequestId = null,
                    factoryResetWriteAcceptedRequestId = null,
                    deviceControlError = ""
                )
            }

            var finalStatus: RegattaLinkDeviceControlStatus? = null
            var errorMessage = ""
            var factoryResetFinalizationDeadline: Long? = null
            var factoryResetWriteAccepted = false
            try {
                val characteristic = activeGatt.getService(CONFIG_SERVICE_UUID)
                    ?.getCharacteristic(DEVICE_CONTROL_UUID)
                    ?: throw RegattaLinkOtaTransportException(
                        "RegattaLink Device Control is unavailable",
                        ambiguous = false
                    )

                val request = buildRegattaLinkDeviceControlRequest(
                    opcode = opcode,
                    requestId = requestId,
                    value = value
                )
                writeCharacteristicBlockingDirect(
                    activeGatt,
                    characteristic,
                    request,
                    onSubmitted =
                        if (opcode == RegattaLinkDeviceControlOpcode.FACTORY_RESET) {
                            {
                                /*
                                 * Local submission is the last point at which
                                 * we know the request was definitely not sent.
                                 * After ACCEPTED, any missing/error callback is
                                 * ambiguous and firmware may already continue
                                 * the destructive reset after disconnect.
                                 */
                                factoryResetWriteAccepted = true
                                factoryResetDisconnectTracker.markAccepted(
                                    session = activeGatt,
                                    requestId = requestId
                                )
                                onFactoryResetRecoveryStateChanged(true)
                                updateConfiguration {
                                    it.copy(
                                        factoryResetWriteAcceptedRequestId = requestId
                                    )
                                }
                            }
                        } else {
                            null
                        }
                )

                var requestAcceptanceObserved = false
                val commandDeadline =
                    SystemClock.elapsedRealtime() +
                        REGATTALINK_DEVICE_CONTROL_CLIENT_TIMEOUT_MS
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    val activeDeadline =
                        factoryResetFinalizationDeadline ?: commandDeadline
                    if (now >= activeDeadline) break

                    if (!optionalFeatureWorkAllowed(activeGatt)) {
                        if (
                            opcode == RegattaLinkDeviceControlOpcode.FACTORY_RESET &&
                            factoryResetFinalizationDeadline != null &&
                            (gatt !== activeGatt || !connected)
                        ) {
                            break
                        }
                        throw RegattaLinkOtaTransportException(
                            "RegattaLink Device Control was interrupted",
                            ambiguous = true
                        )
                    }

                    val status = parseRegattaLinkDeviceControlStatus(
                        readCharacteristicBlocking(activeGatt, characteristic)
                    )

                    if (
                        regattaLinkDeviceControlStatusConfirmsAcceptance(
                            status,
                            requestId
                        ) &&
                        !requestAcceptanceObserved
                    ) {
                        requestAcceptanceObserved = true
                        updateConfiguration {
                            it.copy(
                                deviceControlAcceptedOpcode = opcode,
                                deviceControlAcceptedRequestId = requestId
                            )
                        }
                    }

                    if (
                        opcode == RegattaLinkDeviceControlOpcode.FACTORY_RESET &&
                        status.requestId == requestId &&
                        status.applicationErrorCode != null
                    ) {
                        factoryResetDisconnectTracker.clear(
                            session = activeGatt,
                            requestId = requestId
                        )
                        onFactoryResetRecoveryStateChanged(false)
                        updateConfiguration {
                            it.copy(
                                factoryResetWriteAcceptedRequestId = null
                            )
                        }
                    }

                    if (
                        opcode == RegattaLinkDeviceControlOpcode.FACTORY_RESET &&
                        status.requestId == requestId &&
                        status.factoryResetBondsCleared
                    ) {
                        finalStatus = status
                        updateConfiguration {
                            it.copy(
                                deviceControlSupported = true,
                                factoryResetAwaitingDisconnect = true,
                                deviceControlStatus = status,
                                deviceControlError =
                                    regattaLinkDeviceControlFailureText(status)
                            )
                        }
                        requestFactoryResetLocalDisconnect(activeGatt)
                        break
                    }

                    val resetContinuesToBondReset =
                        opcode == RegattaLinkDeviceControlOpcode.FACTORY_RESET &&
                            regattaLinkFactoryResetContinuesToBondReset(status)
                    when (
                        regattaLinkDeviceControlPollDecision(
                            status,
                            requestId
                        )
                    ) {
                        RegattaLinkDeviceControlPollDecision.IGNORE_OTHER_REQUEST -> Unit

                        RegattaLinkDeviceControlPollDecision.CONTINUE -> {
                            finalStatus = status
                            updateConfiguration {
                                it.copy(
                                    deviceControlSupported = true,
                                    deviceControlStatus = status,
                                    deviceControlError = ""
                                )
                            }
                        }

                        RegattaLinkDeviceControlPollDecision.SUCCESS -> {
                            finalStatus = status
                            if (opcode == RegattaLinkDeviceControlOpcode.RESTART) {
                                restartDisconnectTracker.markExpected(activeGatt)
                                scheduleRestartDisconnectTimeout(activeGatt)
                                updateConfiguration {
                                    it.copy(
                                        deviceControlSupported = true,
                                        restartAwaitingDisconnect = true,
                                        deviceControlStatus = status,
                                        deviceControlError = ""
                                    )
                                }
                                break
                            }
                            if (resetContinuesToBondReset) {
                                if (factoryResetFinalizationDeadline == null) {
                                    factoryResetFinalizationDeadline =
                                        now + REGATTALINK_FACTORY_RESET_FINALIZATION_TIMEOUT_MS
                                }
                                updateConfiguration {
                                    it.copy(
                                        deviceControlSupported = true,
                                        factoryResetAwaitingDisconnect = true,
                                        deviceControlStatus = status,
                                        deviceControlError = ""
                                    )
                                }
                            } else {
                                updateConfiguration {
                                    it.copy(
                                        deviceControlSupported = true,
                                        deviceControlStatus = status,
                                        deviceControlError = ""
                                    )
                                }
                                break
                            }
                        }

                        RegattaLinkDeviceControlPollDecision.FAILURE -> {
                            finalStatus = status
                            if (resetContinuesToBondReset) {
                                if (factoryResetFinalizationDeadline == null) {
                                    factoryResetFinalizationDeadline =
                                        now + REGATTALINK_FACTORY_RESET_FINALIZATION_TIMEOUT_MS
                                }
                                updateConfiguration {
                                    it.copy(
                                        deviceControlSupported = true,
                                        factoryResetAwaitingDisconnect = true,
                                        deviceControlStatus = status,
                                        deviceControlError =
                                            regattaLinkDeviceControlFailureText(status)
                                    )
                                }
                            } else {
                                updateConfiguration {
                                    it.copy(
                                        deviceControlSupported = true,
                                        factoryResetAwaitingDisconnect = false,
                                        deviceControlStatus = status,
                                        deviceControlError = ""
                                    )
                                }
                                throw RegattaLinkOtaTransportException(
                                    regattaLinkDeviceControlFailureText(status)
                                        .ifBlank {
                                            "RegattaLink Device Control failed"
                                        },
                                    ambiguous = false
                                )
                            }
                        }
                    }

                    Thread.sleep(REGATTALINK_DEVICE_CONTROL_POLL_MS)
                }

                if (
                    factoryResetFinalizationDeadline != null &&
                    finalStatus?.factoryResetBondsCleared != true &&
                    gatt === activeGatt &&
                    connected
                ) {
                    requestFactoryResetLocalDisconnect(activeGatt)
                    throw RegattaLinkOtaTransportException(
                        "Factory reset finalization timed out before bond-wipe completion was confirmed; disconnecting without assuming bond-wipe success",
                        ambiguous = true
                    )
                }

                if (finalStatus?.phase?.isTerminal != true) {
                    throw RegattaLinkOtaTransportException(
                        "RegattaLink Device Control response timed out",
                        ambiguous = true
                    )
                }
            } catch (error: Exception) {
                if (
                    opcode == RegattaLinkDeviceControlOpcode.FACTORY_RESET &&
                    factoryResetWriteAccepted &&
                    finalStatus == null &&
                    gatt === activeGatt &&
                    connected
                ) {
                    requestFactoryResetLocalDisconnect(activeGatt)
                }

                val expectedFactoryResetDisconnect =
                    opcode == RegattaLinkDeviceControlOpcode.FACTORY_RESET &&
                        finalStatus?.let(::regattaLinkFactoryResetContinuesToBondReset) == true &&
                        (gatt !== activeGatt || !connected)
                errorMessage =
                    if (expectedFactoryResetDisconnect) {
                        ""
                    } else {
                        error.message ?: "RegattaLink Device Control failed"
                    }
            }

            if (
                opcode == RegattaLinkDeviceControlOpcode.FACTORY_RESET &&
                finalStatus?.phase?.isTerminal == true &&
                !regattaLinkFactoryResetContinuesToBondReset(finalStatus)
            ) {
                factoryResetDisconnectTracker.clear(
                    session = activeGatt,
                    requestId = requestId
                )
                if (factoryResetWriteAccepted) {
                    onFactoryResetRecoveryStateChanged(false)
                }
            }

            if (
                deviceControlExecutionGuard.owns(execution) &&
                gatt === activeGatt &&
                connected
            ) {
                updateConfiguration { current ->
                    if (!deviceControlExecutionGuard.owns(execution)) {
                        current
                    } else {
                        current.copy(
                        deviceControlSupported = true,
                        deviceControlBusy = false,
                        deviceControlAcceptedOpcode = null,
                        deviceControlAcceptedRequestId = null,
                        factoryResetWriteAcceptedRequestId =
                            if (
                                opcode == RegattaLinkDeviceControlOpcode.FACTORY_RESET &&
                                factoryResetWriteAccepted &&
                                (
                                    finalStatus == null ||
                                        finalStatus?.let(
                                            ::regattaLinkFactoryResetContinuesToBondReset
                                        ) == true
                                    )
                            ) {
                                requestId
                            } else {
                                null
                            },
                        factoryResetAwaitingDisconnect =
                            opcode == RegattaLinkDeviceControlOpcode.FACTORY_RESET &&
                                factoryResetWriteAccepted &&
                                (
                                    finalStatus == null ||
                                        finalStatus?.let(
                                            ::regattaLinkFactoryResetContinuesToBondReset
                                        ) == true
                                    ),
                        restartAwaitingDisconnect =
                            opcode == RegattaLinkDeviceControlOpcode.RESTART &&
                                finalStatus?.phase == RegattaLinkDeviceControlPhase.SUCCESS &&
                                finalStatus?.result == RegattaLinkDeviceControlResult.OK,
                            deviceControlStatus = finalStatus,
                            deviceControlError = errorMessage
                        )
                    }
                }
            }

            } finally {
                deviceControlExecutionGuard.release(execution)
            }
        }
        return true
    }

    private fun rejectDeviceControlBeforeStart(
        execution: RegattaLinkDeviceControlExecutionGuard.Lease<BluetoothGatt>
    ) {
        val activeGatt = execution.session
        if (
            deviceControlExecutionGuard.owns(execution) &&
            gatt === activeGatt
        ) {
            updateConfiguration { current ->
                if (!deviceControlExecutionGuard.owns(execution)) {
                    current
                } else {
                    regattaLinkDeviceControlRejectedBeforeStartState(
                        state = current,
                        errorMessage =
                            "RegattaLink Device Control could not start because the connection is no longer ready"
                    )
                }
            }
        }
        deviceControlExecutionGuard.release(execution)
    }

    private fun nextDeviceControlRequestId(): UInt {
        val value = nextDeviceControlRequestId
        nextDeviceControlRequestId =
            if (value == UInt.MAX_VALUE) 1u else value + 1u
        return value
    }

    override fun refreshPgnInventory(): Boolean {
        if (otaRunning.get() || !isConnected()) return false
        val activeGatt = gatt ?: return false

        otaExecutor.execute {
            if (!optionalFeatureWorkAllowed(activeGatt)) return@execute
            updateNmea {
                it.copy(
                    pgnInventoryLoading = true,
                    userMessage = null,
                    error = ""
                )
            }
            try {
                val characteristic = activeGatt
                    .getService(CONFIG_SERVICE_UUID)
                    ?.getCharacteristic(NMEA_PGN_INVENTORY_UUID)
                    ?: throw RegattaLinkOtaTransportException(
                        "RegattaLink PGN inventory is unavailable",
                        ambiguous = false
                    )
                val inventory = parseRegattaLinkPgnInventory(
                    readCharacteristicBlocking(activeGatt, characteristic)
                )
                updateNmea {
                    it.copy(
                        pgnInventorySupported = true,
                        pgnInventoryLoading = false,
                        pgnInventory = inventory,
                        userMessage = null,
                        error = ""
                    )
                }
            } catch (error: Exception) {
                updateNmea {
                    it.copy(
                        pgnInventoryLoading = false,
                        userMessage = RegattaLinkUiMessage.NMEA_PGN_INVENTORY_READ_FAILED,
                        error = error.message ?: "Could not read RegattaLink PGN inventory"
                    )
                }
            }
        }
        return true
    }

    override fun readRawCanFrames(): Boolean {
        if (otaRunning.get() || rawCaptureRunning.get() || !isConnected()) return false
        val activeGatt = gatt ?: return false

        otaExecutor.execute {
            if (!optionalFeatureWorkAllowed(activeGatt)) return@execute
            updateNmea {
                it.copy(
                    rawCanReading = true,
                    rawFrames = emptyList(),
                    userMessage = null,
                    error = ""
                )
            }

            val frames = mutableListOf<RegattaLinkRawCanFrame>()
            var errorMessage = ""
            try {
                val characteristic = activeGatt
                    .getService(CONFIG_SERVICE_UUID)
                    ?.getCharacteristic(NMEA_RAW_CAN_UUID)
                    ?: throw RegattaLinkOtaTransportException(
                        "RegattaLink raw CAN diagnostics are unavailable",
                        ambiguous = false
                    )

                for (readIndex in 0 until REGATTALINK_MAX_RAW_CAN_READS) {
                    if (
                        !optionalFeatureWorkAllowed(activeGatt) ||
                        serviceRediscoveryRequested.get()
                    ) {
                        break
                    }

                    val result = parseRegattaLinkRawCanRead(
                        readCharacteristicBlocking(activeGatt, characteristic)
                    )
                    result.frame?.let(frames::add)

                    if (result.frame == null || result.remainingCount == 0) {
                        break
                    }
                }
            } catch (error: Exception) {
                errorMessage = error.message ?: "Could not read RegattaLink raw CAN frames"
            }

            if (gatt === activeGatt && connected) {
                updateNmea {
                    it.copy(
                        rawCanSupported = true,
                        rawCanReading = false,
                        rawFrames = frames,
                        userMessage =
                            RegattaLinkUiMessage.NMEA_RAW_CAN_READ_FAILED
                                .takeIf { errorMessage.isNotBlank() },
                        error = errorMessage
                    )
                }
            }
        }
        return true
    }

    override fun startRawCanCapture(
        onRecordingStarted: () -> Unit,
        onFrame: (RegattaLinkRawCanFrame) -> Unit,
        onFinished: (RegattaLinkRawCaptureEndReason, String) -> Unit
    ): Boolean {
        if (
            otaRunning.get() ||
            diagnosticLogRunning.get() ||
            deviceControlExecutionGuard.isActive() ||
            !isConnected() ||
            !rawCaptureRunning.compareAndSet(false, true)
        ) {
            return false
        }
        val activeGatt = gatt
        if (activeGatt == null) {
            rawCaptureRunning.set(false)
            return false
        }
        rawCaptureStopReason.set(null)

        otaExecutor.execute {
            var endReason = RegattaLinkRawCaptureEndReason.TIMEOUT
            var errorMessage = ""
            val deadline =
                SystemClock.elapsedRealtime() + REGATTALINK_RAW_CAPTURE_DURATION_MS

            try {
                if (!optionalFeatureWorkAllowed(activeGatt)) {
                    throw RegattaLinkOtaTransportException(
                        "RegattaLink raw CAN diagnostics are unavailable",
                        ambiguous = false
                    )
                }
                val characteristic = activeGatt
                    .getService(CONFIG_SERVICE_UUID)
                    ?.getCharacteristic(NMEA_RAW_CAN_UUID)
                    ?: throw RegattaLinkOtaTransportException(
                        "RegattaLink raw CAN diagnostics are unavailable",
                        ambiguous = false
                    )

                var flushReads = 0
                while (
                    rawCaptureRunning.get() &&
                    gatt === activeGatt &&
                    connected &&
                    SystemClock.elapsedRealtime() < deadline &&
                    flushReads < REGATTALINK_RAW_CAPTURE_FLUSH_READ_LIMIT
                ) {
                    if (serviceRediscoveryRequested.get()) {
                        throw RegattaLinkOtaTransportException(
                            "RegattaLink services changed during raw CAN capture",
                            ambiguous = false
                        )
                    }
                    val result = parseRegattaLinkRawCanRead(
                        readCharacteristicBlocking(activeGatt, characteristic)
                    )
                    flushReads += 1
                    if (!shouldContinueRawCaptureFlush(flushReads, result)) {
                        break
                    }
                }

                if (
                    rawCaptureRunning.get() &&
                    gatt === activeGatt &&
                    connected &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    onRecordingStarted()

                    while (
                        rawCaptureRunning.get() &&
                        gatt === activeGatt &&
                        connected &&
                        SystemClock.elapsedRealtime() < deadline
                    ) {
                        if (serviceRediscoveryRequested.get()) {
                            throw RegattaLinkOtaTransportException(
                                "RegattaLink services changed during raw CAN capture",
                                ambiguous = false
                            )
                        }

                        val result = parseRegattaLinkRawCanRead(
                            readCharacteristicBlocking(activeGatt, characteristic)
                        )
                        result.frame?.let(onFrame)

                        val delayMs = rawCapturePollDelayMs(result)
                        if (delayMs > 0L) {
                            val remaining =
                                (deadline - SystemClock.elapsedRealtime())
                                    .coerceAtLeast(0L)
                            if (remaining > 0L) {
                                Thread.sleep(minOf(delayMs, remaining))
                            }
                        }
                    }
                }

                if (gatt !== activeGatt || !connected) {
                    endReason = RegattaLinkRawCaptureEndReason.INTERRUPTED
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                endReason = RegattaLinkRawCaptureEndReason.INTERRUPTED
            } catch (error: Exception) {
                val requested = rawCaptureStopReason.get()
                if (
                    requested == RegattaLinkRawCaptureStopReason.INTERRUPTED ||
                    gatt !== activeGatt ||
                    !connected
                ) {
                    endReason = RegattaLinkRawCaptureEndReason.INTERRUPTED
                } else {
                    endReason = RegattaLinkRawCaptureEndReason.ERROR
                    errorMessage =
                        error.message ?: "Raw CAN capture failed"
                }
            } finally {
                when (rawCaptureStopReason.getAndSet(null)) {
                    RegattaLinkRawCaptureStopReason.USER ->
                        endReason = RegattaLinkRawCaptureEndReason.USER_STOP
                    RegattaLinkRawCaptureStopReason.INTERRUPTED ->
                        endReason = RegattaLinkRawCaptureEndReason.INTERRUPTED
                    null -> Unit
                }
                rawCaptureRunning.set(false)
                runCatching { onFinished(endReason, errorMessage) }
            }
        }
        return true
    }

    override fun stopRawCanCapture(reason: RegattaLinkRawCaptureStopReason) {
        if (!rawCaptureRunning.get()) return
        rawCaptureStopReason.compareAndSet(null, reason)
        rawCaptureRunning.set(false)
    }

    override fun isConnected(): Boolean = connected && gatt != null

    override fun tuneConnection(info: RegattaLinkDeviceInfo) {
        val activeGatt = requireGatt()
        requestConnectionMtuBestEffort(activeGatt)

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
        timeoutMs: Long,
        allowOtaOnly: Boolean
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
        otaReconnectAllowOtaOnly = allowOtaOnly
        otaReconnectExpectedStableId = expectedStableId
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
            otaReconnectAllowOtaOnly = false
            otaReconnectExpectedStableId = null
            handler.removeCallbacks(scanTimeout)
            scanPurpose = ScanPurpose.NORMAL
        }
    }

    private fun requestConnectionMtuBestEffort(activeGatt: BluetoothGatt) {
        if (gatt !== activeGatt || !connected) return
        if (!mtuRequestAttempted.compareAndSet(false, true)) return

        if (mtu >= REQUESTED_GATT_MTU) {
            publishPhoneGnssTransportReadinessIfChanged()
            return
        }

        val future = CompletableFuture<Int>()
        val pending = PendingGattOperation.Mtu(future)
        if (!setPendingGattOperation(pending)) {
            Log.w(
                LOG_TAG,
                "Skipping connection MTU request because another GATT operation is active"
            )
            return
        }

        val initiated = runCatching {
            activeGatt.requestMtu(REQUESTED_GATT_MTU)
        }.onFailure { error ->
            Log.w(LOG_TAG, "Connection MTU request failed locally", error)
        }.getOrDefault(false)

        if (!initiated) {
            clearPendingGattOperation(pending)
            Log.w(LOG_TAG, "Connection MTU request was rejected locally")
            return
        }

        try {
            future.get(GATT_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            clearPendingGattOperation(pending)
            Log.w(LOG_TAG, "Connection MTU negotiation did not complete", error)
        }
    }

    private fun writeCharacteristicBlockingDirect(
        activeGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        onSubmitted: (() -> Unit)? = null
    ) {
        var attempts = 0
        while (true) {
            attempts += 1
            val future = CompletableFuture<Unit>()
            val pending =
                PendingGattOperation.CharacteristicWrite(characteristic.uuid, future)
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
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
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

            /*
             * From this point on, a missing/error callback is ambiguous: the
             * peripheral may already have processed the ATT Write Request.
             * Destructive commands such as Factory Reset must claim recovery
             * ownership before waiting for the callback.
             */
            afterRegattaLinkGattSubmissionAccepted(
                onSubmitted = onSubmitted
            ) {
                awaitUnitFuture(
                    future,
                    "GATT write " + characteristic.uuid
                )
            }
            return
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
                    message = "GATT write $uuid failed ($status)",
                    ambiguous = true,
                    gattStatus = status
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
                    ambiguous = true,
                    gattStatus = status
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
                    ambiguous = true,
                    gattStatus = status
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

    private fun resetConnectionTransportState() {
        /*
         * Do not reset phoneGnssDrainScheduled here. A previously posted or
         * in-flight drain owns that flag and clears it in its finally block.
         * Clearing it from the disconnect path could schedule two drains after
         * a fast reconnect. Dropping the pending sample is sufficient.
         */
        phoneGnssPending.set(null)
        mtu = 23
        mtuRequestAttempted.set(false)
    }

    private fun publishPhoneGnssTransportReadinessIfChanged() {
        val ready =
            connected &&
                establishedConnection &&
                regattaLinkPhoneGnssTransportReady(mtu)
        val current = lastState
        if (
            current.status != RegattaLinkConnectionStatus.CONNECTED ||
            current.phoneGnssTransportReady == ready
        ) {
            return
        }
        emit(current.copy(phoneGnssTransportReady = ready))
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
        resetConnectionTransportState()
        handler.removeCallbacks(gattTimeout)
        handler.removeCallbacks(gattSchemaReconcileTimeout)
        clearGattSchemaReconnectState()
        gattSchemaReconciliationPending = false
        pendingGattSchemaVersion = 0
        pendingGattSchemaInfo = null
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
    }

    private fun closeGattWithError(
        callbackGatt: BluetoothGatt,
        message: String,
        gattStatus: Int? = null
    ) {
        val wasReadyConnection = establishedConnection
        handler.removeCallbacks(gattTimeout)
        resetServiceDiscoveryState()
        connected = false
        establishedConnection = false
        resetConnectionTransportState()
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
            clearConfiguration()
            clearNmea()
        }

        if (scanPurpose == ScanPurpose.OTA_RECONNECT) {
            reconnectFuture?.complete(null)
        } else if (scanPurpose == ScanPurpose.KNOWN_DEVICE_RECONNECT) {
            retryKnownDeviceReconnect(
                if (
                    gattStatus?.let(
                        ::isRegattaLinkStaleBondSecurityGattStatus
                    ) == true
                ) {
                    REGATTALINK_STALE_ANDROID_BOND_ERROR
                } else {
                    message
                }
            )
        } else if (scanPurpose == ScanPurpose.KNOWN_DEVICE_AUTOCONNECT) {
            finishKnownDeviceAutoConnectError(
                message =
                    if (
                        gattStatus?.let(
                            ::isRegattaLinkStaleBondSecurityGattStatus
                        ) == true
                    ) {
                        REGATTALINK_STALE_ANDROID_BOND_ERROR
                    } else {
                        message
                    },
                userMessage =
                    if (
                        gattStatus?.let(
                            ::isRegattaLinkStaleBondSecurityGattStatus
                        ) == true
                    ) {
                        RegattaLinkUiMessage.PAIRING_REQUIRED
                    } else {
                        RegattaLinkUiMessage.CONNECTION_FAILED
                    }
            )
        } else if (!otaRunning.get()) {
            if (discoveryInProgress) {
                retryDiscoveryAfterCandidateFailure(
                    staleBondSecurityFailure =
                        gattStatus?.let(
                            ::isRegattaLinkStaleBondSecurityGattStatus
                        ) == true
                )
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
        message: String,
        userMessage: RegattaLinkUiMessage = RegattaLinkUiMessage.CONNECTION_FAILED
    ) {
        emit(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.ERROR,
                deviceName = deviceName(device),
                deviceAddress = device.address,
                userMessage = userMessage,
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

    private fun updateConfiguration(
        transform: (RegattaLinkConfigurationState) -> RegattaLinkConfigurationState
    ) {
        val next = synchronized(configurationLock) {
            transform(lastConfigurationState).also {
                lastConfigurationState = it
            }
        }
        handler.post {
            onConfigurationStateChanged(next)
        }
    }

    private fun emitConfiguration(state: RegattaLinkConfigurationState) {
        if (!state.phoneGnssForwardingDesired) {
            clearPhoneGnssPending()
        }
        synchronized(configurationLock) {
            lastConfigurationState = state
        }
        handler.post {
            onConfigurationStateChanged(state)
        }
    }

    private fun clearConfiguration() {
        emitConfiguration(RegattaLinkConfigurationState())
    }

    private fun updateNmea(
        transform: (RegattaLinkNmeaState) -> RegattaLinkNmeaState
    ) {
        val next = synchronized(nmeaLock) {
            transform(lastNmeaState).also {
                lastNmeaState = it
                RegattaLinkNmeaSnapshotStore.update(it)
            }
        }
        handler.post {
            onNmeaStateChanged(next)
        }
    }

    private fun emitNmea(state: RegattaLinkNmeaState) {
        synchronized(nmeaLock) {
            lastNmeaState = state
            RegattaLinkNmeaSnapshotStore.update(state)
        }
        handler.post {
            onNmeaStateChanged(state)
        }
    }

    private fun clearNmea() {
        handler.removeCallbacks(loadTelemetryStaleRunnable)
        loadPacketAssembler.reset()
        RegattaLinkLoadSnapshotStore.clear()
        emitNmea(RegattaLinkNmeaState())
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
        clearConfiguration()
        clearNmea()
        resetConnectionTransportState()
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
        if (lastNmeaState.pausedForOta != paused) {
            updateNmea {
                if (paused) {
                    handler.removeCallbacks(loadTelemetryStaleRunnable)
                    loadPacketAssembler.reset()
                    RegattaLinkLoadSnapshotStore.clear()
                    it.copy(
                        boatState = null,
                        boatStateReceivedAtElapsedMs = null,
                        loadSensors = emptyList(),
                        loadReceivedAtElapsedMs = null,
                        pausedForOta = true
                    )
                } else {
                    it.copy(pausedForOta = false)
                }
            }
        }
        handler.post {
            onOtaStateChanged(state)
        }
    }
}
