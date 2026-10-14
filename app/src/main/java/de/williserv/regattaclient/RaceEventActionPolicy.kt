package de.williserv.regattaclient

private const val REGISTER_CUTOFF_MILLIS = 60L * 60L * 1000L
private const val ENTER_FOCUS_MILLIS = 24L * REGISTER_CUTOFF_MILLIS

/** Presentation and availability of the two existing pre-entry event actions. */
internal data class RaceEventActionState(
    val registerEnabled: Boolean,
    val enterEnabled: Boolean,
    val registerHighlighted: Boolean,
    val raceEnded: Boolean,
    val enterTimeAvailable: Boolean
)

internal fun resolveRaceEventActions(
    startEpochMillis: Long?,
    stopEpochMillis: Long?,
    nowEpochMillis: Long,
    raceStatusCode: String,
    raceRegistered: Boolean,
    baseCanRegister: Boolean,
    baseCanEnter: Boolean
): RaceEventActionState {
    val raceEnded =
        raceStatusCode.trim().equals("finished", ignoreCase = true) ||
            raceStatusCode.trim().equals("cancelled", ignoreCase = true) ||
            (stopEpochMillis != null && nowEpochMillis >= stopEpochMillis)
    val enterTimeAvailable = isEnterRaceTimeAvailable(startEpochMillis, nowEpochMillis)
    val registerEnabled = !raceEnded && !raceRegistered && baseCanRegister &&
        startEpochMillis != null && nowEpochMillis < startEpochMillis - REGISTER_CUTOFF_MILLIS
    val enterEnabled = !raceEnded && baseCanEnter && enterTimeAvailable

    return RaceEventActionState(
        registerEnabled = registerEnabled,
        enterEnabled = enterEnabled,
        registerHighlighted = registerEnabled &&
            startEpochMillis != null &&
            nowEpochMillis < startEpochMillis - ENTER_FOCUS_MILLIS,
        raceEnded = raceEnded,
        enterTimeAvailable = enterTimeAvailable
    )
}
