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

class RegattaTrackingService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "de.williserv.regattaclient.START_TRACKING_SERVICE"
        const val ACTION_STOP = "de.williserv.regattaclient.STOP_TRACKING_SERVICE"

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

    private var isOcs = false
    private var raceStarted = false
    private var raceFinished = false
    private var passedMarks = 0

    private var lastDtlM: Double? = null
    private var lastTtlSeconds: Double? = null
    private var currentTargetDistanceM: Double? = null

    private var sequenceId = 0L
    private var lastLocation: Location? = null

    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f

    private var gyroX = 0f
    private var gyroY = 0f
    private var gyroZ = 0f

    private var autoStopAfterFinishScheduled = false

    private var retryUploadRunning = false
    private var eventPollRunning = false

    private var courseShortened = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
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

            handler.postDelayed(this, 1000L)
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

    private val uploadRunnable = object : Runnable {
        override fun run() {
            if (!serviceRunning) return

            retryPendingUploads()
            handler.postDelayed(this, 1000L)
        }
    }

    private val eventPollRunnable = object : Runnable {
        override fun run() {
            if (!serviceRunning) return

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
        when (intent?.action) {
            ACTION_START -> {
                readIntentExtras(intent)
                startForeground(NOTIFICATION_ID, buildNotification("Tracking active"))
                startTrackingService()
                updateNotification()
                return START_STICKY
            }

            ACTION_STOP -> {
                stopTrackingService()
                return START_NOT_STICKY
            }

            ACTION_SET_COURSE_PROGRESS -> {
                readIntentExtras(intent)

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

        isOcs = false
        raceStarted = false
        raceFinished = false
        passedMarks = 0

        lastDtlM = null
        lastTtlSeconds = null
        currentTargetDistanceM = null

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
            .putInt("passed_marks", passedMarks)
            .putBoolean("is_ocs", isOcs)
            .putLong("saved_at", System.currentTimeMillis())
            .apply()
    }

    private fun readIntentExtras(intent: Intent) {
        serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: serverUrl
        eventName = intent.getStringExtra(EXTRA_EVENT_NAME) ?: eventName
        sharedSecret = intent.getStringExtra(EXTRA_SHARED_SECRET) ?: sharedSecret

        boatName = intent.getStringExtra(EXTRA_BOAT_NAME) ?: boatName
        captainName = intent.getStringExtra(EXTRA_CAPTAIN_NAME) ?: captainName
        hullColor = intent.getStringExtra(EXTRA_HULL_COLOR) ?: hullColor
        sailNumber = intent.getStringExtra(EXTRA_SAIL_NUMBER) ?: sailNumber
        yardstick = intent.getStringExtra(EXTRA_YARDSTICK)?.toDoubleOrNull() ?: yardstick
        boatType = intent.getStringExtra(EXTRA_BOAT_TYPE) ?: boatType

        intent.getStringExtra(EXTRA_RESOLVED_EVENT_NAME)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::adoptResolvedEventName)

        manualRecording = intent.getBooleanExtra(EXTRA_MANUAL_RECORDING, false)
        refreshAccessContextId()
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
        if (serviceRunning) return

        serviceRunning = true

        startLocationUpdates()
        startImuUpdates()

        pollEvent()

        handler.postDelayed(sampleRunnable, 1000L)
        handler.postDelayed(uploadRunnable, 1000L)
        handler.postDelayed(eventPollRunnable, 10_000L)

        publishLocalRaceStatus()
        updateNotification()
    }

    private fun stopTrackingService() {
        serviceRunning = false
        manualRecording = false
        accessContextId = null

        handler.removeCallbacks(sampleRunnable)
        handler.removeCallbacks(uploadRunnable)
        handler.removeCallbacks(eventPollRunnable)

        handler.removeCallbacks(autoStopAfterFinishRunnable)
        autoStopAfterFinishScheduled = false


        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {
        }

        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {
        }

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
        val permissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!permissionGranted) return

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                locationListener,
                Looper.getMainLooper()
            )

            val cachedLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (cachedLocation != null) {
                lastLocation = cachedLocation
            }
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
        if (eventPollRunning) return

        eventPollRunning = true

        thread {
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
                val body = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }

                connection.disconnect()

                if (responseCode in 200..299) {
                    parseEventResponse(body)
                    publishLocalRaceStatus()
                    updateNotification()
                }
            } catch (_: Exception) {
            } finally {
                eventPollRunning = false
            }
        }
    }

    private fun buildEventUrl(): String {
        val baseUrl = getBaseServerUrl()
        val encodedEvent = URLEncoder.encode(eventName, "UTF-8")
        val encodedSecret = URLEncoder.encode(sharedSecret, "UTF-8")

        return "$baseUrl/event?event_name=$encodedEvent&shared_secret=$encodedSecret"
    }

    private fun getBaseServerUrl(): String {
        val trimmed = serverUrl.trim()

        return if (trimmed.endsWith("/ingest")) {
            trimmed.removeSuffix("/ingest")
        } else {
            trimmed.trimEnd('/')
        }
    }

    private fun parseEventResponse(body: String) {
        val obj = JSONObject(body)
        val responseResolvedEventName = obj.optString("event_name", "").trim()

        if (responseResolvedEventName.isBlank()) {
            throw IllegalArgumentException("/event response is missing event_name")
        }

        adoptResolvedEventName(responseResolvedEventName)

        raceStatus = if (obj.has("race_status") && !obj.isNull("race_status")) {
            obj.optString("race_status", raceStatus)
        } else {
            obj.optString("status", raceStatus)
        }
        courseShortened = obj.optBoolean("course_shortened", false)

        val startRaw = if (obj.has("start_time") && !obj.isNull("start_time")) {
            obj.optString("start_time", "")
        } else {
            ""
        }

        val stopRaw = if (obj.has("stop_time") && !obj.isNull("stop_time")) {
            obj.optString("stop_time", "")
        } else {
            ""
        }

        raceStartInstant = parseServerInstant(startRaw)
        raceStopInstant = parseServerInstant(stopRaw)

        val course = obj.optJSONObject("course")

        parseStartLine(course)
        parseFinishLine(course)
        parseMarks(course)
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

        val timestamp = LocalDateTime.now().format(localTimestampFormatter)
        val location = lastLocation

        val lat = location?.latitude ?: 0.0
        val lon = location?.longitude ?: 0.0
        val accuracy = location?.accuracy ?: 9999f
        val cog = location?.bearing ?: 0f
        val sog = location?.speed ?: 0f

        calculateLocalRaceState(
            lat = lat,
            lon = lon,
            accuracy = accuracy
        )

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
            publishDebugError("Storage error: event access context is incomplete")
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
            boatType =  boatType,
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
            accessContextId = sampleAccessContextId
        )

        if (insertedId == -1L) return

        if (manualRecording) {
            db.markUploaded(sequenceId)
            publishLocalRaceStatus()
            updateNotification()
            return
        }

        val sample = PendingTrackingSample(
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
            gyroZ = gyroZ
        )

        thread {
            val ok = uploadSampleBlocking(sample)

            if (ok) {
                db.markUploaded(sample.sequenceId)
            }

            updateNotification()
        }
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

            val crossedAfterStart = StartLineMath.crossedWithTolerance(
                previousSignedDistanceM = metrics.previousSignedDistanceM,
                currentSignedDistanceM = metrics.signedDistanceM,
                toleranceM = startLineToleranceM
            )

            val isOnCourseSide = isBoatOnCourseSide(
                line = line,
                boatSignedDistance = metrics.signedDistanceM
            )

            if (!isOcs && !raceStarted && (crossedAfterStart || isOnCourseSide)) {
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
        val mark = firstCourseMark ?: return

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
        val lastMark = courseMarks.lastOrNull()?.point ?: return false

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

    private fun isBoatOnCourseSide(
        line: StartLine,
        boatSignedDistance: Double
    ): Boolean {
        val mark = firstCourseMark ?: return false

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
            updateCurrentTargetDistance(currentGeoPoint)
            return
        }

        val nextMark = courseMarks.getOrNull(passedMarks)

        if (nextMark != null) {
            val distanceToMark = StartLineMath.distanceBetweenMeters(
                currentGeoPoint,
                nextMark.point
            )

            currentTargetDistanceM = distanceToMark

            if (distanceToMark <= nextMark.radiusM) {
                passedMarks += 1
                savePersistedRaceState()
            }

            return
        }

        val line = finishLine ?: return

        val metrics = StartLineMath.calculateLineMetrics(
            previousPosition = previousFinishLinePosition,
            currentPosition = currentGeoPoint,
            previousTimestampMillis = previousFinishLineTimestampMillis,
            currentTimestampMillis = nowMillis,
            startLine = line
        )

        currentTargetDistanceM = abs(metrics.signedDistanceM)

        val crossedFinish = StartLineMath.crossedWithTolerance(
            previousSignedDistanceM = metrics.previousSignedDistanceM,
            currentSignedDistanceM = metrics.signedDistanceM,
            toleranceM = startLineToleranceM
        )

        val isOnFinishSide = isBoatOnFinishSide(
            line = line,
            boatSignedDistance = metrics.signedDistanceM
        )

        if (!raceFinished && (crossedFinish || isOnFinishSide)) {
            raceFinished = true
            savePersistedRaceState()
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
            "finished" -> "Next: finished"
            "ocs_clear" -> "Next: return across start line"
            "start_line" -> "Next: start line"
            "finish_line" -> "Next: finish line"
            "mark" -> {
                val mark = courseMarks.getOrNull(passedMarks)
                if (mark != null) {
                    "Next: ${mark.order} ${mark.name}"
                } else {
                    "Next: mark"
                }
            }

            else -> "Next: --"
        }
    }

    private fun buildProgressText(): String {
        val totalMarks = courseMarks.size
        val progressPercent = when {
            raceFinished -> 100.0
            totalMarks <= 0 -> 0.0
            else -> (passedMarks.toDouble() / totalMarks.toDouble()) * 100.0
        }

        return if (totalMarks > 0) {
            String.format(
                Locale.US,
                "Progress: %d/%d marks · %.0f%%",
                passedMarks,
                totalMarks,
                progressPercent
            )
        } else {
            "Progress: --"
        }
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
        passedMarks = if (raceStartedFromUser) {
            safePassedMarks
        } else {
            0
        }

        currentTargetDistanceM = null

        savePersistedRaceState()
        publishLocalRaceStatus()
        updateNotification()

        pollEvent()
    }

    private fun buildBoatStatusText(): String {
        val status = when {
            raceFinished -> "finished"
            isOcs -> "OCS"
            raceStarted -> "racing"
            else -> "not started"
        }

        return "Boat: $status"
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
            String.format(Locale.US, "Distance: %.0f m", it)
        } ?: "Distance: --"

        val ttlText = "TTL: --"

        val ocsText = if (isOcs) {
            "OCS: yes"
        } else {
            "OCS: no"
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
            .apply()

        updateAutoStopAfterFinish()
    }

    private fun retryPendingUploads() {
        if (retryUploadRunning) return

        retryUploadRunning = true

        thread {
            try {
                val pendingSamples = db.getPendingSamples(limit = 200)

                for (sample in pendingSamples) {
                    val ok = uploadSampleBlocking(sample)

                    if (ok) {
                        db.markUploaded(sample.sequenceId)
                    }
                }

                updateNotification()
            } finally {
                retryUploadRunning = false
            }
        }
    }

    private fun uploadSampleBlocking(sample: PendingTrackingSample): Boolean {
        if (serverUrl.isBlank()) {
            publishDebugError("Upload error: server URL is empty")
            return false
        }

        return try {
            val json = JSONObject().apply {
                put("sequence_id", sample.sequenceId)
                put("timestamp", sample.timestamp)
                put("boat_name", sample.boatName)
                put("captain_name", sample.captainName)
                put("hull_color", sample.hullColor)
                put("sail_number", sample.sailNumber)
                put("yardstick", sample.yardstick)
                put("boat_type", sample.boatType)
                put("lat", sample.lat)
                put("lon", sample.lon)
                put("accuracy", sample.accuracy)
                put("cog", sample.cog)
                put("sog", sample.sog)
                put("accel_x", sample.accelX)
                put("accel_y", sample.accelY)
                put("accel_z", sample.accelZ)
                put("gyro_x", sample.gyroX)
                put("gyro_y", sample.gyroY)
                put("gyro_z", sample.gyroZ)
            }

            val connection = URL(buildIngestUrl()).openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.doOutput = true

            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("x-event-name", eventName)
            connection.setRequestProperty("x-shared-secret", sharedSecret)
            connection.setRequestProperty("x-api-version", API_VERSION)

            connection.outputStream.use { outputStream ->
                outputStream.write(json.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode

            val ok = responseCode in 200..299

            if (ok) {
                publishDebugError("")
            } else {
                val errorBody = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: ""

                publishDebugError(
                    "Upload error $responseCode: ${errorBody.take(200)}"
                )
            }

            connection.disconnect()

            ok
        } catch (e: Exception) {
            publishDebugError("Upload exception: ${e.message}")
            false
        }
    }

    private fun buildIngestUrl(): String {
        val baseUrl = getBaseServerUrl()
        return "$baseUrl/ingest"
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
            "Regatta Tracking",
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
            .setContentTitle("Regatta Tracking active")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification() {
        val pending = db.countPendingSamples()

        val dtlText = lastDtlM?.let {
            String.format(Locale.US, "DTL %.1f m", it)
        } ?: "DTL --"

        val targetText = buildTargetText()
            .replace("Next: ", "")

        val ocsText = if (isOcs) "OCS" else "clear"

        val message = when {
            manualRecording ->
                "Manual · $targetText · $dtlText · $ocsText · pending: $pending"

            isInsideRaceWindow() ->
                "Race · $targetText · $dtlText · $ocsText · pending: $pending"

            else ->
                "Waiting · $targetText · $dtlText · $ocsText · pending: $pending"
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
        handler.removeCallbacks(sampleRunnable)
        handler.removeCallbacks(uploadRunnable)
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
