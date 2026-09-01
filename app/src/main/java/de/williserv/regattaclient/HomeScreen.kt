package de.williserv.regattaclient

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable

import androidx.compose.material3.MaterialTheme
import de.williserv.regattaclient.ui.theme.RegattaBlue
import de.williserv.regattaclient.ui.theme.RegattaGreen
import de.williserv.regattaclient.ui.theme.RegattaOrange
import de.williserv.regattaclient.ui.theme.RegattaRed

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay

private val HomeGapSmall = 14.dp
private val HomeGapMedium = 14.dp
private val HomeGapLarge = 14.dp
private val HomeBottomGap = 60.dp

@Composable
fun primaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
)
@Composable
fun HomeScreen(
    inRace: Boolean,
    manualTracking: Boolean,
    setupConfirmed: Boolean,
    statusText: String,
    rowCountText: String,
    uploadStatusText: String,
    debugErrorText: String,
    serviceStatusText: String,
    raceStatusText: String,
    raceEvent: String,
    raceStartText: String,
    raceStopText: String,
    raceCourseText: String,
    raceStartLineText: String,
    raceFinishLineText: String,
    raceMarksText: String,
    currentTargetText: String,
    progressText: String,
    boatRaceStatusText: String,
    raceDataReady: Boolean,
    dtlText: String,
    ttlText: String,
    ocsText: String,
    raceInfoText: String,
    raceShortenedText: String,
    raceStartFlags: RaceStartFlags,
    millisToStart: Long?,
    startPanelText: String,
    startPanelMode: String,
    lastCsvLine: String,
    cogText: String,
    sogText: String,
    gpsAccuracyText: String,
    gpsColor: Color,
    showClearConfirmDialog: Boolean,
    showAdvanced: Boolean,
    modifier: Modifier = Modifier,
    onBoatData: () -> Unit,
    onRace: () -> Unit,
    onCourse: () -> Unit,
    onMap: () -> Unit,
    onResults: () -> Unit,
    onLegal: () -> Unit,
    onOcsPanelClick: () -> Unit,
    onToggleManualTracking: () -> Unit,
    onExport: () -> Unit,
    onClearOldDataClick: () -> Unit,
    onConfirmClearOldData: () -> Unit,
    onCancelClearOldData: () -> Unit,
    onToggleAdvanced: () -> Unit,
    seriesDisplayMetadata: SeriesDisplayMetadata = SeriesDisplayMetadata()
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val workerUploadStatus = produceState(initialValue = "", context) {
        val prefs = context.getSharedPreferences("regatta_local_status", Context.MODE_PRIVATE)
        while (true) {
            value = prefs.getString(TelemetryUploadStatusStore.STATUS_KEY, "").orEmpty()
            delay(1000L)
        }
    }.value
    val localizedWorkerStatus = when (workerUploadStatus) {
        TelemetryUploadStatusStore.ACTIVE -> stringResource(R.string.status_active)
        TelemetryUploadStatusStore.WAITING -> stringResource(R.string.status_waiting)
        TelemetryUploadStatusStore.TEMPORARY_ERROR -> stringResource(R.string.status_temporary_error)
        TelemetryUploadStatusStore.ALL_SENT -> stringResource(R.string.status_all_sent)
        else -> workerUploadStatus
    }
    val advancedUploadStatusText = mergeTelemetryUploadStatusText(
        pendingStatusText = uploadStatusText,
        workerStatus = localizedWorkerStatus,
        uploadWorkerPending = { worker, pending ->
            resources.getString(R.string.upload_worker_pending, worker, pending)
        },
        uploadWorker = { worker ->
            resources.getString(R.string.upload_worker, worker)
        }
    )

    val uploadColor = uploadStatusColor(
        uploadStatusText = uploadStatusText,
        inRace = inRace,
        disabledColor = MaterialTheme.colorScheme.outlineVariant
    )

    val showCourseShortened =
        raceShortenedText == stringResource(R.string.course_shortened_yes) &&
                !raceStatusText.contains("finished", ignoreCase = true) &&
                !raceStatusText.contains("cancelled", ignoreCase = true)

    val raceColor = raceStatusColor(raceStatusText, inRace)
    val racePrefix = stringResource(R.string.race_prefix)
    val startPrefix = stringResource(R.string.start_prefix)
    val infoPrefix = stringResource(R.string.info_prefix)
    val distancePrefix = stringResource(R.string.distance_prefix)
    val dtlPrefix = stringResource(R.string.dtl_prefix)
    val gpsStatus = ""
    val hasRaceInfo = raceInfoText
        .removePrefix(infoPrefix)
        .trim()
        .let { it.isNotBlank() && it != "--" }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        TopEventName(
            raceEvent = raceEvent,
            raceDataReady = raceDataReady,
            seriesDisplayMetadata = seriesDisplayMetadata
        )

        Spacer(modifier = Modifier.height(HomeGapSmall))

        HeaderPanel(
            startPanelMode = startPanelMode,
            startPanelText = startPanelText,
            onClick = {
                if (startPanelMode == "ocs") {
                    onOcsPanelClick()
                }
            }
        )

        Spacer(modifier = Modifier.height(HomeGapLarge))

        if (showCourseShortened) {
            CourseShortenedPanel()
            Spacer(modifier = Modifier.height(HomeGapMedium))
        }

        if (inRace) {
            TargetCard(
                currentTargetText = currentTargetText,
                progressText = progressText,
                distanceText = dtlText,
                distancePrefix = distancePrefix,
                dtlPrefix = dtlPrefix
            )

            if (hasRaceInfo) {
                Spacer(modifier = Modifier.height(HomeGapMedium))

                RaceInfoCard(
                    raceInfoText = raceInfoText,
                    infoPrefix = infoPrefix
                )
            }

            Spacer(modifier = Modifier.height(HomeGapMedium))
        }

        StatusOverviewCard(
            gpsStatus = gpsStatus,
            gpsColor = gpsColor,
            raceStatusText = shortRaceStatusText(
                raceStatusText = raceStatusText,
                raceStartText = raceStartText,
                inRace = inRace,
                racePrefix = racePrefix,
                startPrefix = startPrefix,
                activeText = stringResource(R.string.status_active),
                notActiveText = stringResource(R.string.status_not_active),
                loadedText = stringResource(R.string.status_loaded),
                plannedText = stringResource(R.string.status_planned),
                racingText = stringResource(R.string.status_racing),
                startedText = stringResource(R.string.status_started),
                finishedText = stringResource(R.string.status_finished),
                postponedText = stringResource(R.string.status_postponed),
                cancelledText = stringResource(R.string.status_cancelled)
            ),
            raceColor = raceColor,
            uploadStatusText = shortUploadStatus(
                uploadStatusText = uploadStatusText,
                inRace = inRace,
                raceStatusText = raceStatusText,
                pendingText = { pending -> resources.getString(R.string.pending_value, pending) },
                blockedText = stringResource(R.string.status_blocked),
                offText = stringResource(R.string.status_off),
                waitingText = stringResource(R.string.status_waiting),
                readyText = stringResource(R.string.status_ready),
                idleText = stringResource(R.string.status_idle)
            ),
            uploadColor = uploadColor
        )



        Spacer(modifier = Modifier.height(HomeGapSmall))

        StartFlagsPlaceholder(
            raceStartFlags = raceStartFlags,
            millisToStart = millisToStart
        )

        Spacer(modifier = Modifier.height(HomeGapMedium))


        if (isRaceFinished(raceStatusText)) {
            Button(
                onClick = onResults,
                enabled = raceDataReady,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                    disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.results))
            }
        } else {
            RacecourseRow(
                raceDataReady = raceDataReady,
                onCourse = onCourse,
                onMap = onMap
            )
        }

        Spacer(modifier = Modifier.height(HomeGapLarge))

        Text(
            text = stringResource(R.string.setup),
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(HomeGapSmall))

        ActionRow(
            setupConfirmed = setupConfirmed,
            inRace = inRace,
            raceFinished = isRaceFinished(raceStatusText),
            onSetup = onBoatData,
            onRace = onRace
        )

        if (showAdvanced) {
            AdvancedDebugBlock(
                manualTracking = manualTracking,
                rowCountText = rowCountText,
                uploadStatusText = advancedUploadStatusText,
                debugErrorText = debugErrorText,
                cogText = cogText,
                sogText = sogText,
                gpsAccuracyText = gpsAccuracyText,
                onToggleManualTracking = onToggleManualTracking,
                onExport = onExport,
                onClearOldDataClick = onClearOldDataClick
            )
        }

        Spacer(modifier = Modifier.height(if (showAdvanced) HomeGapMedium else HomeBottomGap))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onToggleAdvanced,
                colors = primaryButtonColors(),
                modifier = Modifier.weight(0.35f)
            ){
                if (showAdvanced) {
                    Text(stringResource(R.string.hide))
                } else {
                    Text(stringResource(R.string.advanced))
                }
            }

            Button(
                onClick = onLegal,
                colors = primaryButtonColors(),
                modifier = Modifier.weight(0.65f)
            ) {
                Text(stringResource(R.string.legal_about))
            }
        }
    }
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = onCancelClearOldData,
            title = {
                Text(stringResource(R.string.delete_old_data_title))
            },
            text = {
                Text(stringResource(R.string.delete_old_data_message))
            },
            confirmButton = {
                TextButton(onClick = onConfirmClearOldData) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelClearOldData) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

