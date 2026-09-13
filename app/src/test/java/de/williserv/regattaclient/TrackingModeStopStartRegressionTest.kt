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
        context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        context.deleteDatabase("regatta_tracking.db")
        context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
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
