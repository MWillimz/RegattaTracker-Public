package de.williserv.regattaclient

import android.content.Context
import androidx.compose.runtime.MutableState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewRaceEntryCacheSafetyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB_NAME)
        clearPrefs()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
        clearPrefs()
    }

    @Test
    fun `race entry rechecks readiness after confirmation dialog`() {
        context.getSharedPreferences("boat_setup", Context.MODE_PRIVATE)
            .edit()
            .putString("boat_name", "Test Boat")
            .putString("skipper_name", "Tester")
            .putString("hull_color", "white")
            .putString("sail_number", "GER 42")
            .putString("yardstick", "100")
            .putString("boat_type", "Dinghy")
            .putBoolean("setup_confirmed", true)
            .commit()

        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        getState<String>(activity, "raceServer").value = SERVER
        getState<String>(activity, "raceEvent").value = EVENT
        getState<String>(activity, "raceSecret").value = SECRET
        getState<String>(activity, "resolvedEventName").value = EVENT
        getState<Boolean>(activity, "raceDataReady").value = false
        setField(activity, "rawRaceStart", "2026-09-20T12:00:00Z")

        invokeNoArg(activity, "enterRace")

        assertFalse(getState<Boolean>(activity, "inRace").value)
        assertFalse(
            context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
                .getBoolean("in_race", false)
        )
        val db = TrackingDbHelper(context)
        assertEquals(0L, db.countSamples())
        db.close()
        assertEquals(
            activity.getString(R.string.race_load_valid_data_first),
            getState<String>(activity, "raceStatusText").value
        )

        controller.destroy()
    }

    @Test
    fun `stale activity generation cannot overwrite newer service snapshot`() {
        val staleGeneration = RaceEventSnapshotStore.generation(context)
        val newer = snapshot(
            status = "started",
            courseJson = "{\"marks\":[{\"order\":1,\"name\":\"New\"}]}"
        )
        RaceEventSnapshotStore.save(
            context = context,
            server = SERVER,
            event = EVENT,
            secret = SECRET,
            snapshot = newer
        )

        val staleWriteAccepted = RaceEventSnapshotStore.saveIfGenerationUnchanged(
            context = context,
            server = SERVER,
            event = EVENT,
            secret = SECRET,
            snapshot = snapshot(
                status = "planned",
                courseJson = "{\"marks\":[{\"order\":1,\"name\":\"Old\"}]}"
            ),
            expectedGeneration = staleGeneration
        )

        assertFalse(staleWriteAccepted)
        val restored = RaceEventSnapshotStore.loadMatching(
            context = context,
            server = SERVER,
            event = EVENT,
            secret = SECRET
        )
        assertNotNull(restored)
        assertEquals("started", restored?.status)
        assertEquals(newer.courseJson, restored?.courseJson)
    }

    @Test
    fun `clearing ready state invalidates older event response generation`() {
        val cached = snapshot(
            status = "planned",
            courseJson = "{\"marks\":[{\"order\":1,\"name\":\"Cached\"}]}"
        )
        RaceEventSnapshotStore.save(
            context = context,
            server = SERVER,
            event = EVENT,
            secret = SECRET,
            snapshot = cached
        )
        val staleGeneration = RaceEventSnapshotStore.generation(context)

        context.getSharedPreferences("race_setup", Context.MODE_PRIVATE)
            .edit()
            .remove("resolved_event_name")
            .remove("series_run_name")
            .remove("series_occurrence_no")
            .remove("series_planned_race_count")
            .putBoolean("race_data_ready", false)
            .commit()

        val staleWriteAccepted = RaceEventSnapshotStore.saveIfGenerationUnchanged(
            context = context,
            server = SERVER,
            event = EVENT,
            secret = SECRET,
            snapshot = snapshot(
                status = "started",
                courseJson = "{\"marks\":[{\"order\":1,\"name\":\"Stale\"}]}"
            ),
            expectedGeneration = staleGeneration
        )

        assertFalse(staleWriteAccepted)
        val prefs = context.getSharedPreferences("race_setup", Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean("race_data_ready", true))
        assertEquals("", prefs.getString("resolved_event_name", ""))

        val freshGeneration = RaceEventSnapshotStore.generation(context)
        val replacement = snapshot(
            status = "started",
            courseJson = "{\"marks\":[{\"order\":1,\"name\":\"Replacement\"}]}"
        )
        val freshWriteAccepted = RaceEventSnapshotStore.saveIfGenerationUnchanged(
            context = context,
            server = SECOND_SERVER,
            event = SECOND_EVENT,
            secret = SECOND_SECRET,
            snapshot = replacement.copy(resolvedEventName = SECOND_EVENT),
            expectedGeneration = freshGeneration
        )

        assertTrue(freshWriteAccepted)
        val restored = RaceEventSnapshotStore.loadMatching(
            context = context,
            server = SECOND_SERVER,
            event = SECOND_EVENT,
            secret = SECOND_SECRET
        )
        assertNotNull(restored)
        assertEquals("started", restored?.status)
        assertEquals(SECOND_EVENT, restored?.resolvedEventName)
        assertEquals(replacement.courseJson, restored?.courseJson)
    }

    @Test
    fun `malformed course response is rejected without replacing valid cache`() {
        val valid = snapshot(
            status = "planned",
            courseJson = "{\"start_line\":{}}"
        )
        RaceEventSnapshotStore.save(
            context = context,
            server = SERVER,
            event = EVENT,
            secret = SECRET,
            snapshot = valid
        )

        try {
            parseRaceEventSnapshot(
                """
                {
                  "event_name": "$EVENT",
                  "race_status": "started",
                  "start_time": "2026-09-20T12:00:00Z",
                  "course": "not-an-object"
                }
                """.trimIndent()
            )
            fail("Malformed course must reject the response")
        } catch (_: IllegalArgumentException) {
            // Expected: callers preserve the last-known-good snapshot.
        }

        val restored = RaceEventSnapshotStore.loadMatching(
            context = context,
            server = SERVER,
            event = EVENT,
            secret = SECRET
        )
        assertNotNull(restored)
        assertEquals("planned", restored?.status)
        assertEquals(valid.courseJson, restored?.courseJson)
    }

    private fun snapshot(status: String, courseJson: String): RaceEventSnapshot =
        RaceEventSnapshot(
            resolvedEventName = EVENT,
            status = status,
            startRaw = "2026-09-20T12:00:00Z",
            stopRaw = "2026-09-20T16:00:00Z",
            raceInfo = "",
            courseJson = courseJson,
            courseShortened = false
        )

    private fun invokeNoArg(target: Any, methodName: String) {
        target.javaClass.getDeclaredMethod(methodName).apply {
            isAccessible = true
            invoke(target)
        }
    }

    private fun setField(target: Any, fieldName: String, value: Any?) {
        target.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(target, value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getState(target: Any, fieldName: String): MutableState<T> =
        target.javaClass.getDeclaredField(fieldName).let { field ->
            field.isAccessible = true
            field.get(target) as MutableState<T>
        }

    private fun clearPrefs() {
        listOf(
            "app_state",
            "boat_setup",
            "race_setup",
            "regatta_race_state",
            "regatta_local_status",
            "regatta_connection_state",
            "regatta_consent"
        ).forEach { prefsName ->
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    private companion object {
        const val DB_NAME = "regatta_tracking.db"
        const val SERVER = "https://race.example.org"
        const val EVENT = "Test Race"
        const val SECRET = "secret"
        const val SECOND_SERVER = "https://second.example.org"
        const val SECOND_EVENT = "Second Race"
        const val SECOND_SECRET = "second-secret"
    }
}
