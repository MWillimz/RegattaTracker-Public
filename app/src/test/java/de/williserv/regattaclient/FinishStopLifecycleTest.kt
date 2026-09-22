package de.williserv.regattaclient

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.MutableState
import androidx.work.Configuration
import androidx.work.WorkManager
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
class FinishStopLifecycleTest {

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
    fun `notification stop clears race state preserves pending uploads and blocks sticky restart`() {
        seedAppState(inRace = true, manualTracking = false)
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val db = getField<TrackingDbHelper>(service, "db")
        val accessContextId = requireNotNull(
            db.getOrCreateAccessContext(
                serverUrl = "https://raceoffice.example.org",
                accessIdentifier = "Event 115",
                accessSecret = "secret"
            )
        )
        insertPendingSample(db, accessContextId)

        setField(service, "serviceRunning", true)
        setField(service, "manualRecording", false)
        setField(service, "serverUrl", "https://raceoffice.example.org")
        setField(service, "eventName", "Event 115")
        setField(service, "sharedSecret", "secret")

        assertEquals(
            Service.START_NOT_STICKY,
            service.onStartCommand(stopIntent(), 0, 1)
        )
        assertStoppedAppState()
        assertFalse(getField<Boolean>(service, "serviceRunning"))
        assertEquals(1L, db.countPendingSamples())

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 2))
        assertFalse(getField<Boolean>(service, "serviceRunning"))
        assertEquals(1L, db.countPendingSamples())

        awaitStopHandoff(service)
        controller.destroy()
        db.close()
    }

    @Test
    fun `notification stop clears manual state`() {
        seedAppState(inRace = false, manualTracking = true)
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()

        setField(service, "serviceRunning", true)
        setField(service, "manualRecording", true)

        assertEquals(
            Service.START_NOT_STICKY,
            service.onStartCommand(stopIntent(), 0, 1)
        )
        assertStoppedAppState()
        assertFalse(getField<Boolean>(service, "serviceRunning"))
        assertFalse(getField<Boolean>(service, "manualRecording"))

        awaitStopHandoff(service)
        controller.destroy()
    }

    @Test
    fun `finish continue cancels auto stop preserves progress and permits more samples`() {
        seedAppState(inRace = true, manualTracking = false)
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val db = getField<TrackingDbHelper>(service, "db")
        val accessContextId = requireNotNull(
            db.getOrCreateAccessContext(
                serverUrl = "https://raceoffice.example.org",
                accessIdentifier = "Event 115",
                accessSecret = "secret"
            )
        )

        setField(service, "serviceRunning", true)
        setField(service, "manualRecording", false)
        setField(service, "serverUrl", "https://raceoffice.example.org")
        setField(service, "eventName", "Event 115")
        setField(service, "sharedSecret", "secret")
        setField(service, "resolvedEventName", "Race 115")
        setField(service, "sailNumber", "GER 115")
        setField(service, "accessContextId", accessContextId)
        setField(service, "raceStarted", true)
        setField(service, "raceFinished", true)
        setField(service, "passedMarks", 3)
        setField(service, "isOcs", false)

        invokeNoArg(service, "savePersistedRaceState")
        invokeNoArg(service, "publishLocalRaceStatus")
        assertTrue(getField<Boolean>(service, "autoStopAfterFinishScheduled"))

        val continueIntent = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_CONTINUE_AFTER_FINISH
        }
        assertEquals(Service.START_STICKY, service.onStartCommand(continueIntent, 0, 1))

        assertTrue(getField<Boolean>(service, "serviceRunning"))
        assertFalse(getField<Boolean>(service, "raceFinished"))
        assertTrue(getField<Boolean>(service, "finishDetectionSuppressed"))
        assertFalse(getField<Boolean>(service, "autoStopAfterFinishScheduled"))
        assertTrue(getField<Boolean>(service, "raceStarted"))
        assertEquals(3, getField<Int>(service, "passedMarks"))
        assertEquals(accessContextId, getField<Long?>(service, "accessContextId"))

        val racePrefs = context.getSharedPreferences(RACE_STATE_PREFS, Context.MODE_PRIVATE)
        assertFalse(racePrefs.getBoolean("race_finished", true))
        assertTrue(racePrefs.getBoolean("finish_detection_suppressed", false))
        assertEquals(3, racePrefs.getInt("passed_marks", -1))

        val localPrefs = context.getSharedPreferences(LOCAL_STATUS_PREFS, Context.MODE_PRIVATE)
        assertFalse(localPrefs.getBoolean("race_finished", true))

        runCatching {
            WorkManager.initialize(
                context,
                Configuration.Builder().build()
            )
        }
        invokeNoArg(service, "generateAndStoreSample")
        assertEquals(1L, db.countPendingSamples())
        assertTrue(getField<Boolean>(service, "serviceRunning"))

        setField(service, "serviceRunning", false)
        controller.destroy()
        db.close()
    }

    @Test
    fun `finish suppression rearms only after returning to approach side`() {
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()

        val finishLine = StartLine(
            ref = GeoPoint(lat = 0.0, lon = 0.0),
            mark = GeoPoint(lat = 0.0, lon = 0.01)
        )
        val approachPoint = GeoPoint(lat = 0.01, lon = 0.005)
        val finishPoint = GeoPoint(lat = -0.01, lon = 0.005)
        assertTrue(
            StartLineMath.signedDistanceToStartLineM(approachPoint, finishLine) *
                StartLineMath.signedDistanceToStartLineM(finishPoint, finishLine) < 0.0
        )

        setField(service, "finishLine", finishLine)
        setField(
            service,
            "courseMarks",
            listOf(
                CourseMark(
                    order = 1,
                    name = "Last mark",
                    point = approachPoint,
                    radiusM = 10.0
                )
            )
        )
        setField(service, "raceStarted", true)
        setField(service, "raceFinished", false)
        setField(service, "finishDetectionSuppressed", true)
        setField(service, "passedMarks", 1)

        invokeMarkAndFinishState(service, finishPoint, 1_000L)
        assertTrue(getField<Boolean>(service, "finishDetectionSuppressed"))
        assertFalse(getField<Boolean>(service, "raceFinished"))

        invokeMarkAndFinishState(service, approachPoint, 2_000L)
        assertFalse(getField<Boolean>(service, "finishDetectionSuppressed"))
        assertFalse(getField<Boolean>(service, "raceFinished"))

        invokeMarkAndFinishState(service, finishPoint, 3_000L)
        assertTrue(getField<Boolean>(service, "raceFinished"))

        controller.destroy()
    }

    @Test
    fun `sticky race restart preserves finish suppression`() {
        seedBoatSetup()
        seedRaceSetup()
        seedAppState(inRace = true, manualTracking = false)
        context.getSharedPreferences(RACE_STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("event_name", "Stable Series")
            .putString("resolved_event_name", "Race 7")
            .putString("sail_number", "GER 115")
            .putBoolean("race_started", true)
            .putBoolean("race_finished", false)
            .putBoolean("finish_detection_suppressed", true)
            .putInt("passed_marks", 2)
            .putBoolean("is_ocs", false)
            .commit()

        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        setField(service, "eventPollRunning", true)

        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(getField<Boolean>(service, "serviceRunning"))
        assertTrue(getField<Boolean>(service, "finishDetectionSuppressed"))
        assertFalse(getField<Boolean>(service, "raceFinished"))
        assertEquals(2, getField<Int>(service, "passedMarks"))

        setField(service, "serviceRunning", false)
        controller.destroy()
    }

    @Test
    fun `actual finish auto stop clears active mode but preserves finish state`() {
        seedAppState(inRace = true, manualTracking = false)
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()

        setField(service, "serviceRunning", true)
        setField(service, "manualRecording", false)
        setField(service, "resolvedEventName", "Race 115")
        setField(service, "eventName", "Event 115")
        setField(service, "sailNumber", "GER 115")
        setField(service, "raceStarted", true)
        setField(service, "raceFinished", true)

        invokeNoArg(service, "savePersistedRaceState")
        invokeNoArg(service, "publishLocalRaceStatus")
        assertTrue(getField<Boolean>(service, "autoStopAfterFinishScheduled"))

        getField<Runnable>(service, "autoStopAfterFinishRunnable").run()

        assertFalse(getField<Boolean>(service, "serviceRunning"))
        assertStoppedAppState()
        assertTrue(
            context.getSharedPreferences(RACE_STATE_PREFS, Context.MODE_PRIVATE)
                .getBoolean("race_finished", false)
        )
        assertTrue(
            context.getSharedPreferences(LOCAL_STATUS_PREFS, Context.MODE_PRIVATE)
                .getBoolean("race_finished", false)
        )

        awaitStopHandoff(service)
        controller.destroy()
    }

    @Test
    fun `open activity reconciles service side stop from app state`() {
        seedAppState(inRace = true, manualTracking = false)
        TrackingServiceRuntimeState.markActive()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()

        assertTrue(getState<Boolean>(activity, "inRace").value)
        assertFalse(getState<Boolean>(activity, "manualTracking").value)

        seedAppState(inRace = false, manualTracking = false)
        TrackingServiceRuntimeState.markStopped()
        invokeNoArg(activity, "reconcileTrackingState")

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

        controller.destroy()
    }

    private fun stopIntent(): Intent =
        Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_STOP
        }

    private fun awaitStopHandoff(service: RegattaTrackingService) {
        val deadlineNanos = System.nanoTime() + 5_000_000_000L
        val mainLooper = shadowOf(android.os.Looper.getMainLooper())

        while (getField<Boolean>(service, "stopHandoffInProgress")) {
            mainLooper.idle()

            if (System.nanoTime() >= deadlineNanos) {
                throw AssertionError("Service shutdown handoff did not complete")
            }

            Thread.sleep(10L)
        }

        mainLooper.idle()
    }

    private fun assertStoppedAppState() {
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

    private fun seedBoatSetup() {
        context.getSharedPreferences("boat_setup", Context.MODE_PRIVATE)
            .edit()
            .putString("boat_name", "Test Boat")
            .putString("skipper_name", "Test Skipper")
            .putString("hull_color", "blue")
            .putString("sail_number", "GER 115")
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
            .putString("race_stop_raw", "2026-09-13T23:59:00")
            .putString("race_info_raw", "")
            .putString("race_course_json_raw", "{}")
            .putBoolean("race_course_shortened_raw", false)
            .putBoolean("race_data_ready", true)
            .commit()
    }

    private fun insertPendingSample(db: TrackingDbHelper, accessContextId: Long): Long {
        return db.insertSample(
            sequenceId = 1L,
            timestamp = "2026-09-13T18:00:00",
            boatName = "Test Boat",
            captainName = "Test Skipper",
            hullColor = "blue",
            sailNumber = "GER 115",
            yardstick = 99.5,
            boatType = "Test Class",
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

    private fun invokeMarkAndFinishState(
        service: RegattaTrackingService,
        point: GeoPoint,
        nowMillis: Long
    ) {
        service.javaClass.getDeclaredMethod(
            "calculateMarkAndFinishState",
            GeoPoint::class.java,
            java.lang.Long.TYPE
        ).apply {
            isAccessible = true
            invoke(service, point, nowMillis)
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

    @Suppress("UNCHECKED_CAST")
    private fun <T> getState(target: Any, fieldName: String): MutableState<T> {
        return getField(target, fieldName)
    }

    private fun clearPrefs() {
        listOf(
            APP_STATE_PREFS,
            "boat_setup",
            "race_setup",
            RACE_STATE_PREFS,
            LOCAL_STATUS_PREFS,
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
        const val RACE_STATE_PREFS = "regatta_race_state"
        const val LOCAL_STATUS_PREFS = "regatta_local_status"
    }
}
