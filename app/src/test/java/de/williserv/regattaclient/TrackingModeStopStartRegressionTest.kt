package de.williserv.regattaclient

import android.app.Service
import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class TrackingModeStopStartRegressionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("regatta_tracking.db")
        listOf("app_state", "race_setup", "regatta_race_state", "regatta_local_status", "boat_setup")
            .forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            }
    }

    @After
    fun tearDown() {
        context.deleteDatabase("regatta_tracking.db")
        listOf("app_state", "race_setup", "regatta_race_state", "regatta_local_status", "boat_setup")
            .forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            }
    }

    @Test
    fun `manual stop followed by race start ends with race persisted active`() {
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val prefs = context.getSharedPreferences("app_state", Context.MODE_PRIVATE)

        val manualStart = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_START
            putExtra(RegattaTrackingService.EXTRA_MANUAL_RECORDING, true)
            putExtra(RegattaTrackingService.EXTRA_BOAT_NAME, "Test Boat")
            putExtra(RegattaTrackingService.EXTRA_SAIL_NUMBER, "GER 115")
        }
        assertEquals(Service.START_STICKY, service.onStartCommand(manualStart, 0, 1))
        assertFalse(prefs.getBoolean("in_race", true))
        assertTrue(prefs.getBoolean("manual_tracking", false))

        val stop = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_STOP
        }
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stop, 0, 2))
        assertFalse(prefs.getBoolean("in_race", true))
        assertFalse(prefs.getBoolean("manual_tracking", true))

        setField(service, "eventPollRunning", true)
        val raceStart = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_START
            putExtra(RegattaTrackingService.EXTRA_SERVER_URL, "https://raceoffice.example.org")
            putExtra(RegattaTrackingService.EXTRA_EVENT_NAME, "Event 115")
            putExtra(RegattaTrackingService.EXTRA_SHARED_SECRET, "secret")
            putExtra(RegattaTrackingService.EXTRA_BOAT_NAME, "Test Boat")
            putExtra(RegattaTrackingService.EXTRA_SAIL_NUMBER, "GER 115")
            putExtra(RegattaTrackingService.EXTRA_MANUAL_RECORDING, false)
        }
        assertEquals(Service.START_STICKY, service.onStartCommand(raceStart, 0, 3))

        assertTrue(prefs.getBoolean("in_race", false))
        assertFalse(prefs.getBoolean("manual_tracking", true))
        assertTrue(getField<Boolean>(service, "serviceRunning"))
        assertFalse(getField<Boolean>(service, "manualRecording"))

        setField(service, "serviceRunning", false)
        controller.destroy()
    }

    @Test
    fun `explicit Daily entry resets persisted run progress and personal start time`() {
        context.getSharedPreferences("regatta_race_state", Context.MODE_PRIVATE)
            .edit()
            .putString("event_name", "Daily")
            .putString("resolved_event_name", "Daily")
            .putString("sail_number", "GER 447")
            .putBoolean("race_started", true)
            .putBoolean("race_finished", true)
            .putBoolean("is_ocs", true)
            .putInt("passed_marks", 3)
            .putLong("local_start_timestamp_ms", 123_456L)
            .commit()

        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        setField(service, "eventPollRunning", true)

        val start = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_START
            putExtra(RegattaTrackingService.EXTRA_SERVER_URL, "https://raceoffice.example.org")
            putExtra(RegattaTrackingService.EXTRA_EVENT_NAME, "Daily")
            putExtra(RegattaTrackingService.EXTRA_SHARED_SECRET, "secret")
            putExtra(RegattaTrackingService.EXTRA_RESOLVED_EVENT_NAME, "Daily")
            putExtra(RegattaTrackingService.EXTRA_BOAT_NAME, "Test Boat")
            putExtra(RegattaTrackingService.EXTRA_SAIL_NUMBER, "GER 447")
            putExtra(RegattaTrackingService.EXTRA_MANUAL_RECORDING, false)
            putExtra(RegattaTrackingService.EXTRA_RESET_RACE_RUN_STATE, true)
        }

        assertEquals(Service.START_STICKY, service.onStartCommand(start, 0, 1))

        val state = context.getSharedPreferences("regatta_race_state", Context.MODE_PRIVATE)
        assertFalse(state.getBoolean("race_started", true))
        assertFalse(state.getBoolean("race_finished", true))
        assertFalse(state.getBoolean("is_ocs", true))
        assertEquals(0, state.getInt("passed_marks", -1))
        assertFalse(state.contains("local_start_timestamp_ms"))

        setField(service, "serviceRunning", false)
        controller.destroy()
    }

    @Test
    fun `sticky restart restores personal Flying Start timestamp`() {
        context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("in_race", true)
            .putBoolean("manual_tracking", false)
            .commit()
        context.getSharedPreferences("boat_setup", Context.MODE_PRIVATE)
            .edit()
            .putString("sail_number", "GER 447")
            .commit()
        context.getSharedPreferences("race_setup", Context.MODE_PRIVATE)
            .edit()
            .putString("race_server", "https://raceoffice.example.org")
            .putString("race_event", "Daily")
            .putString("race_secret", "secret")
            .putString("resolved_event_name", "Daily")
            .commit()
        context.getSharedPreferences("regatta_race_state", Context.MODE_PRIVATE)
            .edit()
            .putString("event_name", "Daily")
            .putString("resolved_event_name", "Daily")
            .putString("sail_number", "GER 447")
            .putBoolean("race_started", true)
            .putLong("local_start_timestamp_ms", 987_654L)
            .commit()

        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        setField(service, "eventPollRunning", true)

        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(getField<Boolean>(service, "raceStarted"))
        assertEquals(987_654L, getField<Long>(service, "localStartTimestampMillis"))

        setField(service, "serviceRunning", false)
        controller.destroy()
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
}
