package de.williserv.regattaclient

import android.content.Context

internal fun loadPersistedSeriesDisplayMetadata(context: Context): SeriesDisplayMetadata {
    val prefs = context.getSharedPreferences("race_setup", Context.MODE_PRIVATE)
    return SeriesDisplayMetadata(
        runName = prefs.getString("series_run_name", "").orEmpty(),
        occurrenceNo = prefs.getInt("series_occurrence_no", 0).takeIf { it > 0 },
        plannedRaceCount = prefs.getInt("series_planned_race_count", 0).takeIf { it > 0 }
    )
}
