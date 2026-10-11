package de.williserv.regattaclient

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource

@Composable
fun RaceLegalScreen(
    raceEvent: String,
    legalText: String,
    statusText: String,
    modifier: Modifier = Modifier,
    onAccept: () -> Unit,
    onBack: () -> Unit,
    onVisitEventPage: () -> Unit,
    declineNewEvent: Boolean = false,
    accepting: Boolean = false,
    actionStatusText: String = ""
) {
    val noLegalTextLoaded = stringResource(R.string.no_legal_text_loaded)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text(
            text = stringResource(R.string.race_notice),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (raceEvent.isBlank()) stringResource(R.string.event_unknown) else stringResource(R.string.event_value, raceEvent),
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = legalText.ifBlank { statusText.ifBlank { noLegalTextLoaded } },
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (actionStatusText.isNotBlank()) {
            Text(
                text = actionStatusText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Button(
            onClick = onAccept,
            enabled = legalText.isNotBlank() && !accepting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.i_accept))
        }

        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        ) {
            Text(stringResource(
                if (declineNewEvent) R.string.decline_race_notice else R.string.back
            ))
        }

        Button(
            onClick = onVisitEventPage,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        ) {
            Text(stringResource(R.string.visit_event_page))
        }
    }
}