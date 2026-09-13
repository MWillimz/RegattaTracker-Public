package de.williserv.regattaclient

import android.app.Service
import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class TrackingModeTransitionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("regatta_tracking.db")
        clearPrefs()
    }

    @After
    fun tearDown() {
        context.deleteDatabase("regatta_tracking.db")
        clearPrefs()
    }

    @Test
    fun `manual stop followed by fresh race start reuses service safely`() {
        seedAppState(inRace = false, manualTracking = true)
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()

        assertEquals(Service.START_STICKY, service.onStartCommand(manualStartIntent(), 0, 1))
        assertTrue(getField<Boolean>(service, "serviceRunning"))
        assertTrue(getField<Boolean>(service, "manualRecording"))

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stopIntent(), 0, 2))
        assertFalse(getField<Boolean>(service, "serviceRunning"))

        seedAppState(inRace = true, manualTracking = false)
        setField(service, "eventPollRunning", true)

        assertEquals(Service.START_STICKY, service.onStartCommand(raceStartIntent(), 0, 3))
        assertTrue(getField<Boolean>(service, "serviceRunning"))
        assertFalse(getField<Boolean>(service, "manualRecording"))
        assertTrue(getField<Long?>(service, "accessContextId") != null)

        setField(service, "serviceRunning", false)
        controller.destroy()
    }

    @Test
    fun `manual stop followed by offline race start restores 108 snapshot`() {
        seedAppState(inRace = false, manualTracking = true)
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()

        assertEquals(Service.START_STICKY, service.onStartCommand(manualStartIntent(), 0, 1))
        assertTrue(getField<Boolean>(service, "manualRecording"))

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stopIntent(), 0, 2))
        assertFalse(getField<Boolean>(service, "serviceRunning"))

        seedCachedRaceSnapshot(
            server = "https://raceoffice.example.org",
            event = "Stable Series",
            secret = "secret",
            resolvedEvent = "Race 7"
        )
        ServerConnectionStateStore.markNoConnection(context, "https://raceoffice.example.org")
        seedAppState(inRace = true, manualTracking = false)

        // Prevent a real network poll: this test verifies that the fresh Race start is
        // fully initialized from the #108 cache before any server response is required.
        setField(service, "eventPollRunning", true)

        assertEquals(
            Service.START_STICKY,
            service.onStartCommand(
                raceStartIntent(
                    event = "Stable Series",
                    resolvedEvent = "Race 7"
                ),
                0,
                3
            )
        )

        assertTrue(getField<Boolean>(service, "serviceRunning"))
        assertFalse(getField<Boolean>(service, "manualRecording"))
        assertEquals("Stable Series", getField<String>(service, "eventName"))
        assertEquals("Race 7", getField<String?>(service, "resolvedEventName"))
        assertEquals("racing", getField<String>(service, "raceStatus"))
        assertTrue(getField<Any?>(service, "raceStartInstant") != null)
        assertTrue(getField<Long?>(service, "accessContextId") != null)

        setField(service, "serviceRunning", false)
        controller.destroy()
    }

    @Test
    fun `loading changing and deleting event setup does not affect running manual tracking`() {
        seedAppState(inRace = false, manualTracking = true)
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val helper = getField<TrackingDbHelper>(service, "db")

        assertEquals(Service.START_STICKY, service.onStartCommand(manualStartIntent(), 0, 1))
        assertManualIsRaceIndependent(service)

        seedCachedRaceSnapshot(
            server = "https://raceoffice.example.org",
            event = "Event A",
            secret = "secret-a",
            resolvedEvent = "Event A"
        )
        invokeNoArg(service, "generateAndStoreSample")
        assertManualIsRaceIndependent(service)

        context.getSharedPreferences("race_setup", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        seedCachedRaceSnapshot(
            server = "https://other.example.org",
            event = "Event B",
            secret = "secret-b",
            resolvedEvent = "Event B"
        )
        invokeNoArg(service, "generateAndStoreSample")
        assertManualIsRaceIndependent(service)

        context.getSharedPreferences("race_setup", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        invokeNoArg(service, "generateAndStoreSample")
        assertManualIsRaceIndependent(service)

        assertEquals(3L, helper.countSamples())
        assertEquals(0L, helper.countPendingSamples())
        assertTrue(
            context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
                .getBoolean("manual_tracking", false)
        )
        assertFalse(
            context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
                .getBoolean("in_race", true)
        )

        controller.destroy()
        helper.close()
    }

    private fun assertManualIsRaceIndependent(service: RegattaTrackingService) {
        assertTrue(getField<Boolean>(service, "serviceRunning"))
        assertTrue(getField<Boolean>(service, "manualRecording"))
        assertEquals("", getField<String>(service, "serverUrl"))
        assertEquals("", getField<String>(service, "eventName"))
        assertEquals("", getField<String>(service, "sharedSecret"))
        assertNull(getField<String?>(service, "resolvedEventName"))
        assertNull(getField<Long?>(service, "accessContextId"))
        assertNull(getField<Any?>(service, "raceStartInstant"))
    }

    private fun manualStartIntent(): Intent =
        Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_START
            putExtra(RegattaTrackingService.EXTRA_MANUAL_RECORDING, true)
            putExtra(RegattaTrackingService.EXTRA_BOAT_NAME, "Test Boat")
            putExtra(RegattaTrackingService.EXTRA_SAIL_NUMBER, "GER 117")
        }

    private fun raceStartIntent(
        event: String = "Event 117",
        resolvedEvent: String? = null
    ): Intent = Intent(context, RegattaTrackingService::class.java).apply {
        action = RegattaTrackingService.ACTION_START
        putExtra(RegattaTrackingService.EXTRA_SERVER_URL, "https://raceoffice.example.org")
        putExtra(RegattaTrackingService.EXTRA_EVENT_NAME, event)
        putExtra(RegattaTrackingService.EXTRA_SHARED_SECRET, "secret")
        if (resolvedEvent != null) {
            putExtra(RegattaTrackingService.EXTRA_RESOLVED_EVENT_NAME, resolvedEvent)
        }
        putExtra(RegattaTrackingService.EXTRA_BOAT_NAME, "Test Boat")
        putExtra(RegattaTrackingService.EXTRA_SAIL_NUMBER, "GER 117")
        putExtra(RegattaTrackingService.EXTRA_MANUAL_RECORDING, false)
    }

    private fun stopIntent(): Intent =
        Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_STOP
        }

    private fun seedCachedRaceSnapshot(
        server: String,
        event: String,
        secret: String,
        resolvedEvent: String
    ) {
        RaceEventSnapshotStore.save(
            context = context,
            server = server,
            event = event,
            secret = secret,
            snapshot = RaceEventSnapshot(
                resolvedEventName = resolvedEvent,
                status = "racing",
                startRaw = "2026-09-13T12:00:00",
                stopRaw = "2026-09-13T18:00:00",
                raceInfo = "cached",
                courseJson = "{}",
                courseShortened = false
            )
        )
    }

    private fun seedAppState(inRace: Boolean, manualTracking: Boolean) {
        context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("in_race", inRace)
            .putBoolean("manual_tracking", manualTracking)
            .commit()
    }

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
    private fun <T> getField(target: Any, fieldName: String): T {
        return target.javaClass.getDeclaredField(fieldName).let { field ->
            field.isAccessible = true
            field.get(target) as T
        }
    }

    private fun clearPrefs() {
        listOf(
            "app_state",
            "race_setup",
            "regatta_race_state",
            "regatta_local_status",
            "server_connection_state"
        ).forEach { prefsName ->
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }
}
