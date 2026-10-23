package de.williserv.regattaclient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RegattaLinkMagSetupSheet(
    configurationState: RegattaLinkConfigurationState,
    schemaVersion: Int,
    enabled: Boolean,
    recoveryEnabled: Boolean,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onFitAuto: () -> Unit,
    onInstallAuto: () -> Unit,
    onResumeRecovery: () -> Unit,
    onDiscardRecovery: () -> Unit,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onFit: () -> Unit,
    onInstall: () -> Unit,
    onSelectMode: (RegattaLinkMagLearnerMode) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val mag = configurationState.mag
    val metadata = mag.metadata
    val nextMode = configurationState.magLearnerMode
    var requested by remember(configurationState.configWord) { mutableStateOf(nextMode ?: RegattaLinkMagLearnerMode.AUTO_2D) }
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Int?>(null) }
    val stopCapture by rememberUpdatedState(onStopCapture)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stopCapture() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stopCapture() }
    }
    fun modeName(mode: RegattaLinkMagLearnerMode): Int = when (mode) {
        RegattaLinkMagLearnerMode.AUTO_2D -> R.string.regattalink_mag_learner_2d
        RegattaLinkMagLearnerMode.MANUAL_3D -> R.string.regattalink_mag_learner_3d
        RegattaLinkMagLearnerMode.FACTORY_ONLY -> R.string.regattalink_mag_learner_factory_only
    }
    fun decimal(value: Double) = String.format(Locale.getDefault(),"%.2f",value)
    val phaseLabel = when (mag.phase) {
        RegattaLinkMagPhase.IDLE -> R.string.regattalink_mag_phase_idle
        RegattaLinkMagPhase.READING -> R.string.regattalink_mag_phase_reading
        RegattaLinkMagPhase.CAPTURING -> R.string.regattalink_mag_phase_capturing
        RegattaLinkMagPhase.CAPTURE_READY -> R.string.regattalink_mag_phase_capture_ready
        RegattaLinkMagPhase.FITTING, RegattaLinkMagPhase.FITTING_2D -> R.string.regattalink_mag_phase_fitting
        RegattaLinkMagPhase.PROPOSAL_2D, RegattaLinkMagPhase.FIT_READY -> R.string.regattalink_mag_phase_proposal
        RegattaLinkMagPhase.EXPORTING -> R.string.regattalink_mag_phase_exporting
        RegattaLinkMagPhase.ARCHIVE_READY -> R.string.regattalink_mag_phase_archive
        RegattaLinkMagPhase.TRANSFERRING, RegattaLinkMagPhase.VALIDATING -> R.string.regattalink_mag_phase_transfer
        RegattaLinkMagPhase.PERSISTING -> R.string.regattalink_mag_phase_persisting
        RegattaLinkMagPhase.STORED -> R.string.regattalink_mag_phase_stored
        RegattaLinkMagPhase.RESTORING -> R.string.regattalink_mag_phase_restore
        RegattaLinkMagPhase.UNKNOWN -> R.string.regattalink_mag_phase_unknown
        RegattaLinkMagPhase.ERROR -> R.string.regattalink_mag_phase_error
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp,vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.regattalink_mag_setup_title),style = MaterialTheme.typography.titleLarge)
            if (schemaVersion < MAG_SCHEMA_MIN || !mag.supported) {
                Text(stringResource(R.string.regattalink_mag_firmware_required))
            } else {
                Text(stringResource(when {
                    metadata?.offSelected == true -> R.string.regattalink_mag_off
                    metadata?.activeKind == 1 -> R.string.regattalink_mag_active_auto
                    metadata?.activeKind == 3 -> R.string.regattalink_mag_active_manual
                    else -> R.string.regattalink_mag_collecting
                }))
                Text(stringResource(if (metadata?.autoStored == true) R.string.regattalink_mag_auto_installed else R.string.regattalink_mag_auto_missing))
                Text(stringResource(if (metadata?.manualStored == true) R.string.regattalink_mag_installed else R.string.regattalink_mag_not_installed))
                Text(stringResource(R.string.regattalink_mag_phase,stringResource(phaseLabel)))
                if (mag.busy && mag.phase != RegattaLinkMagPhase.UNKNOWN) {
                    LinearProgressIndicator(progress = { mag.progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                if (mag.recoveryAvailable || mag.phase == RegattaLinkMagPhase.UNKNOWN) {
                    Text(stringResource(R.string.regattalink_mag_recovery_help))
                    Button(onClick = onResumeRecovery,enabled = recoveryEnabled && !mag.operationRunning) { Text(stringResource(R.string.regattalink_mag_recover)) }
                    TextButton(onClick = { confirmation = 4 },enabled = recoveryEnabled && !mag.operationRunning) { Text(stringResource(R.string.regattalink_mag_discard)) }
                }
                TextButton(onClick = onRefresh,enabled = recoveryEnabled && !mag.busy) { Text(stringResource(R.string.regattalink_mag_refresh)) }
                Text(stringResource(R.string.regattalink_mag_capture_help))
                if (metadata?.factoryValid != true || metadata.referenceValid != true) {
                    Text(stringResource(R.string.regattalink_mag_reference_required))
                }
                if (mag.phase == RegattaLinkMagPhase.CAPTURING) {
                    Text(stringResource(R.string.regattalink_mag_samples,mag.captureSamples))
                    Button(onClick = onStopCapture,enabled = recoveryEnabled) { Text(stringResource(R.string.regattalink_mag_stop)) }
                } else {
                    Button(onClick = onStartCapture,enabled = enabled && metadata?.factoryValid == true && metadata.referenceValid) {
                        Text(stringResource(R.string.regattalink_mag_start))
                    }
                }
                if (mag.phase == RegattaLinkMagPhase.CAPTURE_READY) {
                    Button(onClick = onFitAuto,enabled = enabled) { Text(stringResource(R.string.regattalink_mag_fit_auto)) }
                }
                mag.autoFit?.let { fit ->
                    Text(stringResource(R.string.regattalink_mag_auto_quality,fit.inliers,fit.rejected,
                        decimal(fit.radiusUt),decimal(fit.rmsUt),decimal(fit.maxGapDeg),decimal(fit.downStdUt)))
                    Text(stringResource(R.string.regattalink_mag_offset,fit.offset.joinToString(", ",transform = ::decimal)))
                    Button(onClick = { confirmation = 1 },enabled = enabled && mag.phase == RegattaLinkMagPhase.PROPOSAL_2D) {
                        Text(stringResource(R.string.regattalink_mag_install_auto))
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.regattalink_mag_3d_help))
                Button(onClick = onExport,enabled = enabled) { Text(stringResource(R.string.regattalink_mag_export)) }
                mag.archive?.let { archive ->
                    Text(stringResource(R.string.regattalink_mag_coverage,archive.occupiedSectors))
                    Button(onClick = onFit,enabled = enabled && metadata?.autoStored == true && mag.phase == RegattaLinkMagPhase.ARCHIVE_READY) {
                        Text(stringResource(R.string.regattalink_mag_calculate))
                    }
                }
                mag.fit?.let { fit ->
                    Text(stringResource(R.string.regattalink_mag_quality,fit.usedSectors,fit.octants,decimal(fit.rmsUt)))
                    Text(stringResource(R.string.regattalink_mag_offset,fit.offset.joinToString(", ",transform = ::decimal)))
                    Text(stringResource(R.string.regattalink_mag_matrix,fit.matrix.toList().chunked(3).joinToString(" / ") { row -> row.joinToString(", ",transform = ::decimal) }))
                    Button(onClick = { confirmation = 2 },enabled = enabled && mag.phase == RegattaLinkMagPhase.FIT_READY) {
                        Text(stringResource(R.string.regattalink_mag_install))
                    }
                }
                if (mag.error.isNotBlank()) {
                    val reason = when {
                        mag.phase == RegattaLinkMagPhase.UNKNOWN -> R.string.regattalink_mag_recovery_help
                        mag.error.contains("gap",true) || mag.error.contains("Incomplete",true) -> R.string.regattalink_mag_error_coverage
                        mag.error.contains("level",true) -> R.string.regattalink_mag_error_tilt
                        mag.error.contains("reference",true) || mag.error.contains("context",true) -> R.string.regattalink_mag_reference_required
                        mag.errorStage.startsWith("FIT") -> R.string.regattalink_mag_error_fit
                        else -> R.string.regattalink_mag_error_operation
                    }
                    Text(stringResource(reason),color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(R.string.regattalink_mag_next_mode))
                Row {
                    TextButton(onClick = { menuExpanded = true },enabled = enabled && configurationState.configWord != null) {
                        Text(stringResource(modeName(requested)))
                    }
                    DropdownMenu(expanded = menuExpanded,onDismissRequest = { menuExpanded = false }) {
                        RegattaLinkMagLearnerMode.entries.forEach { choice ->
                            DropdownMenuItem(text = { Text(stringResource(modeName(choice))) },
                                enabled = choice != RegattaLinkMagLearnerMode.MANUAL_3D || metadata?.manualStored == true,
                                onClick = { requested = choice; menuExpanded = false })
                        }
                    }
                }
                Button(onClick = { onSelectMode(requested) },enabled = enabled && requested != nextMode &&
                    (requested != RegattaLinkMagLearnerMode.MANUAL_3D || metadata?.manualStored == true)) {
                    Text(stringResource(R.string.regattalink_mag_apply_restart))
                }
                TextButton(onClick = { confirmation = 3 },enabled = enabled && metadata?.autoStored == true) { Text(stringResource(R.string.regattalink_mag_reset)) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.regattalink_mag_close)) }
        }
    }
    confirmation?.let { action ->
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(stringResource(when(action) {
                1 -> R.string.regattalink_mag_install_auto
                2 -> R.string.regattalink_mag_confirm_install_title
                3 -> R.string.regattalink_mag_reset
                else -> R.string.regattalink_mag_discard
            })) },
            text = { Text(stringResource(when(action) {
                1 -> R.string.regattalink_mag_confirm_auto
                2 -> R.string.regattalink_mag_confirm_install_body
                3 -> R.string.regattalink_mag_confirm_reset_body
                else -> R.string.regattalink_mag_confirm_discard
            })) },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                when(action) { 1 -> onInstallAuto(); 2 -> onInstall(); 3 -> onReset(); else -> onDiscardRecovery() }
            }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
}
