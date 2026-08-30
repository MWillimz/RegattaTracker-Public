package de.williserv.regattaclient

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.stringResource
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ResultRow(
    val rank: Int?,
    val boatName: String,
    val sailNumber: String,
    val status: String,
    val officialFinishTime: String?,
    val correctedTime: String
)
@Composable
fun ResultsScreen(
    raceEvent: String,
    published: Boolean,
    publishedAt: String,
    statusText: String,
    rows: List<ResultRow>,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Results",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.event_value, raceEvent.ifBlank { "--" }),
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (publishedAt.isNotBlank()) {
            Text(
                text = stringResource(R.string.published_value, formatPublishedAt(publishedAt)),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!published) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = statusText.ifBlank { "Results not published yet" },
                    fontSize = 18.sp,
                    modifier = Modifier.padding(18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            ResultsTable(rows = rows)
        }

        Spacer(modifier = Modifier.height(18.dp))

        Button(
            onClick = onRefresh,
            colors = primaryButtonColors(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.refresh))
        }

        Button(
            onClick = onBack,
            colors = primaryButtonColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        ) {
            Text(stringResource(R.string.back))
        }
    }
}

@Composable
fun ResultsTable(
    rows: List<ResultRow>
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            ResultTableRow(
                rank = "Rank",
                boatAndSail = "Boat / Sail",
                correctedTime = "Time",
                status = "Status",
                isHeader = true
            )

            rows.forEach { row ->
                ResultTableRow(
                    rank = row.rank?.toString() ?: "-",
                    boatAndSail = listOf(row.boatName, row.sailNumber)
                        .filter { it.isNotBlank() }
                        .joinToString(" / ")
                        .ifBlank { "-" },
                    correctedTime = row.correctedTime.ifBlank { "-" },
                    status = row.status.ifBlank { "-" },
                    isHeader = false
                )
            }
        }
    }
}

@Composable
fun ResultTableRow(
    rank: String,
    boatAndSail: String,
    correctedTime: String,
    status: String,
    isHeader: Boolean
) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp)
    ) {
        TableCell(rank, 56, isHeader)
        TableCell(boatAndSail, 180, isHeader)
        TableCell(correctedTime, 90, isHeader)
        TableCell(status, 120, isHeader)
    }
}

@Composable
fun TableCell(
    text: String,
    widthDp: Int,
    isHeader: Boolean
) {
    Text(
        text = text,
        fontSize = if (isHeader) 15.sp else 14.sp,
        fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .width(widthDp.dp)
            .padding(end = 10.dp)
    )
}

fun formatPublishedAt(
    value: String
): String {
    if (value.isBlank()) return "--"

    return try {
        val instant = OffsetDateTime
            .parse(value)
            .toInstant()

        DateTimeFormatter
            .ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY)
            .withZone(ZoneId.systemDefault())
            .format(instant)
    } catch (_: Exception) {
        value
    }
}