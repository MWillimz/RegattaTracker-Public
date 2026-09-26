package de.williserv.regattaclient

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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

@Composable
fun RegattaLinkScreen(
    state: RegattaLinkClientState,
    firmwareState: RegattaLinkFirmwareUiState,
    otaState: RegattaLinkOtaUiState,
    telemetryState: RegattaLinkTelemetryState,
    configurationState: RegattaLinkConfigurationState,
    nmeaState: RegattaLinkNmeaState,
    rawCaptureState: RegattaLinkRawCaptureState,
    firmwareSourceAvailable: Boolean,
    installAvailable: Boolean,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit,
    onCheckFirmware: () -> Unit,
    onInstallFirmware: () -> Unit,
    onCancelOta: () -> Unit,
    onChangeName: (String) -> Unit,
    onSetLedBrightness: (Int) -> Unit,
    onDrainDiagnosticLog: () -> Unit,
    onDeviceControl: (RegattaLinkDeviceControlOpcode, Int) -> Unit,
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
        RegattaLinkConnectionStatus.SCANNING -> stringResource(R.string.regattalink_status_scanning)
        RegattaLinkConnectionStatus.BONDING -> stringResource(R.string.regattalink_status_pairing)
        RegattaLinkConnectionStatus.CONNECTING -> stringResource(R.string.regattalink_status_connecting)
        RegattaLinkConnectionStatus.DISCOVERING -> stringResource(R.string.regattalink_status_discovering)
        RegattaLinkConnectionStatus.READING_DEVICE_INFO ->
            stringResource(R.string.regattalink_status_reading_device)
        RegattaLinkConnectionStatus.CONNECTED -> stringResource(R.string.regattalink_status_connected)
        RegattaLinkConnectionStatus.ERROR -> stringResource(R.string.regattalink_status_error)
    }

    var technicalDetailsExpanded by rememberSaveable { mutableStateOf(false) }
    var nmeaDetailsExpanded by rememberSaveable { mutableStateOf(false) }
    var pgnInventoryAutoRefreshRequested by remember(state.deviceAddress) {
        mutableStateOf(false)
    }
    var nameDialogOpen by rememberSaveable { mutableStateOf(false) }
    var resetDialogOpen by rememberSaveable { mutableStateOf(false) }
    var nameDraft by rememberSaveable { mutableStateOf("") }
    var brightnessDraft by remember(configurationState.ledBrightnessPct) {
        mutableStateOf((configurationState.ledBrightnessPct ?: 0).toFloat())
    }
    var rawCaptureNowElapsedMs by remember {
        mutableStateOf(SystemClock.elapsedRealtime())
    }

    LaunchedEffect(
        rawCaptureState.isActive,
        rawCaptureState.startedAtElapsedMs
    ) {
        while (rawCaptureState.isActive) {
            rawCaptureNowElapsedMs = SystemClock.elapsedRealtime()
            delay(250L)
        }
        rawCaptureNowElapsedMs = SystemClock.elapsedRealtime()
    }

    val rawCaptureRemainingSeconds =
        rawCaptureState.startedAtElapsedMs
            ?.takeIf { rawCaptureState.isActive }
            ?.let { startedAt ->
                val elapsed =
                    (rawCaptureNowElapsedMs - startedAt).coerceAtLeast(0L)
                ((rawCaptureState.durationMs - elapsed)
                    .coerceAtLeast(0L) + 999L) / 1000L
            }

    val connected = state.status == RegattaLinkConnectionStatus.CONNECTED
    val displayedName = configurationState.deviceName.ifBlank { state.deviceName }
    val configEnabled =
        connected &&
            !otaState.isActive &&
            !rawCaptureState.isActive &&
            !configurationState.busy
    val nameValidationError =
        if (nameDraft.isBlank()) {
            stringResource(R.string.regattalink_name_required)
        } else {
            validateRegattaLinkDeviceName(nameDraft)
        }

    LaunchedEffect(
        nmeaDetailsExpanded,
        nmeaState.pgnInventorySupported,
        nmeaState.pgnInventoryLoading,
        otaState.isActive,
        state.deviceAddress
    ) {
        if (!nmeaDetailsExpanded) {
            pgnInventoryAutoRefreshRequested = false
            return@LaunchedEffect
        }

        if (
            shouldAutoRefreshPgnInventory(
                detailsExpanded = nmeaDetailsExpanded,
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

    Column(
        modifier = modifier
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.regattalink_title),
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold
        )

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
                        if (nameValidationError != null) {
                            Text(
                                text = nameValidationError,
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = displayedName,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        if (configurationState.deviceNameSupported && connected) {
                            TextButton(
                                onClick = {
                                    nameDraft = displayedName
                                    nameDialogOpen = true
                                },
                                enabled = configEnabled
                            ) {
                                Text(stringResource(R.string.regattalink_change))
                            }
                        }
                    }
                }

                if (state.error.isNotBlank()) {
                    Text(
                        text = state.error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (telemetryState.supported) {
                    val fastIsStale = rememberTelemetryStale(
                        telemetryState.fastReceivedAtElapsedMs,
                        REGATTALINK_FAST_STALE_MS
                    )
                    val summaryIsStale = rememberTelemetryStale(
                        telemetryState.summaryReceivedAtElapsedMs,
                        REGATTALINK_SLOW_STALE_MS
                    )
                    val calibrationIsStale = rememberTelemetryStale(
                        telemetryState.calibrationReceivedAtElapsedMs,
                        REGATTALINK_SLOW_STALE_MS
                    )
                    val fastStale = telemetryState.fast != null && fastIsStale
                    val summaryStale = telemetryState.summary != null && summaryIsStale
                    val calibrationStale =
                        telemetryState.calibration != null && calibrationIsStale

                    Text(
                        text = stringResource(R.string.regattalink_motion_title),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp)
                    )

                    if (telemetryState.pausedForOta) {
                        Text(
                            text = stringResource(R.string.regattalink_telemetry_paused_ota),
                            modifier = Modifier.padding(top = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (summaryStale || fastStale) {
                        Text(
                            text = stringResource(R.string.regattalink_telemetry_stale),
                            modifier = Modifier.padding(top = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    telemetryState.summary?.let { summary ->
                        telemetryValue(
                            label = stringResource(R.string.regattalink_heel),
                            value = formatTelemetry(summary.heelFilteredDeg, "°")
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_trim),
                            value = formatTelemetry(summary.trimFilteredDeg, "°")
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_confidence),
                            value = "${summary.confidencePct} %"
                        )

                        if (technicalDetailsExpanded) {
                            telemetryValue(
                                label = stringResource(R.string.regattalink_roll_rms),
                                value = formatTelemetry(summary.rollRmsDeg, "°")
                            )
                            telemetryValue(
                                label = stringResource(R.string.regattalink_pitch_rms),
                                value = formatTelemetry(summary.pitchRmsDeg, "°")
                            )
                            telemetryValue(
                                label = stringResource(R.string.regattalink_vertical_rms),
                                value = formatTelemetry(
                                    summary.verticalAccelRmsG,
                                    " g",
                                    3
                                )
                            )
                            telemetryValue(
                                label = stringResource(
                                    R.string.regattalink_motion_intensity
                                ),
                                value = summary.motionIntensity.toString()
                            )

                            telemetryState.fast?.let { fast ->
                                Text(
                                    text = stringResource(
                                        R.string.regattalink_live_motion
                                    ),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 10.dp)
                                )
                                telemetryValue(
                                    label = stringResource(R.string.regattalink_roll),
                                    value = formatTelemetry(fast.rollDeg, "°")
                                )
                                telemetryValue(
                                    label = stringResource(R.string.regattalink_pitch),
                                    value = formatTelemetry(fast.pitchDeg, "°")
                                )
                                telemetryValue(
                                    label = stringResource(
                                        R.string.regattalink_roll_rate
                                    ),
                                    value = formatTelemetry(
                                        fast.rollRateDps,
                                        "°/s"
                                    )
                                )
                                telemetryValue(
                                    label = stringResource(
                                        R.string.regattalink_pitch_rate
                                    ),
                                    value = formatTelemetry(
                                        fast.pitchRateDps,
                                        "°/s"
                                    )
                                )
                                telemetryValue(
                                    label = stringResource(
                                        R.string.regattalink_yaw_rate
                                    ),
                                    value = formatTelemetry(
                                        fast.yawRateDps,
                                        "°/s"
                                    )
                                )
                                telemetryValue(
                                    label = stringResource(
                                        R.string.regattalink_vertical_accel
                                    ),
                                    value = formatTelemetry(
                                        fast.verticalAccelG,
                                        " g",
                                        3
                                    )
                                )
                            }
                        }
                    }

                    Text(
                        text = stringResource(R.string.regattalink_calibration_title),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp)
                    )

                    if (calibrationStale && !telemetryState.pausedForOta) {
                        Text(
                            text = stringResource(R.string.regattalink_telemetry_stale),
                            modifier = Modifier.padding(top = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    telemetryState.calibration?.let { calibration ->
                        telemetryValue(
                            label = stringResource(R.string.regattalink_boat_frame),
                            value = if (calibration.boatFrameValid) {
                                stringResource(R.string.regattalink_ready)
                            } else {
                                stringResource(R.string.regattalink_not_set)
                            }
                        )
                        if (technicalDetailsExpanded) {
                            telemetryValue(
                                label = stringResource(R.string.regattalink_gyro_bias),
                                value = if (calibration.gyroBiasValid) {
                                    stringResource(R.string.regattalink_valid)
                                } else {
                                    stringResource(R.string.regattalink_not_valid)
                                }
                            )
                            telemetryValue(
                                label = stringResource(
                                    R.string.regattalink_mounting_epoch
                                ),
                                value = calibration.mountingEpoch.toString()
                            )
                            telemetryValue(
                                label = stringResource(
                                    R.string.regattalink_calibration_revision
                                ),
                                value = calibration.calibrationRevision.toString()
                            )
                        }
                    }

                    if (telemetryState.error.isNotBlank()) {
                        Text(
                            text = telemetryState.error,
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.error
                        )
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
                            val value = brightnessDraft
                                .roundToInt()
                                .coerceIn(0, 100)
                            if (value != configurationState.ledBrightnessPct) {
                                onSetLedBrightness(value)
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
                if (connected && configurationState.deviceControlSupported) {
                    val controlEnabled = configEnabled &&
                        !configurationState.deviceControlBusy &&
                        !configurationState.diagnosticLogLoading &&
                        !nmeaState.rawCanReading
                    val controlStatus = configurationState.deviceControlStatus
                    val boatFrameValid = controlStatus?.boatFrameValid == true
                    val trimEnabled = controlEnabled && boatFrameValid
                    Text(
                        stringResource(R.string.regattalink_device_control),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        text = stringResource(
                            R.string.regattalink_set_upright_instruction
                        ),
                        modifier = Modifier.padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { onDeviceControl(RegattaLinkDeviceControlOpcode.SET_UPRIGHT, 0) },
                        enabled = controlEnabled
                    ) { Text(stringResource(R.string.regattalink_set_upright)) }

                    RegattaLinkTrimControl(
                        label = stringResource(R.string.regattalink_forward_trim),
                        value = controlStatus?.forwardTrimDeg,
                        leftLabel = stringResource(R.string.regattalink_one_degree_port),
                        leftDelta = regattaLinkTrimDelta(
                            RegattaLinkDeviceControlOpcode.ADJUST_FORWARD,
                            RegattaLinkTrimDirection.PORT
                        ),
                        rightLabel = stringResource(R.string.regattalink_one_degree_starboard),
                        rightDelta = regattaLinkTrimDelta(
                            RegattaLinkDeviceControlOpcode.ADJUST_FORWARD,
                            RegattaLinkTrimDirection.STARBOARD
                        ),
                        enabled = trimEnabled,
                        onAdjust = {
                            onDeviceControl(
                                RegattaLinkDeviceControlOpcode.ADJUST_FORWARD,
                                it
                            )
                        }
                    )
                    RegattaLinkTrimControl(
                        label = stringResource(R.string.regattalink_heel),
                        value = controlStatus?.heelTrimDeg,
                        leftLabel = stringResource(R.string.regattalink_one_degree_port),
                        leftDelta = regattaLinkTrimDelta(
                            RegattaLinkDeviceControlOpcode.ADJUST_HEEL,
                            RegattaLinkTrimDirection.PORT
                        ),
                        rightLabel = stringResource(R.string.regattalink_one_degree_starboard),
                        rightDelta = regattaLinkTrimDelta(
                            RegattaLinkDeviceControlOpcode.ADJUST_HEEL,
                            RegattaLinkTrimDirection.STARBOARD
                        ),
                        enabled = trimEnabled,
                        onAdjust = {
                            onDeviceControl(
                                RegattaLinkDeviceControlOpcode.ADJUST_HEEL,
                                it
                            )
                        }
                    )
                    RegattaLinkTrimControl(
                        label = stringResource(R.string.regattalink_pitch),
                        value = controlStatus?.pitchTrimDeg,
                        leftLabel = stringResource(R.string.regattalink_one_degree_front),
                        leftDelta = regattaLinkTrimDelta(
                            RegattaLinkDeviceControlOpcode.ADJUST_PITCH,
                            RegattaLinkTrimDirection.FRONT
                        ),
                        rightLabel = stringResource(R.string.regattalink_one_degree_back),
                        rightDelta = regattaLinkTrimDelta(
                            RegattaLinkDeviceControlOpcode.ADJUST_PITCH,
                            RegattaLinkTrimDirection.BACK
                        ),
                        enabled = trimEnabled,
                        onAdjust = {
                            onDeviceControl(
                                RegattaLinkDeviceControlOpcode.ADJUST_PITCH,
                                it
                            )
                        }
                    )

                    controlStatus?.let { status ->
                        Text("${status.phase} · ${status.result}")
                    }
                    if (configurationState.deviceControlError.isNotBlank()) {
                        Text(configurationState.deviceControlError, color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { resetDialogOpen = true }, enabled = controlEnabled) {
                        Text(stringResource(R.string.regattalink_factory_reset))
                    }
                }
                if (connected && configurationState.diagnosticLogSupported) {
                    Button(
                        onClick = onDrainDiagnosticLog,
                        enabled = configEnabled && !configurationState.diagnosticLogLoading &&
                            !configurationState.deviceControlBusy && !nmeaState.rawCanReading
                    ) { Text(stringResource(R.string.regattalink_read_diagnostic_log)) }
                    if (configurationState.diagnosticLogLoading) {
                        Text(stringResource(R.string.regattalink_loading_diagnostic_log))
                    }
                    configurationState.diagnosticLogEntries.forEach { entry ->
                        Text("${entry.timestamp10ms * 10} ms  ${entry.message}")
                    }
                    if (configurationState.diagnosticLogError.isNotBlank()) {
                        Text(configurationState.diagnosticLogError, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (configurationState.error.isNotBlank()) {
                    Text(
                        text = configurationState.error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    DetailsToggle(
                        expanded = technicalDetailsExpanded,
                        onToggle = {
                            technicalDetailsExpanded = !technicalDetailsExpanded
                        }
                    )
                    state.deviceInfo?.let { info ->
                        Text(
                            text = "${stringResource(R.string.regattalink_firmware_build)} ${info.runningBuild}",
                            modifier = Modifier.padding(top = 14.dp)
                        )
                    }
                }

                if (technicalDetailsExpanded) {
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
                            label = stringResource(R.string.regattalink_product_profile),
                            value = "${info.productId} / ${info.profileId}"
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_ota_capability),
                            value = if (info.otaAvailable) {
                                stringResource(R.string.regattalink_available)
                            } else {
                                stringResource(R.string.regattalink_unavailable)
                            }
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.regattalink_firmware_title),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp)
                )

                if (!firmwareSourceAvailable) {
                    Text(
                        text = stringResource(R.string.regattalink_firmware_server_required),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else {
                    when (firmwareState.status) {
                        RegattaLinkFirmwareStatus.IDLE -> {
                            Text(
                                text = stringResource(R.string.regattalink_firmware_not_checked),
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }

                        RegattaLinkFirmwareStatus.LOADING -> {
                            Text(
                                text = stringResource(R.string.regattalink_firmware_checking),
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }

                        RegattaLinkFirmwareStatus.READY -> {
                            Text(
                                text = stringResource(
                                    R.string.regattalink_available_build_value,
                                    firmwareState.availableBuild
                                ),
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            Text(
                                text = when (firmwareState.direction) {
                                    RegattaLinkFirmwareDirection.UPGRADE ->
                                        stringResource(R.string.regattalink_direction_upgrade)
                                    RegattaLinkFirmwareDirection.DOWNGRADE ->
                                        stringResource(R.string.regattalink_direction_downgrade)
                                    RegattaLinkFirmwareDirection.REINSTALL ->
                                        stringResource(R.string.regattalink_direction_reinstall)
                                    null -> stringResource(R.string.regattalink_firmware_not_checked)
                                },
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Text(
                                text = if (firmwareState.signed) {
                                    stringResource(R.string.regattalink_firmware_signed)
                                } else {
                                    stringResource(R.string.regattalink_firmware_unsigned)
                                },
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        RegattaLinkFirmwareStatus.ERROR -> {
                            Text(
                                text = firmwareState.error.ifBlank {
                                    stringResource(R.string.regattalink_firmware_check_failed)
                                },
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }

                Button(
                    onClick = onCheckFirmware,
                    enabled = firmwareSourceAvailable &&
                        connected &&
                        !configurationState.deviceControlBusy &&
                        !configurationState.diagnosticLogLoading &&
                        firmwareState.status != RegattaLinkFirmwareStatus.LOADING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    Text(stringResource(R.string.regattalink_check_firmware))
                }

                if (installAvailable || otaState.phase != RegattaLinkOtaPhase.IDLE) {
                    Text(
                        text = stringResource(R.string.regattalink_ota_title),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp)
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
                            otaState.phase == RegattaLinkOtaPhase.TRANSFERRING ||
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

                        if (otaState.detail.isNotBlank()) {
                            Text(
                                text = otaState.detail,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        if (otaState.error.isNotBlank()) {
                            Text(
                                text = otaState.error,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }

                    if (
                        installAvailable &&
                        !otaState.isActive &&
                        !rawCaptureState.isActive &&
                        otaState.phase !in setOf(
                            RegattaLinkOtaPhase.SUCCESS,
                            RegattaLinkOtaPhase.CANCELLED
                        )
                    ) {
                        Button(
                            onClick = onInstallFirmware,
                            enabled = !configurationState.deviceControlBusy &&
                                !configurationState.diagnosticLogLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                        ) {
                            Text(stringResource(R.string.regattalink_install_firmware))
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
                            Text(stringResource(R.string.regattalink_cancel_update))
                        }
                    }
                }
            }
        }

        if (connected) {
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
                        text = stringResource(R.string.regattalink_nmea_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    val nmeaAvailable =
                        nmeaState.boatStateSupported ||
                            nmeaState.pgnInventorySupported ||
                            nmeaState.rawCanSupported
                    Text(
                        text = if (nmeaAvailable) {
                            stringResource(R.string.regattalink_nmea_available)
                        } else {
                            stringResource(R.string.regattalink_nmea_unavailable)
                        },
                        modifier = Modifier.padding(top = 6.dp)
                    )

                    if (nmeaState.pgnInventorySupported) {
                        telemetryValue(
                            label = stringResource(R.string.regattalink_pgns_seen),
                            value = nmeaState.pgnInventory.size.toString()
                        )
                    }

                    DetailsToggle(
                        expanded = nmeaDetailsExpanded,
                        onToggle = { nmeaDetailsExpanded = !nmeaDetailsExpanded }
                    )

                    if (nmeaDetailsExpanded) {
                        if (nmeaState.pausedForOta) {
                            Text(
                                text = stringResource(
                                    R.string.regattalink_telemetry_paused_ota
                                ),
                                modifier = Modifier.padding(top = 8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (nmeaState.boatStateSupported) {
                            Text(
                                text = stringResource(
                                    R.string.regattalink_boat_state_title
                                ),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 8.dp)
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

                        if (nmeaState.pgnInventorySupported) {
                            Button(
                                onClick = onRefreshPgnInventory,
                                enabled = !otaState.isActive &&
                                    !rawCaptureState.isActive &&
                                    !nmeaState.pgnInventoryLoading,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                            ) {
                                Text(
                                    if (nmeaState.pgnInventoryLoading) {
                                        stringResource(R.string.regattalink_refreshing)
                                    } else {
                                        stringResource(
                                            R.string.regattalink_refresh_pgns
                                        )
                                    }
                                )
                            }
                            nmeaState.pgnInventory
                                .sortedBy { it.pgn }
                                .forEach { entry ->
                                    telemetryValue(
                                        label = "PGN ${entry.pgn}",
                                        value = stringResource(
                                            R.string.regattalink_last_seen_ms,
                                            entry.lastSeenMs
                                        )
                                    )
                                }
                        }

                        if (nmeaState.rawCanSupported) {
                            Text(
                                text = stringResource(
                                    R.string.regattalink_raw_capture_title
                                ),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 14.dp)
                            )
                            Text(
                                text = stringResource(
                                    R.string.regattalink_raw_capture_best_effort
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )

                            when (rawCaptureState.phase) {
                                RegattaLinkRawCapturePhase.FLUSHING -> {
                                    Text(
                                        text = stringResource(
                                            R.string.regattalink_raw_capture_flushing
                                        ),
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                                RegattaLinkRawCapturePhase.CAPTURING -> {
                                    Text(
                                        text = stringResource(
                                            R.string.regattalink_raw_capture_active
                                        ),
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                                RegattaLinkRawCapturePhase.COMPLETED -> {
                                    Text(
                                        text = stringResource(
                                            R.string.regattalink_raw_capture_completed
                                        ),
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                                RegattaLinkRawCapturePhase.INTERRUPTED -> {
                                    Text(
                                        text = stringResource(
                                            R.string.regattalink_raw_capture_interrupted
                                        ),
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                                RegattaLinkRawCapturePhase.ERROR -> {
                                    Text(
                                        text = stringResource(
                                            R.string.regattalink_raw_capture_failed
                                        ),
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                                RegattaLinkRawCapturePhase.IDLE -> Unit
                            }

                            if (
                                rawCaptureState.phase !=
                                RegattaLinkRawCapturePhase.IDLE
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
                                    enabled = connected &&
                                        !otaState.isActive &&
                                        !nmeaState.rawCanReading,
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
                                    horizontalArrangement =
                                        Arrangement.spacedBy(10.dp)
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

                            if (rawCaptureState.error.isNotBlank()) {
                                Text(
                                    text = rawCaptureState.error,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }

                            Button(
                                onClick = onReadRawFrames,
                                enabled = !otaState.isActive &&
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

                        if (nmeaState.error.isNotBlank()) {
                            Text(
                                text = nmeaState.error,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
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
                    !otaState.isActive &&
                    state.status != RegattaLinkConnectionStatus.CONNECTED,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.regattalink_search_connect))
            }

            Button(
                onClick = onDisconnect,
                enabled = !otaState.isActive &&
                    !configurationState.deviceControlBusy &&
                    state.status != RegattaLinkConnectionStatus.IDLE,
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
private fun DetailsToggle(
    expanded: Boolean,
    onToggle: () -> Unit
) {
    TextButton(
        onClick = onToggle,
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Text(
            if (expanded) {
                stringResource(R.string.regattalink_hide_details)
            } else {
                stringResource(R.string.regattalink_details)
            }
        )
    }
}

@Composable
private fun RegattaLinkTrimControl(
    label: String,
    value: Int?,
    leftLabel: String,
    leftDelta: Int,
    rightLabel: String,
    rightDelta: Int,
    enabled: Boolean,
    onAdjust: (Int) -> Unit
) {
    Text(
        text = label,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 10.dp)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = { onAdjust(leftDelta) },
            enabled = enabled,
            modifier = Modifier.weight(1f)
        ) {
            Text(leftLabel)
        }
        Text(
            text = value?.let { "${it}°" } ?: "—",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp)
        )
        Button(
            onClick = { onAdjust(rightDelta) },
            enabled = enabled,
            modifier = Modifier.weight(1f)
        ) {
            Text(rightLabel)
        }
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
