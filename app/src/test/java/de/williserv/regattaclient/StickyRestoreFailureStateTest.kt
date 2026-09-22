package de.williserv.regattaclient

import android.app.Service
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StickyRestoreFailureStateTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearPrefs()
        TrackingServiceRuntimeState.markStopped()
    }

    @After
    fun tearDown() {
        clearPrefs()
        TrackingServiceRuntimeState.markStopped()
    }

    @Test
    fun `failed sticky race restore clears stale active tracking state`() {
        context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("in_race", true)
            .putBoolean("manual_tracking", false)
            .commit()

        context.getSharedPreferences("race_setup", Context.MODE_PRIVATE)
            .edit()
            .putString("race_server", "https://raceoffice.example.org")
            .putString("race_event", "")
            .putString("race_secret", "secret")
            .commit()

        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))

        val appState = context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
        assertFalse(appState.getBoolean("in_race", true))
        assertFalse(appState.getBoolean("manual_tracking", true))
        assertFalse(TrackingServiceRuntimeState.isActive())

        controller.destroy()
    }

    private fun clearPrefs() {
        listOf("app_state", "race_setup", "boat_setup", "regatta_race_state")
            .forEach { prefsName ->
                context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            }
    }
}
