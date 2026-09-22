package de.williserv.regattaclient

import java.text.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Date

data class SessionDetailData(
    val session: TrackingSessionSummary,
    val statistics: SessionStatistics
)

@Composable
fun SessionHistoryScreen(
    sessions: List<TrackingSessionSummary>,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onSessionClick: (Long) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text(
            text = stringResource(R.string.session_history_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        when {
            loading -> {
                CircularProgressIndicator()
            }

            sessions.isEmpty() -> {
                Text(
                    text = stringResource(R.string.session_history_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        SessionSummaryCard(
                            session = session,
                            onClick = { onSessionClick(session.id) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.session_back))
        }
    }
}

@Composable
private fun SessionSummaryCard(
    session: TrackingSessionSummary,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = session.displayName,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = sessionModeText(session),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            session.eventIdentifier
                ?.takeIf { it.isNotBlank() }
                ?.let { event ->
                    Text(
                        text = stringResource(R.string.session_event_value, event),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = stringResource(
                    R.string.session_start_value,
                    formatSessionDateTime(session.startedAt)
                )
            )
            Text(
                text = stringResource(
                    R.string.session_duration_value,
                    formatSessionDuration(
                        startedAt = session.startedAt,
                        endedAt = session.endedAt
                    )
                )
            )
            Text(
                text = stringResource(
                    R.string.session_samples_value,
                    session.sampleCount
                )
            )
        }
    }
}

@Composable
fun SessionDetailScreen(
    detail: SessionDetailData?,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text(
            text = stringResource(R.string.session_details_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (loading) {
            CircularProgressIndicator()
        } else if (detail != null) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = detail.session.displayName,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                item {
                    SessionDetailRow(
                        label = stringResource(R.string.session_mode_label),
                        value = sessionModeText(detail.session)
                    )
                }

                detail.session.eventIdentifier
                    ?.takeIf { it.isNotBlank() }
                    ?.let { event ->
                        item {
                            SessionDetailRow(
                                label = stringResource(R.string.session_event_label),
                                value = event
                            )
                        }
                    }

                item {
                    SessionDetailRow(
                        label = stringResource(R.string.session_start_label),
                        value = formatSessionDateTime(detail.session.startedAt)
                    )
                }

                item {
                    SessionDetailRow(
                        label = stringResource(R.string.session_end_label),
                        value = detail.session.endedAt?.let(::formatSessionDateTime)
                            ?: stringResource(R.string.session_running)
                    )
                }

                item {
                    SessionDetailRow(
                        label = stringResource(R.string.session_duration_label),
                        value = formatDurationMillis(detail.statistics.durationMs)
                    )
                }

                item {
                    SessionDetailRow(
                        label = stringResource(R.string.session_samples_label),
                        value = detail.statistics.sampleCount.toString()
                    )
                }

                item {
                    SessionDetailRow(
                        label = stringResource(R.string.session_distance_label),
                        value = stringResource(
                            R.string.session_distance_nm,
                            detail.statistics.distanceM / 1852.0
                        )
                    )
                }

                item {
                    SessionDetailRow(
                        label = stringResource(R.string.session_average_sog_label),
                        value = detail.statistics.averageSogMps?.let { sog ->
                            stringResource(
                                R.string.session_speed_kn,
                                sog * 1.9438444924406
                            )
                        } ?: stringResource(R.string.session_unknown_value)
                    )
                }

                item {
                    SessionDetailRow(
                        label = stringResource(R.string.session_max_sog_label),
                        value = detail.statistics.maxSogMps?.let { sog ->
                            stringResource(
                                R.string.session_speed_kn,
                                sog * 1.9438444924406
                            )
                        } ?: stringResource(R.string.session_unknown_value)
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.session_details_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.session_back))
        }
    }
}

@Composable
private fun SessionDetailRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.45f)
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.55f)
        )
    }
}

@Composable
private fun sessionModeText(session: TrackingSessionSummary): String {
    return if (session.mode == "race") {
        stringResource(R.string.session_mode_race)
    } else {
        stringResource(R.string.session_mode_manual)
    }
}

@Composable
private fun formatSessionDuration(
    startedAt: Long,
    endedAt: Long?
): String {
    if (endedAt == null) return stringResource(R.string.session_running)
    return formatDurationMillis((endedAt - startedAt).coerceAtLeast(0L))
}

@Composable
private fun formatDurationMillis(durationMs: Long): String {
    val totalMinutes = durationMs.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L

    return if (hours > 0L) {
        stringResource(R.string.session_duration_hours_minutes, hours, minutes)
    } else {
        stringResource(R.string.session_duration_minutes, minutes)
    }
}

private fun formatSessionDateTime(epochMillis: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(epochMillis))
}
