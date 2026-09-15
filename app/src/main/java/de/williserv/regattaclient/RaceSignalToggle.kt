package de.williserv.regattaclient

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun RaceSignalToggle(
    raceServer: String,
    raceEvent: String,
    raceSecret: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var enabled by remember(raceServer, raceEvent, raceSecret) {
        mutableStateOf(
            RaceSignalPreferences.isEnabledForEvent(
                context = context,
                server = raceServer,
                event = raceEvent,
                secret = raceSecret
            )
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.race_acoustic_signals),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.race_acoustic_signals_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Switch(
                checked = enabled,
                onCheckedChange = { nextEnabled ->
                    RaceSignalPreferences.setEnabledForEvent(
                        context = context,
                        server = raceServer,
                        event = raceEvent,
                        secret = raceSecret,
                        enabled = nextEnabled
                    )
                    enabled = nextEnabled
                }
            )
        }
    }
}
