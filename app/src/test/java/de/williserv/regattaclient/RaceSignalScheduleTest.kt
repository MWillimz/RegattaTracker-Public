package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceSignalScheduleTest {
    @Test
    fun emitsEachStartSignalOnceWhenThresholdIsCrossed() {
        val schedule = RaceSignalSchedule()
        val start = 1_000_000L

        schedule.reset(
            nowMillis = start - 301_000L,
            startEpochMillis = start,
            allowStartSignals = true,
            raceFinished = false
        )

        assertEquals(
            listOf(RaceSignalCue.FIVE_MINUTES),
            schedule.update(start - 299_900L, start, true, false)
        )
        assertTrue(schedule.update(start - 250_000L, start, true, false).isEmpty())
        assertEquals(
            listOf(RaceSignalCue.FOUR_MINUTES),
            schedule.update(start - 239_500L, start, true, false)
        )
        assertEquals(
            listOf(RaceSignalCue.ONE_MINUTE),
            schedule.update(start - 59_500L, start, true, false)
        )
        assertEquals(
            listOf(RaceSignalCue.START),
            schedule.update(start + 100L, start, true, false)
        )
        assertTrue(schedule.update(start + 1_000L, start, true, false).isEmpty())
    }

    @Test
    fun delayedTickStillEmitsSlightlyLateCrossedThreshold() {
        val schedule = RaceSignalSchedule()
        val start = 1_000_000L

        schedule.reset(start - 305_000L, start, true, false)

        assertEquals(
            listOf(RaceSignalCue.FIVE_MINUTES),
            schedule.update(start - 299_000L, start, true, false)
        )
    }

    @Test
    fun longDelayDoesNotReplayStaleStartSignals() {
        val schedule = RaceSignalSchedule()
        val start = 1_000_000L

        schedule.reset(start - 305_000L, start, true, false)

        assertTrue(schedule.update(start - 230_000L, start, true, false).isEmpty())
    }

    @Test
    fun enablingLateDoesNotReplayMissedSignals() {
        val schedule = RaceSignalSchedule()
        val start = 1_000_000L

        schedule.reset(start - 210_000L, start, true, false)

        assertTrue(schedule.update(start - 200_000L, start, true, false).isEmpty())
        assertEquals(
            listOf(RaceSignalCue.ONE_MINUTE),
            schedule.update(start - 59_500L, start, true, false)
        )
    }

    @Test
    fun postponedStartDoesNotBackfillSignalsWhenResumed() {
        val schedule = RaceSignalSchedule()
        val start = 1_000_000L

        schedule.reset(start - 305_000L, start, true, false)
        assertTrue(schedule.update(start - 299_000L, start, false, false).isEmpty())
        assertTrue(schedule.update(start - 250_000L, start, true, false).isEmpty())
        assertEquals(
            listOf(RaceSignalCue.FOUR_MINUTES),
            schedule.update(start - 239_500L, start, true, false)
        )
    }

    @Test
    fun changedStartTimeResetsThresholdStateWithoutImmediateCue() {
        val schedule = RaceSignalSchedule()
        val firstStart = 1_000_000L
        val movedStart = 1_600_000L

        schedule.reset(firstStart - 301_000L, firstStart, true, false)
        assertEquals(
            listOf(RaceSignalCue.FIVE_MINUTES),
            schedule.update(firstStart - 299_000L, firstStart, true, false)
        )

        assertTrue(
            schedule.update(
                nowMillis = firstStart - 200_000L,
                startEpochMillis = movedStart,
                allowStartSignals = true,
                raceFinished = false
            ).isEmpty()
        )
        assertEquals(
            listOf(RaceSignalCue.FIVE_MINUTES),
            schedule.update(movedStart - 299_000L, movedStart, true, false)
        )
    }

    @Test
    fun finishCueIsOnlyEmittedOnFalseToTrueTransition() {
        val schedule = RaceSignalSchedule()

        schedule.reset(
            nowMillis = 0L,
            startEpochMillis = null,
            allowStartSignals = true,
            raceFinished = false
        )

        assertEquals(
            listOf(RaceSignalCue.FINISH),
            schedule.update(1L, null, true, true)
        )
        assertTrue(schedule.update(2L, null, true, true).isEmpty())
    }

    @Test
    fun alreadyFinishedAtResetDoesNotPlayFinishCue() {
        val schedule = RaceSignalSchedule()

        schedule.reset(
            nowMillis = 0L,
            startEpochMillis = null,
            allowStartSignals = true,
            raceFinished = true
        )

        assertTrue(schedule.update(1L, null, true, true).isEmpty())
    }
}
