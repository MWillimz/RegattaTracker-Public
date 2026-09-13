package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceOfflinePolicyTest {

    @Test
    fun enterRace_requiresSnapshotConfirmedBoatAndUsableSeriesRun() {
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
        assertFalse(
            canEnterRaceWithLocalState(
                raceDataReady = true,
                setupConfirmed = true,
                cachedSeriesRunObsolete = true
            )
        )
    }

    @Test
    fun finishedOrExpiredSeriesRun_requiresFreshOnlineResolution() {
        val now = 2_000L
        assertTrue(
            isCachedSeriesRunObsolete(
                isSeriesAccess = true,
                status = "finished",
                stopEpochMillis = null,
                nowEpochMillis = now
            )
        )
        assertTrue(
            isCachedSeriesRunObsolete(
                isSeriesAccess = true,
                status = "planned",
                stopEpochMillis = 1_999L,
                nowEpochMillis = now
            )
        )
        assertFalse(
            isCachedSeriesRunObsolete(
                isSeriesAccess = true,
                status = "planned",
                stopEpochMillis = 2_001L,
                nowEpochMillis = now
            )
        )
        assertFalse(
            isCachedSeriesRunObsolete(
                isSeriesAccess = false,
                status = "finished",
                stopEpochMillis = 1_000L,
                nowEpochMillis = now
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

    @Test
    fun snapshotMustContainAParseableRaceStart() {
        assertTrue(
            isUsableRaceEventSnapshot(
                RaceEventSnapshot(
                    resolvedEventName = "race-1",
                    status = "planned",
                    startRaw = "2026-09-20T12:00:00Z",
                    stopRaw = "2026-09-20T16:00:00Z",
                    raceInfo = "",
                    courseJson = "{}",
                    courseShortened = false
                )
            )
        )
        assertFalse(
            isUsableRaceEventSnapshot(
                RaceEventSnapshot(
                    resolvedEventName = "race-1",
                    status = "planned",
                    startRaw = "--",
                    stopRaw = "",
                    raceInfo = "",
                    courseJson = "{}",
                    courseShortened = false
                )
            )
        )
        assertFalse(
            isUsableRaceEventSnapshot(
                RaceEventSnapshot(
                    resolvedEventName = "race-1",
                    status = "planned",
                    startRaw = "2026-09-20T12:00:00Z",
                    stopRaw = "",
                    raceInfo = "",
                    courseJson = "{broken",
                    courseShortened = false
                )
            )
        )
    }
}
