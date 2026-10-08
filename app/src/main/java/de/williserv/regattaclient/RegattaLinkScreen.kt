package de.williserv.regattaclient

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

internal fun shouldAutoRefreshPgnInventory(
    detailsExpanded: Boolean,
    inventorySupported: Boolean,
    inventoryEmpty: Boolean,
    inventoryLoading: Boolean,
    otaActive: Boolean,
    alreadyRequested: Boolean
): Boolean =
    detailsExpanded &&
        inventorySupported &&
        inventoryEmpty &&
        !inventoryLoading &&
        !otaActive &&
        !alreadyRequested

internal data class RegattaLinkConfigSliderSubmission(
    val requestedValue: Int,
    val confirmedDraft: Float
)

internal fun shouldRefreshCalypsoStatusOnOpen(
    bluetoothDevicesOpen: Boolean,
    connected: Boolean,
    deviceControlSupported: Boolean,
    deviceControlEnabled: Boolean,
    alreadyRequested: Boolean
): Boolean =
    bluetoothDevicesOpen &&
        connected &&
        deviceControlSupported &&
        deviceControlEnabled &&
        !alreadyRequested

@Composable
private fun regattaLinkRuntimeMessageText(
    userMessage: RegattaLinkUiMessage?,
    hasTechnicalError: Boolean,
    fallback: RegattaLinkUiMessage
): String? =
    (userMessage ?: fallback.takeIf { hasTechnicalError })?.let { message ->
        stringResource(regattaLinkUiMessageResource(message))
    }

