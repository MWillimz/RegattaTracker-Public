package de.williserv.regattaclient

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme

data class CourseSummary(
    val courseText: String,
    val startLineText: String,
    val finishLineText: String,
    val marksText: String
)

@Composable
fun BoatDataScreen(
    boatName: String,
    skipperName: String,
    hullColor: String,
    sailNumber: String,
    yardstick: String,
    boatType: String,
    setupConfirmed: Boolean,
    modifier: Modifier = Modifier,
    onBoatNameChange: (String) -> Unit,
    onSkipperNameChange: (String) -> Unit,
    onHullColorChange: (String) -> Unit,
    onSailNumberChange: (String) -> Unit,
    onYardstickChange: (String) -> Unit,
    onBoatTypeChange: (String) -> Unit,
    onConfirmSetup: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Boat Data",
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold
        )

        OutlinedTextField(
            value = boatName,
            onValueChange = onBoatNameChange,
            label = { Text("Boat name") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        )

        OutlinedTextField(
            value = skipperName,
            onValueChange = onSkipperNameChange,
            label = { Text("Skipper") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )

        OutlinedTextField(
            value = hullColor,
            onValueChange = onHullColorChange,
            label = { Text("Hull color") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )

        OutlinedTextField(
            value = sailNumber,
            onValueChange = onSailNumberChange,
            label = { Text("Sail number") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )

        OutlinedTextField(
            value = yardstick,
            onValueChange = onYardstickChange,
            label = { Text("Yardstick") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        OutlinedTextField(
            value = boatType,
            onValueChange = onBoatTypeChange,
            label = { Text("Boat type") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )

        Button(
            onClick = onConfirmSetup,
            colors = primaryButtonColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
        ) {
            if (setupConfirmed) {
                Text("Setup confirmed")
            } else {
                Text("Confirm Setup")
            }
        }

        Button(
            onClick = onBack,
            colors = primaryButtonColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
        ) {
            Text("Back")
        }
    }
}

@Composable
fun RaceScreen(
    inRace: Boolean,
    canEnterRace: Boolean,
    canRegisterRace: Boolean,
    raceLegalAccepted: Boolean,
    registerRaceStatusText: String,
    raceServer: String,
    raceEvent: String,
    raceSecret: String,
    raceStatusText: String,
    raceStartText: String,
    raceStopText: String,
    raceRegistered: Boolean,
    raceCourseText: String,
    raceStartLineText: String,
    raceFinishLineText: String,
    raceMarksText: String,
    currentTargetText: String,
    progressText: String,
    raceInfoText: String,
    raceShortenedText: String,
    modifier: Modifier = Modifier,
    onRaceServerChange: (String) -> Unit,
    onRaceEventChange: (String) -> Unit,
    onRaceSecretChange: (String) -> Unit,
    onRefreshRaceData: () -> Unit,
    onScanQr: () -> Unit,
    onRegisterRace: () -> Unit,
    onEnterRace: () -> Unit,
    onLeaveRace: () -> Unit,
    onShowRaceLegal: () -> Unit,
    onClearRaceSetupClick: () -> Unit,
    onBack: () -> Unit,
    seriesDisplayMetadata: SeriesDisplayMetadata? = null
) {
    val hasRaceSetup = raceEvent.isNotBlank() && raceSecret.isNotBlank() && raceServer.isNotBlank()
    val primaryBlue = MaterialTheme.colorScheme.primary
    val successGreen = MaterialTheme.colorScheme.secondary
    val dangerRed = MaterialTheme.colorScheme.error
    val warningYellow = MaterialTheme.colorScheme.tertiary
    val context = LocalContext.current
    val effectiveSeriesDisplayMetadata = seriesDisplayMetadata
        ?: loadPersistedSeriesDisplayMetadata(context)

    Column(
        modifier = modifier
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {

        if (hasRaceSetup) {
            EventSummaryCard(
                raceEvent = raceEvent,
                raceStatusText = raceStatusText,
                raceStartText = raceStartText,
                raceStopText = raceStopText,
                raceShortenedText = raceShortenedText,
                seriesDisplayMetadata = effectiveSeriesDisplayMetadata
            )

            Spacer(modifier = Modifier.height(22.dp))
        }

        when {
            !hasRaceSetup -> {
                HeaderPanel(
                    startPanelMode = "clear",
                    startPanelText = "Regatta Tracker",

                )

                Spacer(modifier = Modifier.height(80.dp))

                Button(
                    onClick = onScanQr,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = successGreen,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                ) {
                    Text(
                        text = "Scan QR Code",
                        fontSize = 18.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onBack,
                    colors = primaryButtonColors(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Back")
                }
            }

            inRace -> {
                Button(
                    onClick = onLeaveRace,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = dangerRed
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                ) {
                    Text("Retire / Finish")
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onShowRaceLegal,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (raceLegalAccepted) primaryBlue else warningYellow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (raceLegalAccepted) {
                            "Race Notice"
                        } else {
                            "Accept Notice"
                        }
                    )
                }
            }

            else -> {
                Button(
                    onClick = onRegisterRace,
                    enabled = canRegisterRace,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (raceRegistered) primaryBlue else warningYellow,
                        disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        if (raceRegistered) {
                            "Registered"
                        } else {
                            "Register for Race"
                        }
                    )
                }

                if (registerRaceStatusText.isNotBlank()) {
                    Text(
                        text = registerRaceStatusText,
                        fontSize = 14.sp,
                        color = Color(0xFF555A66),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Button(
                    onClick = onEnterRace,
                    enabled = canEnterRace,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = successGreen,
                        disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .height(56.dp)
                ) {
                    Text("Enter Race")
                }

                if (!canEnterRace) {
                    Text(
                        text = "Confirm boat setup, accept race notice, and load valid race data first.",
                        fontSize = 14.sp,
                        color = Color(0xFFB26A00),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onRefreshRaceData,
                        modifier = Modifier.weight(0.5f)
                    ) {
                        Text("Refresh")
                    }

                    Button(
                        onClick = onClearRaceSetupClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = dangerRed
                        ),
                        modifier = Modifier.weight(0.5f)
                    ) {
                        Text("Clear Race")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onShowRaceLegal,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (raceLegalAccepted) primaryBlue else warningYellow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (raceLegalAccepted) {
                            "Race Notice"
                        } else {
                            "Accept Notice"
                        }
                    )
                }
            }
        }

        if (hasRaceSetup) {
            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ServerInformationEntry(
                    server = raceServer,
                    event = raceEvent,
                    secret = raceSecret,
                    modifier = Modifier.weight(1f)
                )

                EventQrButton(
                    server = raceServer,
                    event = raceEvent,
                    secret = raceSecret,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onBack,
                colors = primaryButtonColors(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Back")
            }
        }
    }
}

@Composable
fun EventSummaryCard(
    raceEvent: String,
    raceStatusText: String,
    raceStartText: String,
    raceStopText: String,
    raceShortenedText: String,
    seriesDisplayMetadata: SeriesDisplayMetadata = SeriesDisplayMetadata()
) {
    val seriesLine = buildEventSummarySeriesLine(seriesDisplayMetadata)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Text(
                text = "Event: $raceEvent",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )

            if (seriesLine.isNotBlank()) {
                Text(
                    text = seriesLine,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Text(
                text = raceStatusText.replace("Race:", "Status:"),
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 8.dp)
            )

            Text(
                text = raceStartText,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            Text(
                text = raceStopText,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            Text(
                text = raceShortenedText
                    .replace("Bahnverkürzung:", "Course shortened:")
                    .replace("JA", "YES")
                    .replace("nein", "no"),
                fontSize = 18.sp,
                fontWeight = if (
                    raceShortenedText.contains("JA", ignoreCase = true) ||
                    raceShortenedText.contains("YES", ignoreCase = true)
                ) {
                    FontWeight.Bold
                } else {
                    FontWeight.Normal
                },
                color = if (
                    raceShortenedText.contains("JA", ignoreCase = true) ||
                    raceShortenedText.contains("YES", ignoreCase = true)
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.secondary
                },
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