internal fun mergeTelemetryUploadStatusText(
    pendingStatusText: String,
    workerStatus: String,
    uploadWorkerPending: (String, Int) -> String = { worker, pending -> "Upload: $worker · $pending pending" },
    uploadWorker: (String) -> String = { worker -> "Upload: $worker" }
): String {
    if (workerStatus.isBlank()) return pendingStatusText

    val pending = pendingStatusText
        .filter { it.isDigit() }
        .toIntOrNull() ?: 0

    return if (pending > 0) {
        uploadWorkerPending(workerStatus, pending)
    } else {
        uploadWorker(workerStatus)
    }
}

@Composable
fun TopEventName(
    raceEvent: String,
    raceDataReady: Boolean,
    seriesDisplayMetadata: SeriesDisplayMetadata = SeriesDisplayMetadata()
) {
    val headerLines = buildEventHeaderLines(
        raceEvent = raceEvent,
        raceDataReady = raceDataReady,
        seriesDisplayMetadata = seriesDisplayMetadata
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = headerLines.firstOrNull().orEmpty(),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        headerLines.drop(1).forEach { line ->
            Text(
                text = line,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
@Composable
fun HeaderPanel(
    startPanelMode: String,
    startPanelText: String,
    onClick: () -> Unit = {}
) {
    val backgroundColor = when (startPanelMode) {
        "ocs" -> MaterialTheme.colorScheme.error
        "postponed" -> MaterialTheme.colorScheme.tertiary
        "countdown" -> MaterialTheme.colorScheme.primary
        "started" -> MaterialTheme.colorScheme.secondary
        "finished" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.secondary
    }
    val contentColor = when (startPanelMode) {
        "ocs" -> MaterialTheme.colorScheme.onError
        "postponed" -> MaterialTheme.colorScheme.onTertiary
        "countdown" -> MaterialTheme.colorScheme.onPrimary
        "started" -> MaterialTheme.colorScheme.onSecondary
        "finished" -> MaterialTheme.colorScheme.onSecondary
        else -> MaterialTheme.colorScheme.onSecondary
    }

    val text = when (startPanelMode) {
        "ocs" -> {
            if (startPanelText != stringResource(R.string.ocs)) {
                stringResource(R.string.ocs_countdown, startPanelText.substringAfterLast(" ", startPanelText))
            } else {
                stringResource(R.string.ocs)
            }
        }

        "postponed" -> stringResource(R.string.postponed)
        "countdown" -> startPanelText
        "started" -> startPanelText
        "finished" -> stringResource(R.string.finished)
        else -> stringResource(R.string.app_name)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = startPanelMode == "ocs") {
                onClick()
            },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 30.dp, horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = contentColor,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun CourseShortenedPanel() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiary
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.course_shortened_banner),
                color = MaterialTheme.colorScheme.onTertiary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun TargetCard(
    currentTargetText: String,
    progressText: String,
    distanceText: String,
    distancePrefix: String,
    dtlPrefix: String
) {
    val resources = LocalResources.current

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
                text = currentTargetText,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(HomeGapMedium))

            Text(
                text = displayDistanceText(
                    distanceText = distanceText,
                    distancePrefix = distancePrefix,
                    dtlPrefix = dtlPrefix,
                    unknownText = stringResource(R.string.distance_display_unknown),
                    valueText = { value -> resources.getString(R.string.distance_display_value, value) }
                ),
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = displayProgressText(progressText),
                fontSize = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
fun RaceInfoCard(
    raceInfoText: String,
    infoPrefix: String
) {
    val cleaned = raceInfoText
        .removePrefix(infoPrefix)
        .trim()

    if (cleaned.isBlank() || cleaned == "--") {
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Text(
                text = stringResource(R.string.info),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )

            Text(
                text = cleaned,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
fun StatusOverviewCard(
    gpsStatus: String,
    gpsColor: Color,
    raceStatusText: String,
    raceColor: Color,
    uploadStatusText: String,
    uploadColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            StatusRow(
                label = stringResource(R.string.gps),
                value = gpsStatus,
                color = gpsColor
            )

            Spacer(modifier = Modifier.height(HomeGapMedium))

            StatusRow(
                label = stringResource(R.string.race),
                value = raceStatusText,
                color = raceColor
            )

            Spacer(modifier = Modifier.height(HomeGapSmall))

            StatusRow(
                label = stringResource(R.string.upload),
                value = uploadStatusText,
                color = uploadColor
            )
        }
    }
}

@Composable
fun StatusRow(
    label: String,
    value: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color, CircleShape)
        )

        Text(
            text = label,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp)
        )

        Text(
            text = value,
            fontSize = 18.sp,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

@Composable
fun StartFlagsPlaceholder(
    raceStartFlags: RaceStartFlags,
    millisToStart: Long?
) {
    val visibleFlags = visibleRaceFlags(
        flags = raceStartFlags,
        millisToStart = millisToStart
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val flag = visibleFlags.getOrNull(index)

            FlagSlot(
                flag = flag,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun FlagSlot(
    flag: VisibleRaceFlag?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            when (flag) {
                is VisibleRaceFlag.ClassFlag -> {
                    Text(
                        text = flag.label.ifBlank { stringResource(R.string.class_label) },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is VisibleRaceFlag.ImageFlag -> {
                    Image(
                        painter = painterResource(id = flag.drawableResId),
                        contentDescription = flag.code,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                }

                null -> {
                    // Empty slot.
                }
            }
        }
    }
}

@Composable
fun ActionRow(
    setupConfirmed: Boolean,
    inRace: Boolean,
    raceFinished: Boolean,
    onSetup: () -> Unit,
    onRace: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SmallActionButton(
            text = stringResource(R.string.boat),
            isOk = setupConfirmed,
            enabled = !inRace,
            modifier = Modifier.weight(
                if (setupConfirmed) 0.35f else 0.65f
            ),
            onClick = onSetup
        )

        SmallActionButton(
            text = stringResource(R.string.event),
            isOk = inRace || raceFinished,
            modifier = Modifier.weight(
                if (setupConfirmed) 0.65f else 0.35f
            ),
            onClick = onRace
        )
    }
}

@Composable
fun RacecourseRow(
    raceDataReady: Boolean,
    onCourse: () -> Unit,
    onMap: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onCourse,
            enabled = raceDataReady,
            colors = primaryButtonColors(),
            modifier = Modifier.weight(0.5f)
        ) {
            Text(stringResource(R.string.course))
        }

        Button(
            onClick = onMap,
            enabled = raceDataReady,
            colors = primaryButtonColors(),
            modifier = Modifier.weight(0.5f)
        ) {
            Text(stringResource(R.string.map))
        }
    }
}

@Composable
fun SmallActionButton(
    text: String,
    isOk: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.outlineVariant
        isOk -> RegattaBlue
        else -> RegattaOrange
    }

    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Text(text)
    }
}

@Composable
fun AdvancedDebugBlock(
    manualTracking: Boolean,
    rowCountText: String,
    uploadStatusText: String,
    debugErrorText: String,
    cogText: String,
    sogText: String,
    gpsAccuracyText: String,
    onToggleManualTracking: () -> Unit,
    onExport: () -> Unit,
    onClearOldDataClick: () -> Unit
) {
    Spacer(modifier = Modifier.height(HomeGapLarge))

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
                text = stringResource(R.string.advanced),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(HomeGapMedium))

            Button(
                onClick = onToggleManualTracking,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (manualTracking) {
                    Text(stringResource(R.string.stop_manual_tracking))
                } else {
                    Text(stringResource(R.string.start_manual_tracking))
                }
            }

            Spacer(modifier = Modifier.height(HomeGapLarge))

            AdvancedSectionTitle(stringResource(R.string.gps))
            DebugLine("COG", cogText.replace("COG:", "").trim())
            DebugLine("SOG", sogText.replace("SOG:", "").trim())
            DebugLine(stringResource(R.string.accuracy), gpsAccuracyText.removePrefix(stringResource(R.string.gps_prefix)).trim())

            Spacer(modifier = Modifier.height(HomeGapLarge))

            AdvancedSectionTitle(stringResource(R.string.upload))
            DebugLine(stringResource(R.string.pending), uploadStatusText.removePrefix(stringResource(R.string.upload_prefix)).trim())
            DebugLine(stringResource(R.string.stored_rows), rowCountText)
            DebugLine(stringResource(R.string.last_error), debugErrorText.substringAfter(": ", debugErrorText))

            Spacer(modifier = Modifier.height(HomeGapLarge))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onExport,
                    colors = primaryButtonColors(),
                    modifier = Modifier.weight(0.5f)
                ) {
                    Text(stringResource(R.string.export))
                }

                Button(
                    onClick = onClearOldDataClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.weight(0.5f)
                ) {
                    Text(stringResource(R.string.clear))
                }
            }

        }
    }
}

