package de.williserv.regattaclient

import android.Manifest
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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import de.williserv.regattaclient.ui.theme.RegattaClientTheme
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.concurrent.thread
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import de.williserv.regattaclient.ui.theme.RegattaGreen
import de.williserv.regattaclient.ui.theme.RegattaOrange
import de.williserv.regattaclient.ui.theme.RegattaRed

enum class Screen {
    HOME,
    BOAT_DATA,
    RACE,
    RACE_LEGAL,
    COURSE,
    MAP,
    QR_SCANNER,
    LEGAL,
    RESULTS
}

private enum class PendingTrackingAction {
    ENTER_RACE,
    START_MANUAL_TRACKING
}

class MainActivity : ComponentActivity(), SensorEventListener {

    private val showTrackingConsentDialog = mutableStateOf(false)
    private var pendingTrackingAction: PendingTrackingAction? = null

    private val showBoatConfirmDialog = mutableStateOf(false)

    private val raceLegalHash = mutableStateOf("")
    private val raceLegalVersion = mutableStateOf("")
    private val raceLegalAcceptStatusText = mutableStateOf("")
    private var raceLegalAcceptRunning = false

    private val showClearRaceSetupDialog = mutableStateOf(false)
    private lateinit var db: TrackingDbHelper
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager

    private val currentScreen = mutableStateOf(Screen.HOME)

    private val boatName = mutableStateOf("Boat name")
    private val skipperName = mutableStateOf("Max Mustermann")
    private val hullColor = mutableStateOf("white")
    private val sailNumber = mutableStateOf("GER 1234")
    private val yardstick = mutableStateOf("100")

    private val boatPrefsName = "boat_setup"

    private val racePrefsName = "race_setup"

    private val boatType = mutableStateOf("Shark 24")
    private val setupConfirmed = mutableStateOf(false)

    private val raceServer = mutableStateOf("")
    private val raceEvent = mutableStateOf("")
    private val raceSecret = mutableStateOf("")
    private val resolvedEventName = mutableStateOf("")
    private var raceLegalResolvedEventName = ""

    private val inRace = mutableStateOf(false)
    private val manualTracking = mutableStateOf(false)

    private val raceStatusText = mutableStateOf("Race: not loaded")
    private val raceStartText = mutableStateOf("Start: --")
    private val raceStopText = mutableStateOf("Stop: --")
    private val raceCourseText = mutableStateOf("Course: --")
    private val raceStartLineText = mutableStateOf("Start line: --")
    private val raceFinishLineText = mutableStateOf("Finish line: --")
    private val raceMarksText = mutableStateOf("Marks: --")
    private val raceInfoText = mutableStateOf("Info: --")
    private val raceShortenedText = mutableStateOf("Course shortened: no")

    private val raceStartFlags = mutableStateOf(RaceStartFlags())
    private val raceLegalText = mutableStateOf("")
    private val raceLegalAccepted = mutableStateOf(false)
    private val raceLegalStatusText = mutableStateOf("")
    private var raceLegalFetchRunning = false

    private val currentTargetText = mutableStateOf("Next: --")
    private val progressText = mutableStateOf("Progress: --")
    private val boatRaceStatusText = mutableStateOf("Boat: --")

    private val dtlText = mutableStateOf("Distance: --")
    private val ttlText = mutableStateOf("TTL: --")
    private val ocsText = mutableStateOf("OCS: --")
    private val debugErrorText = mutableStateOf("Last error: --")

    private val showFinishDetectedDialog = mutableStateOf(false)
    private val raceDataReady = mutableStateOf(false)

    private val startPanelText = mutableStateOf("CLEAR")
    private val startPanelMode = mutableStateOf("clear")

    private var raceStartEpochMillis: Long? = null
    private var currentRaceStatus = ""
    private var raceDataFetchRunning = false

    private val raceRegistered = mutableStateOf(false)
    private val statusText = mutableStateOf("Tracking stopped")
    private val rowCountText = mutableStateOf("0 rows stored")
    private val uploadStatusText = mutableStateOf("Upload: ready")
    private val serviceStatusText = mutableStateOf("Service: stopped")

    private val registerRaceStatusText = mutableStateOf("")
    private val resultsStatusText = mutableStateOf("")
    private val resultsPublished = mutableStateOf(false)
    private val resultsPublishedAt = mutableStateOf("")
    private val resultRows = mutableStateOf<List<ResultRow>>(emptyList())
    private var resultsFetchRunning = false

    private val showClearConfirmDialog = mutableStateOf(false)
    private val showOcsDecisionDialog = mutableStateOf(false)
    private val showLeaveRaceWarningDialog = mutableStateOf(false)
    private val showAdvanced = mutableStateOf(false)

    private val cogText = mutableStateOf("COG: --")
    private val sogText = mutableStateOf("SOG: --")
    private val gpsAccuracyText = mutableStateOf("GPS: --")
    private val gpsColor = mutableStateOf(RegattaRed)

    private val appStatePrefsName = "app_state"

    private val lastCsvLine = mutableStateOf("CSV preview is no longer updated live by the service.")

    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f

    private var gyroX = 0f
    private var gyroY = 0f
    private var gyroZ = 0f

    private val handler = Handler(Looper.getMainLooper())


