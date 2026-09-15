package de.williserv.regattaclient

private const val ENTER_RACE_WINDOW_MILLIS = 24L * 60L * 60L * 1000L

internal fun isEnterRaceTimeAvailable(
    raceStartEpochMillis: Long?,
    nowEpochMillis: Long
): Boolean {
    val startEpochMillis = raceStartEpochMillis ?: return false
    return nowEpochMillis >= startEpochMillis - ENTER_RACE_WINDOW_MILLIS
}
