package de.williserv.regattaclient

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
import java.util.UUID

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
class RegattaLinkBleClient(
    context: Context,
    private val onStateChanged: (RegattaLinkClientState) -> Unit
) {
    companion object {
        val CONFIG_SERVICE_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b710001")
        val DEVICE_INFO_UUID: UUID =
            UUID.fromString("7f2c4b10-6f63-4a8d-9a3e-2e5d6b710003")

        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val BOND_TIMEOUT_MS = 30_000L
        private const val GATT_TIMEOUT_MS = 20_000L
        private const val BOND_POLL_MS = 250L

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

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val handler = Handler(Looper.getMainLooper())

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var currentDevice: BluetoothDevice? = null
    private var userDisconnect = false
    private var bondDeadline = 0L

    private val scanTimeout = Runnable {
        stopScan()
        emit(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.ERROR,
                error = "No RegattaLink found"
            )
        )
    }

    private val gattTimeout = Runnable {
        val device = currentDevice
        closeGatt()
        if (device != null) {
            emitError(device, "RegattaLink connection timed out")
        }
    }

    private val bondPoll = object : Runnable {
        override fun run() {
            val device = currentDevice ?: return
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> connectGatt(device)
                else -> {
                    if (SystemClock.elapsedRealtime() >= bondDeadline) {
                        emitError(device, "RegattaLink pairing timed out")
                    } else {
                        handler.postDelayed(this, BOND_POLL_MS)
                    }
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan()
            prepareDevice(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            handler.removeCallbacks(scanTimeout)
            emit(
                RegattaLinkClientState(
                    status = RegattaLinkConnectionStatus.ERROR,
                    error = "Bluetooth scan failed ($errorCode)"
                )
            )
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

            if (status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                emitForDevice(
                    callbackGatt.device,
                    RegattaLinkConnectionStatus.DISCOVERING
                )
                if (!callbackGatt.discoverServices()) {
                    closeGattWithError(callbackGatt, "Could not discover RegattaLink services")
                }
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                callbackGatt.close()
                if (gatt === callbackGatt) {
                    gatt = null
                }
                if (userDisconnect) {
                    userDisconnect = false
                    emit(RegattaLinkClientState())
                } else {
                    emitError(callbackGatt.device, "RegattaLink disconnected ($status)")
                }
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGattWithError(callbackGatt, "RegattaLink service discovery failed ($status)")
                return
            }

            val service: BluetoothGattService? =
                callbackGatt.getService(CONFIG_SERVICE_UUID)
            val characteristic: BluetoothGattCharacteristic? =
                service?.getCharacteristic(DEVICE_INFO_UUID)

            if (characteristic == null) {
                closeGattWithError(callbackGatt, "RegattaLink Device Info is unavailable")
                return
            }

            emitForDevice(
                callbackGatt.device,
                RegattaLinkConnectionStatus.READING_DEVICE_INFO
            )
            if (!callbackGatt.readCharacteristic(characteristic)) {
                closeGattWithError(callbackGatt, "Could not read RegattaLink Device Info")
            }
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicRead(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
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
            handleCharacteristicRead(callbackGatt, characteristic.uuid, value, status)
        }
    }

    fun startDiscovery() {
        stopScan()
        handler.removeCallbacks(bondPoll)
        closeGatt()

        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            emit(
                RegattaLinkClientState(
                    status = RegattaLinkConnectionStatus.ERROR,
                    error = "Bluetooth is disabled"
                )
            )
            return
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
            return
        }

        emit(RegattaLinkClientState(status = RegattaLinkConnectionStatus.SCANNING))
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(CONFIG_SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        activeScanner.startScan(filters, settings, scanCallback)
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    fun disconnect() {
        stopScan()
        handler.removeCallbacks(bondPoll)
        handler.removeCallbacks(gattTimeout)
        if (gatt == null) {
            emit(RegattaLinkClientState())
            return
        }
        userDisconnect = true
        gatt?.disconnect()
    }

    fun close() {
        stopScan()
        handler.removeCallbacks(bondPoll)
        handler.removeCallbacks(gattTimeout)
        userDisconnect = true
        closeGatt()
        currentDevice = null
    }

    private fun prepareDevice(device: BluetoothDevice) {
        currentDevice = device
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            connectGatt(device)
            return
        }

        emitForDevice(device, RegattaLinkConnectionStatus.BONDING)
        bondDeadline = SystemClock.elapsedRealtime() + BOND_TIMEOUT_MS
        if (!device.createBond()) {
            emitError(device, "Could not start RegattaLink pairing")
            return
        }
        handler.post(bondPoll)
    }

    private fun connectGatt(device: BluetoothDevice) {
        handler.removeCallbacks(bondPoll)
        closeGatt()
        currentDevice = device
        userDisconnect = false
        emitForDevice(device, RegattaLinkConnectionStatus.CONNECTING)
        gatt = device.connectGatt(
            appContext,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
        if (gatt == null) {
            emitError(device, "Could not open RegattaLink connection")
        } else {
            handler.postDelayed(gattTimeout, GATT_TIMEOUT_MS)
        }
    }

    private fun handleCharacteristicRead(
        callbackGatt: BluetoothGatt,
        characteristicUuid: UUID,
        value: ByteArray,
        status: Int
    ) {
        if (characteristicUuid != DEVICE_INFO_UUID) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            closeGattWithError(callbackGatt, "RegattaLink Device Info read failed ($status)")
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
        emit(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                deviceName = deviceName(device),
                deviceAddress = device.address,
                deviceInfo = info
            )
        )
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        runCatching { scanner?.stopScan(scanCallback) }
    }

    private fun closeGatt() {
        val existing = gatt
        gatt = null
        if (existing != null) {
            runCatching { existing.disconnect() }
            existing.close()
        }
    }

    private fun closeGattWithError(callbackGatt: BluetoothGatt, message: String) {
        handler.removeCallbacks(gattTimeout)
        callbackGatt.disconnect()
        callbackGatt.close()
        if (gatt === callbackGatt) {
            gatt = null
        }
        emitError(callbackGatt.device, message)
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

    private fun emitError(device: BluetoothDevice, message: String) {
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
        handler.post {
            onStateChanged(state)
        }
    }
}
