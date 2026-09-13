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
class TrackingModeTransitionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("regatta_tracking.db")
        context.getSharedPreferences("app_state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.deleteDatabase("regatta_tracking.db")
        context.getSharedPreferences("app_state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `manual stop followed by fresh race start reuses service safely`() {
        seedAppState(inRace = false, manualTracking = true)
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()

        val manualStart = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_START
            putExtra(RegattaTrackingService.EXTRA_MANUAL_RECORDING, true)
            putExtra(RegattaTrackingService.EXTRA_BOAT_NAME, "Test Boat")
            putExtra(RegattaTrackingService.EXTRA_SAIL_NUMBER, "GER 117")
        }
        assertEquals(Service.START_STICKY, service.onStartCommand(manualStart, 0, 1))
        assertTrue(getField<Boolean>(service, "serviceRunning"))
        assertTrue(getField<Boolean>(service, "manualRecording"))

        val stop = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_STOP
        }
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stop, 0, 2))
        assertFalse(getField<Boolean>(service, "serviceRunning"))

        seedAppState(inRace = true, manualTracking = false)
        setField(service, "eventPollRunning", true)
        val raceStart = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_START
            putExtra(RegattaTrackingService.EXTRA_SERVER_URL, "https://raceoffice.example.org")
            putExtra(RegattaTrackingService.EXTRA_EVENT_NAME, "Event 117")
            putExtra(RegattaTrackingService.EXTRA_SHARED_SECRET, "secret")
            putExtra(RegattaTrackingService.EXTRA_BOAT_NAME, "Test Boat")
            putExtra(RegattaTrackingService.EXTRA_SAIL_NUMBER, "GER 117")
            putExtra(RegattaTrackingService.EXTRA_MANUAL_RECORDING, false)
        }

        assertEquals(Service.START_STICKY, service.onStartCommand(raceStart, 0, 3))
        assertTrue(getField<Boolean>(service, "serviceRunning"))
        assertFalse(getField<Boolean>(service, "manualRecording"))
        assertTrue(getField<Long?>(service, "accessContextId") != null)

        setField(service, "serviceRunning", false)
        controller.destroy()
    }

    private fun seedAppState(inRace: Boolean, manualTracking: Boolean) {
        context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("in_race", inRace)
            .putBoolean("manual_tracking", manualTracking)
            .commit()
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