    private val uiRefreshRunnable = object : Runnable {
        override fun run() {
            updateStorageText()
            updateLocalRaceStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun canEnterRaceNow(): Boolean {
        return raceDataReady.value &&
                setupConfirmed.value &&
                raceLegalAccepted.value
    }

    private val raceDataRefreshRunnable = object : Runnable {
        override fun run() {
            if (canEnterRaceNow() || inRace.value) {
                fetchRaceDataForDisplay()
                handler.postDelayed(this, 10_000L)
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val locationGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true

            if (locationGranted) {
                startGpsDisplayUpdates()
            } else {
                statusText.value = "GPS permission denied"
            }
        }

    private val exportCsvLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
            if (uri != null) {
                exportCsvToUri(uri)
            }
        }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            updateGpsDisplay(location)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        db = TrackingDbHelper(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        loadBoatSetup()
        loadRaceSetup()
        loadAppState()

        updateStorageText()
        updateLocalRaceStatus()

        enableEdgeToEdge()

        setContent {
            RegattaClientTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    when (currentScreen.value) {
                        Screen.HOME -> HomeScreen(
                            inRace = inRace.value,
                            manualTracking = manualTracking.value,
                            setupConfirmed = setupConfirmed.value,
                            statusText = statusText.value,
                            rowCountText = rowCountText.value,
                            uploadStatusText = uploadStatusText.value,
                            debugErrorText = debugErrorText.value,
                            serviceStatusText = serviceStatusText.value,
                            raceStatusText = raceStatusText.value,
                            raceEvent = raceEvent.value,
                            raceStartText = raceStartText.value,
                            raceStopText = raceStopText.value,
                            raceCourseText = raceCourseText.value,
                            raceStartLineText = raceStartLineText.value,
                            raceFinishLineText = raceFinishLineText.value,
                            raceMarksText = raceMarksText.value,
                            currentTargetText = currentTargetText.value,
                            progressText = progressText.value,
                            boatRaceStatusText = boatRaceStatusText.value,
                            raceDataReady = raceDataReady.value,
                            dtlText = dtlText.value,
                            ttlText = ttlText.value,
                            ocsText = ocsText.value,
                            raceInfoText = raceInfoText.value,
                            raceShortenedText = raceShortenedText.value,
                            raceStartFlags = raceStartFlags.value,
                            millisToStart = raceStartEpochMillis?.let { it - System.currentTimeMillis() },
                            startPanelText = startPanelText.value,
                            startPanelMode = startPanelMode.value,
                            lastCsvLine = lastCsvLine.value,
                            cogText = cogText.value,
                            sogText = sogText.value,
                            gpsAccuracyText = gpsAccuracyText.value,
                            gpsColor = gpsColor.value,
                            showClearConfirmDialog = showClearConfirmDialog.value,
                            showAdvanced = showAdvanced.value,
                            modifier = Modifier.padding(innerPadding),
                            onBoatData = {
                                currentScreen.value = Screen.BOAT_DATA
                            },
                            onRace = {
                                currentScreen.value = Screen.RACE
                            },
                            onCourse = {
                                if (raceDataReady.value) {
                                    fetchRaceDataForDisplay()
                                    currentScreen.value = Screen.COURSE
                                }
                            },
                            onMap = {
                                if (raceDataReady.value) {
                                    currentScreen.value = Screen.MAP
                                }
                            },
                            onOcsPanelClick = {
                                showOcsDecisionDialog.value = true
                            },
                            onResults = {
                                if (raceDataReady.value && raceStatusText.value.contains("finished", ignoreCase = true)) {
                                    currentScreen.value = Screen.RESULTS
                                    fetchEventResults()
                                }
                            },
                            onLegal = {
                                currentScreen.value = Screen.LEGAL
                            },
                            onToggleManualTracking = {
                                if (manualTracking.value) {
                                    stopManualTracking()
                                } else {
                                    requestTrackingConsent(PendingTrackingAction.START_MANUAL_TRACKING)
                                }
                            },
                            onExport = {
                                exportCsvLauncher.launch("regatta_tracking_export.csv")
                            },
                            onClearOldDataClick = {
                                showClearConfirmDialog.value = true
                            },
                            onConfirmClearOldData = {
                                showClearConfirmDialog.value = false
                                clearOldData()
                            },
                            onCancelClearOldData = {
                                showClearConfirmDialog.value = false
                            },
                            onToggleAdvanced = {
                                showAdvanced.value = !showAdvanced.value
                            }
                        )

                        Screen.RACE_LEGAL -> RaceLegalScreen(
                            raceEvent = raceEvent.value,
                            legalText = raceLegalText.value,
                            statusText = raceLegalStatusText.value,
                            modifier = Modifier.padding(innerPadding),
                            onAccept = {
                                acceptRaceLegalAndLoadRaceData()
                            },
                            onBack = {
                                currentScreen.value = Screen.RACE
                            }
                        )
                        Screen.RESULTS -> ResultsScreen(
                            raceEvent = raceEvent.value,
                            published = resultsPublished.value,
                            publishedAt = resultsPublishedAt.value,
                            statusText = resultsStatusText.value,
                            rows = resultRows.value,
                            modifier = Modifier.padding(innerPadding),
                            onRefresh = {
                                fetchEventResults()
                            },
                            onBack = {
                                currentScreen.value = Screen.HOME
                            }
                        )

                        Screen.BOAT_DATA -> BoatDataScreen(
                            boatName = boatName.value,
                            skipperName = skipperName.value,
                            hullColor = hullColor.value,
                            sailNumber = sailNumber.value,
                            yardstick = yardstick.value,
                            boatType = boatType.value,
                            setupConfirmed = setupConfirmed.value,
                            modifier = Modifier.padding(innerPadding),
                            onBoatNameChange = {
                                boatName.value = it
                                setupConfirmed.value = false
                            },
                            onSkipperNameChange = {
                                skipperName.value = it
                                setupConfirmed.value = false
                            },
                            onHullColorChange = {
                                hullColor.value = it
                                setupConfirmed.value = false
                            },
                            onSailNumberChange = {
                                sailNumber.value = it
                                setupConfirmed.value = false
                            },
                            onYardstickChange = {
                                yardstick.value = it
                                setupConfirmed.value = false
                            },
                            onBoatTypeChange = {
                                boatType.value = it
                                setupConfirmed.value = false
                            },
                            onConfirmSetup = {
                                setupConfirmed.value = isSetupValid()
                                if (setupConfirmed.value) {
                                    saveBoatSetup()
                                    currentScreen.value = Screen.HOME
                                }
                            },
                            onBack = {
                                currentScreen.value = Screen.HOME
                            }
                        )

                        Screen.RACE -> RaceScreen(
                            inRace = inRace.value,
                            canEnterRace = raceDataReady.value && setupConfirmed.value,
                            raceLegalAccepted = raceLegalAccepted.value,
                            raceServer = raceServer.value,
                            raceEvent = raceEvent.value,
                            raceSecret = raceSecret.value,
                            raceStatusText = raceStatusText.value,
                            raceStartText = raceStartText.value,
                            raceStopText = raceStopText.value,
                            raceCourseText = raceCourseText.value,
                            raceStartLineText = raceStartLineText.value,
                            raceFinishLineText = raceFinishLineText.value,
                            raceMarksText = raceMarksText.value,
                            raceRegistered = raceRegistered.value,
                            currentTargetText = currentTargetText.value,
                            progressText = progressText.value,
                            raceInfoText = raceInfoText.value,
                            canRegisterRace = setupConfirmed.value && raceDataReady.value && !inRace.value,
                            registerRaceStatusText = registerRaceStatusText.value,
                            raceShortenedText = raceShortenedText.value,
                            modifier = Modifier.padding(innerPadding),
                            onRaceServerChange = {
                                raceServer.value = it
                                clearResolvedEventContextForAccessChange()
                            },
                            onClearRaceSetupClick = {
                                showClearRaceSetupDialog.value = true
                            },
                            onShowRaceLegal = {
                                if (raceLegalText.value.isBlank()) {
                                    fetchRaceLegalText()
                                } else {
                                    currentScreen.value = Screen.RACE_LEGAL
                                }
                            },
                            onRaceEventChange = {
                                raceEvent.value = it
                                clearResolvedEventContextForAccessChange()

                                if (it.isBlank()) {
                                    raceStatusText.value = "Race: not loaded"
                                    raceStartText.value = "Start: --"
                                    raceStopText.value = "Stop: --"
                                    raceCourseText.value = "Course: --"
                                    raceStartLineText.value = "Start line: --"
                                    raceFinishLineText.value = "Finish line: --"
                                    raceMarksText.value = "Marks: --"
                                    raceInfoText.value = "Info: --"
                                    raceShortenedText.value = "Course shortened: no"
                                }
                            },
                            onRaceSecretChange = {
                                raceSecret.value = it
                                clearResolvedEventContextForAccessChange()
                            },
                            onRefreshRaceData = {
                                fetchRaceDataForDisplay()
                            },
                            onScanQr = {
                                currentScreen.value = Screen.QR_SCANNER
                            },
                            onEnterRace = {
                                when {
                                    !raceDataReady.value -> {
                                        raceStatusText.value = "Race: load valid race data first"
                                    }

                                    !setupConfirmed.value -> {
                                        statusText.value = "Confirm boat setup first"
                                    }

                                    else -> {
                                        showBoatConfirmDialog.value = true
                                    }
                                }
                            },
                            onLeaveRace = {
                                showLeaveRaceWarningDialog.value = true
                            },
                            onRegisterRace = {
                                registerForRace()
                            },
                            onBack = {
                                currentScreen.value = Screen.HOME
                            }
                        )

                        Screen.COURSE -> CourseScreen(
                            raceEvent = raceEvent.value,
                            raceStatusText = raceStatusText.value,
                            raceStartText = raceStartText.value,
                            raceStopText = raceStopText.value,
                            raceCourseText = raceCourseText.value,
                            raceStartLineText = raceStartLineText.value,
                            raceFinishLineText = raceFinishLineText.value,
                            raceMarksText = raceMarksText.value,
                            raceInfoText = raceInfoText.value,
                            raceShortenedText = raceShortenedText.value,
                            currentTargetText = currentTargetText.value,
                            onSetCourseProgress = { passedMarks, raceStarted ->
                                setCourseProgressFromUser(
                                    passedMarks = passedMarks,
                                    raceStarted = raceStarted
                                )
                            },
                            modifier = Modifier.padding(innerPadding),
                            onBack = {
                                currentScreen.value = Screen.HOME
                            }
                        )

                        Screen.MAP -> MapScreen(
                            mapImageUrl = buildCourseMapUrl(),
                            apiVersion = RegattaTrackingService.API_VERSION,
                            sharedSecret = raceSecret.value,
                            modifier = Modifier.padding(innerPadding),
                            onBack = {
                                currentScreen.value = Screen.HOME
                            }
                        )

                        Screen.QR_SCANNER -> QrScannerScreen(
                            modifier = Modifier.padding(innerPadding),
                            onQrScanned = { raw ->
                                handleRaceQrCode(raw)
                            },
                            onBack = {
                                currentScreen.value = Screen.RACE
                            }
                        )

                        Screen.LEGAL -> LegalScreen(
                            modifier = Modifier.padding(innerPadding),
                            onBack = {
                                currentScreen.value = Screen.HOME
                            }
                        )
                    }
                }
                if (showTrackingConsentDialog.value) {
                    TrackingConsentDialog(
                        onAccept = {
                            confirmTrackingConsent()
                        },
                        onCancel = {
                            cancelTrackingConsent()
                        }
                    )
                }

                if (showOcsDecisionDialog.value) {
                    OcsDecisionDialog(
                        onOk = {
                            showOcsDecisionDialog.value = false
                        },
                        onContinueCourse = {
                            showOcsDecisionDialog.value = false
                            setCourseProgressFromUser(
                                passedMarks = 0,
                                raceStarted = true
                            )
                        }
                    )
                }

                if (showLeaveRaceWarningDialog.value) {
                    LeaveRaceWarningDialog(
                        onConfirm = {
                            showLeaveRaceWarningDialog.value = false
                            leaveRace()
                        },
                        onCancel = {
                            showLeaveRaceWarningDialog.value = false
                        }
                    )
                }
                if (showBoatConfirmDialog.value) {
                    BoatConfirmDialog(
                        boatName = boatName.value,
                        skipperName = skipperName.value,
                        sailNumber = sailNumber.value,
                        boatType = boatType.value,
                        hullColor = hullColor.value,
                        yardstick = yardstick.value,
                        onConfirm = {
                            showBoatConfirmDialog.value = false

                            registerForRace(
                                onSuccess = {
                                    requestTrackingConsent(PendingTrackingAction.ENTER_RACE)
                                }
                            )
                        },
                        onCancel = {
                            showBoatConfirmDialog.value = false
                        }
                    )
                }
                if (showFinishDetectedDialog.value) {
                    FinishDetectedDialog(
                        onStopTracking = {
                            showFinishDetectedDialog.value = false
                            leaveRace()
                        },
                        onContinue = {
                            showFinishDetectedDialog.value = false
                        }
                    )
                }

                if (showClearRaceSetupDialog.value) {
                    ClearRaceSetupDialog(
                        onConfirm = {
                            showClearRaceSetupDialog.value = false
                            clearRaceSetup()
                        },
                        onCancel = {
                            showClearRaceSetupDialog.value = false
                        }
                    )
                }

            }
        }

        handleIncomingShareIntent(intent)
        requestPermissionsForApp()
        startImuUpdates()
        handler.postDelayed(uiRefreshRunnable, 1000L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(sharedIntent: Intent) {
        if (sharedIntent.action != Intent.ACTION_SEND || sharedIntent.type != "text/plain") {
            return
        }

        val sharedText = sharedIntent.getStringExtra(Intent.EXTRA_TEXT)

        // Consume the share so an Activity recreation cannot import it a second time.
        sharedIntent.action = null
        sharedIntent.removeExtra(Intent.EXTRA_TEXT)

        val access = sharedText?.let(::parseEventAccessUrl)
        if (access == null) {
            android.widget.Toast.makeText(
                this,
                "Shared text is not a valid RegattaTracker event link.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        val eventAlreadyLoaded = shouldBlockSharedEventImport(
            server = raceServer.value,
            event = raceEvent.value,
            secret = raceSecret.value,
            resolvedEventName = resolvedEventName.value,
            raceDataReady = raceDataReady.value,
            raceRegistered = raceRegistered.value,
            inRace = inRace.value
        )

        if (eventAlreadyLoaded) {
            android.widget.Toast.makeText(
                this,
                "An event is already loaded. Clear the current event before importing another link.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        handleRaceQrCode(
            buildEventQrPayload(
                server = access.server,
                event = access.event,
                secret = access.secret
            )
        )
    }

    private fun loadRaceSetup() {
        val prefs = getSharedPreferences(racePrefsName, Context.MODE_PRIVATE)

        raceServer.value = prefs.getString("race_server", raceServer.value) ?: raceServer.value
        raceEvent.value = prefs.getString("race_event", raceEvent.value) ?: raceEvent.value
        raceSecret.value = prefs.getString("race_secret", raceSecret.value) ?: raceSecret.value
        resolvedEventName.value = prefs.getString("resolved_event_name", "") ?: ""

        raceStatusText.value = prefs.getString("race_status_text", raceStatusText.value) ?: raceStatusText.value
        raceStartText.value = prefs.getString("race_start_text", raceStartText.value) ?: raceStartText.value
        raceStopText.value = prefs.getString("race_stop_text", raceStopText.value) ?: raceStopText.value
        raceCourseText.value = prefs.getString("race_course_text", raceCourseText.value) ?: raceCourseText.value
        raceStartLineText.value = prefs.getString("race_start_line_text", raceStartLineText.value) ?: raceStartLineText.value
        raceFinishLineText.value = prefs.getString("race_finish_line_text", raceFinishLineText.value) ?: raceFinishLineText.value
        raceMarksText.value = prefs.getString("race_marks_text", raceMarksText.value) ?: raceMarksText.value
        raceInfoText.value = prefs.getString("race_info_text", raceInfoText.value) ?: raceInfoText.value
        raceShortenedText.value = prefs.getString("race_shortened_text", raceShortenedText.value) ?: raceShortenedText.value

        raceDataReady.value = prefs.getBoolean("race_data_ready", false)
        if (resolvedEventName.value.isBlank()) {
            raceDataReady.value = false
        }

        currentRaceStatus = raceStatusText.value
            .replace("Race:", "")
            .trim()

        raceStartEpochMillis = parseServerTimeToMillis(
            raceStartText.value.replace("Start:", "").trim()
        )
    }

    private fun resetRaceLegalState() {
        raceLegalAccepted.value = false
        raceLegalText.value = ""
        raceLegalHash.value = ""
        raceLegalVersion.value = ""
        raceLegalStatusText.value = ""
        raceLegalAcceptStatusText.value = ""
        raceLegalResolvedEventName = ""
    }

    private fun resetRunSpecificClientState(clearLegal: Boolean) {
        raceDataReady.value = false
        raceStatusText.value = "Race: not loaded"
        raceStartText.value = "Start: --"
        raceStopText.value = "Stop: --"
        raceCourseText.value = "Course: --"
        raceStartLineText.value = "Start line: --"
        raceFinishLineText.value = "Finish line: --"
        raceMarksText.value = "Marks: --"
        raceInfoText.value = "Info: --"
        raceShortenedText.value = "Course shortened: no"
        raceStartFlags.value = RaceStartFlags()

        currentTargetText.value = "Next: --"
        progressText.value = "Progress: --"
        boatRaceStatusText.value = "Boat: --"
        dtlText.value = "Distance: --"
        ttlText.value = "TTL: --"
        ocsText.value = "OCS: --"

        currentRaceStatus = ""
        raceStartEpochMillis = null
        raceRegistered.value = false
        registerRaceStatusText.value = ""
        resultsStatusText.value = ""
        resultsPublished.value = false
        resultsPublishedAt.value = ""
        resultRows.value = emptyList()
        showFinishDetectedDialog.value = false

        getSharedPreferences("regatta_local_status", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        if (clearLegal) {
            resetRaceLegalState()
        }
    }

    private fun clearResolvedEventContextForAccessChange() {
        resolvedEventName.value = ""
        resetRunSpecificClientState(clearLegal = true)

        getSharedPreferences(racePrefsName, Context.MODE_PRIVATE)
            .edit()
            .remove("resolved_event_name")
            .putBoolean("race_data_ready", false)
            .apply()
    }

    private fun adoptResolvedEventName(nextResolvedEventName: String) {
        val normalized = nextResolvedEventName.trim()
        if (normalized.isBlank() || normalized == resolvedEventName.value) return

        val previousResolvedEventName = resolvedEventName.value
        val shouldReset = previousResolvedEventName.isNotBlank() || raceEvent.value != normalized
        val clearLegal = RaceLegalContextPolicy.shouldClearForResolvedRunChange(
            accessIdentifier = raceEvent.value,
            legalEventIdentity = raceLegalResolvedEventName,
            nextResolvedEventName = normalized
        )

        if (shouldReset) {
            resetRunSpecificClientState(clearLegal = clearLegal)
        }

        resolvedEventName.value = normalized
    }

    private fun clearRaceSetup() {
        if (inRace.value) {
            raceStatusText.value = "Race: cannot clear setup while in race"
            return
        }
        stopRaceDataRefresh()
        raceServer.value = ""
        raceEvent.value = ""
        raceSecret.value = ""
        resolvedEventName.value = ""
        raceLegalResolvedEventName = ""
        raceRegistered.value = false
        registerRaceStatusText.value = ""

        raceStatusText.value = "Race: not loaded"
        raceStartText.value = "Start: --"
        raceStopText.value = "Stop: --"
        raceCourseText.value = "Course: --"
        raceStartLineText.value = "Start line: --"
        raceFinishLineText.value = "Finish line: --"
        raceMarksText.value = "Marks: --"
        raceInfoText.value = "Info: --"
        raceShortenedText.value = "Course shortened: no"
        raceStartFlags.value = RaceStartFlags()

        currentTargetText.value = "Next: --"
        progressText.value = "Progress: --"
        boatRaceStatusText.value = "Boat: --"
        dtlText.value = "Distance: --"
        ttlText.value = "TTL: --"
        ocsText.value = "OCS: --"

        currentRaceStatus = ""
        raceStartEpochMillis = null
        clearSavedRaceDataReady()

        getSharedPreferences(racePrefsName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        getSharedPreferences("regatta_race_state", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        getSharedPreferences("regatta_local_status", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        updateStartPanelStatus()
    }

    private fun saveRaceSetup() {
        getSharedPreferences(racePrefsName, Context.MODE_PRIVATE)
            .edit()
            .putString("race_server", raceServer.value)
            .putString("race_event", raceEvent.value)
            .putString("race_secret", raceSecret.value)
            .putString("resolved_event_name", resolvedEventName.value)
            .putString("race_status_text", raceStatusText.value)
            .putString("race_start_text", raceStartText.value)
            .putString("race_stop_text", raceStopText.value)
            .putString("race_course_text", raceCourseText.value)
            .putString("race_start_line_text", raceStartLineText.value)
            .putString("race_finish_line_text", raceFinishLineText.value)
            .putString("race_marks_text", raceMarksText.value)
            .putString("race_info_text", raceInfoText.value)
            .putString("race_shortened_text", raceShortenedText.value)
            .putBoolean("race_data_ready", raceDataReady.value)
            .apply()
    }

    private fun clearSavedRaceDataReady() {
        raceDataReady.value = false

        getSharedPreferences(racePrefsName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("race_data_ready", false)
            .apply()
    }
    private fun loadBoatSetup() {
        val prefs = getSharedPreferences(boatPrefsName, Context.MODE_PRIVATE)

        boatName.value = prefs.getString("boat_name", boatName.value) ?: boatName.value
        skipperName.value = prefs.getString("skipper_name", skipperName.value) ?: skipperName.value
        hullColor.value = prefs.getString("hull_color", hullColor.value) ?: hullColor.value
        sailNumber.value = prefs.getString("sail_number", sailNumber.value) ?: sailNumber.value
        yardstick.value = prefs.getString("yardstick", yardstick.value) ?: yardstick.value
        boatType.value = prefs.getString("boat_type", boatType.value) ?: boatType.value
        setupConfirmed.value = prefs.getBoolean("setup_confirmed", false)
    }

    private fun saveBoatSetup() {
        getSharedPreferences(boatPrefsName, Context.MODE_PRIVATE)
            .edit()
            .putString("boat_name", boatName.value)
            .putString("skipper_name", skipperName.value)
            .putString("hull_color", hullColor.value)
            .putString("sail_number", sailNumber.value)
            .putString("yardstick", yardstick.value)
            .putString("boat_type", boatType.value)
            .putBoolean("setup_confirmed", setupConfirmed.value)
            .apply()
    }

    private fun requestTrackingConsent(action: PendingTrackingAction) {
        if (hasTrackingConsent()) {
            executeTrackingAction(action)
            return
        }

        pendingTrackingAction = action
        showTrackingConsentDialog.value = true
    }

    private fun hasTrackingConsent(): Boolean {
        return getSharedPreferences("regatta_consent", Context.MODE_PRIVATE)
            .getBoolean("tracking_consent_given", false)
    }

    private fun confirmTrackingConsent() {
        getSharedPreferences("regatta_consent", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("tracking_consent_given", true)
            .apply()

        val action = pendingTrackingAction

        pendingTrackingAction = null
        showTrackingConsentDialog.value = false

        if (action != null) {
            executeTrackingAction(action)
        }
    }

    private fun cancelTrackingConsent() {
        pendingTrackingAction = null
        showTrackingConsentDialog.value = false
        statusText.value = "GPS tracking consent not granted"
    }

    private fun executeTrackingAction(action: PendingTrackingAction) {
        when (action) {
            PendingTrackingAction.ENTER_RACE -> {
                enterRace()
            }

            PendingTrackingAction.START_MANUAL_TRACKING -> {
                startManualTracking()
            }
        }
    }

    private fun isSetupValid(): Boolean {
        return boatName.value.isNotBlank() &&
                skipperName.value.isNotBlank() &&
                sailNumber.value.isNotBlank() &&
                boatType.value.isNotBlank() &&
                yardstick.value.toDoubleOrNull() != null
    }

    private fun loadAppState() {
        val prefs = getSharedPreferences(appStatePrefsName, Context.MODE_PRIVATE)

        inRace.value = prefs.getBoolean("in_race", false)
        manualTracking.value = prefs.getBoolean("manual_tracking", false)

        serviceStatusText.value = when {
            inRace.value -> "Service: race running"
            manualTracking.value -> "Service: manual tracking running"
            else -> "Service: stopped"
        }

        statusText.value = when {
            inRace.value -> "In Race"
            manualTracking.value -> "Manual tracking running"
            else -> "Tracking stopped"
        }
    }

    private fun saveAppState() {
        getSharedPreferences(appStatePrefsName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("in_race", inRace.value)
            .putBoolean("manual_tracking", manualTracking.value)
            .apply()
    }

    private fun handleRaceQrCode(raw: String) {
        try {
            val json = JSONObject(raw)

            val server = json.optString("server", "").trim()
            val event = json.optString("event", "").trim()
            val secret = json.optString("secret", "").trim()

            if (server.isBlank() || event.isBlank() || secret.isBlank()) {
                raceStatusText.value = "QR code incomplete"
                currentScreen.value = Screen.RACE
                return
            }

            raceServer.value = server
            raceEvent.value = event
            raceSecret.value = secret
            clearResolvedEventContextForAccessChange()

            raceRegistered.value = false
            registerRaceStatusText.value = ""

            raceStatusText.value = "QR code loaded"

            raceLegalAccepted.value = false
            raceLegalText.value = ""
            raceLegalHash.value = ""
            raceLegalVersion.value = ""
            raceLegalStatusText.value = "Loading race notice..."
            raceLegalAcceptStatusText.value = ""

            clearSavedRaceDataReady()

            currentScreen.value = Screen.RACE

            fetchRaceLegalText()

        } catch (_: JSONException) {
            raceStatusText.value = "QR code is not valid race JSON"
            currentScreen.value = Screen.RACE
        } catch (e: Exception) {
            raceStatusText.value = "QR error: ${e.message}"
            currentScreen.value = Screen.RACE
        }
    }

    private fun fetchRaceLegalText() {
        if (raceLegalFetchRunning) return
        raceLegalFetchRunning = true

        val previouslyAccepted = raceLegalAccepted.value
        val previousLegalEventIdentity = raceLegalResolvedEventName
        val previousLegalHash = raceLegalHash.value

        raceLegalStatusText.value = "Loading race legal text..."

        thread {
            try {
                val url = buildNormalApiGetUrl(
                    baseUrl = getBaseServerUrl(),
                    path = "/event/legal",
                    eventName = raceEvent.value
                )

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("x-shared-secret", raceSecret.value)
                connection.setRequestProperty("x-api-version", RegattaTrackingService.API_VERSION)

                val responseCode = connection.responseCode
                val body = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }

                connection.disconnect()

                if (responseCode !in 200..299) {
                    runOnUiThread {
                        raceLegalStatusText.value =
                            "Legal text failed $responseCode: ${body.take(160)}"
                        raceLegalAccepted.value = false
                        currentScreen.value = Screen.RACE
                    }
                    return@thread
                }

                val json = JSONObject(body)
                val legalResolvedEventName = json.optString("event_name", "").trim()
                val legalText = json.optString("invitation_legal_text", "").trim()

                val legalHash = json.optString("legal_text_hash", "").trim()
                val legalVersion = if (json.has("legal_text_version") && !json.isNull("legal_text_version")) {
                    json.optString("legal_text_version", "").trim()
                } else {
                    ""
                }

                runOnUiThread {
                    when {
                        legalResolvedEventName.isBlank() -> {
                            raceLegalStatusText.value = "Race notice event identity is missing"
                            raceLegalAccepted.value = false
                            raceLegalResolvedEventName = ""
                            currentScreen.value = Screen.RACE
                        }

                        legalText.isBlank() -> {
                            raceLegalStatusText.value = "Race notice is empty"
                            raceLegalAccepted.value = false
                            currentScreen.value = Screen.RACE
                        }

                        legalHash.isBlank() -> {
                            raceLegalStatusText.value = "Race notice hash is missing"
                            raceLegalAccepted.value = false
                            currentScreen.value = Screen.RACE
                        }

                        else -> {
                            val preserveAcceptance =
                                RaceLegalContextPolicy.canPreserveAcceptanceAfterReload(
                                    currentlyAccepted = previouslyAccepted,
                                    currentLegalEventIdentity = previousLegalEventIdentity,
                                    currentLegalHash = previousLegalHash,
                                    nextLegalEventIdentity = legalResolvedEventName,
                                    nextLegalHash = legalHash
                                )

                            raceLegalResolvedEventName = legalResolvedEventName
                            raceLegalText.value = legalText
                            raceLegalHash.value = legalHash
                            raceLegalVersion.value = legalVersion
                            raceLegalStatusText.value = ""
                            raceLegalAcceptStatusText.value = if (preserveAcceptance) {
                                "Race notice accepted"
                            } else {
                                ""
                            }
                            raceLegalAccepted.value = preserveAcceptance
                            currentScreen.value = Screen.RACE_LEGAL
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    raceLegalStatusText.value = "Legal text failed: ${e.message}"
                    raceLegalAccepted.value = false
                    currentScreen.value = Screen.RACE
                }
            } finally {
                raceLegalFetchRunning = false
            }
        }
    }

    private fun acceptRaceLegalAndLoadRaceData() {
        if (raceLegalAcceptRunning) return
        raceLegalAcceptRunning = true


        raceLegalAcceptStatusText.value = "Accepting race notice..."

        thread {
            try {
                val baseUrl = getBaseServerUrl()
                val url = "$baseUrl/event/legal/accept"

                val json = JSONObject().apply {
                    put("event_name", raceEvent.value)
                    put("sail_number", sailNumber.value)
                    put("boat_name", boatName.value)
                    put("captain_name", skipperName.value)
                    put("legal_text_hash", raceLegalHash.value)
                }

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.doOutput = true

                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("X-Shared-Secret", raceSecret.value)
                connection.setRequestProperty("x-api-version", RegattaTrackingService.API_VERSION)

                connection.outputStream.use { outputStream ->
                    outputStream.write(json.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                val body = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }

                connection.disconnect()

                runOnUiThread {
                    if (responseCode in 200..299) {
                        val acceptedResolvedEventName = try {
                            JSONObject(body).optString("event_name", "").trim()
                        } catch (_: Exception) {
                            ""
                        }

                        if (
                            acceptedResolvedEventName.isBlank() ||
                            raceLegalResolvedEventName.isBlank() ||
                            acceptedResolvedEventName != raceLegalResolvedEventName
                        ) {
                            resetRaceLegalState()
                            raceLegalAcceptStatusText.value =
                                "Race notice changed while accepting; reload required"
                            currentScreen.value = Screen.RACE
                            fetchRaceLegalText()
                        } else {
                            raceLegalAccepted.value = true
                            raceLegalAcceptStatusText.value = "Race notice accepted"
                            currentScreen.value = Screen.RACE
                            fetchRaceDataForDisplay()
                            startRaceDataRefresh()
                        }
                    } else {
                        raceLegalAccepted.value = false
                        raceLegalAcceptStatusText.value =
                            "Accept failed $responseCode: ${body.take(160)}"
                    }
                }
                } catch (e: Exception) {
                runOnUiThread {
                    raceLegalAccepted.value = false
                    raceLegalAcceptStatusText.value = "Accept failed: ${e.message}"
                }
            } finally {
                raceLegalAcceptRunning = false
            }
        }
    }

    private fun updateLocalRaceStatus() {
        val prefs = getSharedPreferences("regatta_local_status", Context.MODE_PRIVATE)

        dtlText.value = prefs.getString("dtl_text", "Distance: --") ?: "Distance: --"
        ttlText.value = prefs.getString("ttl_text", "TTL: --") ?: "TTL: --"
        ocsText.value = prefs.getString("ocs_text", "OCS: --") ?: "OCS: --"

        currentTargetText.value = prefs.getString(
            "target_text",
            fallbackTargetText()
        ) ?: fallbackTargetText()


        progressText.value = prefs.getString(
            "progress_text",
            "Progress: --"
        ) ?: "Progress: --"

        boatRaceStatusText.value = prefs.getString(
            "boat_status_text",
            "Boat: --"
        ) ?: "Boat: --"

        val debugError = prefs.getString("debug_error_text", "") ?: ""
        debugErrorText.value = if (debugError.isBlank()) {
            "Last error: --"
        } else {
            "Last error: $debugError"
        }

        updateStartPanelStatus()
        updateAutoLeaveAfterFinish()
    }


    private fun setCourseProgressFromUser(
        passedMarks: Int,
        raceStarted: Boolean
    ) {
        val intent = Intent(this, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_SET_COURSE_PROGRESS

            putExtra(RegattaTrackingService.EXTRA_SERVER_URL, raceServer.value)
            putExtra(RegattaTrackingService.EXTRA_EVENT_NAME, raceEvent.value)
            putExtra(RegattaTrackingService.EXTRA_SHARED_SECRET, raceSecret.value)
            putExtra(RegattaTrackingService.EXTRA_RESOLVED_EVENT_NAME, resolvedEventName.value)

            putExtra(RegattaTrackingService.EXTRA_BOAT_NAME, boatName.value)
            putExtra(RegattaTrackingService.EXTRA_CAPTAIN_NAME, skipperName.value)
            putExtra(RegattaTrackingService.EXTRA_HULL_COLOR, hullColor.value)
            putExtra(RegattaTrackingService.EXTRA_SAIL_NUMBER, sailNumber.value)
            putExtra(RegattaTrackingService.EXTRA_YARDSTICK, yardstick.value)
            putExtra(RegattaTrackingService.EXTRA_BOAT_TYPE, boatType.value)

            putExtra(RegattaTrackingService.EXTRA_PASSED_MARKS, passedMarks)
            putExtra(RegattaTrackingService.EXTRA_RACE_STARTED, raceStarted)
        }

        startService(intent)
    }

    private fun fallbackTargetText(): String {
        return when (startPanelMode.value) {
            "ocs" -> "Next: return across start line"
            "countdown" -> "Next: start line"
            "clear" -> "Next: start line"
            "postponed" -> "Next: wait"
            "started" -> "Next: course"
            else -> "Next: --"
        }
    }

    private fun registerForRace(
        onSuccess: (() -> Unit)? = null
    ) {
        if (!setupConfirmed.value) {
            registerRaceStatusText.value = "Confirm boat setup first."
            return
        }

        if (!raceDataReady.value) {
            registerRaceStatusText.value = "Load valid race data first."
            return
        }

        val registrationTimestamp =
            RaceRegistrationPolicy.registrationTimestamp(raceStartEpochMillis)
        if (registrationTimestamp == null) {
            registerRaceStatusText.value = "Load a race with a valid start time first."
            return
        }

        registerRaceStatusText.value = "Registering..."

        thread {
            try {
                val url = "${getBaseServerUrl()}/ingest"

                val json = JSONObject().apply {
                    put("sequence_id", System.currentTimeMillis())
                    put("timestamp", registrationTimestamp)

                    put("boat_name", boatName.value)
                    put("captain_name", skipperName.value)
                    put("hull_color", hullColor.value)
                    put("sail_number", sailNumber.value)
                    put("yardstick", yardstick.value.toDoubleOrNull() ?: 0.0)
                    put("boat_type", boatType.value)

                    put("lat", 0.0)
                    put("lon", 0.0)
                    put("accuracy", 9999.0)
                    put("cog", 0.0)
                    put("sog", 0.0)

                    put("accel_x", 0.0)
                    put("accel_y", 0.0)
                    put("accel_z", 0.0)
                    put("gyro_x", 0.0)
                    put("gyro_y", 0.0)
                    put("gyro_z", 0.0)
                }

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.doOutput = true

                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("x-event-name", raceEvent.value)
                connection.setRequestProperty("x-shared-secret", raceSecret.value)
                connection.setRequestProperty("x-api-version", RegattaTrackingService.API_VERSION)

                connection.outputStream.use { outputStream ->
                    outputStream.write(json.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                val body = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }

                connection.disconnect()

                runOnUiThread {
                    if (responseCode in 200..299) {
                        raceRegistered.value = true
                        registerRaceStatusText.value = "Registered for race."
                        onSuccess?.invoke()
                    } else {
                        registerRaceStatusText.value =
                            "Registration failed $responseCode: ${body.take(120)}"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    registerRaceStatusText.value = "Registration failed: ${e.message}"
                }
            }
        }
    }

    private fun updateAutoLeaveAfterFinish() {
        val isFinished = boatRaceStatusText.value.contains("finished", ignoreCase = true)

        if (inRace.value && isFinished) {
            if (!showFinishDetectedDialog.value) {
                showFinishDetectedDialog.value = true
            }
        } else {
            showFinishDetectedDialog.value = false
        }
    }

    private fun updateStartPanelStatus() {
        val isOcs = ocsText.value.contains("yes", ignoreCase = true) ||
                ocsText.value.contains("ja", ignoreCase = true)

        if (isOcs) {
            val startMillis = raceStartEpochMillis
            val remainingSeconds = if (startMillis != null) {
                (startMillis - System.currentTimeMillis()) / 1000L
            } else {
                null
            }

            startPanelText.value = if (remainingSeconds != null && remainingSeconds > 0L) {
                val minutes = remainingSeconds / 60L
                val seconds = remainingSeconds % 60L

                String.format(
                    Locale.US,
                    "START IN %d:%02d",
                    minutes,
                    seconds
                )
            } else {
                "OCS"
            }

            startPanelMode.value = "ocs"
            return
        }

        if (currentRaceStatus.equals("finished", ignoreCase = true)) {
            startPanelText.value = "FINISHED"
            startPanelMode.value = "finished"
            return
        }

        if (currentRaceStatus.equals("postponed", ignoreCase = true)) {
            startPanelText.value = "POSTPONED"
            startPanelMode.value = "postponed"
            return
        }

        val startMillis = raceStartEpochMillis

        if (startMillis == null) {
            startPanelText.value = "Regatta Tracker"
            startPanelMode.value = "clear"
            return
        }

        val remainingSeconds = (startMillis - System.currentTimeMillis()) / 1000L

        when {
            remainingSeconds > 600L -> {
                startPanelText.value = "Regatta Tracker"
                startPanelMode.value = "clear"
            }

            remainingSeconds > 0L -> {
                val minutes = remainingSeconds / 60L
                val seconds = remainingSeconds % 60L

                startPanelText.value = String.format(
                    Locale.US,
                    "START IN %d:%02d",
                    minutes,
                    seconds
                )
                startPanelMode.value = "countdown"
            }

            else -> {
                val elapsedSeconds = -remainingSeconds
                val hours = elapsedSeconds / 3600L
                val minutes = (elapsedSeconds % 3600L) / 60L
                val seconds = elapsedSeconds % 60L

                startPanelText.value = if (hours > 0L) {
                    String.format(
                        Locale.US,
                        "%d:%02d:%02d",
                        hours,
                        minutes,
                        seconds
                    )
                } else {
                    String.format(
                        Locale.US,
                        "%d:%02d",
                        minutes,
                        seconds
                    )
                }

                startPanelMode.value = "started"
            }
        }
    }

    private fun parseServerTimeToMillis(value: String?): Long? {
        if (value.isNullOrBlank() || value == "--") return null

        val raceZone = ZoneId.systemDefault()

        return try {
            when {
                value.endsWith("Z") -> {
                    Instant.parse(value).toEpochMilli()
                }

                value.contains("+") || value.drop(10).contains("-") -> {
                    OffsetDateTime.parse(value).toInstant().toEpochMilli()
                }

                value.count { it == ':' } == 1 -> {
                    LocalDateTime
                        .parse("${value}:00")
                        .atZone(raceZone)
                        .toInstant()
                        .toEpochMilli()
                }

                value.count { it == ':' } == 2 -> {
                    LocalDateTime
                        .parse(value)
                        .atZone(raceZone)
                        .toInstant()
                        .toEpochMilli()
                }

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun startRaceDataRefresh() {
        handler.removeCallbacks(raceDataRefreshRunnable)
        handler.postDelayed(raceDataRefreshRunnable, 10_000L)
    }

    private fun stopRaceDataRefresh() {
        handler.removeCallbacks(raceDataRefreshRunnable)
    }

    private fun requestPermissionsForApp() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needsRequest = permissions.any { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (needsRequest) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            startGpsDisplayUpdates()
        }
    }

    private fun enterRace() {
        inRace.value = true
        statusText.value = "In Race"
        serviceStatusText.value = "Service: starting"
        saveAppState()

        fetchRaceDataForDisplay()
        startRaceDataRefresh()
        startRegattaForegroundService(manualMode = false)

        currentScreen.value = Screen.HOME
    }

    private fun leaveRace() {


        inRace.value = false
        manualTracking.value = false
        saveAppState()

        statusText.value = "Race left"
        serviceStatusText.value = "Service: stopped"

        stopRegattaForegroundService()

        currentScreen.value = Screen.HOME
    }

    private fun startManualTracking() {
        manualTracking.value = true
        statusText.value = "Manual tracking running"
        serviceStatusText.value = "Service: manual tracking running"
        saveAppState()
        startRegattaForegroundService(manualMode = true)
    }

    private fun stopManualTracking() {
        manualTracking.value = false
        statusText.value = "Manual tracking stopped"

        if (inRace.value) {
            serviceStatusText.value = "Service: race continues"
            startRegattaForegroundService(manualMode = false)
        } else {
            serviceStatusText.value = "Service: stopped"
            stopRegattaForegroundService()
        }
    }

    private fun startRegattaForegroundService(manualMode: Boolean) {
        val intent = Intent(this, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_START

            putExtra(RegattaTrackingService.EXTRA_SERVER_URL, raceServer.value)
            putExtra(RegattaTrackingService.EXTRA_EVENT_NAME, raceEvent.value)
            putExtra(RegattaTrackingService.EXTRA_SHARED_SECRET, raceSecret.value)
            putExtra(RegattaTrackingService.EXTRA_RESOLVED_EVENT_NAME, resolvedEventName.value)

            putExtra(RegattaTrackingService.EXTRA_BOAT_NAME, boatName.value)
            putExtra(RegattaTrackingService.EXTRA_CAPTAIN_NAME, skipperName.value)
            putExtra(RegattaTrackingService.EXTRA_HULL_COLOR, hullColor.value)
            putExtra(RegattaTrackingService.EXTRA_SAIL_NUMBER, sailNumber.value)
            putExtra(RegattaTrackingService.EXTRA_YARDSTICK, yardstick.value)
            putExtra(RegattaTrackingService.EXTRA_BOAT_TYPE, boatType.value)

            putExtra(RegattaTrackingService.EXTRA_MANUAL_RECORDING, manualMode)
        }

        ContextCompat.startForegroundService(this, intent)

        serviceStatusText.value = if (manualMode) {
            "Service: manual tracking running"
        } else {
            "Service: race running"
        }
    }

    private fun stopRegattaForegroundService() {
        val intent = Intent(this, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_STOP
        }

        startService(intent)
        serviceStatusText.value = "Service: stopped"
    }

    private fun fetchEventResults() {
        if (resultsFetchRunning) return
        resultsFetchRunning = true

        resultsStatusText.value = "Loading results..."

        thread {
            try {
                val url = buildNormalApiGetUrl(
                    baseUrl = getBaseServerUrl(),
                    path = "/event-results",
                    eventName = raceEvent.value
                )

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("x-shared-secret", raceSecret.value)
                connection.setRequestProperty("x-api-version", RegattaTrackingService.API_VERSION)

                val responseCode = connection.responseCode
                val body = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }

                connection.disconnect()

                if (responseCode !in 200..299) {
                    runOnUiThread {
                        resultsStatusText.value = "Results failed $responseCode: ${body.take(160)}"
                        resultsPublished.value = false
                        resultRows.value = emptyList()
                    }
                    return@thread
                }

                val json = JSONObject(body)
                val published = json.optBoolean("published", false)
                val publishedAt = json.optString("published_at", "")
                val rowsJson = json.optJSONArray("rows")

                val rows = mutableListOf<ResultRow>()

                if (rowsJson != null) {
                    for (i in 0 until rowsJson.length()) {
                        val row = rowsJson.optJSONObject(i) ?: continue

                        rows.add(
                            ResultRow(
                                rank = if (row.isNull("rank")) null else row.optInt("rank"),
                                boatName = row.optString("boat_name", ""),
                                sailNumber = row.optString("sail_number", ""),
                                status = row.optString("status", ""),
                                officialFinishTime = if (row.isNull("official_finish_time")) {
                                    null
                                } else {
                                    row.optString("official_finish_time", "")
                                },
                                correctedTime = row.optString("corrected_time", "")
                            )
                        )
                    }
                }

                runOnUiThread {
                    resultsPublished.value = published
                    resultsPublishedAt.value = publishedAt
                    resultRows.value = rows
                    resultsStatusText.value = if (published) {
                        "Results loaded"
                    } else {
                        "Results not published yet"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    resultsStatusText.value = "Results failed: ${e.message}"
                    resultsPublished.value = false
                    resultRows.value = emptyList()
                }
            } finally {
                resultsFetchRunning = false
            }
        }
    }

    private fun fetchRaceDataForDisplay() {
        if (!raceLegalAccepted.value) {
            raceStatusText.value = "Race: accept race legal first"

            if (raceLegalText.value.isNotBlank()) {
                currentScreen.value = Screen.RACE_LEGAL
            } else {
                fetchRaceLegalText()
            }

            return
        }

        if (raceDataFetchRunning) return
        raceDataFetchRunning = true

        thread {
            try {
                val url = buildNormalApiGetUrl(
                    baseUrl = getBaseServerUrl(),
                    path = "/event",
                    eventName = raceEvent.value
                )

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.setRequestProperty("accept", "application/json")
                connection.setRequestProperty("x-event-name", raceEvent.value)
                connection.setRequestProperty("x-shared-secret", raceSecret.value)
                connection.setRequestProperty(
                    "x-api-version",
                    RegattaTrackingService.API_VERSION
                )

                val responseCode = connection.responseCode
                val body = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }

                connection.disconnect()

                if (responseCode !in 200..299) {
                    runOnUiThread {
                        raceStatusText.value = "Race: error $responseCode"
                        raceDataReady.value = false
                    }
                    return@thread
                }

                val json = JSONObject(body)
                val responseResolvedEventName = json.optString("event_name", "").trim()

                if (responseResolvedEventName.isBlank()) {
                    runOnUiThread {
                        raceStatusText.value = "Race: response missing event_name"
                        raceDataReady.value = false
                    }
                    return@thread
                }

                val parsedStartFlags = parseRaceStartFlags(json)

                val start = getJsonStringAny(
                    json,
                    listOf("start_time", "startTime", "tracking_start", "trackingStart", "start")
                ) ?: "--"

                val stop = if (json.has("stop_time") && !json.isNull("stop_time")) {
                    getJsonStringAny(
                        json,
                        listOf(
                            "stop_time",
                            "stopTime",
                            "tracking_stop",
                            "trackingStop",
                            "end_time",
                            "endTime",
                            "stop"
                        )
                    ) ?: "--"
                } else {
                    "--"
                }

                val status = getJsonStringAny(
                    json,
                    listOf("race_status", "status", "state", "event_status")
                ) ?: "loaded"

                val raceInfo = getJsonStringAny(
                    json,
                    listOf("race_info", "info", "notice", "message")
                ) ?: "--"

                val courseShortened = json.optBoolean("course_shortened", false)

                val courseObj = json.optJSONObject("course")
                    ?: json.optJSONObject("kurs")
                    ?: json.optJSONObject("race_course")
                    ?: json.optJSONObject("track")

                val courseSummary = buildCourseSummary(
                    courseObj = courseObj,
                    courseShortened = courseShortened
                )

                runOnUiThread {
                    adoptResolvedEventName(responseResolvedEventName)
                    raceStartText.value = "Start: $start"
                    raceStopText.value = "Stop: $stop"
                    raceDataReady.value = true
                    raceCourseText.value = courseSummary.courseText
                    raceStartLineText.value = courseSummary.startLineText
                    raceFinishLineText.value = courseSummary.finishLineText
                    raceMarksText.value = courseSummary.marksText

                    raceStatusText.value = "Race: $status"
                    raceInfoText.value = "Info: $raceInfo"
                    raceShortenedText.value = if (courseShortened) {
                        "Course shortened: YES"
                    } else {
                        "Course shortened: no"
                    }
                    raceStartFlags.value = parsedStartFlags

                    currentRaceStatus = status
                    raceStartEpochMillis = parseServerTimeToMillis(start)
                    saveRaceSetup()
                    updateStartPanelStatus()
                    updateLocalRaceStatus()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    raceStatusText.value = "Race: ${e.message}"
                    raceDataReady.value = false
                }
            } finally {
                raceDataFetchRunning = false
            }
        }
    }

    private fun buildCourseMapUrl(): String {
        return buildNormalApiGetUrl(
            baseUrl = getBaseServerUrl(),
            path = "/course-map",
            eventName = raceEvent.value
        )
    }

    private fun buildCourseSummary(
        courseObj: JSONObject?,
        courseShortened: Boolean
    ): CourseSummary {
        if (courseObj == null) {
            return CourseSummary(
                courseText = "Course: --",
                startLineText = "Start line: --",
                finishLineText = "Finish line: --",
                marksText = "Marks: --"
            )
        }

        val startLine = courseObj.optJSONObject("start_line")
        val finishLine = courseObj.optJSONObject("finish_line")
        val marks = courseObj.optJSONArray("marks")

        val startRefLabel = startLine
            ?.optJSONObject("ref")
            ?.optString("label", "Ref") ?: "Ref"

        val startMarkLabel = startLine
            ?.optJSONObject("mark")
            ?.optString("label", "Mark") ?: "Mark"

        val finishRefLabel = finishLine
            ?.optJSONObject("ref")
            ?.optString("label", "Ref") ?: "Ref"

        val finishMarkLabel = finishLine
            ?.optJSONObject("mark")
            ?.optString("label", "Mark") ?: "Mark"

        val markNames = mutableListOf<String>()

        if (marks != null) {
            for (i in 0 until marks.length()) {
                val mark = marks.optJSONObject(i) ?: continue

                val order = mark.optInt("order", i + 1)
                val name = mark.optString("name", "Mark")
                val omitWhenShortened = mark.optBoolean("omit_when_shortened", false)

                val label = if (courseShortened && omitWhenShortened) {
                    "$order $name [skipped]"
                } else {
                    "$order $name"
                }

                markNames.add(label)
            }
        }

        val markCount = marks?.length() ?: 0

        return CourseSummary(
            courseText = "Course: $markCount marks",
            startLineText = "Start line: $startRefLabel → $startMarkLabel",
            finishLineText = "Finish line: $finishRefLabel → $finishMarkLabel",
            marksText = if (markNames.isEmpty()) {
                "Marks: --"
            } else {
                "Marks: ${markNames.joinToString(", ")}"
            }
        )
    }

    private fun getBaseServerUrl(): String {
        val server = raceServer.value.trim()

        return if (server.endsWith("/ingest")) {
            server.removeSuffix("/ingest")
        } else {
            server.trimEnd('/')
        }
    }

    private fun getJsonStringAny(json: JSONObject, keys: List<String>): String? {
        for (key in keys) {
            if (json.has(key) && !json.isNull(key)) {
                return json.optString(key)
            }
        }

        return null
    }

    private fun clearOldData() {
        if (inRace.value || manualTracking.value) {
            statusText.value = "Stop Race/Manual Tracking before deleting data"
            return
        }

        db.deleteAllSamples()
        updateStorageText()
        lastCsvLine.value = "No CSV line yet"
        statusText.value = "Old data deleted"
    }

    private fun startGpsDisplayUpdates() {
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
                updateGpsDisplay(cachedLocation)
            }

        } catch (e: SecurityException) {
            statusText.value = "No GPS permission"
        } catch (e: Exception) {
            statusText.value = "GPS error: ${e.message}"
        }
    }

    private fun updateGpsDisplay(location: Location) {
        val cog = location.bearing
        val sog = location.speed
        val accuracy = location.accuracy

        cogText.value = String.format(Locale.US, "COG: %.1f°", cog)
        sogText.value = String.format(Locale.US, "SOG: %.2f m/s", sog)
        gpsAccuracyText.value = String.format(Locale.US, "GPS: %.1f m", accuracy)

        gpsColor.value = when {
            accuracy <= 10f -> RegattaGreen
            accuracy <= 25f -> RegattaOrange
            else -> RegattaRed
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

    private fun updateStorageText() {
        val total = db.countSamples()
        val pending = db.countPendingSamples()

        rowCountText.value = "$total rows stored"

        uploadStatusText.value = if (pending == 0L) {
            "Upload: all sent"
        } else {
            "Upload: $pending pending"
        }
    }

    private fun exportCsvToUri(uri: Uri) {
        try {
            val csv = db.exportAllAsCsv()
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(csv.toByteArray(Charsets.UTF_8))
            }
            statusText.value = "CSV exported"
        } catch (e: Exception) {
            statusText.value = "CSV export failed: ${e.message}"
        }
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
        // Display only in this MainActivity.
    }

    override fun onDestroy() {
        super.onDestroy()

        handler.removeCallbacks(uiRefreshRunnable)
        handler.removeCallbacks(raceDataRefreshRunnable)

        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {
        }

        sensorManager.unregisterListener(this)
    }
}

@Composable
fun OcsDecisionDialog(
    onOk: () -> Unit,
    onContinueCourse: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onOk,
        title = {
            Text("OCS detected")
        },
        text = {
            Text(
                text = """
                    The boat appears to be over the start line early.

                    Return across the start line, or ignore this warning and continue the course?
                """.trimIndent()
            )
        },
        confirmButton = {
            TextButton(onClick = onOk) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onContinueCourse) {
                Text("Ignore / continue course")
            }
        }
    )
}

@Composable
fun FinishDetectedDialog(
    onStopTracking: () -> Unit,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onContinue,
        title = {
            Text("Finish detected")
        },
        text = {
            Text(
                text = """
                    The boat appears to have finished.

                    Stop race tracking now?

                    If you do nothing, the background tracking service will stop automatically after 5 minutes.
                """.trimIndent()
            )
        },
        confirmButton = {
            TextButton(onClick = onStopTracking) {
                Text("Stop tracking")
            }
        },
        dismissButton = {
            TextButton(onClick = onContinue) {
                Text("Continue")
            }
        }
    )
}

@Composable
fun TrackingConsentDialog(
    onAccept: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text("GPS Tracking Consent")
        },
        text = {
            Text(
                text = """
                    This app processes location data to enable regatta tracking, start line checks, OCS detection, course progress, and finish detection.

                    During a race session, location data may be transmitted to the configured regatta server.

                    During manual training, data is stored locally and is not automatically transmitted to the server.

                    The Android location permission is also required.
                """.trimIndent()
            )
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text("I agree")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun LeaveRaceWarningDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text("Retire / Finish?")
        },
        text = {
            Text(
                text = """
                    Use this only if you are retiring from the race or have finished.

                    This stops race tracking for this boat. If you retire, you may be out of scoring.
                """.trimIndent()
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Retire / Finish")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun BoatConfirmDialog(
    boatName: String,
    skipperName: String,
    sailNumber: String,
    boatType: String,
    hullColor: String,
    yardstick: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text("Is this your boat?")
        },
        text = {
            Text(
                text = """
                    Please confirm your boat data before entering the race.

                    Boat name: $boatName
                    Skipper: $skipperName
                    Sail number: $sailNumber
                    Boat type: $boatType
                    Hull color: $hullColor
                    Yardstick: $yardstick

                    These values will be used for race tracking and sent to the race server.
                """.trimIndent()
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Enter Race")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ClearRaceSetupDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text("Clear Race?")
        },
        text = {
            Text(
                text = """
                    This removes the scanned race from this device.

                    Boat setup will not be changed.

                    You will need to scan the race QR code again before registering or entering the race.
                """.trimIndent()
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Clear Race")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}