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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalResources

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
    onConfirmSetup: (BoatSetupValues) -> Unit,
    onRegattaLink: () -> Unit,
    onBack: () -> Unit
) {
    val confirmedValues = BoatSetupValues(
        boatName = boatName,
        skipperName = skipperName,
        hullColor = hullColor,
        sailNumber = sailNumber,
        yardstick = yardstick,
        boatType = boatType
    )
    // By design: unconfirmed boat-data edits are screen-local and are discarded when
    // navigating away (including RegattaLink). Only Confirm Setup persists them.
    // See DOCUMENTATION.md ("Boat data draft semantics") and issue #221.
    var draft by remember(
        boatName,
        skipperName,
        hullColor,
        sailNumber,
        yardstick,
        boatType
    ) {
        mutableStateOf(confirmedValues)
    }
    val draftMatchesConfirmed = draft == confirmedValues

    Column(
        modifier = modifier
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.boat_data),
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold
        )

        OutlinedTextField(
            value = draft.boatName,
            onValueChange = { draft = draft.copy(boatName = it) },
            label = { Text(stringResource(R.string.boat_name)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        )

        OutlinedTextField(
            value = draft.skipperName,
            onValueChange = { draft = draft.copy(skipperName = it) },
            label = { Text(stringResource(R.string.skipper)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )

        OutlinedTextField(
            value = draft.hullColor,
            onValueChange = { draft = draft.copy(hullColor = it) },
            label = { Text(stringResource(R.string.hull_color)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )

        OutlinedTextField(
            value = draft.sailNumber,
            onValueChange = { draft = draft.copy(sailNumber = it) },
            label = { Text(stringResource(R.string.sail_number)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )

        OutlinedTextField(
            value = draft.yardstick,
            onValueChange = { draft = draft.copy(yardstick = it) },
            label = { Text(stringResource(R.string.yardstick)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        OutlinedTextField(
            value = draft.boatType,
            onValueChange = { draft = draft.copy(boatType = it) },
            label = { Text(stringResource(R.string.boat_type)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )

        TrackingProfileSelector(
            modifier = Modifier.padding(top = 20.dp)
        )

        Button(
            onClick = onRegattaLink,
            colors = primaryButtonColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            Text(stringResource(R.string.regattalink_title))
        }

        Button(
            onClick = {
                if (isBoatSetupValid(draft)) {
                    onConfirmSetup(draft)
                }
            },
            colors = primaryButtonColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
        ) {
            if (setupConfirmed && draftMatchesConfirmed) {
                Text(stringResource(R.string.setup_confirmed))
            } else {
                Text(stringResource(R.string.confirm_setup))
            }
        }

        Button(
            onClick = onBack,
            colors = primaryButtonColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
        ) {
            Text(stringResource(R.string.back))
        }
    }
}

@Composable
fun RetirementTrackingNotice(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(
            text = stringResource(R.string.retire_reported_tracking_active),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp)
        )
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
    raceStartEpochMillis: Long?,
    raceEntryNowEpochMillis: Long,
    raceStopText: String,
    raceRegistered: Boolean,
    retirementReported: Boolean,
    retirementStatusText: String,
    raceCourseText: String,
    raceStartLineText: String,
    raceFinishLineText: String,
    raceMarksText: String,
    currentTargetText: String,
    progressText: String,
    raceInfoText: String,
    raceShortenedText: String,
    raceShortened: Boolean,
    modifier: Modifier = Modifier,
    onRefreshRaceData: () -> Unit,
    onScanQr: () -> Unit,
    onRegisterRace: () -> Unit,
    onEnterRace: () -> Unit,
    onLeaveRace: () -> Unit,
    onShowRaceLegal: () -> Unit,
    onClearRaceSetupClick: () -> Unit,
    onBack: () -> Unit,
    seriesDisplayMetadata: SeriesDisplayMetadata = SeriesDisplayMetadata()
) {
    val hasRaceSetup = raceEvent.isNotBlank() && raceSecret.isNotBlank() && raceServer.isNotBlank()
    val enterRaceTimeAvailable = isEnterRaceTimeAvailable(
        raceStartEpochMillis = raceStartEpochMillis,
        nowEpochMillis = raceEntryNowEpochMillis
    )
    val primaryBlue = MaterialTheme.colorScheme.primary
    val successGreen = MaterialTheme.colorScheme.secondary
    val dangerRed = MaterialTheme.colorScheme.error
    val warningYellow = MaterialTheme.colorScheme.tertiary

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
                raceShortened = raceShortened,
                seriesDisplayMetadata = seriesDisplayMetadata
            )

            RaceSignalToggle(
                raceServer = raceServer,
                raceEvent = raceEvent,
                raceSecret = raceSecret,
                modifier = Modifier.padding(top = 12.dp)
            )

            Spacer(modifier = Modifier.height(22.dp))
        }

        when {
            !hasRaceSetup -> {
                HeaderPanel(
                    startPanelMode = "clear",
                    startPanelText = stringResource(R.string.app_name),

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
                        text = stringResource(R.string.scan_qr_code),
                        fontSize = 18.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onBack,
                    colors = primaryButtonColors(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.back))
                }
            }

            inRace -> {
                if (retirementReported) {
                    RetirementTrackingNotice()
                    Spacer(modifier = Modifier.height(12.dp))
                }
                if (retirementStatusText.isNotBlank()) {
                    Text(
                        text = retirementStatusText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }

                Button(
                    onClick = onLeaveRace,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = dangerRed
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                ) {
                    Text(stringResource(R.string.leave_race))
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
                            stringResource(R.string.race_notice)
                        } else {
                            stringResource(R.string.accept_notice)
                        }
                    )
                }
            }

            else -> {
                Button(
                    onClick = onEnterRace,
                    enabled = canEnterRace && enterRaceTimeAvailable,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = successGreen,
                        disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(stringResource(R.string.enter_race))
                }

                when {
                    !canEnterRace -> {
                        Text(
                            text = stringResource(R.string.enter_race_requirements),
                            fontSize = 14.sp,
                            color = Color(0xFFB26A00),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    !enterRaceTimeAvailable -> {
                        Text(
                            text = stringResource(R.string.enter_race_available_24h),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
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
                        Text(stringResource(R.string.refresh))
                    }

                    Button(
                        onClick = onClearRaceSetupClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = dangerRed
                        ),
                        modifier = Modifier.weight(0.5f)
                    ) {
                        Text(stringResource(R.string.clear_race))
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
                            stringResource(R.string.race_notice)
                        } else {
                            stringResource(R.string.accept_notice)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = onRegisterRace,
                    enabled = canRegisterRace,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryBlue,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (raceRegistered) {
                            stringResource(R.string.registered_with_race_committee)
                        } else {
                            stringResource(R.string.register_with_race_committee)
                        }
                    )
                }

                if (raceRegistered) {
                    Text(
                        text = stringResource(R.string.remember_enter_race),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else if (registerRaceStatusText.isNotBlank()) {
                    Text(
                        text = registerRaceStatusText,
                        fontSize = 14.sp,
                        color = Color(0xFF555A66),
                        modifier = Modifier.padding(top = 6.dp)
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
                Text(stringResource(R.string.back))
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
    raceShortened: Boolean,
    seriesDisplayMetadata: SeriesDisplayMetadata = SeriesDisplayMetadata()
) {
    val resources = LocalResources.current
    val seriesLine = buildEventSummarySeriesLine(
        seriesDisplayMetadata = seriesDisplayMetadata,
        orderFormatter = { occurrence, planned ->
            resources.getString(R.string.series_order, occurrence, planned)
        }
    )

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
                text = stringResource(R.string.event_value, raceEvent),
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
                text = stringResource(
                    R.string.status_value,
                    localizedRaceStatusCode(
                        raceStatusText = raceStatusText,
                        racePrefix = stringResource(R.string.race_prefix),
                        loadedText = stringResource(R.string.status_loaded),
                        plannedText = stringResource(R.string.status_planned),
                        racingText = stringResource(R.string.status_racing),
                        startedText = stringResource(R.string.status_started),
                        finishedText = stringResource(R.string.status_finished),
                        postponedText = stringResource(R.string.status_postponed),
                        cancelledText = stringResource(R.string.status_cancelled)
                    )
                ),
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
                text = raceShortenedText,
                fontSize = 18.sp,
                fontWeight = if (raceShortened) FontWeight.Bold else FontWeight.Normal,
                color = if (raceShortened) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.secondary
                },
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
