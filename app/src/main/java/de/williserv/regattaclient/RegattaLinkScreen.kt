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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun RegattaLinkScreen(
    state: RegattaLinkClientState,
    firmwareState: RegattaLinkFirmwareUiState,
    otaState: RegattaLinkOtaUiState,
    telemetryState: RegattaLinkTelemetryState,
    firmwareSourceAvailable: Boolean,
    installAvailable: Boolean,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit,
    onCheckFirmware: () -> Unit,
    onInstallFirmware: () -> Unit,
    onCancelOta: () -> Unit,
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
    var telemetryNowElapsedMs by remember {
        mutableLongStateOf(SystemClock.elapsedRealtime())
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            telemetryNowElapsedMs = SystemClock.elapsedRealtime()
        }
    }

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

                if (state.deviceName.isNotBlank()) {
                    Text(
                        text = state.deviceName,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (state.deviceAddress.isNotBlank()) {
                    Text(
                        text = stringResource(
                            R.string.regattalink_address_value,
                            state.deviceAddress
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.error.isNotBlank()) {
                    Text(
                        text = state.error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                state.deviceInfo?.let { info ->
                    Text(
                        text = stringResource(
                            R.string.regattalink_stable_id_value,
                            info.stableId
                        ),
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        text = stringResource(
                            R.string.regattalink_build_value,
                            info.runningBuild.toString()
                        ),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = stringResource(
                            R.string.regattalink_protocol_value,
                            info.protocolMajor,
                            info.protocolMinor
                        ),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = stringResource(
                            R.string.regattalink_product_profile_value,
                            info.productId,
                            info.profileId
                        ),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = if (info.otaAvailable) {
                            stringResource(R.string.regattalink_ota_available)
                        } else {
                            stringResource(R.string.regattalink_ota_unavailable)
                        },
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        if (telemetryState.supported) {
            val fastStale = telemetryState.fast != null &&
                !isRegattaLinkTelemetryFresh(
                    telemetryState.fastReceivedAtElapsedMs,
                    REGATTALINK_FAST_STALE_MS,
                    telemetryNowElapsedMs
                )
            val summaryStale = telemetryState.summary != null &&
                !isRegattaLinkTelemetryFresh(
                    telemetryState.summaryReceivedAtElapsedMs,
                    REGATTALINK_SLOW_STALE_MS,
                    telemetryNowElapsedMs
                )
            val calibrationStale = telemetryState.calibration != null &&
                !isRegattaLinkTelemetryFresh(
                    telemetryState.calibrationReceivedAtElapsedMs,
                    REGATTALINK_SLOW_STALE_MS,
                    telemetryNowElapsedMs
                )

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
                        text = stringResource(R.string.regattalink_motion_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (telemetryState.pausedForOta) {
                        Text(
                            text = stringResource(R.string.regattalink_telemetry_paused_ota),
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (summaryStale || fastStale) {
                        Text(
                            text = stringResource(R.string.regattalink_telemetry_stale),
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (telemetryState.error.isNotBlank()) {
                        Text(
                            text = telemetryState.error,
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    telemetryState.summary?.let { summary ->
                        Text(
                            text = stringResource(
                                R.string.regattalink_learning_confidence,
                                summary.confidencePct
                            ),
                            modifier = Modifier.padding(top = 10.dp)
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_heel),
                            value = formatTelemetry(summary.heelFilteredDeg, "°")
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_trim),
                            value = formatTelemetry(summary.trimFilteredDeg, "°")
                        )
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
                            value = formatTelemetry(summary.verticalAccelRmsG, " g", 3)
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_motion_intensity),
                            value = summary.motionIntensity.toString()
                        )
                    } ?: Text(
                        text = stringResource(R.string.regattalink_telemetry_waiting),
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    telemetryState.fast?.let { fast ->
                        Text(
                            text = stringResource(R.string.regattalink_live_motion),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 12.dp)
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
                            label = stringResource(R.string.regattalink_roll_rate),
                            value = formatTelemetry(fast.rollRateDps, "°/s")
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_pitch_rate),
                            value = formatTelemetry(fast.pitchRateDps, "°/s")
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_yaw_rate),
                            value = formatTelemetry(fast.yawRateDps, "°/s")
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_vertical_accel),
                            value = formatTelemetry(fast.verticalAccelG, " g", 3)
                        )
                    }
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
                        text = stringResource(R.string.regattalink_calibration_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (calibrationStale && !telemetryState.pausedForOta) {
                        Text(
                            text = stringResource(R.string.regattalink_telemetry_stale),
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    telemetryState.calibration?.let { calibration ->
                        telemetryValue(
                            label = stringResource(R.string.regattalink_confidence_overall),
                            value = "${calibration.overallConfidencePct} %"
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_confidence_forward),
                            value = "${calibration.forwardConfidencePct} %"
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_confidence_roll),
                            value = "${calibration.rollConfidencePct} %"
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_learner_state),
                            value = when (calibration.learnerState) {
                                1 -> stringResource(R.string.regattalink_learner_turn)
                                2 -> stringResource(R.string.regattalink_learner_post)
                                else -> stringResource(R.string.regattalink_learner_idle)
                            }
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_gyro_bias),
                            value = if (calibration.gyroBiasValid) {
                                stringResource(R.string.regattalink_valid)
                            } else {
                                stringResource(R.string.regattalink_learning)
                            }
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_boat_frame),
                            value = if (calibration.boatFrameValid) {
                                stringResource(R.string.regattalink_valid)
                            } else {
                                stringResource(R.string.regattalink_learning)
                            }
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_positive_maneuvers),
                            value = calibration.positiveManeuvers.toString()
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_negative_maneuvers),
                            value = calibration.negativeManeuvers.toString()
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_roll_pairs),
                            value = calibration.rollPairObservations.toString()
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_contradictions),
                            value = calibration.contradictoryManeuvers.toString()
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_mounting_epoch),
                            value = calibration.mountingEpoch.toString()
                        )
                        telemetryValue(
                            label = stringResource(R.string.regattalink_calibration_revision),
                            value = calibration.calibrationRevision.toString()
                        )
                    } ?: Text(
                        text = stringResource(R.string.regattalink_telemetry_waiting),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
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
                    text = stringResource(R.string.regattalink_firmware_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )

                if (!firmwareSourceAvailable) {
                    Text(
                        text = stringResource(R.string.regattalink_firmware_server_required),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    when (firmwareState.status) {
                        RegattaLinkFirmwareStatus.IDLE -> {
                            Text(
                                text = stringResource(R.string.regattalink_firmware_not_checked),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        RegattaLinkFirmwareStatus.LOADING -> {
                            Text(
                                text = stringResource(R.string.regattalink_firmware_checking),
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
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }

                Button(
                    onClick = onCheckFirmware,
                    enabled = firmwareSourceAvailable &&
                        state.status == RegattaLinkConnectionStatus.CONNECTED &&
                        firmwareState.status != RegattaLinkFirmwareStatus.LOADING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text(stringResource(R.string.regattalink_check_firmware))
                }
            }
        }

        if (installAvailable || otaState.phase != RegattaLinkOtaPhase.IDLE) {
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
                        text = stringResource(R.string.regattalink_ota_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (otaState.phase != RegattaLinkOtaPhase.IDLE) {
                        Text(
                            text = regattaLinkOtaPhaseText(otaState.phase),
                            modifier = Modifier.padding(top = 8.dp)
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
                        otaState.phase !in setOf(
                            RegattaLinkOtaPhase.SUCCESS,
                            RegattaLinkOtaPhase.CANCELLED
                        )
                    ) {
                        Button(
                            onClick = onInstallFirmware,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
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
                                .padding(top = 12.dp)
                        ) {
                            Text(stringResource(R.string.regattalink_cancel_update))
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
