package de.williserv.regattaclient

import org.junit.Assert.assertEquals
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
    fun validSetupRejectsNonFiniteAndImplausibleYardstickValues() {
        assertFalse(isBoatSetupValid(confirmed.copy(yardstick = "NaN")))
        assertFalse(isBoatSetupValid(confirmed.copy(yardstick = "Infinity")))
        assertFalse(isBoatSetupValid(confirmed.copy(yardstick = "-Infinity")))
        assertFalse(isBoatSetupValid(confirmed.copy(yardstick = "1e309")))
        assertFalse(isBoatSetupValid(confirmed.copy(yardstick = "39.9")))
        assertFalse(isBoatSetupValid(confirmed.copy(yardstick = "160.1")))

        assertTrue(isBoatSetupValid(confirmed.copy(yardstick = "40")))
        assertTrue(isBoatSetupValid(confirmed.copy(yardstick = "97.5")))
        assertTrue(isBoatSetupValid(confirmed.copy(yardstick = "100")))
        assertTrue(isBoatSetupValid(confirmed.copy(yardstick = "160")))
    }

    @Test
    fun validSetupAcceptsSpecialCharactersAndOptionalHullColor() {
        assertTrue(
            isBoatSetupValid(
                confirmed.copy(
                    boatName = "Étoile II",
                    skipperName = "Jörg Weiß",
                    hullColor = "",
                    boatType = "J/22"
                )
            )
        )
    }

    @Test
    fun validSetupEnforcesLengthLimitForEachField() {
        val atLimit = confirmed.copy(
            boatName = "a".repeat(80),
            skipperName = "a".repeat(80),
            hullColor = "a".repeat(40),
            sailNumber = "a".repeat(40),
            yardstick = "97." + "5".repeat(13),
            boatType = "a".repeat(80)
        )
        assertTrue(isBoatSetupValid(atLimit))
        assertFalse(isBoatSetupValid(atLimit.copy(boatName = atLimit.boatName + "a")))
        assertFalse(isBoatSetupValid(atLimit.copy(skipperName = atLimit.skipperName + "a")))
        assertFalse(isBoatSetupValid(atLimit.copy(hullColor = atLimit.hullColor + "a")))
        assertFalse(isBoatSetupValid(atLimit.copy(sailNumber = atLimit.sailNumber + "a")))
        assertFalse(isBoatSetupValid(atLimit.copy(yardstick = atLimit.yardstick + "5")))
        assertFalse(isBoatSetupValid(atLimit.copy(boatType = atLimit.boatType + "a")))
    }

    @Test
    fun validSetupRejectsStoredLineBreaksInEveryField() {
        assertFalse(isBoatSetupValid(confirmed.copy(boatName = "Boat\r\nA")))
        assertFalse(isBoatSetupValid(confirmed.copy(skipperName = "Skipper\nA")))
        assertFalse(isBoatSetupValid(confirmed.copy(hullColor = "off\u0085white")))
        assertFalse(isBoatSetupValid(confirmed.copy(sailNumber = "GER\u20281234")))
        assertFalse(isBoatSetupValid(confirmed.copy(yardstick = "97.5\n")))
        assertFalse(isBoatSetupValid(confirmed.copy(boatType = "Banner\u202923")))
    }

    @Test
    fun fieldInputReplacesPastedLineBreaksAndTabsWithSpaces() {
        val separators = listOf("\r\n\n", "\r", "\n", "\u000B", "\u000C", "\u0085", "\u2028", "\u2029", "\t")
        for (separator in separators) {
            assertEquals("Banner 23", BoatSetupField.BOAT_TYPE.acceptInput("", "Banner" + separator + "23"))
        }
    }

    @Test
    fun fieldInputRejectsOverflowAndLetsUsersShortenAnOverlongSavedValue() {
        val field = BoatSetupField.SAIL_NUMBER
        val atLimit = "a".repeat(40)
        val overlong = "a".repeat(43)

        assertEquals(atLimit, field.acceptInput("", atLimit))
        assertEquals(atLimit, field.acceptInput(atLimit, atLimit + "a"))
        assertEquals(overlong, field.acceptInput(overlong, overlong + "a"))
        assertEquals(overlong.dropLast(1), field.acceptInput(overlong, overlong.dropLast(1)))
        assertEquals(atLimit, field.acceptInput(overlong, atLimit))
    }

    @Test
    fun yardstickInputAcceptsDecimalCommaAndIdentifiesInvalidNumbers() {
        val field = BoatSetupField.YARDSTICK
        val normalized = field.acceptInput("", "97,5")
        assertEquals("97.5", normalized)
        assertTrue(isBoatSetupValid(confirmed.copy(yardstick = normalized)))

        val invalid = field.acceptInput("97.", "97..5")
        assertEquals("97..5", invalid)
        assertEquals(BoatSetupFieldError.INVALID_YARDSTICK, field.validationError(invalid))
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
