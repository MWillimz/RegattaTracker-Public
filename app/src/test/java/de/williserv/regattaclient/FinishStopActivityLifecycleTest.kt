package de.williserv.regattaclient

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.MutableState
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinishStopActivityLifecycleTest {

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
        shadowOf(android.os.Looper.getMainLooper()).idle()
        context.deleteDatabase(DB_NAME)
        clearPrefs()
        TrackingServiceRuntimeState.markStopped()
    }

    @Test
    fun `notification stop followed by activity restore shows tracking stopped`() {
        seedAppState(inRace = true, manualTracking = false)
        val serviceController = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = serviceController.get()
        setField(service, "serviceRunning", true)
        setField(service, "manualRecording", false)

        val stopIntent = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_STOP
        }
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stopIntent, 0, 1))
        assertStoppedPrefs()
        serviceController.destroy()

        val activityController = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = activityController.get()

        assertActivityStopped(activity)
        activityController.destroy()
    }

    @Test
    fun `normal leave race persists stopped state`() {
        seedAppState(inRace = true, manualTracking = false)
        TrackingServiceRuntimeState.markActive()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        assertTrue(getState<Boolean>(activity, "inRace").value)

        invokeNoArg(activity, "leaveRace")

        assertStoppedPrefs()
        assertFalse(getState<Boolean>(activity, "inRace").value)
        assertFalse(getState<Boolean>(activity, "manualTracking").value)
        assertEquals(
            activity.getString(R.string.service_stopped),
            getState<String>(activity, "serviceStatusText").value
        )
        assertEquals(
            activity.getString(R.string.race_left),
            getState<String>(activity, "statusText").value
        )

        controller.destroy()
    }

    @Test
    fun `normal manual stop persists stopped state`() {
        seedAppState(inRace = false, manualTracking = true)
        TrackingServiceRuntimeState.markActive()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        assertTrue(getState<Boolean>(activity, "manualTracking").value)

        invokeNoArg(activity, "stopManualTracking")

        assertStoppedPrefs()
        assertFalse(getState<Boolean>(activity, "inRace").value)
        assertFalse(getState<Boolean>(activity, "manualTracking").value)
        assertEquals(
            activity.getString(R.string.service_stopped),
            getState<String>(activity, "serviceStatusText").value
        )
        assertEquals(
            activity.getString(R.string.manual_tracking_stopped),
            getState<String>(activity, "statusText").value
        )

        controller.destroy()
    }

    private fun assertActivityStopped(activity: MainActivity) {
        assertFalse(getState<Boolean>(activity, "inRace").value)
        assertFalse(getState<Boolean>(activity, "manualTracking").value)
        assertEquals(
            activity.getString(R.string.service_stopped),
            getState<String>(activity, "serviceStatusText").value
        )
        assertEquals(
            activity.getString(R.string.tracking_stopped),
            getState<String>(activity, "statusText").value
        )
    }

    private fun assertStoppedPrefs() {
        val prefs = context.getSharedPreferences(APP_STATE_PREFS, Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean("in_race", true))
        assertFalse(prefs.getBoolean("manual_tracking", true))
    }

    private fun seedAppState(inRace: Boolean, manualTracking: Boolean) {
        context.getSharedPreferences(APP_STATE_PREFS, Context.MODE_PRIVATE)
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

    private fun <T> getState(target: Any, fieldName: String): MutableState<T> =
        getField(target, fieldName)

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
    }
}
