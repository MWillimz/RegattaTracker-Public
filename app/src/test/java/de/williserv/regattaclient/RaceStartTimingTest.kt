package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test

class RaceStartTimingTest {

    @Test
    fun `mass start keeps existing event start timer`() {
        val timing = resolveRaceStartTiming(
            scoringMode = "mass_start",
            eventStartMillis = 1_000_000L,
            localStartMillis = null,
            nowMillis = 1_125_000L
        )

        assertEquals(RaceStartTimingPhase.ELAPSED, timing.phase)
        assertEquals(125L, timing.seconds)
    }

    @Test
    fun `flying start counts down to window opening`() {
        val timing = resolveRaceStartTiming(
            scoringMode = "flying_start",
            eventStartMillis = 1_000_000L,
            localStartMillis = null,
            nowMillis = 725_000L
        )

        assertEquals(RaceStartTimingPhase.COUNTDOWN, timing.phase)
        assertEquals(275L, timing.seconds)
    }

    @Test
    fun `flying start does not invent elapsed time before personal start`() {
        val timing = resolveRaceStartTiming(
            scoringMode = "flying_start",
            eventStartMillis = 1_000_000L,
            localStartMillis = null,
            nowMillis = 1_125_000L
        )

        assertEquals(RaceStartTimingPhase.FLYING_WINDOW_OPEN, timing.phase)
        assertEquals(null, timing.seconds)
    }

    @Test
    fun `flying start elapsed time uses personal start only`() {
        val timing = resolveRaceStartTiming(
            scoringMode = "flying_start",
            eventStartMillis = 1_000_000L,
            localStartMillis = 1_075_000L,
            nowMillis = 1_125_000L
        )

        assertEquals(RaceStartTimingPhase.ELAPSED, timing.phase)
        assertEquals(50L, timing.seconds)
    }

    @Test
    fun `start panel stays clear more than ten minutes before window`() {
        val timing = resolveRaceStartTiming(
            scoringMode = "flying_start",
            eventStartMillis = 2_000_000L,
            localStartMillis = null,
            nowMillis = 1_000_000L
        )

        assertEquals(RaceStartTimingPhase.CLEAR, timing.phase)
    }
}
