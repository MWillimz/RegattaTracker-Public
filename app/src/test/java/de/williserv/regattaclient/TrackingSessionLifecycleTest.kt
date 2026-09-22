package de.williserv.regattaclient

import android.app.Service
import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackingSessionLifecycleTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB_NAME)
        clearPrefs()
        TrackingServiceRuntimeState.markStopped()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
        clearPrefs()
        TrackingServiceRuntimeState.markStopped()
    }

    @Test
    fun manualStart_assignsSamplesAndStopFinishesSession() {
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val helper = getField<TrackingDbHelper>(service, "db")

        assertEquals(Service.START_STICKY, service.onStartCommand(manualStartIntent(), 0, 1))

        val sessionId = requireNotNull(getField<Long?>(service, "activeSessionId"))
        val session = requireNotNull(helper.getTrackingSession(sessionId))
        assertEquals("manual", session.mode)
        assertNull(session.accessContextId)
        assertNull(session.endedAt)
        assertTrue(session.displayName.startsWith("Session "))

        invokeNoArg(service, "generateAndStoreSample")
        assertEquals(sessionId, latestSampleSessionId(helper))

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stopIntent(), 0, 2))
        assertTrue(requireNotNull(helper.getTrackingSession(sessionId)).endedAt != null)
        assertFalse(
            context.getSharedPreferences(APP_STATE_PREFS, Context.MODE_PRIVATE)
                .contains(ACTIVE_SESSION_PREF)
        )

        controller.destroy()
        helper.close()
    }

    @Test
    fun repeatedStartWhileRunning_doesNotCreateSecondSession() {
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val helper = getField<TrackingDbHelper>(service, "db")

        assertEquals(Service.START_STICKY, service.onStartCommand(manualStartIntent(), 0, 1))
        val firstSessionId = requireNotNull(getField<Long?>(service, "activeSessionId"))

        assertEquals(Service.START_STICKY, service.onStartCommand(manualStartIntent(), 0, 2))

        assertEquals(firstSessionId, getField<Long?>(service, "activeSessionId"))
        assertEquals(1L, helper.countTrackingSessions())

        setField(service, "serviceRunning", false)
        controller.destroy()
        helper.close()
    }

    @Test
    fun destroyWithoutStop_thenExplicitStart_createsNewSession() {
        val firstController = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val firstService = firstController.get()
        val firstHelper = getField<TrackingDbHelper>(firstService, "db")

        assertEquals(Service.START_STICKY, firstService.onStartCommand(manualStartIntent(), 0, 1))
        val firstSessionId = requireNotNull(getField<Long?>(firstService, "activeSessionId"))

        firstController.destroy()
        firstHelper.close()

        val secondController = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val secondService = secondController.get()
        val secondHelper = getField<TrackingDbHelper>(secondService, "db")

        assertEquals(Service.START_STICKY, secondService.onStartCommand(manualStartIntent(), 0, 2))
        val secondSessionId = requireNotNull(getField<Long?>(secondService, "activeSessionId"))

        assertNotEquals(firstSessionId, secondSessionId)
        assertTrue(requireNotNull(secondHelper.getTrackingSession(firstSessionId)).endedAt != null)
        assertNull(requireNotNull(secondHelper.getTrackingSession(secondSessionId)).endedAt)
        assertEquals(2L, secondHelper.countTrackingSessions())

        setField(secondService, "serviceRunning", false)
        secondController.destroy()
        secondHelper.close()
    }

    @Test
    fun destroyWithoutStop_keepsOpenSessionForStickyRestart() {
        val firstController = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val firstService = firstController.get()
        val firstHelper = getField<TrackingDbHelper>(firstService, "db")

        assertEquals(Service.START_STICKY, firstService.onStartCommand(manualStartIntent(), 0, 1))
        val sessionId = requireNotNull(getField<Long?>(firstService, "activeSessionId"))

        firstController.destroy()
        firstHelper.close()

        val persistedHelper = TrackingDbHelper(context)
        assertNull(requireNotNull(persistedHelper.getTrackingSession(sessionId)).endedAt)
        persistedHelper.close()
        assertEquals(
            sessionId,
            context.getSharedPreferences(APP_STATE_PREFS, Context.MODE_PRIVATE)
                .getLong(ACTIVE_SESSION_PREF, -1L)
        )

        val secondController = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val secondService = secondController.get()
        val secondHelper = getField<TrackingDbHelper>(secondService, "db")

        assertEquals(Service.START_STICKY, secondService.onStartCommand(null, 0, 2))
        assertEquals(sessionId, getField<Long?>(secondService, "activeSessionId"))
        assertEquals(1L, secondHelper.countTrackingSessions())

        setField(secondService, "serviceRunning", false)
        secondController.destroy()
        secondHelper.close()
    }

    @Test
    fun stopThenStart_createsNewSession() {
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val helper = getField<TrackingDbHelper>(service, "db")

        assertEquals(Service.START_STICKY, service.onStartCommand(manualStartIntent(), 0, 1))
        val firstSessionId = requireNotNull(getField<Long?>(service, "activeSessionId"))

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stopIntent(), 0, 2))

        assertEquals(Service.START_STICKY, service.onStartCommand(manualStartIntent(), 0, 3))
        val secondSessionId = requireNotNull(getField<Long?>(service, "activeSessionId"))

        assertNotEquals(firstSessionId, secondSessionId)
        assertEquals(2L, helper.countTrackingSessions())
        assertTrue(requireNotNull(helper.getTrackingSession(firstSessionId)).endedAt != null)
        assertNull(requireNotNull(helper.getTrackingSession(secondSessionId)).endedAt)

        setField(service, "serviceRunning", false)
        controller.destroy()
        helper.close()
    }

    @Test
    fun failedStickyRestore_finishesPersistedOpenSession() {
        val helper = TrackingDbHelper(context)
        val accessContextId = requireNotNull(
            helper.getOrCreateAccessContext(
                serverUrl = "https://raceoffice.example.org",
                accessIdentifier = "Event 194",
                accessSecret = "secret"
            )
        )
        val sessionId = requireNotNull(
            helper.createTrackingSession(
                startedAt = 1_700_000_000_000L,
                mode = "race",
                accessContextId = accessContextId,
                displayName = "Session"
            )
        )
        helper.close()

        context.getSharedPreferences(APP_STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("in_race", true)
            .putBoolean("manual_tracking", false)
            .putLong(ACTIVE_SESSION_PREF, sessionId)
            .commit()

        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val serviceHelper = getField<TrackingDbHelper>(service, "db")

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))

        assertTrue(requireNotNull(serviceHelper.getTrackingSession(sessionId)).endedAt != null)
        assertFalse(
            context.getSharedPreferences(APP_STATE_PREFS, Context.MODE_PRIVATE)
                .contains(ACTIVE_SESSION_PREF)
        )
        assertFalse(
            context.getSharedPreferences(APP_STATE_PREFS, Context.MODE_PRIVATE)
                .getBoolean("in_race", true)
        )

        controller.destroy()
        serviceHelper.close()
    }

    @Test
    fun raceStart_createsSessionWithAccessContext() {
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val helper = getField<TrackingDbHelper>(service, "db")
        setField(service, "eventPollRunning", true)

        val snapshot = RaceEventSnapshot(
            resolvedEventName = "Event 194 Run 1",
            status = "scheduled",
            startRaw = "2026-09-22T10:00:00Z",
            stopRaw = "2026-09-22T12:00:00Z",
            raceInfo = "",
            courseJson = """{"marks":[]}""",
            courseShortened = false,
            courseMapViewport = CourseMapViewport(
                projection = "web_mercator",
                zoom = 15,
                leftPx = 100.0,
                topPx = 200.0,
                widthPx = 1200,
                heightPx = 800,
                generationId = "gen-194"
            )
        )
        RaceEventSnapshotStore.save(
            context = context,
            server = "https://raceoffice.example.org",
            event = "Event 194",
            secret = "secret",
            snapshot = snapshot
        )

        assertEquals(
            Service.START_STICKY,
            service.onStartCommand(
                raceStartIntent().putExtra(
                    RegattaTrackingService.EXTRA_RESOLVED_EVENT_NAME,
                    snapshot.resolvedEventName
                ),
                0,
                1
            )
        )

        val sessionId = requireNotNull(getField<Long?>(service, "activeSessionId"))
        val accessContextId = requireNotNull(getField<Long?>(service, "accessContextId"))
        val session = requireNotNull(helper.getTrackingSession(sessionId))

        assertEquals("race", session.mode)
        assertEquals(accessContextId, session.accessContextId)
        assertEquals(snapshot.resolvedEventName, session.resolvedEventName)
        assertEquals(snapshot.courseJson, session.courseJson)
        assertTrue(session.courseMapViewportJson?.contains("gen-194") == true)

        setField(service, "serviceRunning", false)
        controller.destroy()
        helper.close()
    }

    @Test
    fun raceRunChange_keepsOneSessionAndAssociatesSamplesWithTheirResolvedRuns() {
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val helper = getField<TrackingDbHelper>(service, "db")
        setField(service, "eventPollRunning", true)

        val firstSnapshot = RaceEventSnapshot(
            resolvedEventName = "Event 194 Run 1",
            status = "scheduled",
            startRaw = "2026-09-22T10:00:00Z",
            stopRaw = "2026-09-22T10:45:00Z",
            raceInfo = "",
            courseJson = """{"marks":[{"order":1}]}""",
            courseShortened = false,
            courseMapViewport = CourseMapViewport(
                projection = "web_mercator",
                zoom = 15,
                leftPx = 100.0,
                topPx = 200.0,
                widthPx = 1200,
                heightPx = 800,
                generationId = "gen-run-1"
            )
        )
        RaceEventSnapshotStore.save(
            context = context,
            server = "https://raceoffice.example.org",
            event = "Event 194",
            secret = "secret",
            snapshot = firstSnapshot
        )

        assertEquals(
            Service.START_STICKY,
            service.onStartCommand(
                raceStartIntent().putExtra(
                    RegattaTrackingService.EXTRA_RESOLVED_EVENT_NAME,
                    firstSnapshot.resolvedEventName
                ),
                0,
                1
            )
        )

        val sessionId = requireNotNull(getField<Long?>(service, "activeSessionId"))
        invokeNoArg(service, "generateAndStoreSample")

        val secondSnapshot = firstSnapshot.copy(
            resolvedEventName = "Event 194 Run 2",
            startRaw = "2026-09-22T11:00:00Z",
            stopRaw = "2026-09-22T11:45:00Z",
            courseJson = """{"marks":[{"order":1},{"order":2}]}""",
            courseMapViewport = firstSnapshot.courseMapViewport?.copy(
                generationId = "gen-run-2"
            )
        )
        invokeApplyRaceEventSnapshot(service, secondSnapshot)
        invokeNoArg(service, "generateAndStoreSample")

        assertEquals(sessionId, getField<Long?>(service, "activeSessionId"))
        val samples = helper.getTrackingSamplesForSession(sessionId)
        assertEquals(
            listOf("Event 194 Run 1", "Event 194 Run 2"),
            samples.map { it.resolvedEventName }
        )
        assertNotEquals(samples[0].raceContextId, samples[1].raceContextId)
        assertEquals(firstSnapshot.courseJson, samples[0].courseJson)
        assertEquals(secondSnapshot.courseJson, samples[1].courseJson)

        val session = requireNotNull(helper.getTrackingSession(sessionId))
        assertEquals("Event 194 Run 1", session.resolvedEventName)

        setField(service, "serviceRunning", false)
        controller.destroy()
        helper.close()
    }

    private fun latestSampleSessionId(helper: TrackingDbHelper): Long? {
        helper.readableDatabase.rawQuery(
            "SELECT session_id FROM tracking_samples ORDER BY id DESC LIMIT 1",
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            return if (cursor.isNull(0)) null else cursor.getLong(0)
        }
    }

    private fun manualStartIntent(): Intent =
        Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_START
            putExtra(RegattaTrackingService.EXTRA_MANUAL_RECORDING, true)
            putExtra(RegattaTrackingService.EXTRA_BOAT_NAME, "Test Boat")
            putExtra(RegattaTrackingService.EXTRA_SAIL_NUMBER, "GER 194")
        }

    private fun raceStartIntent(): Intent =
        Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_START
            putExtra(RegattaTrackingService.EXTRA_SERVER_URL, "https://raceoffice.example.org")
            putExtra(RegattaTrackingService.EXTRA_EVENT_NAME, "Event 194")
            putExtra(RegattaTrackingService.EXTRA_SHARED_SECRET, "secret")
            putExtra(RegattaTrackingService.EXTRA_BOAT_NAME, "Test Boat")
            putExtra(RegattaTrackingService.EXTRA_SAIL_NUMBER, "GER 194")
            putExtra(RegattaTrackingService.EXTRA_MANUAL_RECORDING, false)
        }

    private fun stopIntent(): Intent =
        Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_STOP
        }

    private fun invokeNoArg(target: Any, methodName: String) {
        target.javaClass.getDeclaredMethod(methodName).apply {
            isAccessible = true
            invoke(target)
        }
    }

    private fun invokeApplyRaceEventSnapshot(
        target: Any,
        snapshot: RaceEventSnapshot
    ) {
        target.javaClass
            .getDeclaredMethod("applyRaceEventSnapshot", RaceEventSnapshot::class.java)
            .apply {
                isAccessible = true
                invoke(target, snapshot)
            }
    }

    private fun setField(target: Any, fieldName: String, value: Any?) {
        target.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(target, value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(target: Any, fieldName: String): T {
        return target.javaClass.getDeclaredField(fieldName).let { field ->
            field.isAccessible = true
            field.get(target) as T
        }
    }

    private fun clearPrefs() {
        listOf(
            APP_STATE_PREFS,
            "boat_setup",
            "race_setup",
            "regatta_race_state",
            "regatta_local_status",
            "regatta_connection_state"
        ).forEach { prefsName ->
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    private companion object {
        const val DB_NAME = "regatta_tracking.db"
        const val APP_STATE_PREFS = "app_state"
        const val ACTIVE_SESSION_PREF = "active_tracking_session_id"
    }
}
