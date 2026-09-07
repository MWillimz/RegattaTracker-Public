package de.williserv.regattaclient

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

@Composable
internal fun EventUpdateRecommendedDialog(
    onContinue: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(stringResource(R.string.event_update_recommended_title))
        },
        text = {
            Text(stringResource(R.string.event_update_recommended_message))
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.continue_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
internal fun EventUpdateRequiredDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.event_update_required_title))
        },
        text = {
            Text(stringResource(R.string.event_update_required_message))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        }
    )
}
