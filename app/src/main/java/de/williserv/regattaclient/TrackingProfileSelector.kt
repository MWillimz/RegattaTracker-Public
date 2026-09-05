package de.williserv.regattaclient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun TrackingProfileSelector(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedProfile by remember {
        mutableStateOf(TrackingProfileConfig.read(context))
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.tracking_profile_title),
            fontWeight = FontWeight.SemiBold
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedProfile == TrackingProfile.NORMAL,
                onClick = {
                    selectedProfile = TrackingProfile.NORMAL
                    TrackingProfileConfig.write(context, TrackingProfile.NORMAL)
                },
                label = { Text(stringResource(R.string.tracking_profile_normal)) },
                modifier = Modifier.weight(1f)
            )

            FilterChip(
                selected = selectedProfile == TrackingProfile.BATTERY_SAVER,
                onClick = {
                    selectedProfile = TrackingProfile.BATTERY_SAVER
                    TrackingProfileConfig.write(context, TrackingProfile.BATTERY_SAVER)
                },
                label = { Text(stringResource(R.string.tracking_profile_battery_saver)) },
                modifier = Modifier.weight(1f)
            )
        }

        Text(
            text = stringResource(R.string.tracking_profile_description),
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
