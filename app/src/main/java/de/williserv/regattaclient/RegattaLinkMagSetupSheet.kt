package de.williserv.regattaclient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RegattaLinkMagSetupSheet(
    configurationState: RegattaLinkConfigurationState,
    schemaVersion: Int,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onFit: () -> Unit,
    onInstall: () -> Unit,
    onSelectMode: (RegattaLinkMagLearnerMode) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val mag = configurationState.mag
    val status = mag.status
    val nextMode = configurationState.magLearnerMode
    var requested by remember(configurationState.configWord) {
        mutableStateOf(nextMode ?: RegattaLinkMagLearnerMode.AUTO_2D)
    }
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmInstall by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    fun modeName(mode: RegattaLinkMagLearnerMode): Int = when (mode) {
        RegattaLinkMagLearnerMode.AUTO_2D -> R.string.regattalink_mag_learner_2d
        RegattaLinkMagLearnerMode.MANUAL_3D -> R.string.regattalink_mag_learner_3d
        RegattaLinkMagLearnerMode.FACTORY_ONLY -> R.string.regattalink_mag_learner_factory_only
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.regattalink_mag_setup_title),
                style = MaterialTheme.typography.titleLarge)
            if (schemaVersion < MAG_SCHEMA_MIN || !mag.supported) {
                Text(stringResource(R.string.regattalink_mag_firmware_required))
            } else {
                val active = when (status?.activeKind) {
                    1 -> R.string.regattalink_mag_active_auto
                    3 -> R.string.regattalink_mag_active_manual
                    else -> R.string.regattalink_mag_collecting
                }
                Text(stringResource(active))
                Text(stringResource(R.string.regattalink_mag_turns,
                    status?.completedTurns ?: 0))
                Text(stringResource(
                    if (status?.installed3d == true) R.string.regattalink_mag_installed
                    else R.string.regattalink_mag_not_installed
                ))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRefresh, enabled = enabled) {
                        Text(stringResource(R.string.regattalink_mag_refresh))
                    }
                    Button(onClick = onExport, enabled = enabled) {
                        Text(stringResource(R.string.regattalink_mag_export))
                    }
                }
                if (mag.phase in setOf(
                        RegattaLinkMagPhase.EXPORTING, RegattaLinkMagPhase.TRANSFERRING,
                        RegattaLinkMagPhase.VALIDATING, RegattaLinkMagPhase.PERSISTING
                    )) {
                    LinearProgressIndicator(progress = { mag.progress / 100f },
                        modifier = Modifier.fillMaxWidth())
                }
                Text(stringResource(R.string.regattalink_mag_phase,
                    mag.phase.name.replace('_', ' ')))
                mag.archive?.let { archive ->
                    Text(stringResource(R.string.regattalink_mag_coverage,
                        archive.occupiedSectors))
                    Button(onClick = onFit,
                        enabled = enabled && mag.phase == RegattaLinkMagPhase.ARCHIVE_READY) {
                        Text(stringResource(R.string.regattalink_mag_calculate))
                    }
                }
                mag.fit?.let { fit ->
                    Text(stringResource(R.string.regattalink_mag_quality,
                        fit.usedSectors, fit.octants,
                        String.format(Locale.US, "%.2f", fit.rmsUt)))
                    Button(onClick = { confirmInstall = true },
                        enabled = enabled && mag.phase == RegattaLinkMagPhase.FIT_READY) {
                        Text(stringResource(R.string.regattalink_mag_install))
                    }
                }
                if (mag.error.isNotBlank()) {
                    Text(mag.errorStage + ": " + mag.error,
                        color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(R.string.regattalink_mag_next_mode))
                Row {
                    TextButton(onClick = { menuExpanded = true },
                        enabled = enabled && configurationState.configWord != null) {
                        Text(stringResource(modeName(requested)))
                    }
                    DropdownMenu(expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }) {
                        RegattaLinkMagLearnerMode.entries.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(stringResource(modeName(choice))) },
                                enabled = choice != RegattaLinkMagLearnerMode.MANUAL_3D ||
                                    status?.installed3d == true,
                                onClick = { requested = choice; menuExpanded = false }
                            )
                        }
                    }
                }
                Button(
                    onClick = { onSelectMode(requested) },
                    enabled = enabled && requested != nextMode &&
                        (requested != RegattaLinkMagLearnerMode.MANUAL_3D ||
                            status?.installed3d == true)
                ) {
                    Text(stringResource(R.string.regattalink_mag_apply_restart))
                }
                TextButton(onClick = { confirmReset = true }, enabled = enabled) {
                    Text(stringResource(R.string.regattalink_mag_reset))
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.regattalink_mag_close))
            }
        }
    }
    if (confirmInstall) {
        AlertDialog(
            onDismissRequest = { confirmInstall = false },
            title = { Text(stringResource(R.string.regattalink_mag_confirm_install_title)) },
            text = { Text(stringResource(R.string.regattalink_mag_confirm_install_body)) },
            confirmButton = {
                TextButton(onClick = { confirmInstall = false; onInstall() }) {
                    Text(stringResource(R.string.regattalink_mag_install))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmInstall = false }) {
                    Text(stringResource(R.string.regattalink_mag_close))
                }
            }
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.regattalink_mag_reset)) },
            text = { Text(stringResource(R.string.regattalink_mag_confirm_reset_body)) },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; onReset() }) {
                    Text(stringResource(R.string.regattalink_mag_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.regattalink_mag_close))
                }
            }
        )
    }
}
