package de.williserv.regattaclient

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
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
private val CompactButtonContentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

@Composable
fun primaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
private fun AutoSizedSingleLineText(
    text: String,
    minFontSize: TextUnit,
    maxFontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(
            minFontSize = minFontSize,
            maxFontSize = maxFontSize,
            stepSize = 0.5.sp
        )
    )
}
@Composable
fun HomeScreen(
    inRace: Boolean,
    manualTracking: Boolean,
    setupConfirmed: Boolean,
    statusText: String,
    rowCountText: String,
    uploadStatusText: String,
    pendingUploadCount: Long,
    noConnection: Boolean = false,
    debugErrorText: String,
    serviceStatusText: String,
    raceStatusCode: String,
    raceStatusDisplayText: String,
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
    retirementReported: Boolean,
    retirementStatusText: String,
    raceDataReady: Boolean,
    dtlText: String,
    ttlText: String,
    ocsText: String,
    raceInfoText: String,
    raceShortenedText: String,
    raceShortened: Boolean,
    hasRaceInfo: Boolean,
    raceStartFlags: RaceStartFlags,
    millisToStart: Long?,
    startPanelText: String,
    startPanelMode: String,
    lastCsvLine: String,
    cogText: String,
    sogText: String,
    gpsAccuracyText: String,
    gpsColor: Color,
    regattaLinkConnected: Boolean,
    showClearConfirmDialog: Boolean,
    showAdvanced: Boolean,
    modifier: Modifier = Modifier,
    onBoatData: () -> Unit,
    onRace: () -> Unit,
    onCourse: () -> Unit,
    onMap: () -> Unit,
    onResults: () -> Unit,
    onRegattaLinkReconnect: () -> Unit,
    onRegattaLinkOpen: () -> Unit,
    onLegal: () -> Unit,
    onOcsPanelClick: () -> Unit,
    onToggleManualTracking: () -> Unit,
    onSessionHistory: () -> Unit,
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
        pendingCount = pendingUploadCount,
        workerStatus = localizedWorkerStatus,
        uploadWorkerPending = { worker, pending ->
            resources.getString(R.string.upload_worker_pending, worker, pending)
        },
        uploadWorker = { worker ->
            resources.getString(R.string.upload_worker, worker)
        }
    )

    val uploadColor = uploadStatusColor(
        pendingUploadCount = pendingUploadCount,
        noConnection = noConnection
    )

    val showCourseShortened =
        raceShortened &&
                !raceStatusCode.equals("finished", ignoreCase = true) &&
                !raceStatusCode.equals("cancelled", ignoreCase = true)

    val raceColor = raceStatusColor(raceStatusCode, inRace, raceDataReady)
    val startPrefix = stringResource(R.string.start_prefix)
    val infoPrefix = stringResource(R.string.info_prefix)
    val distancePrefix = stringResource(R.string.distance_prefix)
    val dtlPrefix = stringResource(R.string.dtl_prefix)
    val gpsStatus = ""

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
                if (startPanelMode == "ocs" || startPanelMode == "ocs_countdown") {
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
            if (retirementReported) {
                RetirementTrackingNotice()
                Spacer(modifier = Modifier.height(HomeGapMedium))
            }
            if (retirementStatusText.isNotBlank()) {
                Text(
                    text = retirementStatusText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(HomeGapMedium))
            }

            if (boatRaceStatusText.isNotBlank()) {
                Text(
                    text = boatRaceStatusText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(HomeGapMedium))
            }

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
                raceStatusCode = raceStatusCode,
                raceStatusDisplayText = raceStatusDisplayText,
                raceDataReady = raceDataReady,
                raceStartText = raceStartText,
                inRace = inRace,
                racePrefix = stringResource(R.string.race_prefix),
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
                pendingUploadCount = pendingUploadCount,
                inRace = inRace,
                noConnection = noConnection,
                pendingText = { pending -> resources.getString(R.string.pending_value, pending) },
                okText = stringResource(R.string.ok),
                noConnectionText = stringResource(R.string.status_no_connection)
            ),
            uploadColor = uploadColor,
            regattaLinkConnected = regattaLinkConnected,
            onRegattaLinkReconnect = onRegattaLinkReconnect,
            onRegattaLinkOpen = onRegattaLinkOpen
        )



        Spacer(modifier = Modifier.height(HomeGapSmall))

        StartFlagsPlaceholder(
            raceStartFlags = raceStartFlags,
            millisToStart = millisToStart
        )

        Spacer(modifier = Modifier.height(HomeGapMedium))


        if (isRaceFinished(raceStatusCode, raceDataReady)) {
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
            raceFinished = isRaceFinished(raceStatusCode, raceDataReady),
            onSetup = onBoatData,
            onRace = onRace
        )

        if (showAdvanced) {
            AdvancedDebugBlock(
                manualTracking = manualTracking,
                inRace = inRace,
                rowCountText = rowCountText,
                uploadStatusText = advancedUploadStatusText,
                debugErrorText = debugErrorText,
                cogText = cogText,
                sogText = sogText,
                gpsAccuracyText = gpsAccuracyText,
                onToggleManualTracking = onToggleManualTracking,
                onSessionHistory = onSessionHistory,
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
                contentPadding = CompactButtonContentPadding,
                modifier = Modifier.weight(0.35f)
            ){
                AutoSizedSingleLineText(
                    text = if (showAdvanced) {
                        stringResource(R.string.hide)
                    } else {
                        stringResource(R.string.advanced)
                    },
                    minFontSize = 10.sp,
                    maxFontSize = 14.sp
                )
            }

            Button(
                onClick = onLegal,
                colors = primaryButtonColors(),
                contentPadding = CompactButtonContentPadding,
                modifier = Modifier.weight(0.65f)
            ) {
                AutoSizedSingleLineText(
                    text = stringResource(R.string.legal_about),
                    minFontSize = 10.sp,
                    maxFontSize = 14.sp
                )
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
    pendingCount: Long,
    workerStatus: String,
    uploadWorkerPending: (String, Long) -> String,
    uploadWorker: (String) -> String
): String {
    if (workerStatus.isBlank()) return pendingStatusText

    return if (pendingCount > 0L) {
        uploadWorkerPending(workerStatus, pendingCount)
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
        Text(
            text = headerLines.firstOrNull().orEmpty(),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )

        headerLines.drop(1).forEach { line ->
            Text(
                text = line,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
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
        "ocs", "ocs_countdown" -> MaterialTheme.colorScheme.error
        "postponed" -> MaterialTheme.colorScheme.tertiary
        "countdown" -> MaterialTheme.colorScheme.primary
        "started" -> MaterialTheme.colorScheme.secondary
        "finished" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.secondary
    }
    val contentColor = when (startPanelMode) {
        "ocs", "ocs_countdown" -> MaterialTheme.colorScheme.onError
        "postponed" -> MaterialTheme.colorScheme.onTertiary
        "countdown" -> MaterialTheme.colorScheme.onPrimary
        "started" -> MaterialTheme.colorScheme.onSecondary
        "finished" -> MaterialTheme.colorScheme.onSecondary
        else -> MaterialTheme.colorScheme.onSecondary
    }

    val text = when (startPanelMode) {
        "ocs" -> stringResource(R.string.ocs)
        "ocs_countdown" -> stringResource(R.string.ocs_countdown, startPanelText)

        "postponed" -> stringResource(R.string.postponed)
        "countdown" -> startPanelText
        "started" -> startPanelText
        "finished" -> stringResource(R.string.finished)
        else -> stringResource(R.string.app_name)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = startPanelMode == "ocs" || startPanelMode == "ocs_countdown") {
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
            AutoSizedSingleLineText(
                text = text,
                minFontSize = 20.sp,
                maxFontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
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
            AutoSizedSingleLineText(
                text = stringResource(R.string.course_shortened_banner),
                minFontSize = 16.sp,
                maxFontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiary
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

            AutoSizedSingleLineText(
                text = displayDistanceText(
                    distanceText = distanceText,
                    distancePrefix = distancePrefix,
                    dtlPrefix = dtlPrefix,
                    unknownText = stringResource(R.string.distance_display_unknown),
                    valueText = { value -> resources.getString(R.string.distance_display_value, value) }
                ),
                minFontSize = 22.sp,
                maxFontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
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
    uploadColor: Color,
    regattaLinkConnected: Boolean,
    onRegattaLinkReconnect: () -> Unit,
    onRegattaLinkOpen: () -> Unit
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactStatusIndicator(
                    label = stringResource(R.string.gps),
                    value = gpsStatus,
                    color = gpsColor,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.weight(1f))
                RegattaLinkStatusIndicator(
                    connected = regattaLinkConnected,
                    onReconnect = onRegattaLinkReconnect,
                    onOpen = onRegattaLinkOpen,
                    modifier = Modifier.weight(1f)
                )
            }

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
private fun CompactStatusIndicator(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color, CircleShape)
        )
        AutoSizedSingleLineText(
            text = if (value.isBlank()) label else "$label  $value",
            minFontSize = 9.sp,
            maxFontSize = 16.sp,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RegattaLinkStatusIndicator(
    connected: Boolean,
    onReconnect: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.combinedClickable(
            onClick = {
                if (!connected) {
                    onReconnect()
                }
            },
            onLongClick = onOpen
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(
                    if (connected) RegattaGreen else RegattaRed,
                    CircleShape
                )
        )
        AutoSizedSingleLineText(
            text = stringResource(R.string.regattalink_short_label),
            minFontSize = 9.sp,
            maxFontSize = 16.sp,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
fun StatusRow(
    label: String,
    value: String,
    color: Color
) {
    val statusText = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            append(label)
        }
        if (value.isNotBlank()) {
            append("  ")
            append(value)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color, CircleShape)
        )

        Text(
            text = statusText,
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(
                minFontSize = 10.sp,
                maxFontSize = 18.sp,
                stepSize = 0.5.sp
            )
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
            enabled = setupConfirmed,
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
            contentPadding = CompactButtonContentPadding,
            modifier = Modifier.weight(0.5f)
        ) {
            AutoSizedSingleLineText(
                text = stringResource(R.string.course),
                minFontSize = 10.sp,
                maxFontSize = 14.sp
            )
        }

        Button(
            onClick = onMap,
            enabled = raceDataReady,
            colors = primaryButtonColors(),
            contentPadding = CompactButtonContentPadding,
            modifier = Modifier.weight(0.5f)
        ) {
            AutoSizedSingleLineText(
                text = stringResource(R.string.map),
                minFontSize = 10.sp,
                maxFontSize = 14.sp
            )
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
        contentPadding = CompactButtonContentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        AutoSizedSingleLineText(
            text = text,
            minFontSize = 10.sp,
            maxFontSize = 14.sp
        )
    }
}

@Composable
fun AdvancedDebugBlock(
    manualTracking: Boolean,
    inRace: Boolean,
    rowCountText: String,
    uploadStatusText: String,
    debugErrorText: String,
    cogText: String,
    sogText: String,
    gpsAccuracyText: String,
    onToggleManualTracking: () -> Unit,
    onSessionHistory: () -> Unit,
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
                enabled = manualTracking || !inRace,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (manualTracking) {
                    Text(stringResource(R.string.stop_manual_tracking))
                } else {
                    Text(stringResource(R.string.start_manual_tracking))
                }
            }

            Spacer(modifier = Modifier.height(HomeGapMedium))

            Button(
                onClick = onSessionHistory,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.session_history_button))
            }

            Spacer(modifier = Modifier.height(HomeGapLarge))

            AdvancedSectionTitle(stringResource(R.string.gps))
            DebugLine("COG", cogText.removePrefix(stringResource(R.string.cog_prefix)).trim())
            DebugLine("SOG", sogText.removePrefix(stringResource(R.string.sog_prefix)).trim())
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
    raceStatusCode: String,
    raceDataReady: Boolean = true
): Boolean {
    return raceDataReady && raceStatusCode.equals("finished", ignoreCase = true)
}

fun uploadStatusColor(
    pendingUploadCount: Long,
    noConnection: Boolean = false
): Color {
    if (noConnection) return RegattaRed
    return when {
        pendingUploadCount <= 10L -> RegattaGreen
        pendingUploadCount <= 50L -> RegattaOrange
        else -> RegattaRed
    }
}

fun shortUploadStatus(
    pendingUploadCount: Long,
    inRace: Boolean,
    pendingText: (Long) -> String,
    okText: String,
    noConnection: Boolean = false,
    noConnectionText: String = "No connection"
): String {
    if (noConnection) {
        return if (pendingUploadCount > 0L) {
            "$noConnectionText · ${pendingText(pendingUploadCount)}"
        } else {
            noConnectionText
        }
    }
    if (!inRace && pendingUploadCount > 0L) return pendingText(pendingUploadCount)
    return if (inRace && pendingUploadCount > 10L) "$pendingUploadCount" else okText
}

fun raceStatusColor(
    raceStatusCode: String,
    inRace: Boolean,
    raceDataReady: Boolean = true
): Color {
    if (!raceDataReady) {
        return if (inRace) RegattaGreen else RegattaOrange
    }

    return when {
        raceStatusCode.equals("postponed", ignoreCase = true) -> RegattaOrange
        raceStatusCode.equals("cancelled", ignoreCase = true) -> RegattaRed
        raceStatusCode.equals("finished", ignoreCase = true) -> RegattaGreen
        inRace -> RegattaGreen
        else -> RegattaOrange
    }
}

fun localizedRaceStatusValue(
    raceStatusCode: String,
    loadedText: String,
    plannedText: String,
    racingText: String,
    startedText: String,
    finishedText: String,
    postponedText: String,
    cancelledText: String
): String {
    val cleaned = raceStatusCode.trim()

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

fun localizedRaceStatusCode(
    raceStatusText: String,
    racePrefix: String,
    loadedText: String,
    plannedText: String,
    racingText: String,
    startedText: String,
    finishedText: String,
    postponedText: String,
    cancelledText: String
): String {
    return localizedRaceStatusValue(
        raceStatusCode = raceStatusText.removePrefix(racePrefix).trim(),
        loadedText = loadedText,
        plannedText = plannedText,
        racingText = racingText,
        startedText = startedText,
        finishedText = finishedText,
        postponedText = postponedText,
        cancelledText = cancelledText
    )
}

fun shortRaceStatusText(
    raceStatusCode: String,
    raceStatusDisplayText: String = "",
    raceDataReady: Boolean = true,
    raceStartText: String,
    inRace: Boolean,
    racePrefix: String,
    startPrefix: String,
    activeText: String,
    notActiveText: String,
    loadedText: String,
    plannedText: String,
    racingText: String,
    startedText: String,
    finishedText: String,
    postponedText: String,
    cancelledText: String
): String {
    val cleaned = raceStatusCode.trim()

    if (!raceDataReady) {
        return raceStatusDisplayText
            .removePrefix(racePrefix)
            .trim()
            .ifBlank { notActiveText }
    }

    if (cleaned.equals("finished", ignoreCase = true)) return finishedText
    if (cleaned.equals("postponed", ignoreCase = true)) return postponedText
    if (cleaned.equals("cancelled", ignoreCase = true)) return cancelledText

    if (inRace) {
        val startTime = extractStartClockTime(raceStartText, startPrefix)
        return if (startTime.isNotBlank()) startTime else activeText
    }

    return if (cleaned.isBlank()) {
        notActiveText
    } else {
        localizedRaceStatusValue(
            raceStatusCode = cleaned,
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
    startPrefix: String
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
    distancePrefix: String,
    dtlPrefix: String,
    unknownText: String,
    valueText: (String) -> String
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
