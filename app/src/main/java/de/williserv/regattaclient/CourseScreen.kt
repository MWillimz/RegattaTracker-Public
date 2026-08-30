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
            raceShortenedText = raceShortenedText
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
            raceMarksText = raceMarksText,
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
                    text = """
                    Override current target to:
                    
                    ${pending.confirmLabel}

                    Only use this if the displayed course progress is wrong.

                    Be prepared to discuss telemetry with Event Management.
                """.trimIndent()
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
        .replace("Next:", "")
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
    raceMarksText: String,
    onDismiss: () -> Unit,
    onSelectOption: (CourseProgressOption) -> Unit
) {
    val options = courseProgressOptions(raceMarksText)

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

@Composable
fun courseProgressOptions(
    raceMarksText: String
): List<CourseProgressOption> {
    val marks = courseMarkDisplayItems(raceMarksText)
        .filter { it.label.isNotBlank() && it.label != "--" }

    val activeMarks = marks.filterNot { it.skipped }

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
            val activeIndex = activeMarks.indexOfFirst { it.label == mark.label }

            add(
                CourseProgressOption(
                    label = if (mark.skipped) {
                        stringResource(R.string.mark_skipped, mark.label)
                    } else {
                        mark.label
                    },
                    confirmLabel = mark.label,
                    passedMarks = if (mark.skipped) 0 else activeIndex,
                    raceStarted = true,
                    enabled = !mark.skipped
                )
            )
        }

        add(
            CourseProgressOption(
                label = stringResource(R.string.finish_line),
                confirmLabel = stringResource(R.string.finish_line),
                passedMarks = activeMarks.size,
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
    raceShortenedText: String
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
                text = raceStatusText.replace("Race:", "Status:"),
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 8.dp)
            )

            Text(
                text = raceStartText.replace("Start:", "Start:"),
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            Text(
                text = raceStopText.replace("Stop:", "Stop:"),
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
                        .replace("Start line:", "")
                        .replace("Startlinie:", "")
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
                    courseMarkDisplayItems(raceMarksText).forEach { item ->
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
                        .replace("Finish line:", "")
                        .replace("Ziellinie:", "")
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
    raceMarksText: String
): List<CourseMarkDisplayItem> {
    val cleaned = raceMarksText
        .replace("Marks:", "")
        .replace("Marken:", "")
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
            val skipped = raw.contains("[skipped]", ignoreCase = true)
            val label = raw
                .replace("[skipped]", "", ignoreCase = true)
                .trim()

            CourseMarkDisplayItem(
                label = label,
                skipped = skipped
            )
        }
}

fun formatMarksForDisplay(
    raceMarksText: String
): String {
    val cleaned = raceMarksText
        .replace("Marks:", "")
        .replace("Marken:", "")
        .trim()

    if (cleaned.isBlank() || cleaned == "--") {
        return "--"
    }

    return cleaned
        .split(",")
        .joinToString("\n") { it.trim() }
}
