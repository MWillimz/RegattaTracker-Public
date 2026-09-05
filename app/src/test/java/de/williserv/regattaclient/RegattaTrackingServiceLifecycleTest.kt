package de.williserv.regattaclient

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RegattaTrackingServiceLifecycleTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB_NAME)
        clearTrackingPrefs()
        clearLocalStatusPrefs()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        context.deleteDatabase(DB_NAME)
        clearTrackingPrefs()
        clearLocalStatusPrefs()
    }

    @Test
    fun `start while service is already running starts a fresh metadata session`() {
        TrackingProfileConfig.write(context, TrackingProfile.NORMAL)
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val helper = getField<TrackingDbHelper>(service, "db")
        val accessContextId = requireNotNull(
            helper.getOrCreateAccessContext(
                serverUrl = "https://raceoffice.example.org",
                accessIdentifier = "Event A",
                accessSecret = "secret"
            )
        )

        val firstId = insertSample(helper, accessContextId, 1L)
        val secondId = insertSample(helper, accessContextId, 2L)
        val beforeRestart = helper.getPendingSamples(10).associateBy { it.localId }

        assertEquals("normal", beforeRestart.getValue(firstId).trackingProfile)
        assertNull(beforeRestart.getValue(secondId).trackingProfile)

        setField(service, "serviceRunning", true)
        invokeNoArg(service, "startTrackingService")

        val restartedId = insertSample(helper, accessContextId, 3L)
        val afterRestart = helper.getPendingSamples(10).associateBy { it.localId }
        assertEquals("normal", afterRestart.getValue(restartedId).trackingProfile)

        setField(service, "serviceRunning", false)
        controller.destroy()
        helper.close()
    }

    @Test
    fun `slow pending sample is replaced immediately when interval becomes fast`() {
        TrackingProfileConfig.write(context, TrackingProfile.BATTERY_SAVER)
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val handler = getField<Handler>(service, "handler")
        val sampleRunnable = getField<Runnable>(service, "sampleRunnable")
        val looper = shadowOf(Looper.getMainLooper())
        val statusPrefs = context.getSharedPreferences(LOCAL_STATUS_PREFS, Context.MODE_PRIVATE)

        handler.postDelayed(sampleRunnable, 60_000L)
        setField(service, "serviceRunning", true)
        setField(service, "manualRecording", false)
        setField(service, "activeLocationIntervalMs", 60_000L)

        invokeRefreshLocationSampling(service)

        looper.idleFor(1_999L, TimeUnit.MILLISECONDS)
        assertFalse(statusPrefs.contains("target_text"))

        looper.idleFor(1L, TimeUnit.MILLISECONDS)
        assertTrue(statusPrefs.contains("target_text"))

        // Let the recursively scheduled fast callback terminate, then verify that the
        // original 60 s callback was actually removed rather than left behind.
        setField(service, "serviceRunning", false)
        clearLocalStatusPrefs()
        looper.idleFor(2_000L, TimeUnit.MILLISECONDS)

        setField(service, "serviceRunning", true)
        looper.idleFor(56_000L, TimeUnit.MILLISECONDS)
        assertFalse(statusPrefs.contains("target_text"))

        setField(service, "serviceRunning", false)
        controller.destroy()
    }

    private fun insertSample(
        helper: TrackingDbHelper,
        accessContextId: Long,
        sequenceId: Long
    ): Long {
        return helper.insertSample(
            sequenceId = sequenceId,
            timestamp = "2026-09-05T00:00:00",
            boatName = "Test Boat",
            captainName = "Test Captain",
            hullColor = "white",
            sailNumber = "GER 1",
            yardstick = 100.0,
            boatType = "Test",
            lat = 53.0,
            lon = 10.0,
            accuracy = 5f,
            cog = 0f,
            sog = 0f,
            accelX = 0f,
            accelY = 0f,
            accelZ = 0f,
            gyroX = 0f,
            gyroY = 0f,
            gyroZ = 0f,
            batteryPercent = 50,
            batteryCharging = false,
            trackingProfile = null,
            accessContextId = accessContextId
        )
    }

    private fun invokeNoArg(target: Any, methodName: String) {
        target.javaClass.getDeclaredMethod(methodName).apply {
            isAccessible = true
            invoke(target)
        }
    }

    private fun invokeRefreshLocationSampling(service: RegattaTrackingService) {
        service.javaClass
            .getDeclaredMethod("refreshLocationSampling", Location::class.java)
            .apply {
                isAccessible = true
                invoke(service, null)
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

    private fun clearTrackingPrefs() {
        context.getSharedPreferences("tracking_config", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun clearLocalStatusPrefs() {
        context.getSharedPreferences(LOCAL_STATUS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private companion object {
        const val DB_NAME = "regatta_tracking.db"
        const val LOCAL_STATUS_PREFS = "regatta_local_status"
    }
}
