package de.williserv.regattaclient

internal const val SCORING_MODE_FLYING_START = "flying_start"

internal fun isFlyingStart(scoringMode: String): Boolean =
    scoringMode.trim().equals(SCORING_MODE_FLYING_START, ignoreCase = true)

internal enum class RaceStartTimingPhase {
    CLEAR,
    COUNTDOWN,
    FLYING_WINDOW_OPEN,
    ELAPSED
}

internal data class RaceStartTiming(
    val phase: RaceStartTimingPhase,
    val seconds: Long? = null
)

internal fun resolveRaceStartTiming(
    scoringMode: String,
    eventStartMillis: Long?,
    localStartMillis: Long?,
    nowMillis: Long
): RaceStartTiming {
    val startMillis = eventStartMillis
        ?: return RaceStartTiming(RaceStartTimingPhase.CLEAR)

    val remainingSeconds = (startMillis - nowMillis) / 1000L
    if (remainingSeconds > 600L) {
        return RaceStartTiming(RaceStartTimingPhase.CLEAR)
    }
    if (remainingSeconds > 0L) {
        return RaceStartTiming(
            phase = RaceStartTimingPhase.COUNTDOWN,
            seconds = remainingSeconds
        )
    }

    if (isFlyingStart(scoringMode)) {
        val personalStart = localStartMillis
            ?.takeIf { it <= nowMillis }
            ?: return RaceStartTiming(RaceStartTimingPhase.FLYING_WINDOW_OPEN)

        return RaceStartTiming(
            phase = RaceStartTimingPhase.ELAPSED,
            seconds = ((nowMillis - personalStart) / 1000L).coerceAtLeast(0L)
        )
    }

    return RaceStartTiming(
        phase = RaceStartTimingPhase.ELAPSED,
        seconds = (-remainingSeconds).coerceAtLeast(0L)
    )
}
