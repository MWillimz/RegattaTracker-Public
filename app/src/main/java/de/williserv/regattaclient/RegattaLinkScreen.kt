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
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
    ADVANCED_DIAGNOSTICS,
    FIRMWARE
}

internal fun regattaLinkSetupDestinations(): List<RegattaLinkSetupDestination> =
    listOf(
        RegattaLinkSetupDestination.IMU,
        RegattaLinkSetupDestination.NMEA,
        RegattaLinkSetupDestination.ADVANCED_DIAGNOSTICS,
        RegattaLinkSetupDestination.FIRMWARE
    )

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
    onSetMotionDamping: (Int) -> Unit,
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
            stringResource(R.string.regattalink_name_required)
        } else {
            validateRegattaLinkDeviceName(nameDraft)
        }

    val nmeaSetupOpen =
        activeSetupDestination == RegattaLinkSetupDestination.NMEA

    LaunchedEffect(
        nmeaSetupOpen,
        nmeaState.pgnInventorySupported,
        nmeaState.pgnInventoryLoading,
        otaState.isActive,
        state.deviceAddress
    ) {
        if (!nmeaSetupOpen) {
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
                onSetUpright = {
                    onDeviceControl(
                        RegattaLinkDeviceControlOpcode.SET_UPRIGHT,
                        0
                    )
                },
                onAdjust = onDeviceControl,
                onDismiss = { activeSetupDestination = null }
            )
        }
        RegattaLinkSetupDestination.NMEA -> {
            RegattaLinkNmeaSetupSheet(
                nmeaState = nmeaState,
                connected = connected,
                otaActive = otaState.isActive,
                rawCaptureActive = rawCaptureState.isActive,
                onRefreshPgnInventory = onRefreshPgnInventory,
                onDismiss = { activeSetupDestination = null }
            )
        }
        RegattaLinkSetupDestination.ADVANCED_DIAGNOSTICS -> {
            RegattaLinkAdvancedDiagnosticsSheet(
                state = state,
                configurationState = configurationState,
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
                onDrainDiagnosticLog = onDrainDiagnosticLog,
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
                firmwareSourceAvailable = firmwareSourceAvailable,
                installAvailable = installAvailable,
                connected = connected,
                onCheckFirmware = onCheckFirmware,
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
                val openSettingsDescription =
                    stringResource(R.string.regattalink_open_settings)
                IconButton(
                    onClick = { settingsMenuExpanded = true },
                    modifier = Modifier.semantics {
                        contentDescription = openSettingsDescription
                    }
                ) {
                    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
                    Canvas(modifier = Modifier.size(28.dp)) {
                        val radius = 2.2.dp.toPx()
                        val x = size.width / 2f
                        drawCircle(
                            color = dotColor,
                            radius = radius,
                            center = Offset(x, size.height * 0.24f)
                        )
                        drawCircle(
                            color = dotColor,
                            radius = radius,
                            center = Offset(x, size.height * 0.50f)
                        )
                        drawCircle(
                            color = dotColor,
                            radius = radius,
                            center = Offset(x, size.height * 0.76f)
                        )
                    }
                }
                DropdownMenu(
                    expanded = settingsMenuExpanded,
                    onDismissRequest = { settingsMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.regattalink_setup_imu))
                        },
                        onClick = {
                            settingsMenuExpanded = false
                            activeSetupDestination =
                                RegattaLinkSetupDestination.IMU
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.regattalink_setup_nmea))
                        },
                        onClick = {
                            settingsMenuExpanded = false
                            activeSetupDestination =
                                RegattaLinkSetupDestination.NMEA
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    R.string.regattalink_advanced_diagnostics
                                )
                            )
                        },
                        onClick = {
                            settingsMenuExpanded = false
                            activeSetupDestination =
                                RegattaLinkSetupDestination.ADVANCED_DIAGNOSTICS
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    R.string.regattalink_firmware_title
                                )
                            )
                        },
                        onClick = {
                            settingsMenuExpanded = false
                            activeSetupDestination =
                                RegattaLinkSetupDestination.FIRMWARE
                        }
                    )
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

                if (state.error.isNotBlank()) {
                    Text(
                        text = state.error,
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
                                formatTelemetry(it, "°")
                            } ?: "--"
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_pitch),
                            value = motion.pitchDeg?.let {
                                formatTelemetry(it, "°")
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

                    if (telemetryState.error.isNotBlank()) {
                        Text(
                            text = telemetryState.error,
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (configurationState.error.isNotBlank()) {
                    Text(
                        text = configurationState.error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (configurationState.deviceControlBusy) {
                    Text(
                        text = configurationState.deviceControlStatus?.let {
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
                    !configurationState.factoryResetAwaitingDisconnect &&
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegattaLinkNmeaSetupSheet(
    nmeaState: RegattaLinkNmeaState,
    connected: Boolean,
    otaActive: Boolean,
    rawCaptureActive: Boolean,
    onRefreshPgnInventory: () -> Unit,
    onDismiss: () -> Unit
) {
    val nmeaAvailable =
        connected &&
            (nmeaState.boatStateSupported ||
                nmeaState.pgnInventorySupported)

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

            if (connected && nmeaState.pgnInventorySupported) {
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
                            label = "PGN ${entry.pgn}",
                            value = stringResource(
                                R.string.regattalink_last_seen_ms,
                                entry.lastSeenMs
                            )
                        )
                    }
            }

            if (nmeaState.error.isNotBlank()) {
                Text(
                    text = nmeaState.error,
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
    nmeaState: RegattaLinkNmeaState,
    rawCaptureState: RegattaLinkRawCaptureState,
    connected: Boolean,
    configEnabled: Boolean,
    otaActive: Boolean,
    onChangeName: () -> Unit,
    onSetLedBrightness: (Int) -> Unit,
    onDrainDiagnosticLog: () -> Unit,
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
                if (
                    configurationState.diagnosticLogError.isNotBlank()
                ) {
                    Text(
                        configurationState.diagnosticLogError,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            if (nmeaState.rawCanSupported) {
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
                            connected &&
                                !otaActive &&
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

                if (rawCaptureState.error.isNotBlank()) {
                    Text(
                        text = rawCaptureState.error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

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

            if (configurationState.deviceControlError.isNotBlank()) {
                Text(
                    text = configurationState.deviceControlError,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (configurationState.error.isNotBlank()) {
                Text(
                    text = configurationState.error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (nmeaState.error.isNotBlank()) {
                Text(
                    text = nmeaState.error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
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
private fun RegattaLinkFirmwareSheet(
    state: RegattaLinkClientState,
    firmwareState: RegattaLinkFirmwareUiState,
    otaState: RegattaLinkOtaUiState,
    configurationState: RegattaLinkConfigurationState,
    rawCaptureState: RegattaLinkRawCaptureState,
    firmwareSourceAvailable: Boolean,
    installAvailable: Boolean,
    connected: Boolean,
    onCheckFirmware: () -> Unit,
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

            if (!firmwareSourceAvailable) {
                Text(
                    text = stringResource(
                        R.string.regattalink_firmware_server_required
                    ),
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
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
                        Text(
                            text = firmwareState.error.ifBlank {
                                stringResource(
                                    R.string.regattalink_firmware_check_failed
                                )
                            },
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            Button(
                onClick = onCheckFirmware,
                enabled =
                    firmwareSourceAvailable &&
                        connected &&
                        !configurationState.deviceControlBusy &&
                        !configurationState.diagnosticLogLoading &&
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
    onSetUpright: () -> Unit,
    onAdjust: (RegattaLinkDeviceControlOpcode, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var dampingDraft by remember(configurationState.motionDampingSeconds) {
        mutableStateOf((configurationState.motionDampingSeconds ?: 3).toFloat())
    }
    val motionIsStale = rememberTelemetryStale(
        telemetryState.motionOneHzReceivedAtElapsedMs,
        REGATTALINK_MOTION_ONE_HZ_STALE_MS
    )
    val liveMotion = telemetryState.motionOneHz.takeIf {
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
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

            Text(
                text = stringResource(R.string.regattalink_live_orientation),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 18.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                RegattaLinkLiveOrientationValue(
                    label = stringResource(R.string.regattalink_heel),
                    value = liveHeelValue,
                    modifier = Modifier.weight(1f)
                )
                RegattaLinkLiveOrientationValue(
                    label = stringResource(R.string.regattalink_pitch),
                    value = livePitchValue,
                    modifier = Modifier.weight(1f)
                )
            }

            val liveStatusText = when {
                !telemetryState.supported ->
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

            if (configurationState.busy) {
                Text(
                    text = stringResource(R.string.regattalink_saving),
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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
            Button(
                onClick = onSetUpright,
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
                    enabled = controlsEnabled,
                    onAdjust = onAdjust,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                )
            }

            if (deviceControlBusy) {
                Text(
                    text = controlStatus?.let {
                        "${it.phase} · ${it.result}"
                    } ?: stringResource(R.string.regattalink_updating),
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            if (deviceControlError.isNotBlank()) {
                Text(
                    text = deviceControlError,
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

        Text(
            text = stringResource(R.string.regattalink_pitch),
            fontWeight = FontWeight.Medium,
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

        Text(
            text = stringResource(R.string.regattalink_heel),
            fontWeight = FontWeight.Medium,
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
