package de.williserv.regattaclient

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RaceEventSnapshotStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearRaceSetup()
    }

    @After
    fun tearDown() {
        clearRaceSetup()
    }

    @Test
    fun `save keeps resolved run and series display metadata in the same snapshot`() {
        val snapshot = RaceEventSnapshot(
            resolvedEventName = "Series Race 2",
            status = "planned",
            startRaw = "2026-09-20T12:00:00Z",
            stopRaw = "2026-09-20T16:00:00Z",
            raceInfo = "Race two",
            courseJson = "{}",
            courseShortened = false,
            seriesDisplayMetadata = SeriesDisplayMetadata(
                runName = "Sunday Race",
                occurrenceNo = 2,
                plannedRaceCount = 5
            )
        )

        RaceEventSnapshotStore.save(
            context = context,
            server = "https://raceoffice.example.org",
            event = "Stable Series",
            secret = "secret",
            snapshot = snapshot
        )

        val restored = RaceEventSnapshotStore.loadMatching(
            context = context,
            server = "https://raceoffice.example.org",
            event = "Stable Series",
            secret = "secret"
        )

        assertNotNull(restored)
        assertEquals("Series Race 2", restored?.resolvedEventName)
        assertEquals("Sunday Race", restored?.seriesDisplayMetadata?.runName)
        assertEquals(2, restored?.seriesDisplayMetadata?.occurrenceNo)
        assertEquals(5, restored?.seriesDisplayMetadata?.plannedRaceCount)
    }

    @Test
    fun `new non-series snapshot clears stale series display metadata`() {
        val seriesSnapshot = RaceEventSnapshot(
            resolvedEventName = "Series Race 1",
            status = "planned",
            startRaw = "2026-09-20T10:00:00Z",
            stopRaw = "2026-09-20T11:00:00Z",
            raceInfo = "",
            courseJson = "{}",
            courseShortened = false,
            seriesDisplayMetadata = SeriesDisplayMetadata(
                runName = "Morning Race",
                occurrenceNo = 1,
                plannedRaceCount = 3
            )
        )
        RaceEventSnapshotStore.save(
            context = context,
            server = "https://raceoffice.example.org",
            event = "Stable Series",
            secret = "secret",
            snapshot = seriesSnapshot
        )

        val directEventSnapshot = RaceEventSnapshot(
            resolvedEventName = "Direct Race",
            status = "planned",
            startRaw = "2026-09-21T10:00:00Z",
            stopRaw = "2026-09-21T11:00:00Z",
            raceInfo = "",
            courseJson = "{}",
            courseShortened = false
        )
        RaceEventSnapshotStore.save(
            context = context,
            server = "https://raceoffice.example.org",
            event = "Direct Race",
            secret = "secret-2",
            snapshot = directEventSnapshot
        )

        val prefs = context.getSharedPreferences("race_setup", Context.MODE_PRIVATE)
        assertEquals("Direct Race", prefs.getString("resolved_event_name", ""))
        assertEquals("", prefs.getString("series_run_name", ""))
        assertEquals(0, prefs.getInt("series_occurrence_no", -1))
        assertEquals(0, prefs.getInt("series_planned_race_count", -1))
    }

    @Test
    fun `parser carries series display metadata into the snapshot`() {
        val snapshot = parseRaceEventSnapshot(
            """
            {
              "event_name": "Series Race 3",
              "race_status": "planned",
              "start_time": "2026-09-20T14:00:00Z",
              "stop_time": "2026-09-20T15:00:00Z",
              "series": {
                "run_name": "Afternoon Race",
                "occurrence_no": 3,
                "planned_race_count": 4
              }
            }
            """.trimIndent()
        )

        assertEquals("Afternoon Race", snapshot.seriesDisplayMetadata.runName)
        assertEquals(3, snapshot.seriesDisplayMetadata.occurrenceNo)
        assertEquals(4, snapshot.seriesDisplayMetadata.plannedRaceCount)
    }

    private fun clearRaceSetup() {
        context.getSharedPreferences("race_setup", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
