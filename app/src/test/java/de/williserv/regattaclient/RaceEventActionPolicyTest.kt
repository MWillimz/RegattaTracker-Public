package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceEventActionPolicyTest {
    private val start = 2_000_000_000_000L
    private val hour = 60L * 60L * 1000L
    private val day = 24L * hour

    private fun actions(
        now: Long,
        raceStart: Long? = start,
        stop: Long? = start + 3L * hour,
        status: String = "racing",
        registered: Boolean = false,
        canRegister: Boolean = true,
        canEnter: Boolean = true
    ) = resolveRaceEventActions(
        startEpochMillis = raceStart,
        stopEpochMillis = stop,
        nowEpochMillis = now,
        raceStatusCode = status,
        raceRegistered = registered,
        baseCanRegister = canRegister,
        baseCanEnter = canEnter
    )

    @Test
    fun before24HoursRegisterIsHighlightedAndEnterDisabled() {
        val state = actions(start - 48L * hour)
        assertTrue(state.registerEnabled)
        assertTrue(state.registerHighlighted)
        assertFalse(state.enterEnabled)
    }

    @Test
    fun exactly24HoursFocusMovesToEnterWithoutDisablingRegister() {
        val before = actions(start - day - 1L)
        val boundary = actions(start - day)
        assertTrue(before.registerHighlighted)
        assertFalse(before.enterEnabled)
        assertTrue(boundary.registerEnabled)
        assertFalse(boundary.registerHighlighted)
        assertTrue(boundary.enterEnabled)
    }

    @Test
    fun registrationEndsExactlyOneHourBeforeStart() {
        val before = actions(start - hour - 1L)
        val boundary = actions(start - hour)
        assertTrue(before.registerEnabled)
        assertFalse(before.registerHighlighted)
        assertFalse(boundary.registerEnabled)
        assertTrue(boundary.enterEnabled)
    }

    @Test
    fun afterStartEnterRemainsAvailableUntilStop() {
        val inProgress = actions(start + hour)
        assertFalse(inProgress.registerEnabled)
        assertTrue(inProgress.enterEnabled)
        assertFalse(inProgress.raceEnded)
    }

    @Test
    fun stopTimeHasPriorityIncludingExactBoundary() {
        val before = actions(start + 3L * hour - 1L)
        val atStop = actions(start + 3L * hour)
        assertTrue(before.enterEnabled)
        assertFalse(atStop.enterEnabled)
        assertFalse(atStop.registerEnabled)
        assertTrue(atStop.raceEnded)
    }

    @Test
    fun finishedStatusImmediatelyDisablesBothActions() {
        val state = actions(start - 2L * day, status = " Finished ")
        assertTrue(state.raceEnded)
        assertFalse(state.registerEnabled)
        assertFalse(state.registerHighlighted)
        assertFalse(state.enterEnabled)
    }

    @Test
    fun cancelledStatusImmediatelyDisablesBothActions() {
        val state = actions(start - 2L * hour, status = " CaNcElLeD ")
        assertTrue(state.raceEnded)
        assertFalse(state.registerEnabled)
        assertFalse(state.registerHighlighted)
        assertFalse(state.enterEnabled)
    }

    @Test
    fun registeredBoatCannotRegisterAgainButMayEnter() {
        val state = actions(start - hour * 2L, registered = true)
        assertFalse(state.registerEnabled)
        assertTrue(state.enterEnabled)
    }

    @Test
    fun existingRequirementsAlwaysTakePrecedence() {
        val early = actions(start - day * 2L, canRegister = false)
        assertFalse(early.registerEnabled)
        assertFalse(early.registerHighlighted)

        val late = actions(start - hour * 2L, canEnter = false)
        assertFalse(late.enterEnabled)
        assertTrue(late.registerEnabled)
    }

    @Test
    fun missingStartNeverEnablesEitherAction() {
        val state = actions(start, raceStart = null)
        assertFalse(state.registerEnabled)
        assertFalse(state.enterEnabled)
        assertFalse(state.registerHighlighted)
    }

    @Test
    fun currentRunStartTimeControlsThresholds() {
        val nextRunStart = start + 2L * day
        val state = actions(start, raceStart = nextRunStart, stop = nextRunStart + 2L * hour)
        assertTrue(state.registerEnabled)
        assertTrue(state.registerHighlighted)
        assertFalse(state.enterEnabled)
    }
}
