package de.williserv.regattaclient

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RegattaLinkScreen(
    state: RegattaLinkClientState,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit,
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onSearch,
                enabled = !busy && state.status != RegattaLinkConnectionStatus.CONNECTED,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.regattalink_search_connect))
            }

            Button(
                onClick = onDisconnect,
                enabled = state.status != RegattaLinkConnectionStatus.IDLE,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.regattalink_disconnect))
            }
        }

        Button(
            onClick = onBack,
            colors = primaryButtonColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp)
        ) {
            Text(stringResource(R.string.back))
        }
    }
}
