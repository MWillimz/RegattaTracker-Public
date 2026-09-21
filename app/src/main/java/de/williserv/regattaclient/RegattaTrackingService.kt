package de.williserv.regattaclient

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

data class CourseMark(
    val order: Int,
    val name: String,
    val point: GeoPoint,
    val radiusM: Double
)

internal fun shouldFinishTrackingServiceStop(
    handoffGeneration: Long,
    currentGeneration: Long,
    serviceRunning: Boolean
): Boolean {
    return handoffGeneration == currentGeneration && !serviceRunning
}

class RegattaTrackingService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "de.williserv.regattaclient.START_TRACKING_SERVICE"
        const val ACTION_STOP = "de.williserv.regattaclient.STOP_TRACKING_SERVICE"
        const val ACTION_CONTINUE_AFTER_FINISH =
            "de.williserv.regattaclient.CONTINUE_AFTER_FINISH"

        internal fun shouldIgnoreCommandDuringStopHandoff(
            stopHandoffInProgress: Boolean,
            action: String?
        ): Boolean {
            return stopHandoffInProgress && action != ACTION_START
        }

        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_EVENT_NAME = "event_name"
        const val EXTRA_SHARED_SECRET = "shared_secret"
        const val EXTRA_RESOLVED_EVENT_NAME = "resolved_event_name"

        const val API_VERSION = "v1"
        const val EXTRA_BOAT_NAME = "boat_name"
        const val EXTRA_CAPTAIN_NAME = "captain_name"
        const val EXTRA_HULL_COLOR = "hull_color"
        const val EXTRA_SAIL_NUMBER = "sail_number"
        const val EXTRA_YARDSTICK = "yardstick"

        const val EXTRA_BOAT_TYPE = "boat_type"
        const val EXTRA_MANUAL_RECORDING = "manual_recording"

        private const val NOTIFICATION_CHANNEL_ID = "regatta_tracking_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TRACKING_SERVICE_LOG_TAG = "RegattaTrackingService"

        const val ACTION_SET_COURSE_PROGRESS = "de.williserv.regattaclient.SET_COURSE_PROGRESS"

        const val EXTRA_PASSED_MARKS = "passed_marks"

        const val EXTRA_RACE_STARTED = "race_started"
    }

    private lateinit var db: TrackingDbHelper
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager

    private val handler = Handler(Looper.getMainLooper())
    private val localStatusPrefsName = "regatta_local_status"

    private val raceStatePrefsName = "regatta_race_state"
    private val localTimestampFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private var serviceRunning = false
    private var manualRecording = false

    private var serverUrl = ""
    private var eventName = ""
    private var sharedSecret = ""
    private var resolvedEventName: String? = null
    private var accessContextId: Long? = null

    private var boatName = "Boat name"
    private var captainName = "Max Mustermann"
    private var hullColor = "white"
    private var sailNumber = "GER 1234"
    private var yardstick = 100.0

    private var boatType = ""
    private var raceStatus = "unknown"
    private var raceStartInstant: Instant? = null
    private var raceStopInstant: Instant? = null

    private var startLine: StartLine? = null
    private var finishLine: StartLine? = null
    private var courseMarks: List<CourseMark> = emptyList()
    private var firstCourseMark: GeoPoint? = null

    private val startLineToleranceM = 10.0
    private val usableAccuracyM = 25f

    private var previousStartLinePosition: GeoPoint? = null
    private var previousStartLineTimestampMillis: Long? = null

    private var previousFinishLinePosition: GeoPoint? = null
    private var previousFinishLineTimestampMillis: Long? = null
    private var lastFinishStableSide: Int? = null

    private var previousMarkDetectionPosition: GeoPoint? = null
    private var markDetectionProgress: MarkDetectionProgress? = null

    private var isOcs = false
    private var raceStarted = false
    private var raceFinished = false
    private var finishDetectionSuppressed = false
    private var passedMarks = 0

    private var lastDtlM: Double? = null
    private var lastTtlSeconds: Double? = null
    private var currentTargetDistanceM: Double? = null

    private var samplingBand: SamplingDistanceBand? = null
    private var activeLocationIntervalMs = 1_000L

    private var sequenceId = 0L
    private var lastLocation: Location? = null

    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f

    private var gyroX = 0f
    private var gyroY = 0f
    private var gyroZ = 0f

    private var autoStopAfterFinishScheduled = false
    private var stopHandoffInProgress = false
    private var stopHandoffGeneration = 0L
    private var eventPollRunning = false
    private val eventPollLifecycleLock = Any()
    private var eventPollGeneration = 0L

    private var courseShortened = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            if (!manualRecording) {
                refreshLocationSampling(location)
            }
        }
    }

    private val sampleRunnable = object : Runnable {
        override fun run() {
            if (!serviceRunning) return

            if (manualRecording || isInsideRaceWindow()) {
                generateAndStoreSample()
            } else {
                publishLocalRaceStatus()
            }

            handler.postDelayed(this, currentSamplingIntervalMs())
        }
    }

    private val autoStopAfterFinishRunnable = object : Runnable {
        override fun run() {
            autoStopAfterFinishScheduled = false

            if (serviceRunning && raceFinished && !manualRecording) {
                stopTrackingService()
            }
        }
    }

    private val eventPollRunnable = object : Runnable {
        override fun run() {
            if (!serviceRunning || manualRecording) return

            pollEvent()
            handler.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()

        db = TrackingDbHelper(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (
            shouldIgnoreCommandDuringStopHandoff(
                stopHandoffInProgress = stopHandoffInProgress,
                action = intent?.action
            )
        ) {
            return START_NOT_STICKY
        }

        if (intent == null) {
            return handleStickyRestart()
        }

        when (intent.action) {
            ACTION_START -> {
                val requestedManual = intent.getBooleanExtra(EXTRA_MANUAL_RECORDING, false)
                val persistedInRace = getSharedPreferences("app_state", Context.MODE_PRIVATE)
                    .getBoolean("in_race", false)

                if (requestedManual && persistedInRace) {
                    getSharedPreferences("app_state", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("manual_tracking", false)
                        .apply()
                    if (!serviceRunning) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    return START_STICKY
                }

                if (serviceRunning && requestedManual != manualRecording) {
                    return START_STICKY
                }

                invalidatePendingStopHandoff()
                synchronized(eventPollLifecycleLock) {
                    eventPollGeneration += 1
                }
                readStartIntentExtras(intent, requestedManual)
                getSharedPreferences("app_state", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("in_race", !manualRecording)
                    .putBoolean("manual_tracking", manualRecording)
                    .apply()
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.tracking_active)))
                startTrackingService()
                updateNotification()
                return START_STICKY
            }

            ACTION_STOP -> {
                val appPrefs = getSharedPreferences("app_state", Context.MODE_PRIVATE)
                val explicitRaceLeave = !manualRecording &&
                    !appPrefs.getBoolean("in_race", false) &&
                    !appPrefs.getBoolean("manual_tracking", false)
                stopTrackingService(clearLocalRaceStatus = explicitRaceLeave)
                return START_NOT_STICKY
            }

            ACTION_CONTINUE_AFTER_FINISH -> {
                val persistedInRace = getSharedPreferences("app_state", Context.MODE_PRIVATE)
                    .getBoolean("in_race", false)
                if (!serviceRunning) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (manualRecording || !persistedInRace) {
                    return START_STICKY
                }

                continueAfterDetectedFinish()
                return START_STICKY
            }

            ACTION_SET_COURSE_PROGRESS -> {
                val persistedInRace = getSharedPreferences("app_state", Context.MODE_PRIVATE)
                    .getBoolean("in_race", false)
                if (!serviceRunning) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (manualRecording || !persistedInRace) {
                    return START_STICKY
                }

                val passedMarksFromUser = intent.getIntExtra(EXTRA_PASSED_MARKS, passedMarks)
                val raceStartedFromUser = intent.getBooleanExtra(EXTRA_RACE_STARTED, true)

                setCourseProgressFromUser(
                    passedMarksFromUser = passedMarksFromUser,
                    raceStartedFromUser = raceStartedFromUser
                )

                return START_STICKY
            }

            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    private fun adoptResolvedEventName(nextResolvedEventName: String) {
        val normalized = nextResolvedEventName.trim()
        if (normalized.isBlank() || normalized == resolvedEventName) return

        val previousResolvedEventName = resolvedEventName
        resolvedEventName = normalized

        if (previousResolvedEventName == null) {
            loadPersistedRaceStateForResolvedEvent(normalized)
            return
        }

        resetRunSpecificState()
        savePersistedRaceState()
    }

    private fun resetRunSpecificState() {
        raceStatus = "unknown"
        raceStartInstant = null
        raceStopInstant = null
        courseShortened = false

        startLine = null
        finishLine = null
        courseMarks = emptyList()
        firstCourseMark = null

        previousStartLinePosition = null
        previousStartLineTimestampMillis = null
        previousFinishLinePosition = null
        previousFinishLineTimestampMillis = null
        lastFinishStableSide = null
        previousMarkDetectionPosition = null
        markDetectionProgress = null

        isOcs = false
        raceStarted = false
        raceFinished = false
        finishDetectionSuppressed = false
        passedMarks = 0

        lastDtlM = null
        lastTtlSeconds = null
        currentTargetDistanceM = null
        samplingBand = null

        handler.removeCallbacks(autoStopAfterFinishRunnable)
        autoStopAfterFinishScheduled = false
    }

    private fun loadPersistedRaceStateForResolvedEvent(resolvedName: String) {
        val prefs = getSharedPreferences(raceStatePrefsName, Context.MODE_PRIVATE)
        val savedSailNumber = prefs.getString("sail_number", "") ?: ""

        if (savedSailNumber != sailNumber) return

        val savedResolvedEventName = prefs
            .getString("resolved_event_name", "")
            ?.trim()
            .orEmpty()

        val canRestore = if (savedResolvedEventName.isNotBlank()) {
            savedResolvedEventName == resolvedName
        } else {
            val legacyEventName = prefs.getString("event_name", "") ?: ""
            legacyEventName == eventName && eventName == resolvedName
        }

        if (!canRestore) return

        raceStarted = prefs.getBoolean("race_started", false)
        raceFinished = prefs.getBoolean("race_finished", false)
        finishDetectionSuppressed = prefs.getBoolean("finish_detection_suppressed", false)
        passedMarks = prefs.getInt("passed_marks", 0)
        isOcs = prefs.getBoolean("is_ocs", false)

        if (savedResolvedEventName.isBlank()) {
            savePersistedRaceState()
        }
    }

    private fun savePersistedRaceState() {
        val resolvedName = resolvedEventName ?: return

        getSharedPreferences(raceStatePrefsName, Context.MODE_PRIVATE)
            .edit()
            .putString("event_name", eventName)
            .putString("resolved_event_name", resolvedName)
            .putString("sail_number", sailNumber)
            .putBoolean("race_started", raceStarted)
            .putBoolean("race_finished", raceFinished)
            .putBoolean("finish_detection_suppressed", finishDetectionSuppressed)
            .putInt("passed_marks", passedMarks)
            .putBoolean("is_ocs", isOcs)
            .putLong("saved_at", System.currentTimeMillis())
            .apply()
    }

    private fun handleStickyRestart(): Int {
        synchronized(eventPollLifecycleLock) {
            eventPollGeneration += 1
        }

        if (!restoreStickyStartContext()) {
            persistTrackingStoppedState()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.tracking_active)))
        startTrackingService()
        updateNotification()
        return START_STICKY
    }

    private fun restoreStickyStartContext(): Boolean {
        val appPrefs = getSharedPreferences("app_state", Context.MODE_PRIVATE)
        val inRace = appPrefs.getBoolean("in_race", false)
        val persistedManual = appPrefs.getBoolean("manual_tracking", false)
        val manual = persistedManual && !inRace

        if (inRace && persistedManual) {
            appPrefs.edit()
                .putBoolean("manual_tracking", false)
                .apply()
        }

        if (!manual && !inRace) {
            return false
        }

        restoreBoatSetupForStickyRestart()
        manualRecording = manual

        if (manualRecording) {
            clearRaceContextForManualMode()
            return true
        }

        restoreRaceSetupForStickyRestart()

        if (
            serverUrl.isBlank() ||
            eventName.isBlank() ||
            sharedSecret.isBlank()
        ) {
            accessContextId = null
            return false
        }

        refreshAccessContextId()
        return accessContextId != null
    }

    private fun restoreBoatSetupForStickyRestart() {
        val prefs = getSharedPreferences("boat_setup", Context.MODE_PRIVATE)

        boatName = prefs.getString("boat_name", boatName) ?: boatName
        captainName = prefs.getString("skipper_name", captainName) ?: captainName
        hullColor = prefs.getString("hull_color", hullColor) ?: hullColor
        sailNumber = prefs.getString("sail_number", sailNumber) ?: sailNumber
        yardstick = prefs.getString("yardstick", yardstick.toString())
            ?.toDoubleOrNull()
            ?: yardstick
        boatType = prefs.getString("boat_type", boatType) ?: boatType
    }

    private fun restoreRaceSetupForStickyRestart() {
        val prefs = getSharedPreferences("race_setup", Context.MODE_PRIVATE)

        serverUrl = prefs.getString("race_server", "").orEmpty()
        eventName = prefs.getString("race_event", "").orEmpty()
        sharedSecret = prefs.getString("race_secret", "").orEmpty()

        prefs.getString("resolved_event_name", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::adoptResolvedEventName)
    }

    private fun readStartIntentExtras(intent: Intent, manualMode: Boolean) {
        manualRecording = manualMode

        boatName = intent.getStringExtra(EXTRA_BOAT_NAME) ?: boatName
        captainName = intent.getStringExtra(EXTRA_CAPTAIN_NAME) ?: captainName
        hullColor = intent.getStringExtra(EXTRA_HULL_COLOR) ?: hullColor
        sailNumber = intent.getStringExtra(EXTRA_SAIL_NUMBER) ?: sailNumber
        yardstick = intent.getStringExtra(EXTRA_YARDSTICK)?.toDoubleOrNull() ?: yardstick
        boatType = intent.getStringExtra(EXTRA_BOAT_TYPE) ?: boatType

        if (manualRecording) {
            clearRaceContextForManualMode()
            return
        }

        serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: serverUrl
        eventName = intent.getStringExtra(EXTRA_EVENT_NAME) ?: eventName
        sharedSecret = intent.getStringExtra(EXTRA_SHARED_SECRET) ?: sharedSecret

        intent.getStringExtra(EXTRA_RESOLVED_EVENT_NAME)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::adoptResolvedEventName)

        refreshAccessContextId()
    }

    private fun clearRaceContextForManualMode() {
        serverUrl = ""
        eventName = ""
        sharedSecret = ""
        resolvedEventName = null
        accessContextId = null
    }

    private fun refreshAccessContextId() {
        accessContextId = if (manualRecording) {
            null
        } else {
            db.getOrCreateAccessContext(
                serverUrl = serverUrl,
                accessIdentifier = eventName,
                accessSecret = sharedSecret
            )
        }
    }

    private fun startTrackingService() {
        db.resetTrackingSessionMetadata()

        if (serviceRunning) {
            reconfigureSamplingSchedule()
            return
        }

        previousMarkDetectionPosition = null
        markDetectionProgress = null

        if (!manualRecording) {
            restoreCachedEventSnapshot()
        }
        serviceRunning = true

        startLocationUpdates()
        startImuUpdates()

        if (!manualRecording) {
            pollEvent()
        }

        handler.postDelayed(sampleRunnable, currentSamplingIntervalMs())
        if (!manualRecording) {
            handler.postDelayed(eventPollRunnable, 10_000L)
            publishLocalRaceStatus()
        }

        updateNotification()
    }

    private fun reconfigureSamplingSchedule() {
        handler.removeCallbacks(sampleRunnable)

        val intervalMs = currentSamplingIntervalMs()
        requestLocationUpdatesForInterval(intervalMs)
        handler.postDelayed(sampleRunnable, intervalMs)
    }

    private fun persistTrackingStoppedState() {
        getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("in_race", false)
            .putBoolean("manual_tracking", false)
            .commit()
    }

    private fun stopTrackingService(clearLocalRaceStatus: Boolean = false) {
        if (stopHandoffInProgress) return

        stopHandoffInProgress = true
        val handoffGeneration = ++stopHandoffGeneration

        persistTrackingStoppedState()

        synchronized(eventPollLifecycleLock) {
            eventPollGeneration += 1
            serviceRunning = false
            if (!manualRecording) {
                RaceRuntimeStateStore.clearFor(serverUrl, eventName, sharedSecret)
            }
        }
        manualRecording = false
        accessContextId = null

        handler.removeCallbacks(sampleRunnable)
        handler.removeCallbacks(eventPollRunnable)
        handler.removeCallbacks(autoStopAfterFinishRunnable)
        autoStopAfterFinishScheduled = false

        if (clearLocalRaceStatus) {
            getSharedPreferences(localStatusPrefsName, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }

        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {
        }

        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {
        }

        thread(name = "regatta-telemetry-shutdown-handoff") {
            val operation = try {
                TelemetryUploadScheduler.enqueueShutdownHandoffIfNeeded(this)
            } catch (e: Exception) {
                Log.e(
                    TRACKING_SERVICE_LOG_TAG,
                    "Could not enqueue telemetry shutdown handoff",
                    e
                )
                null
            }

            if (operation == null) {
                handler.post {
                    finishTrackingServiceStop(handoffGeneration)
                }
                return@thread
            }

            operation.result.addListener(
                {
                    runCatching { operation.result.get() }
                        .exceptionOrNull()
                        ?.let { error ->
                            Log.e(
                                TRACKING_SERVICE_LOG_TAG,
                                "Telemetry shutdown handoff was not persisted",
                                error
                            )
                        }
                    finishTrackingServiceStop(handoffGeneration)
                },
                ContextCompat.getMainExecutor(this)
            )
        }
    }

    private fun invalidatePendingStopHandoff() {
        if (!stopHandoffInProgress) return

        stopHandoffGeneration += 1
        stopHandoffInProgress = false
    }

    private fun finishTrackingServiceStop(handoffGeneration: Long) {
        if (
            !shouldFinishTrackingServiceStop(
                handoffGeneration = handoffGeneration,
                currentGeneration = stopHandoffGeneration,
                serviceRunning = serviceRunning
            )
        ) {
            return
        }

        stopHandoffInProgress = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun startLocationUpdates() {
        requestLocationUpdatesForInterval(currentSamplingIntervalMs())

        val permissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!permissionGranted) return

        try {
            val cachedLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (cachedLocation != null) {
                lastLocation = cachedLocation
                if (!manualRecording) {
                    refreshLocationSampling(cachedLocation)
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun currentSamplingDecision(): SamplingDecision {
        if (manualRecording) {
            return SamplingDecision(
                intervalMs = 1_000L,
                band = samplingBand,
                nearestDistanceM = null
            )
        }

        val position = lastLocation?.let {
            GeoPoint(lat = it.latitude, lon = it.longitude)
        }
        val decision = SamplingPolicy.decide(
            position = position,
            startLine = startLine,
            finishLine = finishLine,
            courseMarks = courseMarks,
            trackingProfile = TrackingProfileConfig.read(this),
            sailNumber = sailNumber,
            previousBand = samplingBand
        )
        samplingBand = decision.band
        return decision
    }

    private fun currentSamplingIntervalMs(): Long {
        return currentSamplingDecision().intervalMs
    }

    private fun refreshLocationSampling(location: Location?) {
        if (!serviceRunning || manualRecording) return

        if (location != null) {
            lastLocation = location
        }

        val nextIntervalMs = currentSamplingIntervalMs()
        if (nextIntervalMs != activeLocationIntervalMs) {
            handler.removeCallbacks(sampleRunnable)
            requestLocationUpdatesForInterval(nextIntervalMs)
            handler.postDelayed(sampleRunnable, nextIntervalMs)
        }
    }

    private fun requestLocationUpdatesForInterval(intervalMs: Long) {
        val permissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!permissionGranted) return

        try {
            locationManager.removeUpdates(locationListener)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                intervalMs,
                0f,
                locationListener,
                Looper.getMainLooper()
            )
            activeLocationIntervalMs = intervalMs
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun updateAutoStopAfterFinish() {
        if (raceFinished && !manualRecording) {
            if (!autoStopAfterFinishScheduled) {
                autoStopAfterFinishScheduled = true
                handler.postDelayed(autoStopAfterFinishRunnable, 5 * 60 * 1000L)
            }
        } else {
            if (autoStopAfterFinishScheduled) {
                autoStopAfterFinishScheduled = false
                handler.removeCallbacks(autoStopAfterFinishRunnable)
            }
        }
    }

    private fun startImuUpdates() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (accelerometer != null) {
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        if (gyroscope != null) {
            sensorManager.registerListener(
                this,
                gyroscope,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
    }

    private fun pollEvent() {
        if (!serviceRunning || manualRecording || eventPollRunning) return

        val pollGeneration = synchronized(eventPollLifecycleLock) {
            eventPollGeneration
        }
        eventPollRunning = true

        thread {
            var serverResponded = false
            try {
                val eventUrl = buildEventUrl()
                val connection = URL(eventUrl).openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.setRequestProperty("accept", "application/json")
                connection.setRequestProperty("x-event-name", eventName)
                connection.setRequestProperty("x-shared-secret", sharedSecret)
                connection.setRequestProperty("x-api-version", API_VERSION)

                val responseCode = connection.responseCode
                serverResponded = true
                ServerConnectionStateStore.markReachable(this, serverUrl)
                val body = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }

                connection.disconnect()

                if (responseCode in 200..299) {
                    val applied = synchronized(eventPollLifecycleLock) {
                        if (!serviceRunning || manualRecording || pollGeneration != eventPollGeneration) {
                            false
                        } else {
                            parseEventResponse(body)
                            publishLocalRaceStatus()
                            updateNotification()
                            true
                        }
                    }
                    if (applied) {
                        handler.post {
                            refreshLocationSampling(lastLocation)
                        }
                    }
                }
            } catch (_: Exception) {
                if (!serverResponded) {
                    ServerConnectionStateStore.markNoConnection(this, serverUrl)
                }
            } finally {
                eventPollRunning = false
            }
        }
    }

    private fun buildEventUrl(): String {
        return buildNormalApiGetUrl(
            baseUrl = getBaseServerUrl(),
            path = "/event",
            eventName = eventName
        )
    }

    private fun getBaseServerUrl(): String =
        normalizeServerBaseUrl(serverUrl)

    private fun restoreCachedEventSnapshot() {
        val snapshot = RaceEventSnapshotStore.loadMatching(
            context = this,
            server = serverUrl,
            event = eventName,
            secret = sharedSecret,
            expectedResolvedEventName = resolvedEventName
        ) ?: return

        applyRaceEventSnapshot(snapshot)
    }

    private fun parseEventResponse(body: String) {
        val snapshot = parseRaceEventSnapshot(body)
        applyRaceEventSnapshot(snapshot)
        RaceEventSnapshotStore.save(
            context = this,
            server = serverUrl,
            event = eventName,
            secret = sharedSecret,
            snapshot = snapshot
        )
    }

    private fun applyRaceEventSnapshot(snapshot: RaceEventSnapshot) {
        adoptResolvedEventName(snapshot.resolvedEventName)

        raceStatus = snapshot.status.ifBlank { "unknown" }
        courseShortened = snapshot.courseShortened
        raceStartInstant = parseServerInstant(snapshot.startRaw)
        raceStopInstant = parseServerInstant(snapshot.stopRaw)

        startLine = null
        finishLine = null
        courseMarks = emptyList()
        firstCourseMark = null

        val course = snapshot.courseJson
            .takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }

        parseStartLine(course)
        parseFinishLine(course)
        parseMarks(course)

        RaceRuntimeStateStore.publish(
            server = serverUrl,
            event = eventName,
            secret = sharedSecret,
            resolvedEventName = snapshot.resolvedEventName,
            status = raceStatus,
            startEpochMillis = raceStartInstant?.toEpochMilli(),
            stopEpochMillis = raceStopInstant?.toEpochMilli()
        )
    }

    private fun parseStartLine(course: JSONObject?) {
        val startLineObj = course?.optJSONObject("start_line")
        val ref = startLineObj?.optJSONObject("ref")
        val mark = startLineObj?.optJSONObject("mark")

        if (ref != null && mark != null) {
            startLine = StartLine(
                ref = GeoPoint(
                    lat = ref.optDouble("lat"),
                    lon = ref.optDouble("lon")
                ),
                mark = GeoPoint(
                    lat = mark.optDouble("lat"),
                    lon = mark.optDouble("lon")
                )
            )
        }
    }

    private fun parseFinishLine(course: JSONObject?) {
        val finishLineObj = course?.optJSONObject("finish_line")
        val ref = finishLineObj?.optJSONObject("ref")
        val mark = finishLineObj?.optJSONObject("mark")

        if (ref != null && mark != null) {
            finishLine = StartLine(
                ref = GeoPoint(
                    lat = ref.optDouble("lat"),
                    lon = ref.optDouble("lon")
                ),
                mark = GeoPoint(
                    lat = mark.optDouble("lat"),
                    lon = mark.optDouble("lon")
                )
            )
        }
    }

    private fun parseMarks(course: JSONObject?) {
        val marksArray = course?.optJSONArray("marks") ?: return

        val parsedMarks = mutableListOf<CourseMark>()

        for (i in 0 until marksArray.length()) {
            val mark = marksArray.optJSONObject(i) ?: continue

            val omitWhenShortened = mark.optBoolean("omit_when_shortened", false)

            if (courseShortened && omitWhenShortened) {
                continue
            }

            parsedMarks.add(
                CourseMark(
                    order = mark.optInt("order", i + 1),
                    name = mark.optString("name", "Mark ${i + 1}"),
                    point = GeoPoint(
                        lat = mark.optDouble("lat"),
                        lon = mark.optDouble("lon")
                    ),
                    radiusM = mark.optDouble("radius_m", 100.0)
                )
            )
        }

        courseMarks = parsedMarks.sortedBy { it.order }
        firstCourseMark = courseMarks.firstOrNull()?.point
    }

    private fun parseServerInstant(value: String): Instant? {
        if (value.isBlank()) return null

        val raceZone = ZoneId.systemDefault()

        return try {
            when {
                value.endsWith("Z") -> {
                    Instant.parse(value)
                }

                value.contains("+") || value.drop(10).contains("-") -> {
                    OffsetDateTime.parse(value).toInstant()
                }

                value.count { it == ':' } == 1 -> {
                    LocalDateTime
                        .parse("${value}:00")
                        .atZone(raceZone)
                        .toInstant()
                }

                value.count { it == ':' } == 2 -> {
                    LocalDateTime
                        .parse(value)
                        .atZone(raceZone)
                        .toInstant()
                }

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isInsideRaceWindow(): Boolean {
        if (raceStatus.equals("postponed", ignoreCase = true)) {
            return false
        }

        val start = raceStartInstant
        val stop = raceStopInstant

        if (start == null) {
            return false
        }

        val recordingStart = start.minus(Duration.ofMinutes(30))
        val now = Instant.now()

        if (now.isBefore(recordingStart)) {
            return false
        }

        if (stop == null) {
            return true
        }

        return !now.isAfter(stop)
    }

    private fun generateAndStoreSample() {
        sequenceId += 1

        val sampleTime = telemetrySampleTime(
            now = OffsetDateTime.now(),
            formatter = localTimestampFormatter
        )
        val timestamp = sampleTime.timestamp
        val location = lastLocation
        val lat = location?.latitude ?: 0.0
        val lon = location?.longitude ?: 0.0
        val accuracy = location?.accuracy ?: 9999f
        val cog = location?.bearing ?: 0f
        val sog = location?.speed ?: 0f

        if (!manualRecording) {
            calculateLocalRaceState(
                lat = lat,
                lon = lon,
                accuracy = accuracy
            )
        }

        val sampleAccessContextId = if (manualRecording) {
            null
        } else {
            accessContextId ?: db.getOrCreateAccessContext(
                serverUrl = serverUrl,
                accessIdentifier = eventName,
                accessSecret = sharedSecret
            ).also { accessContextId = it }
        }

        if (!manualRecording && sampleAccessContextId == null) {
            publishDebugError(getString(R.string.storage_error_access_context))
            return
        }

        val insertedId = db.insertSample(
            sequenceId = sequenceId,
            timestamp = timestamp,
            boatName = boatName,
            captainName = captainName,
            hullColor = hullColor,
            sailNumber = sailNumber,
            yardstick = yardstick,
            boatType = boatType,
            lat = lat,
            lon = lon,
            accuracy = accuracy,
            cog = cog,
            sog = sog,
            accelX = accelX,
            accelY = accelY,
            accelZ = accelZ,
            gyroX = gyroX,
            gyroY = gyroY,
            gyroZ = gyroZ,
            accessContextId = sampleAccessContextId,
            utcOffsetMinutes = sampleTime.utcOffsetMinutes
        )

        if (insertedId == -1L) return

        if (manualRecording) {
            db.markUploaded(insertedId)
            updateNotification()
            return
        }

        TelemetryUploadScheduler.enqueueWakeup(this)
        updateNotification()
    }

    private fun calculateLocalRaceState(
        lat: Double,
        lon: Double,
        accuracy: Float
    ) {
        if (accuracy > usableAccuracyM) {
            publishLocalRaceStatus()
            return
        }

        val currentGeoPoint = GeoPoint(lat = lat, lon = lon)
        val nowMillis = System.currentTimeMillis()
        val nowInstant = Instant.ofEpochMilli(nowMillis)

        calculateStartLineState(
            currentGeoPoint = currentGeoPoint,
            nowMillis = nowMillis,
            nowInstant = nowInstant
        )

        calculateMarkAndFinishState(
            currentGeoPoint = currentGeoPoint,
            nowMillis = nowMillis
        )

        publishLocalRaceStatus()
    }

    private fun calculateStartLineState(
        currentGeoPoint: GeoPoint,
        nowMillis: Long,
        nowInstant: Instant
    ) {
        val line = startLine
        val startInstant = raceStartInstant

        if (line == null || startInstant == null) {
            return
        }

        val metrics = StartLineMath.calculateLineMetrics(
            previousPosition = previousStartLinePosition,
            currentPosition = currentGeoPoint,
            previousTimestampMillis = previousStartLineTimestampMillis,
            currentTimestampMillis = nowMillis,
            startLine = line
        )

        lastDtlM = metrics.signedDistanceM
        lastTtlSeconds = metrics.ttlSeconds

        val beforeStart = nowInstant.isBefore(startInstant)

        if (beforeStart) {
            if (!raceStarted) {
                updateOcsByCourseSide(
                    line = line,
                    boatSignedDistance = metrics.signedDistanceM
                )
            }
        } else {
            if (isOcs) {
                updateOcsByCourseSide(
                    line = line,
                    boatSignedDistance = metrics.signedDistanceM
                )
            }

            val isOnCourseSide = isBoatOnCourseSide(
                line = line,
                boatSignedDistance = metrics.signedDistanceM
            )

            if (
                shouldMarkRaceStarted(
                    isOcs = isOcs,
                    raceStarted = raceStarted,
                    isOnCourseSide = isOnCourseSide
                )
            ) {
                raceStarted = true
                savePersistedRaceState()
            }
        }

        previousStartLinePosition = currentGeoPoint
        previousStartLineTimestampMillis = nowMillis
    }

    private fun updateOcsByCourseSide(
        line: StartLine,
        boatSignedDistance: Double
    ) {
        val mark = resolveCourseSideReference(firstCourseMark, finishLine) ?: return

        val markSignedDistance = StartLineMath.signedDistanceToStartLineM(
            point = mark,
            startLine = line
        )

        val markSide = sideWithTolerance(markSignedDistance)
        val boatSide = sideWithTolerance(boatSignedDistance)

        if (markSide == 0) return

        val previousOcs = isOcs

        when (boatSide) {
            markSide -> isOcs = true
            -markSide -> isOcs = false
            else -> {
                // Inside the tolerance zone: do not change OCS state.
            }
        }

        if (previousOcs != isOcs) {
            savePersistedRaceState()
        }
    }

    private fun isBoatOnFinishSide(
        line: StartLine,
        boatSignedDistance: Double
    ): Boolean {
        val lastMark = resolveFinishApproachReference(
            courseMarks.lastOrNull()?.point,
            startLine
        ) ?: return false

        val lastMarkSignedDistance = StartLineMath.signedDistanceToStartLineM(
            point = lastMark,
            startLine = line
        )

        val lastMarkSide = sideWithTolerance(lastMarkSignedDistance)
        val boatSide = sideWithTolerance(boatSignedDistance)

        if (lastMarkSide == 0) return false
        if (boatSide == 0) return false

        return boatSide == -lastMarkSide
    }

    private fun isBoatOnFinishApproachSide(
        line: StartLine,
        boatSignedDistance: Double
    ): Boolean {
        val approachReference = resolveFinishApproachReference(
            courseMarks.lastOrNull()?.point,
            startLine
        ) ?: return false

        val approachSignedDistance = StartLineMath.signedDistanceToStartLineM(
            point = approachReference,
            startLine = line
        )

        val approachSide = sideWithTolerance(approachSignedDistance)
        val boatSide = sideWithTolerance(boatSignedDistance)

        if (approachSide == 0 || boatSide == 0) return false
        return boatSide == approachSide
    }

    private fun isBoatOnCourseSide(
        line: StartLine,
        boatSignedDistance: Double
    ): Boolean {
        val mark = resolveCourseSideReference(firstCourseMark, finishLine) ?: return false

        val markSignedDistance = StartLineMath.signedDistanceToStartLineM(
            point = mark,
            startLine = line
        )

        val markSide = sideWithTolerance(markSignedDistance)
        val boatSide = sideWithTolerance(boatSignedDistance)

        if (markSide == 0) return false
        if (boatSide == 0) return false

        return boatSide == markSide
    }

    private fun sideWithTolerance(signedDistanceM: Double): Int {
        return when {
            signedDistanceM > startLineToleranceM -> 1
            signedDistanceM < -startLineToleranceM -> -1
            else -> 0
        }
    }

    private fun calculateMarkAndFinishState(
        currentGeoPoint: GeoPoint,
        nowMillis: Long
    ) {
        if (!raceStarted || raceFinished || isOcs) {
            previousMarkDetectionPosition = null
            markDetectionProgress = null
            updateCurrentTargetDistance(currentGeoPoint)
            return
        }

        val line = finishLine
        val currentFinishSignedDistance = line?.let {
            StartLineMath.signedDistanceToStartLineM(currentGeoPoint, it)
        }
        val currentFinishStableSide = currentFinishSignedDistance?.let(::sideWithTolerance) ?: 0

        val nextMarkIndex = passedMarks
        val nextMark = courseMarks.getOrNull(nextMarkIndex)

        if (nextMark != null) {
            if (currentFinishStableSide != 0) {
                lastFinishStableSide = currentFinishStableSide
            }

            currentTargetDistanceM = StartLineMath.distanceBetweenMeters(
                currentGeoPoint,
                nextMark.point
            )

            val anchors = resolveMarkDetectionAnchors(
                previousCoursePosition = courseMarks.getOrNull(nextMarkIndex - 1)?.point,
                nextCoursePosition = courseMarks.getOrNull(nextMarkIndex + 1)?.point,
                startLine = startLine,
                finishLine = finishLine
            )
            val geometry = anchors?.let {
                buildMarkDetectionGeometry(
                    previousAnchor = it.previous,
                    mark = nextMark.point,
                    nextAnchor = it.next,
                    radiusM = nextMark.radiusM
                )
            }

            val previousPosition = previousMarkDetectionPosition
            if (geometry == null) {
                markDetectionProgress = null
            } else if (previousPosition != null) {
                val progress = updateMarkDetectionProgress(
                    previousPosition = previousPosition,
                    currentPosition = currentGeoPoint,
                    geometry = geometry,
                    previousProgress = markDetectionProgress
                )
                markDetectionProgress = progress

                if (progress.completed) {
                    passedMarks += 1
                    markDetectionProgress = null
                    savePersistedRaceState()
                }
            }

            previousMarkDetectionPosition = currentGeoPoint
            return
        }

        previousMarkDetectionPosition = null
        markDetectionProgress = null

        if (line == null) return

        val metrics = StartLineMath.calculateLineMetrics(
            previousPosition = previousFinishLinePosition,
            currentPosition = currentGeoPoint,
            previousTimestampMillis = previousFinishLineTimestampMillis,
            currentTimestampMillis = nowMillis,
            startLine = line
        )

        currentTargetDistanceM = abs(metrics.signedDistanceM)

        val stableSide = sideWithTolerance(metrics.signedDistanceM)
        val approachReference = resolveFinishApproachReference(
            courseMarks.lastOrNull()?.point,
            startLine
        )
        val approachSide = approachReference?.let {
            sideWithTolerance(
                StartLineMath.signedDistanceToStartLineM(
                    point = it,
                    startLine = line
                )
            )
        } ?: 0

        if (finishDetectionSuppressed) {
            if (approachSide != 0 && stableSide == approachSide) {
                finishDetectionSuppressed = false
                savePersistedRaceState()
            }

            if (stableSide != 0) {
                lastFinishStableSide = stableSide
            }
            previousFinishLinePosition = currentGeoPoint
            previousFinishLineTimestampMillis = nowMillis
            return
        }

        val crossedFinishInRaceDirection =
            approachSide != 0 &&
                lastFinishStableSide == approachSide &&
                stableSide == -approachSide

        if (!raceFinished && crossedFinishInRaceDirection) {
            raceFinished = true
            savePersistedRaceState()
        }

        if (stableSide != 0) {
            lastFinishStableSide = stableSide
        }
        previousFinishLinePosition = currentGeoPoint
        previousFinishLineTimestampMillis = nowMillis
    }

    private fun updateCurrentTargetDistance(currentGeoPoint: GeoPoint) {
        val target = getCurrentTarget()

        currentTargetDistanceM = when (target) {
            "start_line", "ocs_clear" -> {
                val line = startLine
                if (line != null) {
                    abs(StartLineMath.signedDistanceToStartLineM(currentGeoPoint, line))
                } else {
                    null
                }
            }

            "finish_line" -> {
                val line = finishLine
                if (line != null) {
                    abs(StartLineMath.signedDistanceToStartLineM(currentGeoPoint, line))
                } else {
                    null
                }
            }

            "finished" -> {
                null
            }

            else -> {
                val nextMark = courseMarks.getOrNull(passedMarks)
                if (nextMark != null) {
                    StartLineMath.distanceBetweenMeters(currentGeoPoint, nextMark.point)
                } else {
                    null
                }
            }
        }
    }

    private fun getCurrentTarget(): String {
        if (raceFinished) return "finished"
        if (isOcs) return "ocs_clear"
        if (!raceStarted) return "start_line"

        val nextMark = courseMarks.getOrNull(passedMarks)
        if (nextMark != null) {
            return "mark"
        }

        return "finish_line"
    }

    private fun buildTargetText(): String {
        return when (getCurrentTarget()) {
            "finished" -> getString(R.string.next_finished)
            "ocs_clear" -> getString(R.string.next_return_start)
            "start_line" -> getString(R.string.next_start_line)
            "finish_line" -> getString(R.string.next_finish_line)
            "mark" -> {
                val mark = courseMarks.getOrNull(passedMarks)
                if (mark != null) {
                    getString(R.string.next_mark_value, mark.order, mark.name)
                } else {
                    getString(R.string.next_mark)
                }
            }

            else -> getString(R.string.next_unknown)
        }
    }

    private fun buildProgressText(): String {
        return buildLocalProgressText(
            totalMarks = courseMarks.size,
            passedMarks = passedMarks,
            raceStarted = raceStarted,
            raceFinished = raceFinished,
            markedFormatter = { passed, total, percent ->
                getString(R.string.progress_value, passed, total, percent)
            },
            directFormatter = { percent ->
                getString(R.string.progress_direct_value, percent)
            }
        )
    }

    private fun continueAfterDetectedFinish() {
        if (!serviceRunning || manualRecording || !raceFinished) return

        raceFinished = false
        finishDetectionSuppressed = true
        currentTargetDistanceM = null

        handler.removeCallbacks(autoStopAfterFinishRunnable)
        autoStopAfterFinishScheduled = false

        savePersistedRaceState()
        publishLocalRaceStatus()
        updateNotification()
    }

    private fun setCourseProgressFromUser(
        passedMarksFromUser: Int,
        raceStartedFromUser: Boolean
    ) {
        val safePassedMarks = if (courseMarks.isNotEmpty()) {
            passedMarksFromUser.coerceIn(0, courseMarks.size)
        } else {
            passedMarksFromUser.coerceAtLeast(0)
        }

        isOcs = false
        raceStarted = raceStartedFromUser
        raceFinished = false
        finishDetectionSuppressed = false
        passedMarks = if (raceStartedFromUser) {
            safePassedMarks
        } else {
            0
        }
        lastFinishStableSide = null
        previousMarkDetectionPosition = null
        markDetectionProgress = null

        currentTargetDistanceM = null

        savePersistedRaceState()
        publishLocalRaceStatus()
        updateNotification()

        pollEvent()
    }

    private fun buildBoatStatusText(): String {
        val status = when {
            raceFinished -> getString(R.string.boat_status_finished)
            isOcs -> getString(R.string.boat_status_ocs)
            raceStarted -> getString(R.string.boat_status_racing)
            else -> getString(R.string.boat_status_not_started)
        }

        return getString(R.string.boat_status_value, status)
    }

    private fun buildTargetDistanceSuffix(): String {
        val distance = currentTargetDistanceM ?: return ""

        return String.format(
            Locale.US,
            " · %.0f m",
            distance
        )
    }

    private fun publishLocalRaceStatus() {
        val distanceText = currentTargetDistanceM?.let {
            getString(R.string.distance_meters, it)
        } ?: getString(R.string.distance_unknown)

        val ttlText = getString(R.string.ttl_unknown)

        val ocsText = if (isOcs) {
            getString(R.string.ocs_yes)
        } else {
            getString(R.string.ocs_no)
        }

        val targetText = buildTargetText()
        val progressText = buildProgressText()
        val boatStatusText = buildBoatStatusText()

        getSharedPreferences(localStatusPrefsName, Context.MODE_PRIVATE)
            .edit()
            .putString("dtl_text", distanceText)
            .putString("ttl_text", ttlText)
            .putString("ocs_text", ocsText)
            .putString("target_text", targetText)
            .putString("progress_text", progressText)
            .putString("boat_status_text", boatStatusText)
            .putBoolean("is_ocs", isOcs)
            .putBoolean("race_started", raceStarted)
            .putBoolean("race_finished", raceFinished)
            .putInt("passed_marks", passedMarks)
            .apply()

        updateAutoStopAfterFinish()
    }

    private fun publishDebugError(message: String) {
        getSharedPreferences(localStatusPrefsName, Context.MODE_PRIVATE)
            .edit()
            .putString("debug_error_text", message)
            .apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.tracking_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        val stopIntent = Intent(this, RegattaTrackingService::class.java).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)

        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            2,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.stop),
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification() {
        val pending = db.countPendingSamples()

        val message = if (manualRecording) {
            getString(
                R.string.notification_manual,
                getString(R.string.next_unknown),
                getString(R.string.dtl_unknown),
                getString(R.string.clear_status),
                pending
            )
        } else {
            val dtlText = lastDtlM?.let {
                getString(R.string.dtl_meters, it)
            } ?: getString(R.string.dtl_unknown)
            val targetText = buildTargetText()
            val ocsText = if (isOcs) getString(R.string.ocs) else getString(R.string.clear_status)

            if (isInsideRaceWindow()) {
                getString(R.string.notification_race, targetText, dtlText, ocsText, pending)
            } else {
                getString(R.string.notification_waiting, targetText, dtlText, ocsText, pending)
            }
        }

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(message)
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelX = event.values[0]
                accelY = event.values[1]
                accelZ = event.values[2]
            }

            Sensor.TYPE_GYROSCOPE -> {
                gyroX = event.values[0]
                gyroY = event.values[1]
                gyroZ = event.values[2]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not relevant for this demo.
    }

    override fun onDestroy() {
        synchronized(eventPollLifecycleLock) {
            eventPollGeneration += 1
            serviceRunning = false
            if (!manualRecording) {
                RaceRuntimeStateStore.clearFor(serverUrl, eventName, sharedSecret)
            }
        }
        handler.removeCallbacks(sampleRunnable)
        handler.removeCallbacks(eventPollRunnable)

        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {
        }

        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
