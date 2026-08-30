package de.williserv.regattaclient

import org.json.JSONObject

data class SeriesDisplayMetadata(
    val runName: String = "",
    val occurrenceNo: Int? = null,
    val plannedRaceCount: Int? = null
) {
    fun orderText(): String {
        val occurrence = occurrenceNo?.takeIf { it > 0 } ?: return ""
        val planned = plannedRaceCount?.takeIf { it > 0 } ?: return ""
        return "$occurrence of $planned"
    }
}

internal fun parseSeriesDisplayMetadata(eventJson: JSONObject): SeriesDisplayMetadata {
    val series = eventJson.optJSONObject("series") ?: return SeriesDisplayMetadata()

    val runName = if (series.has("run_name") && !series.isNull("run_name")) {
        series.optString("run_name", "")
    } else {
        ""
    }

    return SeriesDisplayMetadata(
        runName = runName,
        occurrenceNo = positiveIntOrNull(series, "occurrence_no"),
        plannedRaceCount = positiveIntOrNull(series, "planned_race_count")
    )
}

private fun positiveIntOrNull(json: JSONObject, key: String): Int? {
    if (!json.has(key) || json.isNull(key)) return null
    return json.optInt(key, 0).takeIf { it > 0 }
}

internal fun buildEventHeaderLines(
    raceEvent: String,
    raceDataReady: Boolean,
    seriesDisplayMetadata: SeriesDisplayMetadata
): List<String> {
    if (!raceDataReady || raceEvent.isBlank()) return emptyList()

    return buildList {
        add(raceEvent)

        if (seriesDisplayMetadata.runName.isNotBlank()) {
            add(seriesDisplayMetadata.runName)
        }

        val orderText = seriesDisplayMetadata.orderText()
        if (orderText.isNotBlank()) {
            add(orderText)
        }
    }
}
