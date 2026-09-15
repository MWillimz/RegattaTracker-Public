package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoatSetupStateTest {

    private val confirmed = BoatSetupValues(
        boatName = "Boat A",
        skipperName = "Skipper A",
        hullColor = "white",
        sailNumber = "GER 1234",
        yardstick = "100",
        boatType = "Laser"
    )

    @Test
    fun validSetupRequiresIdentityTypeAndNumericYardstick() {
        assertTrue(isBoatSetupValid(confirmed))
        assertFalse(isBoatSetupValid(confirmed.copy(boatName = "")))
        assertFalse(isBoatSetupValid(confirmed.copy(skipperName = "")))
        assertFalse(isBoatSetupValid(confirmed.copy(sailNumber = "")))
        assertFalse(isBoatSetupValid(confirmed.copy(boatType = "")))
        assertFalse(isBoatSetupValid(confirmed.copy(yardstick = "abc")))
    }

    @Test
    fun firstConfirmationDoesNotInvalidateExistingRaceState() {
        val next = confirmed.copy(sailNumber = "GER 9999")

        assertFalse(
            shouldInvalidateRaceRegistration(
                previous = confirmed,
                next = next,
                hadConfirmedSetup = false
            )
        )
        assertFalse(
            shouldInvalidateRaceLegal(
                previous = confirmed,
                next = next,
                hadConfirmedSetup = false
            )
        )
    }

    @Test
    fun anyConfirmedBoatChangeInvalidatesRegistration() {
        assertTrue(
            shouldInvalidateRaceRegistration(
                previous = confirmed,
                next = confirmed.copy(hullColor = "blue"),
                hadConfirmedSetup = true
            )
        )
    }

    @Test
    fun legalInvalidationOnlyTracksLegalIdentityFields() {
        assertTrue(
            shouldInvalidateRaceLegal(
                previous = confirmed,
                next = confirmed.copy(sailNumber = "GER 9999"),
                hadConfirmedSetup = true
            )
        )
        assertTrue(
            shouldInvalidateRaceLegal(
                previous = confirmed,
                next = confirmed.copy(boatName = "Boat B"),
                hadConfirmedSetup = true
            )
        )
        assertTrue(
            shouldInvalidateRaceLegal(
                previous = confirmed,
                next = confirmed.copy(skipperName = "Skipper B"),
                hadConfirmedSetup = true
            )
        )
        assertFalse(
            shouldInvalidateRaceLegal(
                previous = confirmed,
                next = confirmed.copy(yardstick = "101"),
                hadConfirmedSetup = true
            )
        )
    }
}
