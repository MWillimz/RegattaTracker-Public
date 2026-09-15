package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceEntryAvailabilityTest {

    private val start = 2_000_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    @Test
    fun missingStartKeepsEnterRaceUnavailable() {
        assertFalse(isEnterRaceTimeAvailable(null, start))
    }

    @Test
    fun moreThan24HoursBeforeStartIsUnavailable() {
        assertFalse(isEnterRaceTimeAvailable(start, start - day - 1L))
    }

    @Test
    fun exactly24HoursBeforeStartIsAvailable() {
        assertTrue(isEnterRaceTimeAvailable(start, start - day))
    }

    @Test
    fun afterStartRemainsAvailable() {
        assertTrue(isEnterRaceTimeAvailable(start, start + 1L))
    }
}