@Composable
fun AdvancedSectionTitle(
    text: String
) {
    Text(
        text = text,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
fun DebugLine(
    label: String,
    value: String
) {
    Text(
        text = "$label: $value",
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 3.dp)
    )
}

fun isRaceFinished(
    raceStatusText: String
): Boolean {
    return raceStatusText.contains("finished", ignoreCase = true)
}

fun uploadStatusColor(
    uploadStatusText: String,
    inRace: Boolean,
    disabledColor: Color
): Color {
    val pending = uploadStatusText
        .filter { it.isDigit() }
        .toIntOrNull() ?: 0

    if (!inRace && pending == 0) {
        return disabledColor
    }

    return when {
        uploadStatusText.contains("all sent", ignoreCase = true) -> RegattaGreen
        pending <= 10 -> RegattaGreen
        pending <= 50 -> RegattaOrange
        else -> RegattaRed
    }
}

fun shortUploadStatus(
    uploadStatusText: String,
    inRace: Boolean,
    raceStatusText: String,
    pendingText: (Int) -> String = { "$it pending" },
    blockedText: String = "blocked",
    offText: String = "off",
    waitingText: String = "waiting",
    readyText: String = "ready",
    idleText: String = "idle"
): String {
    val pending = uploadStatusText
        .filter { it.isDigit() }
        .toIntOrNull() ?: 0

    if (!inRace) {
        if (pending > 0) {
            return pendingText(pending)
        }

        return when {
            raceStatusText.contains("accept race legal", ignoreCase = true) -> blockedText
            raceStatusText.contains("not loaded", ignoreCase = true) -> offText
            raceStatusText.contains("planned", ignoreCase = true) -> waitingText
            raceStatusText.contains("racing", ignoreCase = true) -> readyText
            raceStatusText.contains("started", ignoreCase = true) -> readyText
            else -> idleText
        }
    }

    return when {
        uploadStatusText.contains("all sent", ignoreCase = true) -> "OK"
        pending <= 10 -> "OK"
        else -> "$pending"
    }
}

fun raceStatusColor(
    raceStatusText: String,
    inRace: Boolean
): Color {
    return when {
        raceStatusText.contains("postponed", ignoreCase = true) -> RegattaOrange
        raceStatusText.contains("cancelled", ignoreCase = true) -> RegattaRed
        raceStatusText.contains("finished", ignoreCase = true) -> RegattaGreen
        inRace -> RegattaGreen
        else -> RegattaOrange
    }
}

fun localizedRaceStatusCode(
    raceStatusText: String,
    racePrefix: String = "Race:",
    loadedText: String = "loaded",
    plannedText: String = "planned",
    racingText: String = "racing",
    startedText: String = "started",
    finishedText: String = "finished",
    postponedText: String = "postponed",
    cancelledText: String = "cancelled"
): String {
    val cleaned = raceStatusText
        .removePrefix(racePrefix)
        .trim()

    return when {
        cleaned.equals("loaded", ignoreCase = true) -> loadedText
        cleaned.equals("planned", ignoreCase = true) -> plannedText
        cleaned.equals("racing", ignoreCase = true) -> racingText
        cleaned.equals("started", ignoreCase = true) -> startedText
        cleaned.equals("finished", ignoreCase = true) -> finishedText
        cleaned.equals("postponed", ignoreCase = true) -> postponedText
        cleaned.equals("cancelled", ignoreCase = true) -> cancelledText
        else -> cleaned
    }
}

fun shortRaceStatusText(
    raceStatusText: String,
    raceStartText: String,
    inRace: Boolean,
    racePrefix: String = "Race:",
    startPrefix: String = "Start:",
    activeText: String = "active",
    notActiveText: String = "not active",
    loadedText: String = "loaded",
    plannedText: String = "planned",
    racingText: String = "racing",
    startedText: String = "started",
    finishedText: String = "finished",
    postponedText: String = "postponed",
    cancelledText: String = "cancelled"
): String {
    val cleaned = raceStatusText
        .removePrefix(racePrefix)
        .trim()

    if (cleaned.equals("finished", ignoreCase = true)) {
        return finishedText
    }

    if (cleaned.equals("postponed", ignoreCase = true)) {
        return postponedText
    }

    if (cleaned.equals("cancelled", ignoreCase = true)) {
        return cancelledText
    }

    if (inRace) {
        val startTime = extractStartClockTime(raceStartText, startPrefix)

        return if (startTime.isNotBlank()) {
            startTime
        } else {
            activeText
        }
    }

    return when {
        cleaned.isBlank() || cleaned.equals("not loaded", ignoreCase = true) -> notActiveText
        else -> localizedRaceStatusCode(
            raceStatusText = raceStatusText,
            racePrefix = racePrefix,
            loadedText = loadedText,
            plannedText = plannedText,
            racingText = racingText,
            startedText = startedText,
            finishedText = finishedText,
            postponedText = postponedText,
            cancelledText = cancelledText
        )
    }
}

fun extractStartClockTime(
    raceStartText: String,
    startPrefix: String = "Start:"
): String {
    val cleaned = raceStartText
        .removePrefix(startPrefix)
        .trim()

    if (cleaned.isBlank() || cleaned == "--") {
        return ""
    }

    return when {
        cleaned.contains("T") -> {
            cleaned
                .substringAfter("T")
                .take(5)
        }

        cleaned.length >= 5 -> {
            cleaned.takeLast(5)
        }

        else -> cleaned
    }
}

fun displayDistanceText(
    distanceText: String,
    distancePrefix: String = "Distance:",
    dtlPrefix: String = "DTL:",
    unknownText: String = "Distance --",
    valueText: (String) -> String = { "Distance $it" }
): String {
    val cleaned = distanceText
        .removePrefix(distancePrefix)
        .removePrefix(dtlPrefix)
        .trim()

    return if (cleaned == "--" || cleaned.isBlank()) {
        unknownText
    } else {
        valueText(cleaned)
    }
}

fun displayProgressText(
    progressText: String
): String {
    return progressText
        .replace(Regex("""\s*·\s*\d+%"""), "")
}
