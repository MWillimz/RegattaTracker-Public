package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceOfflinePolicyTest {

    @Test
    fun enterRace_requiresOnlySnapshotAndConfirmedBoatSetup() {
        assertTrue(
            canEnterRaceWithLocalState(
                raceDataReady = true,
                setupConfirmed = true
            )
        )
        assertFalse(
            canEnterRaceWithLocalState(
                raceDataReady = false,
                setupConfirmed = true
            )
        )
        assertFalse(
            canEnterRaceWithLocalState(
                raceDataReady = true,
                setupConfirmed = false
            )
        )
    }

    @Test
    fun transientServerFailuresPreserveLastKnownGoodSnapshot() {
        assertTrue(shouldPreserveEventSnapshotForHttpStatus(408))
        assertTrue(shouldPreserveEventSnapshotForHttpStatus(429))
        assertTrue(shouldPreserveEventSnapshotForHttpStatus(500))
        assertTrue(shouldPreserveEventSnapshotForHttpStatus(503))
    }

    @Test
    fun authoritativeAccessFailuresInvalidateSnapshot() {
        assertTrue(shouldInvalidateEventSnapshotForHttpStatus(401))
        assertTrue(shouldInvalidateEventSnapshotForHttpStatus(403))
        assertTrue(shouldInvalidateEventSnapshotForHttpStatus(404))
        assertFalse(shouldInvalidateEventSnapshotForHttpStatus(409))
        assertFalse(shouldInvalidateEventSnapshotForHttpStatus(500))
    }
}
