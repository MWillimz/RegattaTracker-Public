package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class RaceEntrySampleFactoryTest {

    @Test
    fun enterRaceSample_usesCurrentBoatSetupAndRegistrationTimestamp() {
        val setup = BoatSetupValues(
            boatName = "Current Boat",
            skipperName = "Current Skipper",
            hullColor = "blue",
            sailNumber = "GER 4242",
            yardstick = "97.5",
            boatType = "J/70"
        )

        val sample = buildRaceEntrySample(
            rawRaceStart = "2026-09-20T12:00:00Z",
            boatSetup = setup,
            sequenceId = 1234L
        )

        assertNotNull(sample)
        sample!!
        assertEquals(1234L, sample.sequenceId)
        assertEquals("2026-09-20T11:30:00Z", sample.timestamp)
        assertEquals("Current Boat", sample.boatName)
        assertEquals("Current Skipper", sample.captainName)
        assertEquals("blue", sample.hullColor)
        assertEquals("GER 4242", sample.sailNumber)
        assertEquals(97.5, sample.yardstick, 0.0)
        assertEquals("J/70", sample.boatType)
        assertEquals(0.0, sample.lat, 0.0)
        assertEquals(0.0, sample.lon, 0.0)
        assertEquals(9999f, sample.accuracy, 0f)
    }

    @Test
    fun enterRaceSample_requiresAValidRaceStart() {
        assertNull(
            buildRaceEntrySample(
                rawRaceStart = "--",
                boatSetup = BoatSetupValues(
                    boatName = "Boat",
                    skipperName = "Skipper",
                    hullColor = "white",
                    sailNumber = "GER 1",
                    yardstick = "100",
                    boatType = "Type"
                ),
                sequenceId = 1L
            )
        )
    }
}
