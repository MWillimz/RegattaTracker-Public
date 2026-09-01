package de.williserv.regattaclient

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource

@Composable
fun CourseScreen(
    raceEvent: String,
    raceStatusText: String,
    raceStartText: String,
    raceStopText: String,
    raceCourseText: String,
    raceStartLineText: String,
    raceFinishLineText: String,
    raceMarksText: String,
    raceInfoText: String,
    raceShortenedText: String,
    raceShortened: Boolean,
    currentTargetText: String,
    courseMapMarks: List<CourseMapMark>,
    onSetCourseProgress: (Int, Boolean) -> Unit,
    onOpenMapDetail: (CourseMapView) -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val showTargetDialog = remember { mutableStateOf(false) }
    val pendingCourseOverride = remember { mutableStateOf<CourseProgressOption?>(null) }

    Column(
        modifier = modifier
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.course),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        CourseInfoCard(
            raceEvent = raceEvent,
            raceStatusText = raceStatusText,
            raceStartText = raceStartText,
            raceStopText = raceStopText,
            raceShortenedText = raceShortenedText,
            raceShortened = raceShortened
        )

        Spacer(modifier = Modifier.height(14.dp))

        CourseRouteCard(
            raceStartLineText = raceStartLineText,
            raceMarksText = raceMarksText,
            raceFinishLineText = raceFinishLineText,
            courseMapMarks = courseMapMarks,
            onOpenMapDetail = onOpenMapDetail
        )

        Spacer(modifier = Modifier.height(14.dp))

        CourseTargetCard(
            currentTargetText = currentTargetText,
            onClick = {
                showTargetDialog.value = true
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        CourseRaceInfoCard(
            raceInfoText = raceInfoText
        )

        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.back))
        }

    }
    if (showTargetDialog.value) {
        CourseTargetDialog(
            courseMapMarks = courseMapMarks,
            onDismiss = {
                showTargetDialog.value = false
            },
            onSelectOption = { option ->
                showTargetDialog.value = false
                pendingCourseOverride.value = option
            }
        )
    }
    val pending = pendingCourseOverride.value

    if (pending != null) {
        AlertDialog(
            onDismissRequest = {
                pendingCourseOverride.value = null
            },
            title = {
                Text(stringResource(R.string.override_course_title))
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.override_course_confirmation,
                        pending.confirmLabel
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingCourseOverride.value = null
                        onSetCourseProgress(
                            pending.passedMarks,
                            pending.raceStarted
                        )
                    }
                ) {
                    Text(stringResource(R.string.confirm_override))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingCourseOverride.value = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun CourseTargetCard(
    currentTargetText: String,
    onClick: () -> Unit
) {
    val cleaned = currentTargetText
        .removePrefix(stringResource(R.string.next_prefix))
        .trim()
        .ifBlank { "--" }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onClick()
            },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Text(
                text = stringResource(R.string.next),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary
            )

            Text(
                text = cleaned,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun CourseTargetDialog(
    courseMapMarks: List<CourseMapMark>,
    onDismiss: () -> Unit,
    onSelectOption: (CourseProgressOption) -> Unit
) {
    val options = courseProgressOptions(courseMapMarks)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.set_next_target))
        },
        text = {
            Column {
                options.forEach { option ->
                    Button(
                        onClick = {
                            onSelectOption(option)
                        },
                        enabled = option.enabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        )
                    ) {
                        Text(option.label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

data class CourseProgressOption(
    val label: String,
    val confirmLabel: String,
    val passedMarks: Int,
    val raceStarted: Boolean,
    val enabled: Boolean = true
)

data class CourseProgressMarkState(
    val label: String,
    val passedMarks: Int,
    val skipped: Boolean
)

internal fun buildCourseProgressMarkStates(
    courseMapMarks: List<CourseMapMark>
): List<CourseProgressMarkState> {
    var activeMarksBefore = 0

    return courseMapMarks.mapNotNull { mark ->
        val label = mark.label.trim()
        if (label.isBlank() || label == "--") {
            null
        } else if (mark.skipped) {
            CourseProgressMarkState(
                label = label,
                passedMarks = 0,
                skipped = true
            )
        } else {
            CourseProgressMarkState(
                label = label,
                passedMarks = activeMarksBefore++,
                skipped = false
            )
        }
    }
}

@Composable
fun courseProgressOptions(
    courseMapMarks: List<CourseMapMark>
): List<CourseProgressOption> {
    val marks = buildCourseProgressMarkStates(courseMapMarks)
    val activeMarkCount = marks.count { !it.skipped }

    return buildList {
        add(
            CourseProgressOption(
                label = stringResource(R.string.restart_at_start_line),
                confirmLabel = stringResource(R.string.start_line),
                passedMarks = 0,
                raceStarted = false
            )
        )

        marks.forEach { mark ->
            add(
                CourseProgressOption(
                    label = if (mark.skipped) {
                        stringResource(R.string.mark_skipped, mark.label)
                    } else {
                        mark.label
                    },
                    confirmLabel = mark.label,
                    passedMarks = mark.passedMarks,
                    raceStarted = true,
                    enabled = !mark.skipped
                )
            )
        }

        add(
            CourseProgressOption(
                label = stringResource(R.string.finish_line),
                confirmLabel = stringResource(R.string.finish_line),
                passedMarks = activeMarkCount,
                raceStarted = true
            )
        )
    }
}

@Composable
fun CourseInfoCard(
    raceEvent: String,
    raceStatusText: String,
    raceStartText: String,
    raceStopText: String,
    raceShortenedText: String,
    raceShortened: Boolean
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
                text = stringResource(R.string.event_value, raceEvent),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )

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

@Composable
fun CourseRouteCard(
    raceStartLineText: String,
    raceMarksText: String,
    raceFinishLineText: String,
    courseMapMarks: List<CourseMapMark>,
    onOpenMapDetail: (CourseMapView) -> Unit
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onOpenMapDetail(CourseMapView.Start)
                    }
            ) {
                Text(
                    text = stringResource(R.string.start_line),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = raceStartLineText
                        .removePrefix(stringResource(R.string.start_line_prefix))
                        .trim(),
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.marks),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Column(
                modifier = Modifier.padding(top = 4.dp)
            ) {
                if (courseMapMarks.isNotEmpty()) {
                    courseMapMarks.forEach { mark ->
                        val markModifier = if (mark.clickable) {
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp)
                                .clickable {
                                    onOpenMapDetail(CourseMapView.Mark(order = mark.order!!))
                                }
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp)
                        }

                        Text(
                            text = mark.label,
                            fontSize = 18.sp,
                            color = when {
                                mark.skipped -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                mark.clickable -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textDecoration = if (mark.skipped) {
                                TextDecoration.LineThrough
                            } else {
                                TextDecoration.None
                            },
                            modifier = markModifier
                        )
                    }
                } else {
                    val localizedSkippedMarker = stringResource(R.string.mark_skipped_compact, 0, "")
                        .removePrefix("0")
                        .trim()
                    courseMarkDisplayItems(
                        raceMarksText = raceMarksText,
                        marksPrefix = stringResource(R.string.marks_prefix),
                        skippedMarker = localizedSkippedMarker
                    ).forEach { item ->
                        Text(
                            text = item.label,
                            fontSize = 18.sp,
                            color = if (item.skipped) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textDecoration = if (item.skipped) {
                                TextDecoration.LineThrough
                            } else {
                                TextDecoration.None
                            },
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onOpenMapDetail(CourseMapView.Finish)
                    }
            ) {
                Text(
                    text = stringResource(R.string.finish_line),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = raceFinishLineText
                        .removePrefix(stringResource(R.string.finish_line_prefix))
                        .trim(),
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun CourseRaceInfoCard(
    raceInfoText: String
) {
    val cleaned = raceInfoText
        .removePrefix(stringResource(R.string.info_prefix))
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
                text = stringResource(R.string.race_info),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )

            Text(
                text = cleaned,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

data class CourseMarkDisplayItem(
    val label: String,
    val skipped: Boolean
)

fun courseMarkDisplayItems(
    raceMarksText: String,
    marksPrefix: String = "Marks:",
    skippedMarker: String = "[skipped]"
): List<CourseMarkDisplayItem> {
    val cleaned = raceMarksText
        .removePrefix(marksPrefix)
        .trim()

    if (cleaned.isBlank() || cleaned == "--") {
        return listOf(
            CourseMarkDisplayItem(
                label = "--",
                skipped = false
            )
        )
    }

    return cleaned
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { raw ->
            val skipped = skippedMarker.isNotBlank() &&
                    raw.contains(skippedMarker, ignoreCase = true)
            val label = if (skipped) {
                raw.replace(skippedMarker, "", ignoreCase = true).trim()
            } else {
                raw
            }

            CourseMarkDisplayItem(
                label = label,
                skipped = skipped
            )
        }
}

fun formatMarksForDisplay(
    raceMarksText: String,
    marksPrefix: String = "Marks:"
): String {
    val cleaned = raceMarksText
        .removePrefix(marksPrefix)
        .trim()

    if (cleaned.isBlank() || cleaned == "--") {
        return "--"
    }

    return cleaned
        .split(",")
        .joinToString("\n") { it.trim() }
}
