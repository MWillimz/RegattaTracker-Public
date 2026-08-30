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
    val workerUploadStatus = produceState(initialValue = "", context) {
        val prefs = context.getSharedPreferences("regatta_local_status", Context.MODE_PRIVATE)
        while (true) {
            value = prefs.getString(TelemetryUploadStatusStore.STATUS_KEY, "").orEmpty()
            delay(1000L)
        }
    }.value
    val advancedUploadStatusText = mergeTelemetryUploadStatusText(
        pendingStatusText = uploadStatusText,
        workerStatus = workerUploadStatus
    )

    val uploadColor = uploadStatusColor(
        uploadStatusText = uploadStatusText,
        inRace = inRace,
        disabledColor = MaterialTheme.colorScheme.outlineVariant
    )

    val showCourseShortened =
        raceShortenedText.contains("YES", ignoreCase = true) &&
                !raceStatusText.contains("finished", ignoreCase = true) &&
                !raceStatusText.contains("cancelled", ignoreCase = true)

    val raceColor = raceStatusColor(raceStatusText, inRace)
    val gpsStatus = gpsStatusLabel(gpsAccuracyText)
    val hasRaceInfo = raceInfoText
        .replace("Info:", "")
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
                distanceText = dtlText
            )

            if (hasRaceInfo) {
                Spacer(modifier = Modifier.height(HomeGapMedium))

                RaceInfoCard(
                    raceInfoText = raceInfoText
                )
            }

            Spacer(modifier = Modifier.height(HomeGapMedium))
        }

        StatusOverviewCard(
            gpsStatus = gpsStatus,
            gpsColor = gpsStatusColor(gpsAccuracyText),
            raceStatusText = shortRaceStatusText(
                raceStatusText = raceStatusText,
                raceStartText = raceStartText,
                inRace = inRace
            ),
            raceColor = raceColor,
            uploadStatusText = shortUploadStatus(
                uploadStatusText = uploadStatusText,
                inRace = inRace,
                raceStatusText = raceStatusText
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
                Text("Results")
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
            text = "Setup",
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
                    Text("Hide")
                } else {
                    Text("Advanced")
                }
            }

            Button(
                onClick = onLegal,
                colors = primaryButtonColors(),
                modifier = Modifier.weight(0.65f)
            ) {
                Text("Legal / About")
            }
        }
    }
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = onCancelClearOldData,
            title = {
                Text("Delete old data?")
            },
            text = {
                Text("All stored tracking data on this device will be deleted. This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = onConfirmClearOldData) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelClearOldData) {
                    Text("Cancel")
                }
            }
        )
    }
}