@Composable
private fun RegattaLinkTechnicalDetail(
    detail: String,
    modifier: Modifier = Modifier
) {
    if (detail.isBlank()) return
    Text(
        text = stringResource(
            R.string.regattalink_technical_detail_value,
            detail
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        modifier = modifier
    )
}

internal fun prepareRegattaLinkConfigSliderSubmission(
    draftValue: Float,
    confirmedValue: Int?,
    validRange: IntRange
): RegattaLinkConfigSliderSubmission {
    val requestedValue = draftValue.roundToInt().coerceIn(validRange)
    val confirmedDraft = (confirmedValue ?: requestedValue)
        .coerceIn(validRange)
        .toFloat()
    return RegattaLinkConfigSliderSubmission(
        requestedValue = requestedValue,
        confirmedDraft = confirmedDraft
    )
}

internal enum class RegattaLinkSetupDestination {
    IMU,
    NMEA,
    BLUETOOTH_DEVICES,
    ADVANCED_DIAGNOSTICS,
    FIRMWARE
}

internal data class RegattaLinkSetupMenuItem(
    val destination: RegattaLinkSetupDestination,
    val labelResId: Int
)

internal val regattaLinkSetupMenuItems = listOf(
    RegattaLinkSetupMenuItem(
        RegattaLinkSetupDestination.IMU,
        R.string.regattalink_setup_imu
    ),
    RegattaLinkSetupMenuItem(
        RegattaLinkSetupDestination.NMEA,
        R.string.regattalink_setup_nmea
    ),
    RegattaLinkSetupMenuItem(
        RegattaLinkSetupDestination.BLUETOOTH_DEVICES,
        R.string.regattalink_bluetooth_devices
    ),
    RegattaLinkSetupMenuItem(
        RegattaLinkSetupDestination.ADVANCED_DIAGNOSTICS,
        R.string.regattalink_advanced_diagnostics
    ),
    RegattaLinkSetupMenuItem(
        RegattaLinkSetupDestination.FIRMWARE,
        R.string.regattalink_firmware_title
    )
)

@Composable
internal fun RegattaLinkScreen(
    state: RegattaLinkClientState,
    deviceSelectionState: RegattaLinkDeviceSelectionState =
        RegattaLinkDeviceSelectionState(),
    firmwareState: RegattaLinkFirmwareUiState,
    otaState: RegattaLinkOtaUiState,
    telemetryState: RegattaLinkTelemetryState,
    configurationState: RegattaLinkConfigurationState,
    nmeaState: RegattaLinkNmeaState,
    rawCaptureState: RegattaLinkRawCaptureState,
    installAvailable: Boolean,
    phoneGpsRelayEnabled: Boolean = false,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit,
    onSelectKnownDevice: (String) -> Unit = {},
    onConnectDiscoveredDevice: (String) -> Unit = {},
    onEnableBluetooth: () -> Unit = {},
    onCheckFirmware: () -> Unit,
    onFirmwareSourceSelected: (RegattaLinkFirmwareSource) -> Unit,
    onInstallFirmware: () -> Unit,
    onCancelOta: () -> Unit,
    onChangeName: (String) -> Unit,
    onSetLedBrightness: (Int) -> Unit,
    onSetMotionDamping: (Int) -> Unit,
    onSetLoadPrecisionX10: (Boolean) -> Unit = {},
    onApplyTxConfigAndRestart: (UInt) -> Unit = {},
    onApplyBluetoothConfigAndRestart: (UInt) -> Unit = {},
    onSetPhoneGpsRelayEnabled: (Boolean) -> Unit = {},
    onSetNmea0183Baud: (Int) -> Unit = {},
    onSetMagBackgroundLearningEnabled: (Boolean) -> Unit = {},
    onApplySubsystemConfigAndRestart: (UInt) -> Unit = {},
    onSetHeadingTrimDeg: (Int) -> Unit = {},
    onSetLoadSensorAlias: (String, String) -> Unit = { _, _ -> },
    onDrainDiagnosticLog: () -> Unit,
    onDeviceControl: (RegattaLinkDeviceControlOpcode, Int) -> Unit,
    onSetImuRawPreviewEnabled: (Boolean) -> Unit = {},
    onRefreshPgnInventory: () -> Unit,
    onReadRawFrames: () -> Unit,
    onStartRawCapture: () -> Unit,
    onStopRawCapture: () -> Unit,
    onExportRawCapture: () -> Unit,
    onDiscardRawCapture: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit
) {
    val busy = state.status in setOf(
        RegattaLinkConnectionStatus.SCANNING,
        RegattaLinkConnectionStatus.BONDING,
        RegattaLinkConnectionStatus.CONNECTING,
        RegattaLinkConnectionStatus.DISCOVERING,
        RegattaLinkConnectionStatus.READING_DEVICE_INFO
    )
    val statusText = when (state.status) {
        RegattaLinkConnectionStatus.IDLE -> stringResource(R.string.regattalink_status_not_connected)
        RegattaLinkConnectionStatus.WAITING -> stringResource(R.string.regattalink_status_waiting)
        RegattaLinkConnectionStatus.SCANNING -> stringResource(R.string.regattalink_status_scanning)
        RegattaLinkConnectionStatus.BONDING -> stringResource(R.string.regattalink_status_pairing)
        RegattaLinkConnectionStatus.CONNECTING -> stringResource(R.string.regattalink_status_connecting)
        RegattaLinkConnectionStatus.DISCOVERING -> stringResource(R.string.regattalink_status_discovering)
        RegattaLinkConnectionStatus.READING_DEVICE_INFO ->
            stringResource(R.string.regattalink_status_reading_device)
        RegattaLinkConnectionStatus.CONNECTED -> stringResource(R.string.regattalink_status_connected)
        RegattaLinkConnectionStatus.BLUETOOTH_OFF ->
            stringResource(R.string.regattalink_status_bluetooth_off)
        RegattaLinkConnectionStatus.ERROR -> stringResource(R.string.regattalink_status_error)
    }

    var settingsMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var activeSetupDestination by rememberSaveable {
        mutableStateOf<RegattaLinkSetupDestination?>(null)
    }
    var pgnInventoryAutoRefreshRequested by remember(state.deviceAddress) {
        mutableStateOf(false)
    }
    var nameDialogOpen by rememberSaveable { mutableStateOf(false) }
    var resetDialogOpen by rememberSaveable { mutableStateOf(false) }
    var nameDraft by rememberSaveable { mutableStateOf("") }

    val connected = state.status == RegattaLinkConnectionStatus.CONNECTED
    val deviceKey = state.deviceInfo?.stableId ?: state.deviceAddress
    var calypsoStatusRequestedForOpen by remember(
        deviceKey,
        activeSetupDestination,
        connected
    ) {
        mutableStateOf(false)
    }
    val connectedStableId = state.deviceInfo?.stableId.takeIf { connected }
    val firmwareSetupOpen =
        activeSetupDestination == RegattaLinkSetupDestination.FIRMWARE
    val displayedName = configurationState.deviceName.ifBlank { state.deviceName }
    val configEnabled =
        connected &&
            !otaState.isActive &&
            !rawCaptureState.isActive &&
            !configurationState.busy &&
            !regattaLinkConfigurationMutationBlocked(configurationState)
    val deviceControlBaseEnabled =
        configEnabled &&
            !configurationState.diagnosticLogLoading &&
            !nmeaState.rawCanReading
    val deviceControlEnabled =
        deviceControlBaseEnabled &&
            !configurationState.deviceControlBusy
    val controlStatus = configurationState.deviceControlStatus
    val boatFramePresentation =
        regattaLinkBoatFramePresentation(
            status = controlStatus,
            deviceControlBusy = configurationState.deviceControlBusy
        )
    val boatFrameValid =
        boatFramePresentation == RegattaLinkBoatFramePresentation.READY
    val orientationControlsEnabled =
        regattaLinkOrientationControlsEnabled(
            baseControlsEnabled = deviceControlBaseEnabled,
            boatFrameValid = boatFrameValid,
            deviceControlBusy = configurationState.deviceControlBusy
        )
    val nameValidationError =
        if (nameDraft.isBlank()) {
            RegattaLinkDeviceNameValidationError.EMPTY
        } else {
            validateRegattaLinkDeviceName(nameDraft)
        }
    val nameValidationErrorText = when (nameValidationError) {
        RegattaLinkDeviceNameValidationError.EMPTY ->
            stringResource(R.string.regattalink_name_validation_empty)
        RegattaLinkDeviceNameValidationError.TOO_LONG_UTF8 ->
            stringResource(R.string.regattalink_name_validation_too_long_utf8)
        RegattaLinkDeviceNameValidationError.UNSUPPORTED_CONTROL_CHARACTER ->
            stringResource(
                R.string.regattalink_name_validation_unsupported_control_character
            )
        null -> null
    }

    val nmeaSetupOpen =
        activeSetupDestination == RegattaLinkSetupDestination.NMEA &&
            connected
    val bluetoothDevicesOpen =
        activeSetupDestination ==
            RegattaLinkSetupDestination.BLUETOOTH_DEVICES

    LaunchedEffect(
        bluetoothDevicesOpen,
        connected,
        configurationState.deviceControlSupported,
        deviceControlEnabled,
        deviceKey
    ) {
        if (
            shouldRefreshCalypsoStatusOnOpen(
                bluetoothDevicesOpen = bluetoothDevicesOpen,
                connected = connected,
                deviceControlSupported =
                    configurationState.deviceControlSupported,
                deviceControlEnabled = deviceControlEnabled,
                alreadyRequested = calypsoStatusRequestedForOpen
            )
        ) {
            calypsoStatusRequestedForOpen = true
            onDeviceControl(
                RegattaLinkDeviceControlOpcode.CALYPSO_STATUS,
                0
            )
        }
    }

    LaunchedEffect(
        firmwareSetupOpen,
        connected,
        state.deviceAddress
    ) {
        if (firmwareSetupOpen && connected) {
            onCheckFirmware()
        }
    }

    LaunchedEffect(
        nmeaSetupOpen,
        nmeaState.pgnInventorySupported,
        nmeaState.pgnInventoryLoading,
        configurationState.canSessionAvailable,
        otaState.isActive,
        state.deviceAddress
    ) {
        if (
            !nmeaSetupOpen ||
            configurationState.canSessionAvailable == false
        ) {
            pgnInventoryAutoRefreshRequested = false
            return@LaunchedEffect
        }

        if (
            shouldAutoRefreshPgnInventory(
                detailsExpanded = nmeaSetupOpen,
                inventorySupported = nmeaState.pgnInventorySupported,
                inventoryEmpty = nmeaState.pgnInventory.isEmpty(),
                inventoryLoading = nmeaState.pgnInventoryLoading,
                otaActive = otaState.isActive,
                alreadyRequested = pgnInventoryAutoRefreshRequested
            )
        ) {
            pgnInventoryAutoRefreshRequested = true
            onRefreshPgnInventory()
        }
    }

    when (activeSetupDestination) {
        RegattaLinkSetupDestination.IMU -> {
            RegattaLinkImuSetupSheet(
                controlStatus = controlStatus,
                telemetryState = telemetryState,
                configurationState = configurationState,
                boatFramePresentation = boatFramePresentation,
                controlsEnabled = orientationControlsEnabled,
                setUprightEnabled = deviceControlEnabled,
                configEnabled = configEnabled,
                deviceControlBusy = configurationState.deviceControlBusy,
                deviceControlError = configurationState.deviceControlError,
                onSetMotionDamping = onSetMotionDamping,
                onSetMagBackgroundLearningEnabled =
                    onSetMagBackgroundLearningEnabled,
                onSetHeadingTrimDeg = onSetHeadingTrimDeg,
                onRestart = {
                    onDeviceControl(RegattaLinkDeviceControlOpcode.RESTART, 0)
                },
                onSetUpright = {
                    onDeviceControl(
                        RegattaLinkDeviceControlOpcode.SET_UPRIGHT,
                        0
                    )
                },
                onSetImuRawPreviewEnabled = onSetImuRawPreviewEnabled,
                onSetImuRawModeEnabled = { enabled ->
                    onDeviceControl(
                        RegattaLinkDeviceControlOpcode.IMU_RAW_MODE,
                        if (enabled) 1 else 0
                    )
                },
                onAdjust = onDeviceControl,
                onDismiss = { activeSetupDestination = null }
            )
        }
        RegattaLinkSetupDestination.NMEA -> {
            RegattaLinkNmeaSetupSheet(
                nmeaState = nmeaState,
                configurationState = configurationState,
                deviceKey = deviceKey,
                connected = connected,
                configEnabled = configEnabled,
                otaActive = otaState.isActive,
                rawCaptureActive = rawCaptureState.isActive,
                onSetLoadPrecisionX10 = onSetLoadPrecisionX10,
                onApplyTxConfigAndRestart = onApplyTxConfigAndRestart,
                phoneGpsRelayEnabled = phoneGpsRelayEnabled,
                onSetPhoneGpsRelayEnabled = onSetPhoneGpsRelayEnabled,
                onSetNmea0183Baud = onSetNmea0183Baud,
                onRestart = {
                    onDeviceControl(RegattaLinkDeviceControlOpcode.RESTART, 0)
                },
                onSetLoadSensorAlias = onSetLoadSensorAlias,
                onRefreshPgnInventory = onRefreshPgnInventory,
                onDismiss = { activeSetupDestination = null }
            )
        }
        RegattaLinkSetupDestination.BLUETOOTH_DEVICES -> {
            RegattaLinkBluetoothDevicesSheet(
                configurationState = configurationState,
                deviceKey = deviceKey,
                connected = connected,
                configEnabled = configEnabled,
                deviceControlEnabled = deviceControlEnabled,
                onScanCalypso = {
                    onDeviceControl(
                        RegattaLinkDeviceControlOpcode.CALYPSO_SCAN,
                        0
                    )
                },
                onApplyBluetoothConfigAndRestart =
                    onApplyBluetoothConfigAndRestart,
                onDismiss = { activeSetupDestination = null }
            )
        }
        RegattaLinkSetupDestination.ADVANCED_DIAGNOSTICS -> {
            RegattaLinkAdvancedDiagnosticsSheet(
                state = state,
                configurationState = configurationState,
                deviceKey = state.deviceInfo?.stableId ?: state.deviceAddress,
                nmeaState = nmeaState,
                rawCaptureState = rawCaptureState,
                connected = connected,
                configEnabled = configEnabled,
                otaActive = otaState.isActive,
                onChangeName = {
                    activeSetupDestination = null
                    nameDraft = displayedName
                    nameDialogOpen = true
                },
                onSetLedBrightness = onSetLedBrightness,
                onApplySubsystemConfigAndRestart =
                    onApplySubsystemConfigAndRestart,
                onDrainDiagnosticLog = onDrainDiagnosticLog,
                onCanErrorTrace = {
                    onDeviceControl(
                        RegattaLinkDeviceControlOpcode.CAN_ERROR_TRACE_60S,
                        0
                    )
                },
                onReadRawFrames = onReadRawFrames,
                onStartRawCapture = onStartRawCapture,
                onStopRawCapture = onStopRawCapture,
                onExportRawCapture = onExportRawCapture,
                onDiscardRawCapture = onDiscardRawCapture,
                onFactoryReset = {
                    activeSetupDestination = null
                    resetDialogOpen = true
                },
                onDismiss = { activeSetupDestination = null }
            )
        }
        RegattaLinkSetupDestination.FIRMWARE -> {
            RegattaLinkFirmwareSheet(
                state = state,
                firmwareState = firmwareState,
                otaState = otaState,
                configurationState = configurationState,
                rawCaptureState = rawCaptureState,
                installAvailable = installAvailable,
                connected = connected,
                onCheckFirmware = onCheckFirmware,
                onFirmwareSourceSelected = onFirmwareSourceSelected,
                onInstallFirmware = onInstallFirmware,
                onCancelOta = onCancelOta,
                onDismiss = { activeSetupDestination = null }
            )
        }
        null -> Unit
    }

    Column(
        modifier = modifier
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.regattalink_title),
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold
            )
            Box {
                Button(
                    onClick = { settingsMenuExpanded = true }
                ) {
                    Text(stringResource(R.string.regattalink_setup))
                }
                DropdownMenu(
                    expanded = settingsMenuExpanded,
                    onDismissRequest = { settingsMenuExpanded = false }
                ) {
                    regattaLinkSetupMenuItems
                        .filter { menuItem ->
                            when (menuItem.destination) {
                                RegattaLinkSetupDestination.IMU ->
                                    configurationState.imuSessionAvailable != false ||
                                        configurationState.magSessionAvailable != false
                                RegattaLinkSetupDestination.NMEA ->
                                    configurationState.canSessionAvailable != false ||
                                        configurationState.nmea0183SessionAvailable != false
                                else -> true
                            }
                        }
                        .forEach { menuItem ->
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(menuItem.labelResId))
                            },
                            onClick = {
                                settingsMenuExpanded = false
                                activeSetupDestination = menuItem.destination
                            }
                        )
                    }
                }
            }
        }

        if (nameDialogOpen) {
            AlertDialog(
                onDismissRequest = {
                    if (!configurationState.busy) nameDialogOpen = false
                },
                title = {
                    Text(stringResource(R.string.regattalink_change_name))
                },
                text = {
                    Column {
                        OutlinedTextField(
                            value = nameDraft,
                            onValueChange = { nameDraft = it },
                            singleLine = true,
                            enabled = !configurationState.busy,
                            label = {
                                Text(stringResource(R.string.regattalink_name))
                            }
                        )
                        if (nameValidationErrorText != null) {
                            Text(
                                text = nameValidationErrorText,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onChangeName(nameDraft)
                            nameDialogOpen = false
                        },
                        enabled = nameValidationError == null &&
                            configEnabled
                    ) {
                        Text(stringResource(R.string.regattalink_save))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { nameDialogOpen = false },
                        enabled = !configurationState.busy
                    ) {
                        Text(stringResource(R.string.regattalink_cancel))
                    }
                }
            )
        }

        if (resetDialogOpen) {
            AlertDialog(
                onDismissRequest = { resetDialogOpen = false },
                title = { Text(stringResource(R.string.regattalink_factory_reset)) },
                text = { Text(stringResource(R.string.regattalink_factory_reset_warning)) },
                confirmButton = {
                    TextButton(onClick = {
                        resetDialogOpen = false
                        onDeviceControl(RegattaLinkDeviceControlOpcode.FACTORY_RESET, 0)
                    }) { Text(stringResource(R.string.regattalink_factory_reset)) }
                },
                dismissButton = {
                    TextButton(onClick = { resetDialogOpen = false }) {
                        Text(stringResource(R.string.regattalink_cancel))
                    }
                }
            )
        }

        Text(
            text = stringResource(R.string.regattalink_devices),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 18.dp)
        )

        if (deviceSelectionState.knownDevices.isEmpty()) {
            Text(
                text = stringResource(R.string.regattalink_no_known_devices),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else {
            deviceSelectionState.knownDevices
                .sortedBy {
                    it.stableId != deviceSelectionState.selectedStableId
                }
                .forEach { device ->
                val isConnected = connectedStableId == device.stableId
                val isSelected =
                    deviceSelectionState.selectedStableId == device.stableId
                TextButton(
                    onClick = { onSelectKnownDevice(device.stableId) },
                    enabled = !otaState.isActive &&
                        !configurationState.deviceControlBusy &&
                        !configurationState.factoryResetAwaitingDisconnect &&
                        !configurationState.restartAwaitingDisconnect &&
                        !isConnected,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text =
                                if (device.deviceName.isBlank()) {
                                    stringResource(R.string.regattalink_title)
                                } else {
                                    device.deviceName
                                },
                            fontWeight =
                                if (isSelected) FontWeight.SemiBold
                                else FontWeight.Normal
                        )
                        Text(
                            text = when {
                                isConnected ->
                                    stringResource(
                                        R.string.regattalink_status_connected
                                    )
                                isSelected ->
                                    stringResource(R.string.regattalink_selected)
                                else ->
                                    stringResource(R.string.regattalink_known)
                            } + " · ID …" + device.stableId.takeLast(8),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        if (
            deviceSelectionState.discovery.scanning ||
            deviceSelectionState.discovery.devices.isNotEmpty()
        ) {
            Text(
                text = stringResource(R.string.regattalink_nearby),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp)
            )
            if (deviceSelectionState.discovery.scanning) {
                Text(
                    text = stringResource(R.string.regattalink_status_scanning),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            deviceSelectionState.discovery.devices.forEach { device ->
                val known = deviceSelectionState.knownDevices.firstOrNull {
                    it.deviceAddress.equals(
                        device.deviceAddress,
                        ignoreCase = true
                    )
                }
                TextButton(
                    onClick = {
                        if (known != null) {
                            onSelectKnownDevice(known.stableId)
                        } else {
                            onConnectDiscoveredDevice(device.deviceAddress)
                        }
                    },
                    enabled = !otaState.isActive &&
                        !configurationState.deviceControlBusy &&
                        !configurationState.factoryResetAwaitingDisconnect &&
                        !configurationState.restartAwaitingDisconnect,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text =
                                if (device.deviceName.isBlank()) {
                                    stringResource(R.string.regattalink_title)
                                } else {
                                    device.deviceName
                                }
                        )
                        Text(
                            text =
                                (if (known != null) {
                                    stringResource(R.string.regattalink_known)
                                } else {
                                    device.deviceAddress
                                }),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } else if (!deviceSelectionState.discovery.scanning) {
            deviceSelectionState.discovery.userMessage?.let { message ->
                Text(
                    text = stringResource(regattaLinkUiMessageResource(message)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = statusText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )

                if (displayedName.isNotBlank()) {
                    Text(
                        text = displayedName,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                state.deviceInfo?.let { info ->
                    telemetryValue(
                        label = stringResource(
                            R.string.regattalink_firmware_build
                        ),
                        value = info.runningBuild.toString()
                    )
                }

                regattaLinkRuntimeMessageText(
                    userMessage = state.userMessage,
                    hasTechnicalError = state.error.isNotBlank(),
                    fallback = RegattaLinkUiMessage.CONNECTION_FAILED
                )?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (telemetryState.supported) {
                    val motionIsStale = rememberTelemetryStale(
                        telemetryState.motionOneHzReceivedAtElapsedMs,
                        REGATTALINK_MOTION_ONE_HZ_STALE_MS
                    )

                    Text(
                        text = stringResource(R.string.regattalink_motion_title),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp)
                    )

                    if (telemetryState.pausedForOta) {
                        Text(
                            text = stringResource(
                                R.string.regattalink_telemetry_paused_ota
                            ),
                            modifier = Modifier.padding(top = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (
                        telemetryState.motionOneHz != null &&
                        motionIsStale
                    ) {
                        Text(
                            text = stringResource(
                                R.string.regattalink_telemetry_stale
                            ),
                            modifier = Modifier.padding(top = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (telemetryState.motionOneHz == null) {
                        Text(
                            text = stringResource(
                                R.string.regattalink_telemetry_waiting
                            ),
                            modifier = Modifier.padding(top = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    telemetryState.motionOneHz?.let { motion ->
                        telemetryValue(
                            label = stringResource(R.string.regattalink_heel),
                            value = motion.heelDeg?.let {
                                formatRegattaLinkWholeDegreeAngle(it)
                            } ?: "--"
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_pitch),
                            value = motion.pitchDeg?.let {
                                formatRegattaLinkWholeDegreeAngle(it)
                            } ?: "--"
                        )
                        telemetryValue(
                            label = stringResource(
                                R.string.regattalink_yaw_rate
                            ),
                            value = motion.yawRateDps?.let {
                                formatTelemetry(it, "°/s")
                            } ?: "--"
                        )
                    }

                    regattaLinkRuntimeMessageText(
                        userMessage = telemetryState.userMessage,
                        hasTechnicalError = telemetryState.error.isNotBlank(),
                        fallback = RegattaLinkUiMessage.TELEMETRY_FAILED
                    )?.let { message ->
                        Text(
                            text = message,
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                regattaLinkRuntimeMessageText(
                    userMessage = configurationState.userMessage,
                    hasTechnicalError = configurationState.error.isNotBlank(),
                    fallback = RegattaLinkUiMessage.CONFIGURATION_FAILED
                )?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (configurationState.deviceControlBusy) {
                    Text(
                        text = configurationState.deviceControlStatus
                            ?.takeIf { !it.phase.isTerminal }
                            ?.let {
                                "${stringResource(R.string.regattalink_device_control)}: " +
                                    "${it.phase} · ${it.result}"
                            } ?: stringResource(R.string.regattalink_updating),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (rawCaptureState.isActive) {
                    Text(
                        text = stringResource(
                            R.string.regattalink_raw_capture_active
                        ),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        text = stringResource(
                            R.string.regattalink_raw_capture_frames,
                            rawCaptureState.frameCount
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                if (otaState.isActive) {
                    Text(
                        text = "${stringResource(R.string.regattalink_ota_title)}: " +
                            regattaLinkOtaPhaseText(otaState.phase),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    if (
                        otaState.phase == RegattaLinkOtaPhase.TRANSFERRING ||
                        otaState.committedBytes > 0
                    ) {
                        LinearProgressIndicator(
                            progress = { otaState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                        )
                    }
                }
            }
        }

        if (state.status == RegattaLinkConnectionStatus.BLUETOOTH_OFF) {
            Button(
                onClick = onEnableBluetooth,
                enabled = !otaState.isActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp)
            ) {
                Text(stringResource(R.string.regattalink_enable_bluetooth))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onSearch,
                enabled = !busy &&
                    !deviceSelectionState.discovery.scanning &&
                    !otaState.isActive &&
                    !configurationState.deviceControlBusy &&
                    !configurationState.restartAwaitingDisconnect &&
                    !configurationState.factoryResetAwaitingDisconnect &&
                    state.status != RegattaLinkConnectionStatus.BLUETOOTH_OFF,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.regattalink_find))
            }

            Button(
                onClick = onDisconnect,
                enabled = !otaState.isActive &&
                    !configurationState.deviceControlBusy &&
                    !configurationState.factoryResetAwaitingDisconnect &&
                    state.status != RegattaLinkConnectionStatus.IDLE &&
                    state.status != RegattaLinkConnectionStatus.BLUETOOTH_OFF,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.regattalink_disconnect))
            }
        }

        Button(
            onClick = onBack,
            enabled = !otaState.isActive,
            colors = primaryButtonColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp)
        ) {
            Text(stringResource(R.string.back))
        }
    }
}


@Composable
private fun rememberTelemetryStale(
    receivedAtElapsedMs: Long?,
    staleAfterMs: Long
): Boolean {
    var stale by remember { mutableStateOf(false) }

    LaunchedEffect(receivedAtElapsedMs, staleAfterMs) {
        if (receivedAtElapsedMs == null || receivedAtElapsedMs <= 0L) {
            stale = false
            return@LaunchedEffect
        }

        val now = SystemClock.elapsedRealtime()
        stale = !isRegattaLinkTelemetryFresh(
            receivedAtElapsedMs,
            staleAfterMs,
            now
        )
        if (stale) {
            return@LaunchedEffect
        }

        val ageMs = (now - receivedAtElapsedMs).coerceAtLeast(0L)
        val untilStaleMs = (staleAfterMs - ageMs + 1L).coerceAtLeast(1L)
        delay(untilStaleMs)

        stale = !isRegattaLinkTelemetryFresh(
            receivedAtElapsedMs,
            staleAfterMs,
            SystemClock.elapsedRealtime()
        )
    }

    return stale
}

@Composable
private fun rememberRawCaptureRemainingSeconds(
    state: RegattaLinkRawCaptureState
): Long? {
    var nowElapsedMs by remember {
        mutableStateOf(SystemClock.elapsedRealtime())
    }

    LaunchedEffect(state.isActive, state.startedAtElapsedMs) {
        while (state.isActive) {
            nowElapsedMs = SystemClock.elapsedRealtime()
            delay(250L)
        }
        nowElapsedMs = SystemClock.elapsedRealtime()
    }

    return state.startedAtElapsedMs
        ?.takeIf { state.isActive }
        ?.let { startedAt ->
            val elapsed =
                (nowElapsedMs - startedAt).coerceAtLeast(0L)
            ((state.durationMs - elapsed)
                .coerceAtLeast(0L) + 999L) / 1000L
        }
}

@Composable
private fun regattaLinkBoatDataOutputStateText(
    desired: Boolean?,
    bootMask: Int?,
    activeMask: Int?,
    runtimeBit: Int
): String =
    when {
        desired == null ->
            stringResource(R.string.regattalink_nmea_tx_state_unavailable)
        bootMask == null || activeMask == null ->
            stringResource(
                if (desired) {
                    R.string.regattalink_boat_data_output_selected_runtime_unknown
                } else {
                    R.string.regattalink_boat_data_output_off_runtime_unknown
                }
            )
        desired != (bootMask and runtimeBit != 0) ->
            stringResource(R.string.regattalink_boat_data_output_restart_required)
        activeMask and runtimeBit != 0 ->
            stringResource(R.string.regattalink_boat_data_output_active)
        bootMask and runtimeBit != 0 ->
            stringResource(R.string.regattalink_boat_data_output_selected_inactive)
        else ->
            stringResource(R.string.regattalink_boat_data_output_off)
    }

@Composable
private fun RegattaLinkBoatDataSelectorRow(
    title: String,
    desired: Boolean?,
    draft: Boolean?,
    draftChanged: Boolean,
    bootMask: Int?,
    activeMask: Int?,
    runtimeBit: Int,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (draftChanged && draft != null) {
                    stringResource(
                        if (draft) {
                            R.string.regattalink_config_draft_enable
                        } else {
                            R.string.regattalink_config_draft_disable
                        }
                    )
                } else {
                    regattaLinkBoatDataOutputStateText(
                        desired = desired,
                        bootMask = bootMask,
                        activeMask = activeMask,
                        runtimeBit = runtimeBit
                    )
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = draft == true,
            onCheckedChange = onCheckedChange,
            enabled = enabled && draft != null
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegattaLinkBluetoothDevicesSheet(
    configurationState: RegattaLinkConfigurationState,
    deviceKey: String,
    connected: Boolean,
    configEnabled: Boolean,
    deviceControlEnabled: Boolean,
    onScanCalypso: () -> Unit,
    onApplyBluetoothConfigAndRestart: (UInt) -> Unit,
    onDismiss: () -> Unit
) {
    val selectionFromDevice =
        configurationState.configWord
            ?.and(REGATTALINK_CONFIG_BLUETOOTH_DEVICE_MASK)
    var baseline by remember(deviceKey, connected) {
        mutableStateOf(selectionFromDevice)
    }
    var draft by remember(deviceKey, connected) {
        mutableStateOf(selectionFromDevice)
    }
    LaunchedEffect(deviceKey, connected, selectionFromDevice) {
        if (baseline == null && selectionFromDevice != null) {
            baseline = selectionFromDevice
            draft = selectionFromDevice
        }
    }

    val dirty =
        baseline != null &&
            draft != null &&
            baseline != draft
    val calypsoEnabledDraft =
        regattaLinkConfigDraftBit(
            draft,
            REGATTALINK_CONFIG_CALYPSO_ENABLE
        )
    val calypso = configurationState.calypso
    val scanActive =
        calypso.scanning ||
            (
                configurationState.deviceControlBusy &&
                    configurationState.deviceControlAcceptedOpcode ==
                    RegattaLinkDeviceControlOpcode.CALYPSO_SCAN
                )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.regattalink_bluetooth_devices),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    R.string.regattalink_calypso_wind_sensor
                ),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 18.dp)
            )

            telemetryValue(
                label = stringResource(
                    R.string.regattalink_calypso_bound_sensor
                ),
                value = when {
                    !calypso.statusKnown -> "—"
                    calypso.boundId != null -> calypso.boundId
                    else -> stringResource(
                        R.string.regattalink_calypso_not_bound
                    )
                }
            )
            telemetryValue(
                label = stringResource(
                    R.string.regattalink_calypso_runtime
                ),
                value = when {
                    !calypso.statusKnown -> "—"
                    calypso.connected ->
                        stringResource(R.string.regattalink_status_connected)
                    else ->
                        stringResource(
                            R.string.regattalink_calypso_disconnected
                        )
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            R.string.regattalink_calypso_enable
                        ),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(
                            R.string.regattalink_applies_after_restart
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = calypsoEnabledDraft == true,
                    onCheckedChange = { enabled ->
                        draft = regattaLinkConfigDraftWithBit(
                            draft,
                            REGATTALINK_CONFIG_CALYPSO_ENABLE,
                            enabled
                        )
                    },
                    enabled = configEnabled && calypsoEnabledDraft != null
                )
            }

            if (dirty && draft != null) {
                Button(
                    onClick = {
                        onApplyBluetoothConfigAndRestart(draft!!)
                    },
                    enabled =
                        configEnabled &&
                            configurationState.deviceControlSupported,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    Text(stringResource(R.string.regattalink_apply_restart))
                }
            }

            Button(
                onClick = onScanCalypso,
                enabled = connected && deviceControlEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp)
            ) {
                Text(
                    stringResource(
                        if (scanActive) {
                            R.string.regattalink_calypso_scanning
                        } else {
                            R.string.regattalink_calypso_scan
                        }
                    )
                )
            }

            when (calypso.lastScanResult) {
                RegattaLinkDeviceControlResult.NOT_FOUND ->
                    Text(
                        text = stringResource(
                            R.string.regattalink_calypso_not_found
                        ),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                RegattaLinkDeviceControlResult.AMBIGUOUS ->
                    Text(
                        text = stringResource(
                            R.string.regattalink_calypso_ambiguous
                        ),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                RegattaLinkDeviceControlResult.VERIFY_FAILED ->
                    Text(
                        text = stringResource(
                            R.string.regattalink_calypso_verify_failed
                        ),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                RegattaLinkDeviceControlResult.NONE,
                RegattaLinkDeviceControlResult.OK,
                null -> Unit
                else ->
                    Text(
                        text = stringResource(
                            R.string.regattalink_error_configuration_failed
                        ),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
            }

            RegattaLinkTechnicalDetail(
                detail = calypso.error,
                modifier = Modifier.padding(top = 4.dp)
            )

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 12.dp)
            ) {
                Text(stringResource(R.string.close))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegattaLinkNmeaSetupSheet(
    nmeaState: RegattaLinkNmeaState,
    configurationState: RegattaLinkConfigurationState,
    deviceKey: String,
    connected: Boolean,
    configEnabled: Boolean,
    otaActive: Boolean,
    rawCaptureActive: Boolean,
    onSetLoadPrecisionX10: (Boolean) -> Unit,
    onApplyTxConfigAndRestart: (UInt) -> Unit,
    phoneGpsRelayEnabled: Boolean,
    onSetPhoneGpsRelayEnabled: (Boolean) -> Unit,
    onSetNmea0183Baud: (Int) -> Unit,
    onRestart: () -> Unit,
    onSetLoadSensorAlias: (String, String) -> Unit,
    onRefreshPgnInventory: () -> Unit,
    onDismiss: () -> Unit
) {
    val canAvailable = configurationState.canSessionAvailable != false
    val nmea0183Available =
        configurationState.nmea0183SessionAvailable != false
    val imuAvailable = configurationState.imuSessionAvailable != false
    val magAvailable = configurationState.magSessionAvailable != false
    val nmeaAvailable =
        connected && (canAvailable || nmea0183Available)
    val txSelectionFromDevice =
        configurationState.configWord?.and(REGATTALINK_CONFIG_TX_SELECTION_MASK)
    var txBaseline by remember(deviceKey, connected) {
        mutableStateOf(txSelectionFromDevice)
    }
    var txDraft by remember(deviceKey, connected) {
        mutableStateOf(txSelectionFromDevice)
    }
    LaunchedEffect(deviceKey, connected, txSelectionFromDevice) {
        if (txBaseline == null && txSelectionFromDevice != null) {
            txBaseline = txSelectionFromDevice
            txDraft = txSelectionFromDevice
        }
    }
    val txDirty =
        txBaseline != null &&
            txDraft != null &&
            txBaseline != txDraft
    val txMasterDraft =
        regattaLinkConfigDraftBit(txDraft, REGATTALINK_CONFIG_TX_MASTER)
    val txAttitudeDraft =
        regattaLinkConfigDraftBit(txDraft, REGATTALINK_CONFIG_TX_IMU)
    val tx0183Draft =
        regattaLinkConfigDraftBit(txDraft, REGATTALINK_CONFIG_TX_NMEA0183)
    val txPhoneGpsDraft =
        regattaLinkConfigDraftBit(txDraft, REGATTALINK_CONFIG_TX_PHONE_GPS)
    val txCompassDraft =
        regattaLinkConfigDraftBit(txDraft, REGATTALINK_CONFIG_TX_COMPASS)
    val txCalypsoWindDraft =
        regattaLinkConfigDraftBit(
            txDraft,
            REGATTALINK_CONFIG_TX_CALYPSO_WIND
        )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.regattalink_setup_nmea),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (nmeaAvailable) {
                    stringResource(R.string.regattalink_nmea_available)
                } else {
                    stringResource(R.string.regattalink_nmea_unavailable)
                },
                modifier = Modifier.padding(top = 8.dp)
            )

            if (nmeaState.pausedForOta) {
                Text(
                    text = stringResource(
                        R.string.regattalink_telemetry_paused_ota
                    ),
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val nmeaRuntimeKnown =
                configurationState.nmeaTxRuntimeStatusSupported &&
                    configurationState.nmeaTxBootSelected != null &&
                    configurationState.nmeaTxActive != null
            val nmeaAttitudeBootSelected =
                configurationState.nmeaBootOutputMask?.let {
                    it and REGATTALINK_TX_OUTPUT_IMU != 0
                }
            val nmeaAttitudeRuntimeActive =
                configurationState.nmeaActiveOutputMask?.let {
                    it and REGATTALINK_TX_OUTPUT_IMU != 0
                }

            if (
                connected &&
                canAvailable &&
                configurationState.nmeaTxSupported
            ) {
                val masterDraftChanged =
                    regattaLinkConfigDraftBitChanged(
                        txBaseline,
                        txDraft,
                        REGATTALINK_CONFIG_TX_MASTER
                    )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                R.string.regattalink_nmea_tx_title
                            ),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = when {
                                txMasterDraft == null ->
                                    stringResource(
                                        R.string.regattalink_nmea_tx_state_unavailable
                                    )
                                masterDraftChanged ->
                                    stringResource(
                                        if (txMasterDraft) {
                                            R.string.regattalink_config_draft_enable
                                        } else {
                                            R.string.regattalink_config_draft_disable
                                        }
                                    )
                                !nmeaRuntimeKnown ->
                                    stringResource(
                                        if (configurationState.nmeaTxEnabled == true) {
                                            R.string.regattalink_nmea_tx_selected_on_runtime_unknown
                                        } else {
                                            R.string.regattalink_nmea_tx_selected_off_runtime_unknown
                                        }
                                    )
                                configurationState.nmeaTxRestartRequired ->
                                    stringResource(
                                        if (configurationState.nmeaTxEnabled == true) {
                                            R.string.regattalink_nmea_tx_enable_pending
                                        } else {
                                            R.string.regattalink_nmea_tx_disable_pending
                                        }
                                    )
                                configurationState.nmeaTxActive == true ->
                                    stringResource(
                                        R.string.regattalink_nmea_tx_enabled
                                    )
                                configurationState.nmeaTxBootSelected == true ->
                                    stringResource(
                                        R.string.regattalink_nmea_tx_selected_but_inactive
                                    )
                                else ->
                                    stringResource(
                                        R.string.regattalink_nmea_tx_receive_only
                                    )
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = txMasterDraft == true,
                        onCheckedChange = { enabled ->
                            txDraft = regattaLinkConfigDraftWithBit(
                                txDraft,
                                REGATTALINK_CONFIG_TX_MASTER,
                                enabled
                            )
                        },
                        enabled = configEnabled && txMasterDraft != null
                    )
                }
            }

            if (
                connected &&
                canAvailable &&
                nmea0183Available &&
                configurationState.configWordSupported
            ) {
                RegattaLinkBoatDataSelectorRow(
                    title = stringResource(
                        R.string.regattalink_nmea0183_tx
                    ),
                    desired = configurationState.nmea0183TxEnabled,
                    draft = tx0183Draft,
                    draftChanged = regattaLinkConfigDraftBitChanged(
                        txBaseline,
                        txDraft,
                        REGATTALINK_CONFIG_TX_NMEA0183
                    ),
                    bootMask = configurationState.nmeaBootOutputMask,
                    activeMask = configurationState.nmeaActiveOutputMask,
                    runtimeBit = REGATTALINK_TX_OUTPUT_NMEA0183,
                    enabled = configEnabled,
                    onCheckedChange = { enabled ->
                        txDraft = regattaLinkConfigDraftWithBit(
                            txDraft,
                            REGATTALINK_CONFIG_TX_NMEA0183,
                            enabled
                        )
                    }
                )
            }

            if (
                connected &&
                canAvailable &&
                imuAvailable &&
                configurationState.nmeaAttitudeTxSupported
            ) {
                val attitudeDraftChanged =
                    regattaLinkConfigDraftBitChanged(
                        txBaseline,
                        txDraft,
                        REGATTALINK_CONFIG_TX_IMU
                    )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                R.string.regattalink_nmea_attitude_tx
                            ),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = when {
                                txAttitudeDraft == null ->
                                    stringResource(
                                        R.string.regattalink_nmea_tx_state_unavailable
                                    )
                                attitudeDraftChanged ->
                                    stringResource(
                                        if (txAttitudeDraft) {
                                            R.string.regattalink_config_draft_enable
                                        } else {
                                            R.string.regattalink_config_draft_disable
                                        }
                                    )
                                nmeaAttitudeBootSelected == null ||
                                    nmeaAttitudeRuntimeActive == null ->
                                    stringResource(
                                        if (configurationState.nmeaAttitudeTxEnabled == true) {
                                            R.string.regattalink_nmea_attitude_selected_on_runtime_unknown
                                        } else {
                                            R.string.regattalink_nmea_attitude_selected_off_runtime_unknown
                                        }
                                    )
                                configurationState.nmeaAttitudeTxRestartRequired ->
                                    stringResource(
                                        if (configurationState.nmeaAttitudeTxEnabled == true) {
                                            R.string.regattalink_nmea_attitude_enable_pending
                                        } else {
                                            R.string.regattalink_nmea_attitude_disable_pending
                                        }
                                    )
                                nmeaAttitudeRuntimeActive ->
                                    stringResource(
                                        R.string.regattalink_nmea_attitude_tx_enabled
                                    )
                                nmeaAttitudeBootSelected ->
                                    stringResource(
                                        R.string.regattalink_nmea_attitude_tx_inactive
                                    )
                                else ->
                                    stringResource(
                                        R.string.regattalink_nmea_attitude_tx_disabled
                                    )
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = txAttitudeDraft == true,
                        onCheckedChange = { enabled ->
                            txDraft = regattaLinkConfigDraftWithBit(
                                txDraft,
                                REGATTALINK_CONFIG_TX_IMU,
                                enabled
                            )
                        },
                        enabled = configEnabled && txAttitudeDraft != null
                    )
                }
            }

            if (
                connected &&
                canAvailable &&
                magAvailable &&
                configurationState.configWordSupported
            ) {
                RegattaLinkBoatDataSelectorRow(
                    title = stringResource(
                        R.string.regattalink_compass_tx
                    ),
                    desired = configurationState.compassTxEnabled,
                    draft = txCompassDraft,
                    draftChanged = regattaLinkConfigDraftBitChanged(
                        txBaseline,
                        txDraft,
                        REGATTALINK_CONFIG_TX_COMPASS
                    ),
                    bootMask = configurationState.nmeaBootOutputMask,
                    activeMask = configurationState.nmeaActiveOutputMask,
                    runtimeBit = REGATTALINK_TX_OUTPUT_COMPASS,
                    enabled = configEnabled,
                    onCheckedChange = { enabled ->
                        txDraft = regattaLinkConfigDraftWithBit(
                            txDraft,
                            REGATTALINK_CONFIG_TX_COMPASS,
                            enabled
                        )
                    }
                )
            }

            if (
                connected &&
                canAvailable &&
                configurationState.configWordSupported
            ) {
                RegattaLinkBoatDataSelectorRow(
                    title = stringResource(
                        R.string.regattalink_phone_gps_tx
                    ),
                    desired = configurationState.phoneGpsTxEnabled,
                    draft = txPhoneGpsDraft,
                    draftChanged = regattaLinkConfigDraftBitChanged(
                        txBaseline,
                        txDraft,
                        REGATTALINK_CONFIG_TX_PHONE_GPS
                    ),
                    bootMask = configurationState.nmeaBootOutputMask,
                    activeMask = configurationState.nmeaActiveOutputMask,
                    runtimeBit = REGATTALINK_TX_OUTPUT_PHONE_GPS,
                    enabled = configEnabled,
                    onCheckedChange = { enabled ->
                        txDraft = regattaLinkConfigDraftWithBit(
                            txDraft,
                            REGATTALINK_CONFIG_TX_PHONE_GPS,
                            enabled
                        )
                    }
                )
            }

            if (
                connected &&
                canAvailable &&
                configurationState.configWordSupported
            ) {
                RegattaLinkBoatDataSelectorRow(
                    title = stringResource(
                        R.string.regattalink_calypso_wind_tx
                    ),
                    desired = configurationState.calypsoWindTxEnabled,
                    draft = txCalypsoWindDraft,
                    draftChanged = regattaLinkConfigDraftBitChanged(
                        txBaseline,
                        txDraft,
                        REGATTALINK_CONFIG_TX_CALYPSO_WIND
                    ),
                    bootMask = configurationState.nmeaBootOutputMask,
                    activeMask = configurationState.nmeaActiveOutputMask,
                    runtimeBit = REGATTALINK_TX_OUTPUT_CALYPSO_WIND,
                    enabled = configEnabled,
                    onCheckedChange = { enabled ->
                        txDraft = regattaLinkConfigDraftWithBit(
                            txDraft,
                            REGATTALINK_CONFIG_TX_CALYPSO_WIND,
                            enabled
                        )
                    }
                )
            }

            if (canAvailable) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                R.string.regattalink_phone_gps_relay
                            ),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(
                                R.string.regattalink_phone_gps_relay_hint
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = phoneGpsRelayEnabled,
                        onCheckedChange = onSetPhoneGpsRelayEnabled
                    )
                }
            }

            if (
                connected &&
                nmea0183Available &&
                configurationState.configWordSupported
            ) {
                var baudMenuExpanded by remember {
                    mutableStateOf(false)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                R.string.regattalink_nmea0183_baud
                            ),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(
                                R.string.regattalink_applies_after_restart
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        TextButton(
                            onClick = { baudMenuExpanded = true },
                            enabled =
                                configEnabled &&
                                    configurationState.nmea0183Baud != null
                        ) {
                            Text(
                                configurationState.nmea0183Baud
                                    ?.baudRate
                                    ?.toString()
                                    ?: "--"
                            )
                        }
                        DropdownMenu(
                            expanded = baudMenuExpanded,
                            onDismissRequest = {
                                baudMenuExpanded = false
                            }
                        ) {
                            RegattaLinkNmea0183Baud.entries.forEach { baud ->
                                DropdownMenuItem(
                                    text = {
                                        Text(baud.baudRate.toString())
                                    },
                                    onClick = {
                                        baudMenuExpanded = false
                                        onSetNmea0183Baud(baud.baudRate)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            val nmeaAppliedStateUnknown =
                connected &&
                    !configurationState.nmeaTxRuntimeStatusSupported &&
                    (
                        configurationState.nmeaTxSupported ||
                            configurationState.nmeaAttitudeTxSupported
                        )
            val nmeaRestartActionAvailable =
                txDirty ||
                    regattaLinkNmeaRestartRequired(configurationState) ||
                    nmeaAppliedStateUnknown

            if (
                connected &&
                (
                    nmeaRestartActionAvailable ||
                        configurationState.restartAwaitingDisconnect
                    )
            ) {
                Text(
                    text =
                        when {
                            configurationState.restartAwaitingDisconnect ->
                                stringResource(R.string.regattalink_restarting)
                            txDirty ->
                                stringResource(
                                    R.string.regattalink_config_draft_restart
                                )
                            nmeaAppliedStateUnknown ->
                                stringResource(
                                    R.string.regattalink_nmea_runtime_unknown_restart
                                )
                            else ->
                                stringResource(
                                    R.string.regattalink_nmea_restart_required
                                )
                        },
                    modifier = Modifier.padding(top = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        val draft = txDraft
                        if (txDirty && draft != null) {
                            onApplyTxConfigAndRestart(draft)
                        } else {
                            onRestart()
                        }
                    },
                    enabled =
                        configEnabled &&
                            configurationState.deviceControlSupported &&
                            nmeaRestartActionAvailable &&
                            !configurationState.restartAwaitingDisconnect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(
                        when {
                            configurationState.restartAwaitingDisconnect ->
                                stringResource(R.string.regattalink_restarting)
                            txDirty ->
                                stringResource(
                                    R.string.regattalink_apply_restart
                                )
                            else ->
                                stringResource(R.string.regattalink_restart)
                        }
                    )
                }
            }

            if (
                connected &&
                configurationState.deviceControlStatus?.opcode ==
                    RegattaLinkDeviceControlOpcode.RESTART &&
                configurationState.deviceControlError.isNotBlank()
            ) {
                Text(
                    text = stringResource(
                        R.string.regattalink_restart_timeout
                    ),
                    modifier = Modifier.padding(top = 10.dp),
                    color = MaterialTheme.colorScheme.error
                )
                RegattaLinkTechnicalDetail(
                    detail = configurationState.deviceControlError,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (
                connected &&
                canAvailable &&
                configurationState.loadPrecisionSupported
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                R.string.regattalink_load_precision
                            ),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(
                                if (configurationState.loadPrecisionX10 == true) {
                                    R.string.regattalink_load_precision_x10
                                } else {
                                    R.string.regattalink_load_precision_x1
                                }
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked =
                            configurationState.loadPrecisionX10 == true,
                        onCheckedChange = onSetLoadPrecisionX10,
                        enabled = configEnabled
                    )
                }
            }

            if (connected && canAvailable && nmeaState.loadSupported) {
                Text(
                    text = stringResource(
                        R.string.regattalink_load_sensors
                    ),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 18.dp)
                )

                if (
                    nmeaRuntimeKnown &&
                    configurationState.nmeaTxActive == false
                ) {
                    Text(
                        text = stringResource(
                            R.string.regattalink_load_listen_only
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (!nmeaState.loadSubscribed) {
                    Text(
                        text = stringResource(
                            R.string.regattalink_load_not_subscribed
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else if (nmeaState.loadSensors.isEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.regattalink_load_waiting
                        ),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                nmeaState.loadSensors.forEach { sensor ->
                    var aliasDraft by remember(
                        sensor.identityKey,
                        sensor.alias
                    ) {
                        mutableStateOf(sensor.alias.orEmpty())
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            telemetryValue(
                                label = sensor.label,
                                value = "%.1f kg".format(
                                    Locale.ROOT,
                                    sensor.loadKg
                                )
                            )
                            if (sensor.stableIdentity) {
                                Text(
                                    text = sensor.measurementKey,
                                    color =
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                                OutlinedTextField(
                                    value = aliasDraft,
                                    onValueChange = { aliasDraft = it },
                                    singleLine = true,
                                    enabled = configEnabled,
                                    label = {
                                        Text(
                                            stringResource(
                                                R.string.regattalink_load_alias
                                            )
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                )
                                TextButton(
                                    onClick = {
                                        onSetLoadSensorAlias(
                                            sensor.identityKey,
                                            aliasDraft
                                        )
                                    },
                                    enabled =
                                        configEnabled &&
                                            aliasDraft
                                                .toByteArray(Charsets.UTF_8)
                                                .size <=
                                                REGATTALINK_LOAD_MAX_ALIAS_BYTES,
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.regattalink_save
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (connected && nmeaState.boatStateSupported) {
                Text(
                    text = stringResource(R.string.regattalink_boat_state_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 18.dp)
                )
                if (
                    nmeaState.boatStateSubscribed &&
                    !nmeaState.boatStateLiveNotifications
                ) {
                    Text(
                        text = stringResource(
                            R.string.regattalink_boat_state_snapshot_only
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                val boatState = nmeaState.boatState
                if (boatState == null) {
                    Text(
                        text = stringResource(
                            R.string.regattalink_boat_state_waiting
                        ),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else if (!boatState.hasAnyValidData) {
                    Text(
                        text = stringResource(
                            R.string.regattalink_boat_state_no_data
                        ),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    BoatStateValues(boatState)
                }
            }

            if (
                connected &&
                canAvailable &&
                nmeaState.pgnInventorySupported
            ) {
                Text(
                    text = stringResource(R.string.regattalink_pgns_seen),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 18.dp)
                )
                telemetryValue(
                    label = stringResource(R.string.regattalink_pgns_seen),
                    value = nmeaState.pgnInventory.size.toString()
                )
                Button(
                    onClick = onRefreshPgnInventory,
                    enabled = !otaActive &&
                        !rawCaptureActive &&
                        !nmeaState.pgnInventoryLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    Text(
                        if (nmeaState.pgnInventoryLoading) {
                            stringResource(R.string.regattalink_refreshing)
                        } else {
                            stringResource(R.string.regattalink_refresh_pgns)
                        }
                    )
                }
                nmeaState.pgnInventory
                    .sortedBy { it.pgn }
                    .forEach { entry ->
                        telemetryValue(
                            label = "PGN " + entry.pgn,
                            value = stringResource(
                                R.string.regattalink_last_seen_ms,
                                entry.lastSeenMs
                            )
                        )
                    }
            }

            regattaLinkRuntimeMessageText(
                userMessage = nmeaState.userMessage,
                hasTechnicalError = nmeaState.error.isNotBlank(),
                fallback = RegattaLinkUiMessage.NMEA_FAILED
            )?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 12.dp)
            ) {
                Text(stringResource(R.string.close))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegattaLinkAdvancedDiagnosticsSheet(
    state: RegattaLinkClientState,
    configurationState: RegattaLinkConfigurationState,
    deviceKey: String,
    nmeaState: RegattaLinkNmeaState,
    rawCaptureState: RegattaLinkRawCaptureState,
    connected: Boolean,
    configEnabled: Boolean,
    otaActive: Boolean,
    onChangeName: () -> Unit,
    onSetLedBrightness: (Int) -> Unit,
    onApplySubsystemConfigAndRestart: (UInt) -> Unit,
    onDrainDiagnosticLog: () -> Unit,
    onCanErrorTrace: () -> Unit,
    onReadRawFrames: () -> Unit,
    onStartRawCapture: () -> Unit,
    onStopRawCapture: () -> Unit,
    onExportRawCapture: () -> Unit,
    onDiscardRawCapture: () -> Unit,
    onFactoryReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var brightnessDraft by remember(configurationState.ledBrightnessPct) {
        mutableStateOf((configurationState.ledBrightnessPct ?: 0).toFloat())
    }
    val subsystemSelectionFromSession =
        configurationState.configWord
            ?.and(REGATTALINK_CONFIG_SESSION_SUBSYSTEM_MASK)
    var subsystemBaseline by remember(deviceKey, connected) {
        mutableStateOf(subsystemSelectionFromSession)
    }
    var subsystemDraft by remember(deviceKey, connected) {
        mutableStateOf(subsystemSelectionFromSession)
    }
    LaunchedEffect(deviceKey, connected, subsystemSelectionFromSession) {
        if (
            subsystemBaseline == null &&
            subsystemSelectionFromSession != null
        ) {
            subsystemBaseline = subsystemSelectionFromSession
            subsystemDraft = subsystemSelectionFromSession
        }
    }
    val subsystemDirty =
        subsystemBaseline != null &&
            subsystemDraft != null &&
            subsystemBaseline != subsystemDraft
    val rawCaptureRemainingSeconds =
        rememberRawCaptureRemainingSeconds(rawCaptureState)
    val displayedName =
        configurationState.deviceName.ifBlank { state.deviceName }
    val deviceControlEnabled =
        configEnabled &&
            !configurationState.deviceControlBusy &&
            !configurationState.diagnosticLogLoading &&
            !nmeaState.rawCanReading

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(
                    R.string.regattalink_advanced_diagnostics
                ),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = stringResource(R.string.regattalink_device_settings),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 18.dp)
            )

            if (displayedName.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${stringResource(R.string.regattalink_name)}: " +
                            displayedName,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 4.dp)
                    )
                    if (
                        configurationState.deviceNameSupported &&
                        connected
                    ) {
                        TextButton(
                            onClick = onChangeName,
                            enabled = configEnabled
                        ) {
                            Text(stringResource(R.string.regattalink_change))
                        }
                    }
                }
            }

            if (
                connected &&
                configurationState.ledBrightnessSupported &&
                configurationState.ledBrightnessPct != null
            ) {
                val shownBrightness = brightnessDraft
                    .roundToInt()
                    .coerceIn(0, 100)
                telemetryValue(
                    label = stringResource(
                        R.string.regattalink_led_brightness
                    ),
                    value = "$shownBrightness %"
                )
                Slider(
                    value = brightnessDraft.coerceIn(0f, 100f),
                    onValueChange = { brightnessDraft = it },
                    onValueChangeFinished = {
                        val submission =
                            prepareRegattaLinkConfigSliderSubmission(
                                draftValue = brightnessDraft,
                                confirmedValue =
                                    configurationState.ledBrightnessPct,
                                validRange = 0..100
                            )
                        brightnessDraft = submission.confirmedDraft
                        if (
                            submission.requestedValue !=
                            configurationState.ledBrightnessPct
                        ) {
                            onSetLedBrightness(submission.requestedValue)
                        }
                    },
                    valueRange = 0f..100f,
                    enabled = configEnabled
                )
            }

            if (configurationState.busy) {
                Text(
                    text = stringResource(R.string.regattalink_saving),
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (
                connected &&
                configurationState.configWordSupported &&
                configurationState.configWord != null
            ) {
                Text(
                    text = stringResource(R.string.regattalink_subsystems),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 18.dp)
                )
                Text(
                    text = stringResource(
                        R.string.regattalink_subsystems_session_help
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                val subsystemRows = listOf(
                    RegattaLinkSubsystem.IMU to
                        stringResource(R.string.regattalink_subsystem_imu),
                    RegattaLinkSubsystem.MAG to
                        stringResource(R.string.regattalink_subsystem_mag),
                    RegattaLinkSubsystem.BOAT_DATA to
                        stringResource(R.string.regattalink_subsystem_boat_data),
                    RegattaLinkSubsystem.NMEA0183_RX to
                        stringResource(R.string.regattalink_subsystem_nmea0183)
                )
                subsystemRows.forEach { (subsystem, label) ->
                    val draftEnabled =
                        regattaLinkConfigDraftBit(
                            subsystemDraft,
                            subsystem.configBit
                        )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = draftEnabled == true,
                            onCheckedChange = { enabled ->
                                subsystemDraft =
                                    regattaLinkConfigDraftWithBit(
                                        subsystemDraft,
                                        subsystem.configBit,
                                        enabled
                                    )
                            },
                            enabled = configEnabled && draftEnabled != null
                        )
                    }
                }

                if (
                    subsystemDirty ||
                    configurationState.restartAwaitingDisconnect
                ) {
                    Text(
                        text = if (
                            configurationState.restartAwaitingDisconnect
                        ) {
                            stringResource(R.string.regattalink_restarting)
                        } else {
                            stringResource(
                                R.string.regattalink_config_draft_restart
                            )
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Button(
                        onClick = {
                            subsystemDraft?.let(
                                onApplySubsystemConfigAndRestart
                            )
                        },
                        enabled =
                            configEnabled &&
                                configurationState.deviceControlSupported &&
                                subsystemDirty &&
                                !configurationState.restartAwaitingDisconnect,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text(
                            if (configurationState.restartAwaitingDisconnect) {
                                stringResource(R.string.regattalink_restarting)
                            } else {
                                stringResource(
                                    R.string.regattalink_apply_restart
                                )
                            }
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.regattalink_technical_details),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 18.dp)
            )
            if (state.deviceAddress.isNotBlank()) {
                telemetryValue(
                    label = stringResource(R.string.regattalink_address),
                    value = state.deviceAddress
                )
            }
            state.deviceInfo?.let { info ->
                telemetryValue(
                    label = stringResource(R.string.regattalink_stable_id),
                    value = info.stableId
                )
                telemetryValue(
                    label = stringResource(R.string.regattalink_protocol),
                    value = "${info.protocolMajor}.${info.protocolMinor}"
                )
                telemetryValue(
                    label = stringResource(
                        R.string.regattalink_product_profile
                    ),
                    value = "${info.productId} / ${info.profileId}"
                )
                telemetryValue(
                    label = stringResource(
                        R.string.regattalink_firmware_build
                    ),
                    value = info.runningBuild.toString()
                )
                telemetryValue(
                    label = stringResource(
                        R.string.regattalink_ota_capability
                    ),
                    value = if (info.otaAvailable) {
                        stringResource(R.string.regattalink_available)
                    } else {
                        stringResource(R.string.regattalink_unavailable)
                    }
                )
            }

            Text(
                text = stringResource(R.string.regattalink_diagnostics_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 18.dp)
            )

            if (configurationState.diagnosticLogSupported) {
                Button(
                    onClick = onDrainDiagnosticLog,
                    enabled =
                        configEnabled &&
                            !configurationState.diagnosticLogLoading &&
                            !configurationState.deviceControlBusy &&
                            !nmeaState.rawCanReading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(
                        stringResource(
                            R.string.regattalink_read_diagnostic_log
                        )
                    )
                }
                if (configurationState.deviceControlSupported) {
                    Button(
                        onClick = onCanErrorTrace,
                        enabled = deviceControlEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text(
                            stringResource(
                                R.string.regattalink_can_error_trace_60s
                            )
                        )
                    }
                }
                if (configurationState.diagnosticLogLoading) {
                    Text(
                        stringResource(
                            R.string.regattalink_loading_diagnostic_log
                        ),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                configurationState.diagnosticLogEntries.forEach { entry ->
                    Text(
                        "${entry.timestamp10ms * 10} ms  ${entry.message}",
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                RegattaLinkTechnicalDetail(
                    detail = configurationState.diagnosticLogError,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (
                nmeaState.rawCanSupported &&
                configurationState.canSessionAvailable != false
            ) {
                Text(
                    text = stringResource(
                        R.string.regattalink_raw_capture_title
                    ),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    text = stringResource(
                        R.string.regattalink_raw_capture_best_effort
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                when (rawCaptureState.phase) {
                    RegattaLinkRawCapturePhase.FLUSHING ->
                        Text(
                            stringResource(
                                R.string.regattalink_raw_capture_flushing
                            ),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    RegattaLinkRawCapturePhase.CAPTURING ->
                        Text(
                            stringResource(
                                R.string.regattalink_raw_capture_active
                            ),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    RegattaLinkRawCapturePhase.COMPLETED ->
                        Text(
                            stringResource(
                                R.string.regattalink_raw_capture_completed
                            ),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    RegattaLinkRawCapturePhase.INTERRUPTED ->
                        Text(
                            stringResource(
                                R.string.regattalink_raw_capture_interrupted
                            ),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    RegattaLinkRawCapturePhase.ERROR ->
                        Text(
                            stringResource(
                                R.string.regattalink_raw_capture_failed
                            ),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    RegattaLinkRawCapturePhase.IDLE -> Unit
                }

                if (
                    rawCaptureState.phase != RegattaLinkRawCapturePhase.IDLE
                ) {
                    Text(
                        text = stringResource(
                            R.string.regattalink_raw_capture_frames,
                            rawCaptureState.frameCount
                        ),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                rawCaptureRemainingSeconds?.let { seconds ->
                    Text(
                        text = stringResource(
                            R.string.regattalink_raw_capture_remaining,
                            seconds
                        ),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (rawCaptureState.isActive) {
                    Button(
                        onClick = onStopRawCapture,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        Text(
                            stringResource(
                                R.string.regattalink_raw_capture_stop
                            )
                        )
                    }
                } else if (!rawCaptureState.hasFile) {
                    Button(
                        onClick = onStartRawCapture,
                        enabled =
                            regattaLinkRawCaptureStartAllowed(
                                connected = connected,
                                otaActive = otaActive,
                                configurationState = configurationState,
                                nmeaState = nmeaState,
                                rawCaptureState = rawCaptureState
                            ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        Text(
                            stringResource(
                                R.string.regattalink_raw_capture_start
                            )
                        )
                    }
                }

                if (
                    rawCaptureState.hasFile &&
                    !rawCaptureState.isActive
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onExportRawCapture,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(
                                    R.string.regattalink_raw_capture_export
                                )
                            )
                        }
                        Button(
                            onClick = onDiscardRawCapture,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(
                                    R.string.regattalink_raw_capture_discard
                                )
                            )
                        }
                    }
                }

                regattaLinkRuntimeMessageText(
                    userMessage = rawCaptureState.userMessage,
                    hasTechnicalError = rawCaptureState.error.isNotBlank(),
                    fallback = RegattaLinkUiMessage.RAW_CAPTURE_FAILED
                )?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                RegattaLinkTechnicalDetail(
                    detail = rawCaptureState.error,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Button(
                    onClick = onReadRawFrames,
                    enabled =
                        connected &&
                            !otaActive &&
                            !rawCaptureState.isActive &&
                            !nmeaState.rawCanReading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text(
                        if (nmeaState.rawCanReading) {
                            stringResource(
                                R.string.regattalink_reading_raw_frames
                            )
                        } else {
                            stringResource(
                                R.string.regattalink_read_raw_frames
                            )
                        }
                    )
                }

                if (nmeaState.rawFrames.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.regattalink_raw_frames_count,
                            nmeaState.rawFrames.size
                        ),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    nmeaState.rawFrames.takeLast(20).forEach { frame ->
                        Text(
                            text = String.format(
                                Locale.US,
                                "0x%08X · DLC %d · %s",
                                frame.canId,
                                frame.dlc,
                                frame.dataHex
                            ),
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
            }

            if (configurationState.deviceControlSupported) {
                TextButton(
                    onClick = onFactoryReset,
                    enabled = deviceControlEnabled,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text(stringResource(R.string.regattalink_factory_reset))
                }
            }

            RegattaLinkTechnicalDetail(
                detail = configurationState.deviceControlError,
                modifier = Modifier.padding(top = 6.dp)
            )
            regattaLinkRuntimeMessageText(
                userMessage = configurationState.userMessage,
                hasTechnicalError = configurationState.error.isNotBlank(),
                fallback = RegattaLinkUiMessage.CONFIGURATION_FAILED
            )?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            RegattaLinkTechnicalDetail(
                detail = configurationState.error,
                modifier = Modifier.padding(top = 4.dp)
            )
            regattaLinkRuntimeMessageText(
                userMessage = nmeaState.userMessage,
                hasTechnicalError = nmeaState.error.isNotBlank(),
                fallback = RegattaLinkUiMessage.NMEA_FAILED
            )?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            RegattaLinkTechnicalDetail(
                detail = nmeaState.error,
                modifier = Modifier.padding(top = 4.dp)
            )
            RegattaLinkTechnicalDetail(
                detail = state.error,
                modifier = Modifier.padding(top = 4.dp)
            )

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 12.dp)
            ) {
                Text(stringResource(R.string.close))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegattaLinkFirmwareSheet(
    state: RegattaLinkClientState,
    firmwareState: RegattaLinkFirmwareUiState,
    otaState: RegattaLinkOtaUiState,
    configurationState: RegattaLinkConfigurationState,
    rawCaptureState: RegattaLinkRawCaptureState,
    installAvailable: Boolean,
    connected: Boolean,
    onCheckFirmware: () -> Unit,
    onFirmwareSourceSelected: (RegattaLinkFirmwareSource) -> Unit,
    onInstallFirmware: () -> Unit,
    onCancelOta: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.regattalink_firmware_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )

            state.deviceInfo?.let { info ->
                telemetryValue(
                    label = stringResource(
                        R.string.regattalink_firmware_build
                    ),
                    value = info.runningBuild.toString()
                )
            }

            when (firmwareState.status) {
                RegattaLinkFirmwareStatus.IDLE -> {
                    Text(
                        text = stringResource(
                            R.string.regattalink_firmware_not_checked
                        ),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                RegattaLinkFirmwareStatus.LOADING -> {
                    Text(
                        text = stringResource(
                            R.string.regattalink_firmware_checking
                        ),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                RegattaLinkFirmwareStatus.READY -> {
                    Text(
                        text = stringResource(
                            R.string.regattalink_available_build_value,
                            firmwareState.availableBuild
                        ),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        text = when (firmwareState.direction) {
                            RegattaLinkFirmwareDirection.UPGRADE ->
                                stringResource(
                                    R.string.regattalink_direction_upgrade
                                )
                            RegattaLinkFirmwareDirection.DOWNGRADE ->
                                stringResource(
                                    R.string.regattalink_direction_downgrade
                                )
                            RegattaLinkFirmwareDirection.REINSTALL ->
                                stringResource(
                                    R.string.regattalink_direction_reinstall
                                )
                            null ->
                                stringResource(
                                    R.string.regattalink_firmware_not_checked
                                )
                        },
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = if (firmwareState.signed) {
                            stringResource(
                                R.string.regattalink_firmware_signed
                            )
                        } else {
                            stringResource(
                                R.string.regattalink_firmware_unsigned
                            )
                        },
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                RegattaLinkFirmwareStatus.ERROR -> {
                    val message =
                        firmwareState.userMessage
                            ?: RegattaLinkUiMessage.FIRMWARE_CHECK_FAILED
                    Text(
                        text = stringResource(
                            regattaLinkUiMessageResource(message)
                        ),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    RegattaLinkTechnicalDetail(
                        detail = firmwareState.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            if (firmwareState.availableSources.size > 1) {
                Text(
                    text = stringResource(
                        R.string.regattalink_firmware_source
                    ),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 14.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val sourceSelectionEnabled =
                        !otaState.isActive &&
                            !firmwareState.installPreparing &&
                            firmwareState.status !=
                            RegattaLinkFirmwareStatus.LOADING

                    if (
                        firmwareState.selectedSource ==
                        RegattaLinkFirmwareSource.STANDARD
                    ) {
                        Button(
                            onClick = {},
                            enabled = sourceSelectionEnabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(
                                    R.string.regattalink_firmware_source_standard
                                )
                            )
                        }
                    } else {
                        TextButton(
                            onClick = {
                                onFirmwareSourceSelected(
                                    RegattaLinkFirmwareSource.STANDARD
                                )
                            },
                            enabled = sourceSelectionEnabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(
                                    R.string.regattalink_firmware_source_standard
                                )
                            )
                        }
                    }

                    if (
                        firmwareState.selectedSource ==
                        RegattaLinkFirmwareSource.EVENT
                    ) {
                        Button(
                            onClick = {},
                            enabled = sourceSelectionEnabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(
                                    R.string.regattalink_firmware_source_event_beta
                                )
                            )
                        }
                    } else {
                        TextButton(
                            onClick = {
                                onFirmwareSourceSelected(
                                    RegattaLinkFirmwareSource.EVENT
                                )
                            },
                            enabled = sourceSelectionEnabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(
                                    R.string.regattalink_firmware_source_event_beta
                                )
                            )
                        }
                    }
                }
            }

            if (
                firmwareState.selectedSource ==
                    RegattaLinkFirmwareSource.EVENT &&
                RegattaLinkFirmwareSource.EVENT in
                    firmwareState.availableSources
            ) {
                Text(
                    text = stringResource(
                        R.string.regattalink_firmware_source_event_helper
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Button(
                onClick = onCheckFirmware,
                enabled =
                    connected &&
                        !configurationState.deviceControlBusy &&
                        !configurationState.diagnosticLogLoading &&
                        !firmwareState.installPreparing &&
                        firmwareState.status !=
                            RegattaLinkFirmwareStatus.LOADING,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Text(stringResource(R.string.regattalink_check_firmware))
            }

            if (
                installAvailable ||
                otaState.phase != RegattaLinkOtaPhase.IDLE
            ) {
                Text(
                    text = stringResource(R.string.regattalink_ota_title),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 18.dp)
                )

                if (otaState.phase != RegattaLinkOtaPhase.IDLE) {
                    Text(
                        text = regattaLinkOtaPhaseText(otaState.phase),
                        modifier = Modifier.padding(top = 6.dp)
                    )

                    if (
                        otaState.installedBuild.isNotBlank() &&
                        otaState.targetBuild.isNotBlank()
                    ) {
                        Text(
                            text = stringResource(
                                R.string.regattalink_ota_builds_value,
                                otaState.installedBuild,
                                otaState.targetBuild
                            ),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    if (
                        otaState.phase ==
                            RegattaLinkOtaPhase.TRANSFERRING ||
                        otaState.committedBytes > 0
                    ) {
                        LinearProgressIndicator(
                            progress = { otaState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                        )
                        Text(
                            text = stringResource(
                                R.string.regattalink_ota_progress_value,
                                otaState.committedBytes,
                                otaState.totalBytes,
                                (otaState.progress * 100f).toInt()
                            ),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    otaState.throughputKibPerSec?.let { throughput ->
                        Text(
                            text = stringResource(
                                R.string.regattalink_ota_throughput_value,
                                throughput
                            ),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    if (otaState.transport.isNotBlank()) {
                        Text(
                            text = stringResource(
                                R.string.regattalink_ota_transport_value,
                                otaState.transport
                            ),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    regattaLinkRuntimeMessageText(
                        userMessage = otaState.userMessage,
                        hasTechnicalError = otaState.error.isNotBlank(),
                        fallback = RegattaLinkUiMessage.OTA_FAILED
                    )?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    RegattaLinkTechnicalDetail(
                        detail = otaState.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (firmwareState.installPreparing) {
                    Text(
                        text = stringResource(R.string.regattalink_ota_preparing),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                if (
                    installAvailable &&
                    !firmwareState.installPreparing &&
                    !otaState.isActive &&
                    !rawCaptureState.isActive &&
                    otaState.phase !in setOf(
                        RegattaLinkOtaPhase.SUCCESS,
                        RegattaLinkOtaPhase.CANCELLED
                    )
                ) {
                    Button(
                        onClick = onInstallFirmware,
                        enabled =
                            !regattaLinkFirmwareInstallBlocked(
                                configurationState
                            ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        Text(
                            stringResource(
                                R.string.regattalink_install_firmware
                            )
                        )
                    }
                }

                if (
                    otaState.phase in setOf(
                        RegattaLinkOtaPhase.PREPARING,
                        RegattaLinkOtaPhase.STARTING,
                        RegattaLinkOtaPhase.TRANSFERRING
                    )
                ) {
                    Button(
                        onClick = onCancelOta,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        Text(
                            stringResource(
                                R.string.regattalink_cancel_update
                            )
                        )
                    }
                }
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 12.dp)
            ) {
                Text(stringResource(R.string.close))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegattaLinkImuSetupSheet(
    controlStatus: RegattaLinkDeviceControlStatus?,
    telemetryState: RegattaLinkTelemetryState,
    configurationState: RegattaLinkConfigurationState,
    boatFramePresentation: RegattaLinkBoatFramePresentation,
    controlsEnabled: Boolean,
    setUprightEnabled: Boolean,
    configEnabled: Boolean,
    deviceControlBusy: Boolean,
    deviceControlError: String,
    onSetMotionDamping: (Int) -> Unit,
    onSetMagBackgroundLearningEnabled: (Boolean) -> Unit,
    onSetHeadingTrimDeg: (Int) -> Unit,
    onRestart: () -> Unit,
    onSetUpright: () -> Unit,
    onSetImuRawPreviewEnabled: (Boolean) -> Unit,
    onSetImuRawModeEnabled: (Boolean) -> Unit,
    onAdjust: (RegattaLinkDeviceControlOpcode, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val imuAvailable = configurationState.imuSessionAvailable != false
    val magAvailable = configurationState.magSessionAvailable != false
    var dampingDraft by remember(configurationState.motionDampingSeconds) {
        mutableStateOf((configurationState.motionDampingSeconds ?: 3).toFloat())
    }
    var uprightDialogOpen by rememberSaveable { mutableStateOf(false) }
    var uprightPreviewStartedAtElapsedMs by remember {
        mutableStateOf<Long?>(null)
    }
    var uprightRawTiltSamples by remember {
        mutableStateOf<List<Double>>(emptyList())
    }
    var uprightLastRawSampleAtElapsedMs by remember {
        mutableStateOf<Long?>(null)
    }
    val rawPreview = telemetryState.rawImu
    val rawPreviewReceivedAt = telemetryState.rawImuReceivedAtElapsedMs
    val rawPreviewIsStale = rememberTelemetryStale(
        rawPreviewReceivedAt,
        REGATTALINK_RAW_IMU_PREVIEW_STALE_MS
    )

    LaunchedEffect(
        uprightDialogOpen,
        rawPreview?.sequence,
        rawPreviewReceivedAt
    ) {
        val startedAt = uprightPreviewStartedAtElapsedMs
        if (
            uprightDialogOpen &&
            startedAt != null &&
            rawPreview != null &&
            rawPreviewReceivedAt != null &&
            rawPreviewReceivedAt >= startedAt
        ) {
            regattaLinkRawImuFrontTiltDeg(rawPreview)?.let { tilt ->
                val previousReceivedAt = uprightLastRawSampleAtElapsedMs
                uprightRawTiltSamples =
                    if (
                        previousReceivedAt == null ||
                        rawPreviewReceivedAt - previousReceivedAt >
                        REGATTALINK_RAW_IMU_PREVIEW_STALE_MS
                    ) {
                        listOf(tilt)
                    } else {
                        (uprightRawTiltSamples + tilt).takeLast(5)
                    }
                uprightLastRawSampleAtElapsedMs = rawPreviewReceivedAt
            }
        }
    }

    LaunchedEffect(uprightDialogOpen, deviceControlBusy) {
        if (!uprightDialogOpen || deviceControlBusy) {
            return@LaunchedEffect
        }

        while (true) {
            delay(REGATTALINK_SENSOR_RAW_MODE_RENEW_INTERVAL_MS)
            onSetImuRawModeEnabled(true)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (uprightDialogOpen) {
                onSetImuRawModeEnabled(false)
                onSetImuRawPreviewEnabled(false)
            }
        }
    }

    val stopUprightPreview = {
        onSetImuRawModeEnabled(false)
        onSetImuRawPreviewEnabled(false)
        uprightDialogOpen = false
        uprightPreviewStartedAtElapsedMs = null
        uprightRawTiltSamples = emptyList()
        uprightLastRawSampleAtElapsedMs = null
    }

    val motionIsStale = rememberTelemetryStale(
        telemetryState.motionOneHzReceivedAtElapsedMs,
        REGATTALINK_MOTION_ONE_HZ_STALE_MS
    )
    val liveMotion = telemetryState.motionOneHz.takeIf {
        imuAvailable &&
            telemetryState.supported &&
            !telemetryState.pausedForOta &&
            !motionIsStale
    }
    val port = stringResource(R.string.regattalink_port)
    val starboard = stringResource(R.string.regattalink_starboard)
    val bowUp = stringResource(R.string.regattalink_bow_up)
    val bowDown = stringResource(R.string.regattalink_bow_down)
    val liveHeelValue = formatRegattaLinkDirectionalMeasurement(
        valueDeg = liveMotion?.heelDeg,
        positiveDirectionLabel = starboard,
        negativeDirectionLabel = port
    )
    val livePitchValue = formatRegattaLinkDirectionalMeasurement(
        valueDeg = liveMotion?.pitchDeg,
        positiveDirectionLabel = bowUp,
        negativeDirectionLabel = bowDown
    )

    ModalBottomSheet(
        onDismissRequest = {
            if (uprightDialogOpen) {
                stopUprightPreview()
            }
            onDismiss()
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.regattalink_setup_imu),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )

            val liveStatusText = when {
                !imuAvailable || !telemetryState.supported ->
                    stringResource(R.string.regattalink_unavailable)
                telemetryState.pausedForOta ->
                    stringResource(R.string.regattalink_telemetry_paused_ota)
                telemetryState.motionOneHz == null ->
                    stringResource(R.string.regattalink_telemetry_waiting)
                motionIsStale ->
                    stringResource(R.string.regattalink_telemetry_stale)
                else -> null
            }
            liveStatusText?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (
                imuAvailable &&
                configurationState.motionDampingSupported &&
                configurationState.motionDampingSeconds != null
            ) {
                val shownDamping = dampingDraft
                    .roundToInt()
                    .coerceIn(1, 10)
                Text(
                    text = stringResource(
                        R.string.regattalink_motion_damping
                    ),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 22.dp)
                )
                Text(
                    text = stringResource(
                        R.string.regattalink_motion_damping_value,
                        shownDamping
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Slider(
                    value = dampingDraft.coerceIn(1f, 10f),
                    onValueChange = { dampingDraft = it },
                    onValueChangeFinished = {
                        val submission =
                            prepareRegattaLinkConfigSliderSubmission(
                                draftValue = dampingDraft,
                                confirmedValue =
                                    configurationState.motionDampingSeconds,
                                validRange = 1..10
                            )
                        dampingDraft = submission.confirmedDraft
                        if (
                            submission.requestedValue !=
                            configurationState.motionDampingSeconds
                        ) {
                            onSetMotionDamping(submission.requestedValue)
                        }
                    },
                    valueRange = 1f..10f,
                    steps = 8,
                    enabled = configEnabled
                )
            }

            if (
                magAvailable &&
                configurationState.configWordSupported &&
                configurationState.magBackgroundLearningEnabled != null
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                R.string.regattalink_mag_background_learning
                            ),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(
                                R.string.regattalink_applies_after_restart
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked =
                            configurationState.magBackgroundLearningEnabled == true,
                        onCheckedChange =
                            onSetMagBackgroundLearningEnabled,
                        enabled = configEnabled
                    )
                }
            }

            if (
                configurationState.configRestartRequired ||
                configurationState.restartAwaitingDisconnect
            ) {
                Text(
                    text = if (configurationState.restartAwaitingDisconnect) {
                        stringResource(R.string.regattalink_restarting)
                    } else {
                        stringResource(
                            R.string.regattalink_config_restart_required
                        )
                    },
                    modifier = Modifier.padding(top = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onRestart,
                    enabled =
                        configEnabled &&
                            configurationState.deviceControlSupported &&
                            configurationState.configRestartRequired &&
                            !configurationState.restartAwaitingDisconnect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(stringResource(R.string.regattalink_restart))
                }
            }

            if (configurationState.busy) {
                Text(
                    text = stringResource(R.string.regattalink_saving),
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (imuAvailable) {
            Text(
                text = stringResource(R.string.regattalink_set_upright),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 22.dp)
            )
            Text(
                text = stringResource(R.string.regattalink_set_upright_instruction),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = stringResource(
                    R.string.regattalink_orientation_set_upright_resets
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            if (uprightDialogOpen) {
                val startedAt = uprightPreviewStartedAtElapsedMs
                val rawTilt =
                    uprightRawTiltSamples
                        .takeIf {
                            it.isNotEmpty() &&
                                !rawPreviewIsStale
                        }
                        ?.average()
                val fallbackTilt = telemetryState.fast?.pitchDeg?.takeIf {
                    val receivedAt = telemetryState.fastReceivedAtElapsedMs
                    startedAt != null &&
                        receivedAt != null &&
                        receivedAt >= startedAt
                }
                val frontTiltDeg = rawTilt ?: fallbackTilt
                val roundedTilt =
                    frontTiltDeg?.let(::roundRegattaLinkUserFacingDegrees)
                val tiltMagnitude = roundedTilt?.let { kotlin.math.abs(it) }
                val tiltDirection = when {
                    roundedTilt == null -> null
                    roundedTilt > 0 ->
                        stringResource(R.string.regattalink_arrow_tilt_up)
                    roundedTilt < 0 ->
                        stringResource(R.string.regattalink_arrow_tilt_down)
                    else ->
                        stringResource(R.string.regattalink_arrow_tilt_level)
                }
                val mountingLooksOff =
                    tiltMagnitude != null &&
                        tiltMagnitude > REGATTALINK_UPRIGHT_MAX_FRONT_TILT_DEG

                AlertDialog(
                    onDismissRequest = {
                        stopUprightPreview()
                    },
                    title = {
                        Text(stringResource(R.string.regattalink_set_upright))
                    },
                    text = {
                        Column {
                            Text(
                                stringResource(
                                    R.string.regattalink_set_upright_arrow_forward
                                )
                            )
                            Text(
                                text = if (
                                    tiltMagnitude != null &&
                                    tiltDirection != null
                                ) {
                                    stringResource(
                                        if (mountingLooksOff) {
                                            R.string.regattalink_set_upright_tilt_off
                                        } else {
                                            R.string.regattalink_set_upright_tilt
                                        },
                                        tiltMagnitude,
                                        tiltDirection,
                                        REGATTALINK_UPRIGHT_MAX_FRONT_TILT_DEG
                                    )
                                } else {
                                    stringResource(
                                        R.string.regattalink_set_upright_tilt_waiting
                                    )
                                },
                                color =
                                    if (mountingLooksOff) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                modifier = Modifier.padding(top = 12.dp)
                            )
                            Text(
                                text = stringResource(
                                    R.string.regattalink_set_upright_dialog_instruction
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onSetUpright()
                                stopUprightPreview()
                            },
                            enabled = setUprightEnabled
                        ) {
                            Text(stringResource(R.string.regattalink_set_upright))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                stopUprightPreview()
                            }
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                )
            }

            Button(
                onClick = {
                    uprightRawTiltSamples = emptyList()
                    uprightLastRawSampleAtElapsedMs = null
                    uprightPreviewStartedAtElapsedMs =
                        SystemClock.elapsedRealtime()
                    uprightDialogOpen = true
                    onSetImuRawPreviewEnabled(true)
                    onSetImuRawModeEnabled(true)
                },
                enabled = setUprightEnabled,
                modifier = Modifier.padding(top = 10.dp)
            ) {
                Text(stringResource(R.string.regattalink_set_upright))
            }

            if (
                !regattaLinkShouldShowManualOrientationControls(
                    boatFramePresentation
                )
            ) {
                Text(
                    text = stringResource(
                        R.string.regattalink_orientation_requires_boat_frame
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.regattalink_manual_orientation_correction
                    ),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 22.dp)
                )
                Text(
                    text = stringResource(
                        R.string.regattalink_installation_orientation_help
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                RegattaLinkOrientationControl(
                    status = controlStatus,
                    liveHeelValue = liveHeelValue,
                    livePitchValue = livePitchValue,
                    enabled = controlsEnabled,
                    onAdjust = onAdjust,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                )
            }


            }

            if (
                magAvailable &&
                configurationState.headingTrimSupported &&
                configurationState.headingTrimDeg != null
            ) {
                val headingTrim = configurationState.headingTrimDeg
                    .coerceIn(-180, 180)
                Text(
                    text = stringResource(R.string.regattalink_heading_trim),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 22.dp)
                )
                Text(
                    text = stringResource(
                        R.string.regattalink_heading_trim_value,
                        headingTrim
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RegattaLinkOrientationActionButton(
                        text = "−1°",
                        contentDescription =
                            stringResource(R.string.regattalink_heading_trim) +
                                " −1°",
                        enabled = configEnabled && headingTrim > -180,
                        onClick = {
                            onSetHeadingTrimDeg(headingTrim - 1)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    RegattaLinkOrientationActionButton(
                        text = "+1°",
                        contentDescription =
                            stringResource(R.string.regattalink_heading_trim) +
                                " +1°",
                        enabled = configEnabled && headingTrim < 180,
                        onClick = {
                            onSetHeadingTrimDeg(headingTrim + 1)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = stringResource(
                        R.string.regattalink_heading_trim_immediate
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (deviceControlBusy) {
                Text(
                    text = controlStatus
                        ?.takeIf { !it.phase.isTerminal }
                        ?.let { "${it.phase} · ${it.result}" }
                        ?: stringResource(R.string.regattalink_updating),
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            regattaLinkDeviceControlUiMessage(
                status = controlStatus,
                hasError = deviceControlError.isNotBlank()
            )?.let { message ->
                Text(
                    text = stringResource(
                        regattaLinkUiMessageResource(message)
                    ),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 12.dp)
            ) {
                Text(stringResource(R.string.close))
            }
        }
    }
}

@Composable
private fun RegattaLinkLiveOrientationValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun RegattaLinkOrientationControl(
    status: RegattaLinkDeviceControlStatus?,
    liveHeelValue: String,
    livePitchValue: String,
    enabled: Boolean,
    onAdjust: (RegattaLinkDeviceControlOpcode, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val port = stringResource(R.string.regattalink_port)
    val starboard = stringResource(R.string.regattalink_starboard)
    val bow = stringResource(R.string.regattalink_bow)
    val stern = stringResource(R.string.regattalink_stern)
    val correction = stringResource(R.string.regattalink_correction)

    val forwardValue = formatRegattaLinkDirectionalTrim(
        valueDeg = status?.forwardTrimDeg,
        positiveDirectionLabel = starboard,
        negativeDirectionLabel = port
    )
    val heelValue = formatRegattaLinkDirectionalTrim(
        valueDeg = status?.heelTrimDeg,
        positiveDirectionLabel = port,
        negativeDirectionLabel = starboard
    )
    val pitchValue = formatRegattaLinkDirectionalTrim(
        valueDeg = status?.pitchTrimDeg,
        positiveDirectionLabel = bow,
        negativeDirectionLabel = stern
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.regattalink_forward_alignment),
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "$correction: $forwardValue",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RegattaLinkOrientationActionButton(
                text = "↶ " + stringResource(
                    R.string.regattalink_one_degree_port
                ),
                contentDescription = stringResource(
                    R.string.regattalink_adjust_forward_port
                ),
                enabled = enabled,
                onClick = {
                    onAdjust(
                        RegattaLinkDeviceControlOpcode.ADJUST_FORWARD,
                        regattaLinkTrimDelta(
                            RegattaLinkDeviceControlOpcode.ADJUST_FORWARD,
                            RegattaLinkTrimDirection.PORT
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            )
            RegattaLinkOrientationActionButton(
                text = stringResource(
                    R.string.regattalink_one_degree_starboard
                ) + " ↷",
                contentDescription = stringResource(
                    R.string.regattalink_adjust_forward_starboard
                ),
                enabled = enabled,
                onClick = {
                    onAdjust(
                        RegattaLinkDeviceControlOpcode.ADJUST_FORWARD,
                        regattaLinkTrimDelta(
                            RegattaLinkDeviceControlOpcode.ADJUST_FORWARD,
                            RegattaLinkTrimDirection.STARBOARD
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }

        RegattaLinkLiveOrientationValue(
            label = stringResource(R.string.regattalink_pitch),
            value = livePitchValue,
            modifier = Modifier.padding(top = 18.dp)
        )
        Text(
            text = "$correction: $pitchValue",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )

        RegattaLinkOrientationActionButton(
            text = "↑ " + stringResource(R.string.regattalink_one_degree_bow),
            contentDescription = stringResource(
                R.string.regattalink_adjust_pitch_bow
            ),
            enabled = enabled,
            onClick = {
                onAdjust(
                    RegattaLinkDeviceControlOpcode.ADJUST_PITCH,
                    regattaLinkTrimDelta(
                        RegattaLinkDeviceControlOpcode.ADJUST_PITCH,
                        RegattaLinkTrimDirection.FRONT
                    )
                )
            },
            modifier = Modifier.padding(top = 8.dp)
        )

        RegattaLinkLiveOrientationValue(
            label = stringResource(R.string.regattalink_heel),
            value = liveHeelValue,
            modifier = Modifier.padding(top = 18.dp)
        )
        Text(
            text = "$correction: $heelValue",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RegattaLinkOrientationActionButton(
                text = "← " + stringResource(
                    R.string.regattalink_one_degree_port
                ),
                contentDescription = stringResource(
                    R.string.regattalink_adjust_heel_port
                ),
                enabled = enabled,
                onClick = {
                    onAdjust(
                        RegattaLinkDeviceControlOpcode.ADJUST_HEEL,
                        regattaLinkTrimDelta(
                            RegattaLinkDeviceControlOpcode.ADJUST_HEEL,
                            RegattaLinkTrimDirection.PORT
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            )

            RegattaLinkBoatTopView(
                modifier = Modifier.size(width = 124.dp, height = 176.dp)
            )

            RegattaLinkOrientationActionButton(
                text = stringResource(
                    R.string.regattalink_one_degree_starboard
                ) + " →",
                contentDescription = stringResource(
                    R.string.regattalink_adjust_heel_starboard
                ),
                enabled = enabled,
                onClick = {
                    onAdjust(
                        RegattaLinkDeviceControlOpcode.ADJUST_HEEL,
                        regattaLinkTrimDelta(
                            RegattaLinkDeviceControlOpcode.ADJUST_HEEL,
                            RegattaLinkTrimDirection.STARBOARD
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }

        RegattaLinkOrientationActionButton(
            text = "↓ " + stringResource(R.string.regattalink_one_degree_stern),
            contentDescription = stringResource(
                R.string.regattalink_adjust_pitch_stern
            ),
            enabled = enabled,
            onClick = {
                onAdjust(
                    RegattaLinkDeviceControlOpcode.ADJUST_PITCH,
                    regattaLinkTrimDelta(
                        RegattaLinkDeviceControlOpcode.ADJUST_PITCH,
                        RegattaLinkTrimDirection.BACK
                    )
                )
            },
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun RegattaLinkOrientationActionButton(
    text: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics {
            this.contentDescription = contentDescription
        }
    ) {
        Text(text)
    }
}

@Composable
private fun RegattaLinkBoatTopView(
    modifier: Modifier = Modifier
) {
    val outlineColor = MaterialTheme.colorScheme.onSurface
    val guideColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) {
        val stroke = 2.dp.toPx()
        val hull = Path().apply {
            moveTo(size.width * 0.50f, size.height * 0.06f)
            cubicTo(
                size.width * 0.33f,
                size.height * 0.17f,
                size.width * 0.22f,
                size.height * 0.39f,
                size.width * 0.20f,
                size.height * 0.70f
            )
            quadraticBezierTo(
                size.width * 0.20f,
                size.height * 0.86f,
                size.width * 0.28f,
                size.height * 0.94f
            )
            lineTo(size.width * 0.72f, size.height * 0.94f)
            quadraticBezierTo(
                size.width * 0.80f,
                size.height * 0.86f,
                size.width * 0.80f,
                size.height * 0.70f
            )
            cubicTo(
                size.width * 0.78f,
                size.height * 0.39f,
                size.width * 0.67f,
                size.height * 0.17f,
                size.width * 0.50f,
                size.height * 0.06f
            )
            close()
        }
        drawPath(
            path = hull,
            color = outlineColor,
            style = Stroke(width = stroke)
        )
        drawLine(
            color = outlineColor,
            start = Offset(size.width * 0.50f, size.height * 0.12f),
            end = Offset(size.width * 0.50f, size.height * 0.90f),
            strokeWidth = stroke
        )

        val arcSize = Size(size.width * 0.92f, size.width * 0.92f)
        val arcTopLeft = Offset(
            x = (size.width - arcSize.width) / 2f,
            y = 0f
        )
        drawArc(
            color = guideColor,
            startAngle = 205f,
            sweepAngle = -58f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = stroke)
        )
        drawArc(
            color = guideColor,
            startAngle = 335f,
            sweepAngle = 58f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = stroke)
        )
    }
}

@Composable
private fun BoatStateValues(state: RegattaLinkBoatState) {
    state.headingDeg?.let {
        telemetryValue(
            stringResource(R.string.regattalink_nmea_heading),
            formatTelemetry(it, "°")
        )
    }
    state.rateOfTurnDps?.let {
        telemetryValue(
            stringResource(R.string.regattalink_nmea_rot),
            formatTelemetry(it, "°/s")
        )
    }
    state.rollDeg?.let {
        telemetryValue(
            stringResource(R.string.regattalink_roll),
            formatTelemetry(it, "°")
        )
    }
    state.pitchDeg?.let {
        telemetryValue(
            stringResource(R.string.regattalink_pitch),
            formatTelemetry(it, "°")
        )
    }
    state.yawDeg?.let {
        telemetryValue(
            stringResource(R.string.regattalink_nmea_yaw),
            formatTelemetry(it, "°")
        )
    }
    state.speedThroughWaterMps?.let {
        telemetryValue(
            stringResource(R.string.regattalink_nmea_stw),
            formatTelemetry(it, " m/s")
        )
    }
    state.depthM?.let {
        telemetryValue(
            stringResource(R.string.regattalink_nmea_depth),
            formatTelemetry(it, " m")
        )
    }
    state.waterTemperatureC?.let {
        telemetryValue(
            stringResource(R.string.regattalink_nmea_water_temp),
            formatTelemetry(it, " °C")
        )
    }
    if (state.latitudeDeg != null && state.longitudeDeg != null) {
        telemetryValue(
            stringResource(R.string.regattalink_nmea_position),
            String.format(
                Locale.US,
                "%.6f, %.6f",
                state.latitudeDeg,
                state.longitudeDeg
            )
        )
    }
    state.cogDeg?.let {
        telemetryValue(
            stringResource(R.string.regattalink_nmea_cog),
            formatTelemetry(it, "°")
        )
    }
    state.sogMps?.let {
        telemetryValue(
            stringResource(R.string.regattalink_nmea_sog),
            formatTelemetry(it, " m/s")
        )
    }
    state.satellites?.let {
        telemetryValue(
            stringResource(R.string.regattalink_nmea_satellites),
            it.toString()
        )
    }
    state.hdop?.let {
        telemetryValue(
            stringResource(R.string.regattalink_nmea_hdop),
            formatTelemetry(it, "")
        )
    }
    state.altitudeM?.let {
        telemetryValue(
            stringResource(R.string.regattalink_nmea_altitude),
            formatTelemetry(it, " m")
        )
    }
    state.windSpeedMps?.let {
        telemetryValue(
            stringResource(R.string.regattalink_nmea_wind_speed),
            formatTelemetry(it, " m/s")
        )
    }
    state.windAngleDeg?.let {
        telemetryValue(
            stringResource(R.string.regattalink_nmea_wind_angle),
            formatTelemetry(it, "°")
        )
    }
}

@Composable
private fun telemetryValue(label: String, value: String) {
    Text(
        text = "$label: $value",
        modifier = Modifier.padding(top = 4.dp)
    )
}

private fun formatTelemetry(
    value: Double,
    suffix: String,
    decimals: Int = 2
): String = String.format(
    Locale.getDefault(),
    "%.${decimals}f%s",
    value,
    suffix
)

@Composable
private fun regattaLinkOtaPhaseText(
    phase: RegattaLinkOtaPhase
): String = when (phase) {
    RegattaLinkOtaPhase.IDLE ->
        stringResource(R.string.regattalink_ota_idle)
    RegattaLinkOtaPhase.PREPARING ->
        stringResource(R.string.regattalink_ota_preparing)
    RegattaLinkOtaPhase.STARTING ->
        stringResource(R.string.regattalink_ota_starting)
    RegattaLinkOtaPhase.TRANSFERRING ->
        stringResource(R.string.regattalink_ota_transferring)
    RegattaLinkOtaPhase.VERIFYING ->
        stringResource(R.string.regattalink_ota_verifying)
    RegattaLinkOtaPhase.REBOOTING ->
        stringResource(R.string.regattalink_ota_rebooting)
    RegattaLinkOtaPhase.RECONNECTING ->
        stringResource(R.string.regattalink_ota_reconnecting)
    RegattaLinkOtaPhase.VALIDATING ->
        stringResource(R.string.regattalink_ota_validating)
    RegattaLinkOtaPhase.SUCCESS ->
        stringResource(R.string.regattalink_ota_success)
    RegattaLinkOtaPhase.CANCELLING ->
        stringResource(R.string.regattalink_ota_cancelling)
    RegattaLinkOtaPhase.CANCELLED ->
        stringResource(R.string.regattalink_ota_cancelled)
    RegattaLinkOtaPhase.ERROR ->
        stringResource(R.string.regattalink_ota_error)
}
