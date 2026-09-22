package de.williserv.regattaclient

import android.app.Service
import android.content.Context
import android.content.Intent
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
        clearStickyRestartPrefs()
        TrackingServiceRuntimeState.markStopped()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        context.deleteDatabase(DB_NAME)
        clearTrackingPrefs()
        clearLocalStatusPrefs()
        clearStickyRestartPrefs()
        TrackingServiceRuntimeState.markStopped()
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

        setField(service, "serviceRunning", false)
        clearLocalStatusPrefs()
        looper.idleFor(2_000L, TimeUnit.MILLISECONDS)

        setField(service, "serviceRunning", true)
        looper.idleFor(56_000L, TimeUnit.MILLISECONDS)
        assertFalse(statusPrefs.contains("target_text"))

        setField(service, "serviceRunning", false)
        controller.destroy()
    }

    @Test
    fun `normal start and stop actions keep existing sticky semantics`() {
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()

        val startIntent = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_START
            putExtra(RegattaTrackingService.EXTRA_MANUAL_RECORDING, true)
        }
        assertEquals(Service.START_STICKY, service.onStartCommand(startIntent, 0, 1))
        assertTrue(TrackingServiceRuntimeState.isActive())

        val stopIntent = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_STOP
        }
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stopIntent, 0, 2))
        assertFalse(getField<Boolean>(service, "serviceRunning"))
        assertFalse(TrackingServiceRuntimeState.isActive())

        controller.destroy()
    }

    @Test
    fun `explicit race start clears stale persisted manual mode`() {
        seedAppState(inRace = true, manualTracking = true)

        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        setField(service, "eventPollRunning", true)

        val raceIntent = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_START
            putExtra(RegattaTrackingService.EXTRA_SERVER_URL, "https://raceoffice.example.org")
            putExtra(RegattaTrackingService.EXTRA_EVENT_NAME, "Event A")
            putExtra(RegattaTrackingService.EXTRA_SHARED_SECRET, "secret")
            putExtra(RegattaTrackingService.EXTRA_MANUAL_RECORDING, false)
        }

        assertEquals(Service.START_STICKY, service.onStartCommand(raceIntent, 0, 1))
        assertFalse(getField<Boolean>(service, "manualRecording"))
        assertFalse(
            context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
                .getBoolean("manual_tracking", true)
        )

        controller.destroy()
    }

    @Test
    fun `manual start is rejected while race is persisted active`() {
        seedAppState(inRace = true, manualTracking = false)

        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val manualIntent = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_START
            putExtra(RegattaTrackingService.EXTRA_MANUAL_RECORDING, true)
        }

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(manualIntent, 0, 1))
        assertFalse(getField<Boolean>(service, "serviceRunning"))
        assertFalse(getField<Boolean>(service, "manualRecording"))
        assertFalse(
            context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
                .getBoolean("manual_tracking", true)
        )

        controller.destroy()
    }

    @Test
    fun `unknown non-null action stays non-sticky`() {
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val intent = Intent(context, RegattaTrackingService::class.java).apply {
            action = "de.williserv.regattaclient.UNKNOWN"
        }

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1))
        assertFalse(getField<Boolean>(service, "serviceRunning"))

        controller.destroy()
    }

    @Test
    fun `sticky restart without active tracking does not start service`() {
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertFalse(getField<Boolean>(service, "serviceRunning"))

        controller.destroy()
    }

    @Test
    fun `sticky restart restores manual tracking and current boat setup`() {
        seedBoatSetup()
        seedAppState(inRace = false, manualTracking = true)

        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()

        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(getField<Boolean>(service, "serviceRunning"))
        assertTrue(TrackingServiceRuntimeState.isActive())
        assertTrue(getField<Boolean>(service, "manualRecording"))
        assertEquals("Test Boat", getField<String>(service, "boatName"))
        assertEquals("Test Skipper", getField<String>(service, "captainName"))
        assertEquals("GER 104", getField<String>(service, "sailNumber"))
        assertEquals(99.5, getField<Double>(service, "yardstick"), 0.0)
        assertNull(getField<Long?>(service, "accessContextId"))

        controller.destroy()
    }

    @Test
    fun `manual sticky restart ignores persisted race context and snapshot`() {
        seedBoatSetup()
        seedRaceSetup()
        seedRaceProgress()
        seedAppState(inRace = false, manualTracking = true)

        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()

        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(getField<Boolean>(service, "manualRecording"))
        assertEquals("", getField<String>(service, "serverUrl"))
        assertEquals("", getField<String>(service, "eventName"))
        assertEquals("", getField<String>(service, "sharedSecret"))
        assertNull(getField<String?>(service, "resolvedEventName"))
        assertNull(getField<Long?>(service, "accessContextId"))
        assertNull(getField<Any?>(service, "raceStartInstant"))
        assertFalse(
            context.getSharedPreferences(LOCAL_STATUS_PREFS, Context.MODE_PRIVATE)
                .contains("target_text")
        )

        controller.destroy()
    }

    @Test
    fun `manual sample does not mutate persisted race progress or local race status`() {
        seedBoatSetup()
        seedRaceSetup()
        seedRaceProgress()
        seedAppState(inRace = false, manualTracking = true)

        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val helper = getField<TrackingDbHelper>(service, "db")

        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        invokeNoArg(service, "generateAndStoreSample")

        val racePrefs = context.getSharedPreferences("regatta_race_state", Context.MODE_PRIVATE)
        assertTrue(racePrefs.getBoolean("race_started", false))
        assertFalse(racePrefs.getBoolean("race_finished", true))
        assertEquals(2, racePrefs.getInt("passed_marks", -1))
        assertTrue(racePrefs.getBoolean("is_ocs", false))
        assertFalse(
            context.getSharedPreferences(LOCAL_STATUS_PREFS, Context.MODE_PRIVATE)
                .contains("target_text")
        )
        assertEquals(1L, helper.countSamples())
        assertEquals(0L, helper.countPendingSamples())

        controller.destroy()
        helper.close()
    }

    @Test
    fun `sticky restart normalizes legacy double mode to race`() {
        seedBoatSetup()
        seedRaceSetup()
        seedRaceProgress()
        seedAppState(inRace = true, manualTracking = true)

        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        setField(service, "eventPollRunning", true)

        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertFalse(getField<Boolean>(service, "manualRecording"))
        assertTrue(getField<Long?>(service, "accessContextId") != null)
        assertEquals("Race 7", getField<String?>(service, "resolvedEventName"))
        assertFalse(
            context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
                .getBoolean("manual_tracking", true)
        )

        controller.destroy()
    }

    @Test
    fun `sticky race restart rejects incomplete persisted access context`() {
        seedBoatSetup()
        seedAppState(inRace = true, manualTracking = false)
        context.getSharedPreferences("race_setup", Context.MODE_PRIVATE)
            .edit()
            .putString("race_server", "https://raceoffice.example.org")
            .putString("race_event", "")
            .putString("race_secret", "secret")
            .commit()

        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertFalse(getField<Boolean>(service, "serviceRunning"))
        assertNull(getField<Long?>(service, "accessContextId"))

        controller.destroy()
    }

    @Test
    fun `sticky race restart restores offline snapshot access and race progress`() {
        seedBoatSetup()
        seedAppState(inRace = true, manualTracking = false)
        seedRaceSetup()
        seedRaceProgress()

        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        setField(service, "eventPollRunning", true)

        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(getField<Boolean>(service, "serviceRunning"))
        assertFalse(getField<Boolean>(service, "manualRecording"))
        assertEquals("https://raceoffice.example.org", getField<String>(service, "serverUrl"))
        assertEquals("Stable Series", getField<String>(service, "eventName"))
        assertEquals("secret", getField<String>(service, "sharedSecret"))
        assertEquals("Race 7", getField<String?>(service, "resolvedEventName"))
        assertTrue(getField<Long?>(service, "accessContextId") != null)
        assertTrue(getField<Any?>(service, "raceStartInstant") != null)
        assertTrue(getField<Boolean>(service, "raceStarted"))
        assertEquals(2, getField<Int>(service, "passedMarks"))
        assertTrue(getField<Boolean>(service, "isOcs"))

        controller.destroy()
    }

    @Test
    fun `course progress on stopped service is non-sticky and does not start tracking`() {
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val intent = courseProgressIntent(passedMarks = 3, raceStarted = true)

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1))
        assertFalse(getField<Boolean>(service, "serviceRunning"))
        assertEquals(0, getField<Int>(service, "passedMarks"))
        assertFalse(getField<Boolean>(service, "raceStarted"))

        controller.destroy()
    }

    @Test
    fun `course progress is ignored while manual tracking remains active`() {
        seedAppState(inRace = false, manualTracking = true)
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()

        val startIntent = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_START
            putExtra(RegattaTrackingService.EXTRA_MANUAL_RECORDING, true)
        }
        assertEquals(Service.START_STICKY, service.onStartCommand(startIntent, 0, 1))

        assertEquals(
            Service.START_STICKY,
            service.onStartCommand(courseProgressIntent(3, true), 0, 2)
        )
        assertTrue(getField<Boolean>(service, "serviceRunning"))
        assertTrue(getField<Boolean>(service, "manualRecording"))
        assertEquals(0, getField<Int>(service, "passedMarks"))
        assertFalse(getField<Boolean>(service, "raceStarted"))

        controller.destroy()
    }

    @Test
    fun `course progress still applies to active race without changing mode`() {
        seedAppState(inRace = true, manualTracking = false)
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        setField(service, "serviceRunning", true)
        setField(service, "manualRecording", false)
        setField(service, "resolvedEventName", "Race 7")
        setField(service, "eventName", "Stable Series")
        setField(service, "sailNumber", "GER 104")
        setField(service, "eventPollRunning", true)

        assertEquals(
            Service.START_STICKY,
            service.onStartCommand(courseProgressIntent(3, true), 0, 1)
        )
        assertFalse(getField<Boolean>(service, "manualRecording"))
        assertEquals(3, getField<Int>(service, "passedMarks"))
        assertTrue(getField<Boolean>(service, "raceStarted"))

        setField(service, "serviceRunning", false)
        controller.destroy()
    }

    @Test
    fun `rescheduling the same sample loop keeps only one pending callback`() {
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val helper = getField<TrackingDbHelper>(service, "db")
        val looper = shadowOf(Looper.getMainLooper())

        setField(service, "serviceRunning", true)
        setField(service, "manualRecording", true)

        invokeScheduleNextSample(service, 2_000L)
        invokeScheduleNextSample(service, 2_000L)

        looper.idleFor(1_999L, TimeUnit.MILLISECONDS)
        assertEquals(0L, helper.countSamples())

        looper.idleFor(1L, TimeUnit.MILLISECONDS)
        assertEquals(1L, helper.countSamples())

        setField(service, "serviceRunning", false)
        controller.destroy()
        helper.close()
    }

    @Test
    fun `repeated sticky restart does not duplicate manual sample loop`() {
        seedBoatSetup()
        seedAppState(inRace = false, manualTracking = true)

        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val helper = getField<TrackingDbHelper>(service, "db")
        val looper = shadowOf(Looper.getMainLooper())

        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 2))

        looper.idleFor(1, TimeUnit.SECONDS)
        assertEquals(1L, helper.countSamples())

        controller.destroy()
        helper.close()
    }

    private fun courseProgressIntent(passedMarks: Int, raceStarted: Boolean): Intent =
        Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_SET_COURSE_PROGRESS
            putExtra(RegattaTrackingService.EXTRA_PASSED_MARKS, passedMarks)
            putExtra(RegattaTrackingService.EXTRA_RACE_STARTED, raceStarted)
        }

    private fun seedAppState(inRace: Boolean, manualTracking: Boolean) {
        context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("in_race", inRace)
            .putBoolean("manual_tracking", manualTracking)
            .commit()
    }

    private fun seedBoatSetup() {
        context.getSharedPreferences("boat_setup", Context.MODE_PRIVATE)
            .edit()
            .putString("boat_name", "Test Boat")
            .putString("skipper_name", "Test Skipper")
            .putString("hull_color", "blue")
            .putString("sail_number", "GER 104")
            .putString("yardstick", "99.5")
            .putString("boat_type", "Test Class")
            .putBoolean("setup_confirmed", true)
            .commit()
    }

    private fun seedRaceSetup() {
        context.getSharedPreferences("race_setup", Context.MODE_PRIVATE)
            .edit()
            .putString("race_server", "https://raceoffice.example.org")
            .putString("race_event", "Stable Series")
            .putString("race_secret", "secret")
            .putString("resolved_event_name", "Race 7")
            .putInt("race_raw_state_version", RACE_RAW_STATE_VERSION)
            .putString("race_status_raw", "racing")
            .putString("race_start_raw", "2026-09-13T12:00:00")
            .putString("race_stop_raw", "2026-09-13T18:00:00")
            .putString("race_info_raw", "")
            .putString("race_course_json_raw", "{}")
            .putBoolean("race_course_shortened_raw", false)
            .putBoolean("race_data_ready", true)
            .commit()
    }

    private fun seedRaceProgress() {
        context.getSharedPreferences("regatta_race_state", Context.MODE_PRIVATE)
            .edit()
            .putString("event_name", "Stable Series")
            .putString("resolved_event_name", "Race 7")
            .putString("sail_number", "GER 104")
            .putBoolean("race_started", true)
            .putBoolean("race_finished", false)
            .putInt("passed_marks", 2)
            .putBoolean("is_ocs", true)
            .commit()
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

    private fun invokeScheduleNextSample(
        service: RegattaTrackingService,
        intervalMs: Long
    ) {
        service.javaClass
            .getDeclaredMethod("scheduleNextSample", Long::class.javaPrimitiveType)
            .apply {
                isAccessible = true
                invoke(service, intervalMs)
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

    private fun clearStickyRestartPrefs() {
        listOf(
            "app_state",
            "boat_setup",
            "race_setup",
            "regatta_race_state"
        ).forEach { prefsName ->
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    private companion object {
        const val DB_NAME = "regatta_tracking.db"
        const val LOCAL_STATUS_PREFS = "regatta_local_status"
    }
}
