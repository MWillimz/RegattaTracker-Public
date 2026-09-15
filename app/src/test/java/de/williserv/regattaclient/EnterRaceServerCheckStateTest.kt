package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnterRaceServerCheckStateTest {

    @Test
    fun staleGenerationIsIgnoredAfterNewCheckStarts() {
        val state = EnterRaceServerCheckState()
        val first = state.begin()
        val second = state.begin()

        assertFalse(state.isActive(first))
        assertTrue(state.isActive(second))
    }

    @Test
    fun finishedGenerationIsNoLongerActive() {
        val state = EnterRaceServerCheckState()
        val generation = state.begin()

        state.finish(generation)

        assertFalse(state.isActive(generation))
    }

    @Test
    fun cancelledGenerationIsNoLongerActive() {
        val state = EnterRaceServerCheckState()
        val generation = state.begin()

        state.cancel()

        assertFalse(state.isActive(generation))
    }

    @Test
    fun nullGenerationRepresentsNonEnterRaceFetch() {
        val state = EnterRaceServerCheckState()

        assertTrue(state.isActive(null))
    }
}