internal fun mergeTelemetryUploadStatusText(
    pendingStatusText: String,
    workerStatus: String
): String {
    if (workerStatus.isBlank()) return pendingStatusText

    val pending = pendingStatusText
        .filter { it.isDigit() }
        .toIntOrNull() ?: 0

    return if (pending > 0) {
        "Upload: $workerStatus · $pending pending"
    } else {
        "Upload: $workerStatus"
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
            if (startPanelText.startsWith("START IN", ignoreCase = true)) {
                "OCS: ${startPanelText.removePrefix("START IN").trim()}"
            } else {
                "OCS"
            }
        }

        "postponed" -> "POSTPONED"
        "countdown" -> startPanelText
        "started" -> startPanelText
        "finished" -> "FINISHED"
        else -> "Regatta Tracker"
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
                text = "COURSE SHORTENED",
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
    distanceText: String
) {
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
                text = currentTargetText.replace("Nächstes Ziel:", "Next:"),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(HomeGapMedium))

            Text(
                text = displayDistanceText(distanceText),
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
    raceInfoText: String
) {
    val cleaned = raceInfoText
        .replace("Info:", "")
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
                text = "Info",
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
                label = "GPS",
                value = gpsStatus,
                color = gpsColor
            )

            Spacer(modifier = Modifier.height(HomeGapMedium))

            StatusRow(
                label = "Race",
                value = raceStatusText,
                color = raceColor
            )

            Spacer(modifier = Modifier.height(HomeGapSmall))

            StatusRow(
                label = "Upload",
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
                        text = flag.label.ifBlank { "Class" },
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
            text = "Boat",
            isOk = setupConfirmed,
            enabled = !inRace,
            modifier = Modifier.weight(
                if (setupConfirmed) 0.35f else 0.65f
            ),
            onClick = onSetup
        )

        SmallActionButton(
            text = "Event",
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
            Text("Course")
        }

        Button(
            onClick = onMap,
            enabled = raceDataReady,
            colors = primaryButtonColors(),
            modifier = Modifier.weight(0.5f)
        ) {
            Text("Map")
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
                text = "Advanced",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(HomeGapMedium))

            Button(
                onClick = onToggleManualTracking,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (manualTracking) {
                    Text("Stop Manual Tracking")
                } else {
                    Text("Start Manual Tracking")
                }
            }

            Spacer(modifier = Modifier.height(HomeGapLarge))

            AdvancedSectionTitle("GPS")
            DebugLine("COG", cogText.replace("COG:", "").trim())
            DebugLine("SOG", sogText.replace("SOG:", "").trim())
            DebugLine("Accuracy", gpsAccuracyText.replace("GPS:", "").trim())

            Spacer(modifier = Modifier.height(HomeGapLarge))

            AdvancedSectionTitle("Upload")
            DebugLine("Pending", uploadStatusText.replace("Upload:", "").trim())
            DebugLine("Stored rows", rowCountText)
            DebugLine("Last error", debugErrorText.replace("Letzter Fehler:", "").trim())

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
                    Text("Export")
                }

                Button(
                    onClick = onClearOldDataClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.weight(0.5f)
                ) {
                    Text("Clear")
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

fun gpsStatusLabel(
    gpsAccuracyText: String
): String {
    val value = gpsAccuracyText
        .replace("GPS:", "")
        .replace("m", "")
        .trim()
        .toDoubleOrNull()

    return when {
        value == null -> ""
        value <= 10.0 -> ""
        value <= 25.0 -> ""
        else -> ""
    }
}

fun gpsStatusColor(
    gpsAccuracyText: String
): Color {
    val value = gpsAccuracyText
        .replace("GPS:", "")
        .replace("m", "")
        .trim()
        .toDoubleOrNull()

    return when {
        value == null -> RegattaRed
        value <= 10.0 -> RegattaGreen
        value <= 25.0 -> RegattaOrange
        else -> RegattaRed
    }
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
    raceStatusText: String
): String {
    val pending = uploadStatusText
        .filter { it.isDigit() }
        .toIntOrNull() ?: 0

    if (!inRace) {
        if (pending > 0) {
            return "$pending pending"
        }

        return when {
            raceStatusText.contains("accept race legal", ignoreCase = true) -> "blocked"
            raceStatusText.contains("not loaded", ignoreCase = true) -> "off"
            raceStatusText.contains("planned", ignoreCase = true) -> "waiting"
            raceStatusText.contains("racing", ignoreCase = true) -> "ready"
            raceStatusText.contains("started", ignoreCase = true) -> "ready"
            else -> "idle"
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

fun shortRaceStatusText(
    raceStatusText: String,
    raceStartText: String,
    inRace: Boolean
): String {
    val cleaned = raceStatusText
        .replace("Race:", "")
        .trim()

    if (cleaned.contains("finished", ignoreCase = true)) {
        return "finished"
    }

    if (cleaned.contains("postponed", ignoreCase = true)) {
        return "postponed"
    }

    if (cleaned.contains("cancelled", ignoreCase = true)) {
        return "cancelled"
    }

    if (inRace) {
        val startTime = extractStartClockTime(raceStartText)

        return if (startTime.isNotBlank()) {
            startTime
        } else {
            "active"
        }
    }

    return when {
        cleaned.isNotBlank() && cleaned != "not loaded" -> cleaned
        else -> "not active"
    }
}

fun extractStartClockTime(
    raceStartText: String
): String {
    val cleaned = raceStartText
        .replace("Start:", "")
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
    distanceText: String
): String {
    val cleaned = distanceText
        .replace("Distance:", "")
        .replace("DTL:", "")
        .trim()

    return if (cleaned == "--" || cleaned.isBlank()) {
        "Distance --"
    } else {
        "Distance $cleaned"
    }
}

fun displayProgressText(
    progressText: String
): String {
    return progressText
        .replace(Regex("""\s*·\s*\d+%"""), "")
}
