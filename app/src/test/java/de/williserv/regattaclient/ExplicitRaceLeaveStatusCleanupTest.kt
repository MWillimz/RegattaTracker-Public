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
class ExplicitRaceLeaveStatusCleanupTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearPrefs()
    }

    @After
    fun tearDown() {
        clearPrefs()
    }

    @Test
    fun `explicit race leave clears local UI status but preserves event and persisted race state`() {
        context.getSharedPreferences(APP_STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("in_race", false)
            .putBoolean("manual_tracking", false)
            .commit()
        context.getSharedPreferences(RACE_SETUP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("race_event", "series-access")
            .putString("resolved_event_name", "race-3")
            .commit()
        context.getSharedPreferences(RACE_STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("race_started", true)
            .putInt("passed_marks", 2)
            .commit()
        context.getSharedPreferences(LOCAL_STATUS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_ocs", true)
            .putBoolean("race_started", true)
            .putBoolean("race_finished", true)
            .putInt("passed_marks", 2)
            .putString("target_text", "stale target")
            .putString("dtl_text", "12 m")
            .commit()

        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        setField(service, "serviceRunning", true)
        setField(service, "manualRecording", false)

        val stopIntent = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_STOP
        }

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stopIntent, 0, 1))

        assertTrue(
            context.getSharedPreferences(LOCAL_STATUS_PREFS, Context.MODE_PRIVATE)
                .all
                .isEmpty()
        )
        assertEquals(
            "series-access",
            context.getSharedPreferences(RACE_SETUP_PREFS, Context.MODE_PRIVATE)
                .getString("race_event", null)
        )
        assertEquals(
            "race-3",
            context.getSharedPreferences(RACE_SETUP_PREFS, Context.MODE_PRIVATE)
                .getString("resolved_event_name", null)
        )
        assertTrue(
            context.getSharedPreferences(RACE_STATE_PREFS, Context.MODE_PRIVATE)
                .getBoolean("race_started", false)
        )
        assertEquals(
            2,
            context.getSharedPreferences(RACE_STATE_PREFS, Context.MODE_PRIVATE)
                .getInt("passed_marks", 0)
        )

        controller.destroy()
    }

    @Test
    fun `notification stop does not use explicit leave cleanup path`() {
        context.getSharedPreferences(APP_STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("in_race", true)
            .putBoolean("manual_tracking", false)
            .commit()
        context.getSharedPreferences(LOCAL_STATUS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_ocs", true)
            .putInt("passed_marks", 2)
            .commit()

        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        setField(service, "serviceRunning", true)
        setField(service, "manualRecording", false)

        val stopIntent = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_STOP
        }

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stopIntent, 0, 1))

        val localPrefs = context.getSharedPreferences(LOCAL_STATUS_PREFS, Context.MODE_PRIVATE)
        assertTrue(localPrefs.getBoolean("is_ocs", false))
        assertEquals(2, localPrefs.getInt("passed_marks", 0))
        assertFalse(
            context.getSharedPreferences(APP_STATE_PREFS, Context.MODE_PRIVATE)
                .getBoolean("in_race", true)
        )

        controller.destroy()
    }

    private fun setField(target: Any, fieldName: String, value: Any?) {
        target.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun clearPrefs() {
        listOf(
            APP_STATE_PREFS,
            RACE_SETUP_PREFS,
            RACE_STATE_PREFS,
            LOCAL_STATUS_PREFS
        ).forEach { prefsName ->
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    private companion object {
        const val APP_STATE_PREFS = "app_state"
        const val RACE_SETUP_PREFS = "race_setup"
        const val RACE_STATE_PREFS = "regatta_race_state"
        const val LOCAL_STATUS_PREFS = "regatta_local_status"
    }
}
