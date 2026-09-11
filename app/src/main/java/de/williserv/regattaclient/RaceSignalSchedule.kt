package de.williserv.regattaclient

internal enum class RaceSignalCue {
    FIVE_MINUTES,
    FOUR_MINUTES,
    ONE_MINUTE,
    START,
    FINISH
}

internal class RaceSignalSchedule {
    private var startEpochMillis: Long? = null
    private var previousRemainingMillis: Long? = null
    private var startSignalsAllowed = false
    private var previousFinished = false
    private val firedThresholds = mutableSetOf<Long>()

    fun reset(
        nowMillis: Long,
        startEpochMillis: Long?,
        allowStartSignals: Boolean,
        raceFinished: Boolean
    ) {
        this.startEpochMillis = startEpochMillis
        previousRemainingMillis = startEpochMillis?.minus(nowMillis)
        startSignalsAllowed = allowStartSignals
        previousFinished = raceFinished
        firedThresholds.clear()
    }

    fun update(
        nowMillis: Long,
        startEpochMillis: Long?,
        allowStartSignals: Boolean,
        raceFinished: Boolean
    ): List<RaceSignalCue> {
        val cues = mutableListOf<RaceSignalCue>()

        if (!previousFinished && raceFinished) {
            cues += RaceSignalCue.FINISH
        }
        previousFinished = raceFinished

        val remainingMillis = startEpochMillis?.minus(nowMillis)

        if (this.startEpochMillis != startEpochMillis) {
            this.startEpochMillis = startEpochMillis
            previousRemainingMillis = remainingMillis
            startSignalsAllowed = allowStartSignals
            firedThresholds.clear()
            return cues
        }

        if (startEpochMillis == null) {
            previousRemainingMillis = null
            startSignalsAllowed = allowStartSignals
            return cues
        }

        if (!allowStartSignals) {
            previousRemainingMillis = remainingMillis
            startSignalsAllowed = false
            return cues
        }

        if (!startSignalsAllowed) {
            previousRemainingMillis = remainingMillis
            startSignalsAllowed = true
            return cues
        }

        val previous = previousRemainingMillis
        if (previous != null && remainingMillis != null) {
            THRESHOLDS.forEach { (thresholdMillis, cue) ->
                if (
                    thresholdMillis !in firedThresholds &&
                    previous > thresholdMillis &&
                    remainingMillis <= thresholdMillis &&
                    remainingMillis >= thresholdMillis - MAX_SIGNAL_LATENESS_MILLIS
                ) {
                    firedThresholds += thresholdMillis
                    cues += cue
                }
            }
        }

        previousRemainingMillis = remainingMillis
        return cues
    }

    private companion object {
        const val MAX_SIGNAL_LATENESS_MILLIS = 2_000L

        val THRESHOLDS = listOf(
            5 * 60 * 1000L to RaceSignalCue.FIVE_MINUTES,
            4 * 60 * 1000L to RaceSignalCue.FOUR_MINUTES,
            60 * 1000L to RaceSignalCue.ONE_MINUTE,
            0L to RaceSignalCue.START
        )
    }
}
