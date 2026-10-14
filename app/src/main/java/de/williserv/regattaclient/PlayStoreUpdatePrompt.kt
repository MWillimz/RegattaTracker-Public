package de.williserv.regattaclient

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.util.concurrent.atomic.AtomicBoolean

/** Application-scoped: Activity recreation must not start a second Play check. */
internal class PlayStoreUpdateCheckGate {
    private val started = AtomicBoolean(false)

    fun tryStart(): Boolean = started.compareAndSet(false, true)
}

/** A Play update notice must never preempt race operation or a higher-priority flow. */
internal fun shouldOfferPlayStoreUpdate(
    isPlayUpdateAvailable: Boolean,
    screen: Screen,
    raceTrackingActive: Boolean,
    manualTrackingActive: Boolean,
    blockingFlowActive: Boolean
): Boolean =
    isPlayUpdateAvailable &&
        screen == Screen.HOME &&
        !raceTrackingActive &&
        !manualTrackingActive &&
        !blockingFlowActive

/** Only the store page is opened; no Play-managed update/install flow is started. */
internal fun ComponentActivity.openOwnPlayStoreListing() {
    val id = packageName
    val market = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=${Uri.encode(id)}")
    ).setPackage("com.android.vending")
    try {
        startActivity(market)
    } catch (_: ActivityNotFoundException) {
        openOwnPlayStoreListingInBrowser(id)
    } catch (_: SecurityException) {
        openOwnPlayStoreListingInBrowser(id)
    }
}

private fun ComponentActivity.openOwnPlayStoreListingInBrowser(id: String) {
    val url = Uri.parse("https://play.google.com/store/apps/details")
        .buildUpon()
        .appendQueryParameter("id", id)
        .build()
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
}

@Composable
internal fun PlayStoreUpdateDialog(
    onUpdate: () -> Unit,
    onLater: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.play_store_update_title)) },
        text = { Text(stringResource(R.string.play_store_update_message)) },
        confirmButton = {
            TextButton(onClick = onUpdate) {
                Text(stringResource(R.string.play_store_update_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text(stringResource(R.string.play_store_update_later))
            }
        }
    )
}
