package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test

class RaceHomeStatusTest {

    @Test
    fun unresolvedConfiguredRace_isChecking() {
        assertEquals(
            RaceHomeStatus.CHECKING,
            resolveRaceHomeStatus(
                raceStatusCode = "",
                raceDataReady = false,
                raceConfigured = true,
                inRace = false,
                raceRegistered = false,
                millisToStart = null
            )
        )
    }

    @Test
    fun unavailableDataWithKnownStatus_preservesServerStatusForErrorDisplay() {
        assertEquals(
            RaceHomeStatus.SERVER_STATUS,
            resolveRaceHomeStatus(
                raceStatusCode = "racing",
                raceDataReady = false,
                raceConfigured = true,
                inRace = false,
                raceRegistered = false,
                millisToStart = 0L
            )
        )
    }

    @Test
    fun prestartNotEntered_isExplicitError() {
        assertEquals(
            RaceHomeStatus.NOT_ENTERED,
            resolveRaceHomeStatus(
                raceStatusCode = "planned",
                raceDataReady = true,
                raceConfigured = true,
                inRace = false,
                raceRegistered = true,
                millisToStart = RACE_OPERATIONAL_PRESTART_MILLIS
            )
        )
    }

    @Test
    fun registeredBeforeOperationalPrestart_isNeutralRegisteredState() {
        assertEquals(
            RaceHomeStatus.REGISTERED_NOT_ENTERED,
            resolveRaceHomeStatus(
                raceStatusCode = "planned",
                raceDataReady = true,
                raceConfigured = true,
                inRace = false,
                raceRegistered = true,
                millisToStart = RACE_OPERATIONAL_PRESTART_MILLIS + 1L
            )
        )
    }

    @Test
    fun runningRaceNotEntered_isExplicitError() {
        assertEquals(
            RaceHomeStatus.NOT_ENTERED,
            resolveRaceHomeStatus(
                raceStatusCode = "racing",
                raceDataReady = true,
                raceConfigured = true,
                inRace = false,
                raceRegistered = true,
                millisToStart = -1L
            )
        )
    }

    @Test
    fun enteredPrestart_isReady() {
        assertEquals(
            RaceHomeStatus.READY,
            resolveRaceHomeStatus(
                raceStatusCode = "planned",
                raceDataReady = true,
                raceConfigured = true,
                inRace = true,
                raceRegistered = true,
                millisToStart = 5L * 60L * 1000L
            )
        )
    }

    @Test
    fun enteredRunningRace_isRacing() {
        assertEquals(
            RaceHomeStatus.RACING,
            resolveRaceHomeStatus(
                raceStatusCode = "started",
                raceDataReady = true,
                raceConfigured = true,
                inRace = true,
                raceRegistered = true,
                millisToStart = -1L
            )
        )
    }

    @Test
    fun terminalRace_keepsServerStatus() {
        assertEquals(
            RaceHomeStatus.SERVER_STATUS,
            resolveRaceHomeStatus(
                raceStatusCode = "finished",
                raceDataReady = true,
                raceConfigured = true,
                inRace = false,
                raceRegistered = true,
                millisToStart = -60_000L
            )
        )
    }
}
