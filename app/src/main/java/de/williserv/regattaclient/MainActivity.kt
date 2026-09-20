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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.res.stringResource
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
    private val eventLegalFlowState = EventLegalFlowState()
    private var pendingEnterRaceAfterLegal = false
    private val enterRaceServerCheckInProgress = mutableStateOf(false)
    private val enterRaceServerCheckState = EnterRaceServerCheckState()
    private var activeLegalFetchContext: EventCompatibilityContext? = null
    private var activeLegalFetchEnterRaceGeneration: Long? = null

    private val showClearRaceSetupDialog = mutableStateOf(false)
    private lateinit var db: TrackingDbHelper
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager

    private val currentScreen = mutableStateOf(Screen.HOME)

    private val boatName = mutableStateOf("")
    private val skipperName = mutableStateOf("")
    private val hullColor = mutableStateOf("")
    private val sailNumber = mutableStateOf("")
    private val yardstick = mutableStateOf("")

    private val boatPrefsName = "boat_setup"

    private val racePrefsName = "race_setup"

    private val boatType = mutableStateOf("")
    private val setupConfirmed = mutableStateOf(false)

    private val raceServer = mutableStateOf("")
    private val raceEvent = mutableStateOf("")
    private val raceSecret = mutableStateOf("")
    private val resolvedEventName = mutableStateOf("")
    private val raceSeriesDisplayMetadata = mutableStateOf(SeriesDisplayMetadata())
    private var raceLegalResolvedEventName = ""

    private val inRace = mutableStateOf(false)
    private val manualTracking = mutableStateOf(false)

    private val raceStatusText = mutableStateOf("")
    private val raceStartText = mutableStateOf("")
    private val raceStopText = mutableStateOf("")
    private val raceCourseText = mutableStateOf("")
    private val raceStartLineText = mutableStateOf("")
    private val raceFinishLineText = mutableStateOf("")
    private val raceMarksText = mutableStateOf("")
    private val raceInfoText = mutableStateOf("")
    private val raceShortenedText = mutableStateOf("")
    private val courseMapMarks = mutableStateOf<List<CourseMapMark>>(emptyList())
    private val selectedCourseMapView = mutableStateOf<CourseMapView?>(null)

    private val raceStartFlags = mutableStateOf(RaceStartFlags())
    private val raceLegalText = mutableStateOf("")
    private val raceLegalAccepted = mutableStateOf(false)
    private val raceLegalStatusText = mutableStateOf("")

    private val showEventUpdateRecommendedDialog = mutableStateOf(false)
    private val showEventUpdateRequiredDialog = mutableStateOf(false)
    private var eventCompatibilityAllowedAccess: EventAccessKey? = null
    private var eventCompatibilityWarningAccess: EventAccessKey? = null
    private var eventCompatibilityBlockedAccess: EventAccessKey? = null
    private var eventCompatibilityCheckAccess: EventAccessKey? = null
    private var eventCompatibilityCheckGeneration = -1L
    private var eventCompatibilityGeneration = 0L
    private var eventCompatibilityEnterRaceGeneration: Long? = null

    private val currentTargetText = mutableStateOf("")
    private val progressText = mutableStateOf("")
    private val boatRaceStatusText = mutableStateOf("")

    private val dtlText = mutableStateOf("")
    private val ttlText = mutableStateOf("")
    private val ocsText = mutableStateOf("")
    private val debugErrorText = mutableStateOf("")
    private var localIsOcs = false
    private var localRaceFinished = false

    private val showFinishDetectedDialog = mutableStateOf(false)
    private val raceDataReady = mutableStateOf(false)

    private val startPanelText = mutableStateOf("")
    private val startPanelMode = mutableStateOf("clear")
    private val raceEntryNowEpochMillis = mutableStateOf(System.currentTimeMillis())

    private var raceStartEpochMillis: Long? = null
    private var currentRaceStatus by mutableStateOf("")
    private var rawRaceStatus = ""
    private var rawRaceStart = ""
    private var rawRaceStop = ""
    private var rawRaceInfo = ""
    private var rawRaceCourseJson = ""
    private var rawRaceCourseShortened = false
    private val raceDataRequestGate = EventRequestGate()

    private val raceRegistered = mutableStateOf(false)
    private val statusText = mutableStateOf("")
    private val rowCountText = mutableStateOf("")
    private val uploadStatusText = mutableStateOf("")
    private val pendingUploadCount = mutableStateOf(0L)
    private val serverNoConnection = mutableStateOf(false)
    private val serviceStatusText = mutableStateOf("")

    private val registerRaceStatusText = mutableStateOf("")
    private val resultsStatusText = mutableStateOf("")
    private val resultsPublished = mutableStateOf(false)
    private val resultsPublishedAt = mutableStateOf("")
    private val resultRows = mutableStateOf<List<ResultRow>>(emptyList())
    private var resultsFetchRunning = false

    private val showClearConfirmDialog = mutableStateOf(false)
    private val showOcsDecisionDialog = mutableStateOf(false)
    private val showLeaveRaceOptionsDialog = mutableStateOf(false)
    private val showRetireConfirmDialog = mutableStateOf(false)
    private val retirementReported = mutableStateOf(false)
    private val retirementStatusText = mutableStateOf("")
    private val retirementRequestInFlight = mutableStateOf(false)
    private val showAdvanced = mutableStateOf(false)

    private val cogText = mutableStateOf("")
    private val sogText = mutableStateOf("")
    private val gpsAccuracyText = mutableStateOf("")
    private val gpsColor = mutableStateOf(RegattaRed)

    private val appStatePrefsName = "app_state"

    private val lastCsvLine = mutableStateOf("")

    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f

    private var gyroX = 0f
    private var gyroY = 0f
    private var gyroZ = 0f

    private val handler = Handler(Looper.getMainLooper())
    private val asyncLifetime = ActivityAsyncLifetime()


    private val uiRefreshRunnable = object : Runnable {
        override fun run() {
            if (!asyncLifetime.isActive()) return
            raceEntryNowEpochMillis.value = System.currentTimeMillis()
            reconcileTrackingState()
            updateStorageText()
            updateLocalRaceStatus()
            updateConnectionUiState()
            if (asyncLifetime.isActive()) {
                handler.postDelayed(this, 1000L)
            }
        }
    }

    private fun canEnterRaceNow(): Boolean {
        val isSeriesAccess =
            raceSeriesDisplayMetadata.value.runName.isNotBlank() ||
                (resolvedEventName.value.isNotBlank() && resolvedEventName.value != raceEvent.value)
        val cachedSeriesRunObsolete = isCachedSeriesRunObsolete(
            isSeriesAccess = isSeriesAccess,
            status = rawRaceStatus,
            stopEpochMillis = parseServerTimeToMillis(rawRaceStop),
            nowEpochMillis = System.currentTimeMillis()
        )

        return canEnterRaceWithLocalState(
            raceDataReady = raceDataReady.value,
            setupConfirmed = setupConfirmed.value,
            cachedSeriesRunObsolete = cachedSeriesRunObsolete
        )
    }

    private fun navigateBack() {
        currentScreen.value = when (currentScreen.value) {
            Screen.HOME -> Screen.HOME
            Screen.BOAT_DATA,
            Screen.RACE,
            Screen.COURSE,
            Screen.LEGAL,
            Screen.RESULTS -> Screen.HOME
            Screen.RACE_LEGAL,
            Screen.QR_SCANNER -> Screen.RACE
            Screen.MAP -> if (selectedCourseMapView.value == null) {
                Screen.HOME
            } else {
                Screen.COURSE
            }
        }
    }

    private val raceDataRefreshRunnable = object : Runnable {
        override fun run() {
            if (!asyncLifetime.isActive()) return
            if (currentEventAccessKey() != null) {
                fetchRaceDataForDisplay()
                if (asyncLifetime.isActive()) {
                    handler.postDelayed(this, 10_000L)
                }
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
                statusText.value = getString(R.string.gps_permission_denied)
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


    private fun initializeLocalizedUiText() {
        boatName.value = getString(R.string.default_boat_name)
        skipperName.value = getString(R.string.default_skipper_name)
        hullColor.value = getString(R.string.default_hull_color)
        sailNumber.value = getString(R.string.default_sail_number)
        yardstick.value = getString(R.string.default_yardstick)
        boatType.value = getString(R.string.default_boat_type)
        raceStatusText.value = getString(R.string.race_not_loaded)
        raceStartText.value = getString(R.string.start_unknown)
        raceStopText.value = getString(R.string.stop_unknown)
        raceCourseText.value = getString(R.string.course_unknown)
        raceStartLineText.value = getString(R.string.start_line_unknown)
        raceFinishLineText.value = getString(R.string.finish_line_unknown)
        raceMarksText.value = getString(R.string.marks_unknown)
        raceInfoText.value = getString(R.string.info_unknown)
        raceShortenedText.value = getString(R.string.course_shortened_no)
        currentTargetText.value = getString(R.string.next_unknown)
        progressText.value = getString(R.string.progress_unknown)
        boatRaceStatusText.value = getString(R.string.boat_status_unknown)
        dtlText.value = getString(R.string.distance_unknown)
        ttlText.value = getString(R.string.ttl_unknown)
        ocsText.value = getString(R.string.ocs_unknown)
        debugErrorText.value = getString(R.string.last_error_unknown)
        startPanelText.value = getString(R.string.app_name)
        statusText.value = getString(R.string.tracking_stopped)
        rowCountText.value = getString(R.string.rows_stored, 0)
        uploadStatusText.value = getString(R.string.upload_ready)
        serviceStatusText.value = getString(R.string.service_stopped)
        cogText.value = "${getString(R.string.cog_prefix)} --"
        sogText.value = "${getString(R.string.sog_prefix)} --"
        gpsAccuracyText.value = getString(R.string.gps_accuracy_unknown)
        lastCsvLine.value = getString(R.string.csv_preview_not_live)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeLocalizedUiText()

        db = TrackingDbHelper(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        loadBoatSetup()
        loadRaceSetup()
        loadAppState()
        refreshRetirementReportedState()

        updateStorageText()
        updateLocalRaceStatus()

        enableEdgeToEdge()

        setContent {
            RegattaClientTheme {
                BackHandler(enabled = currentScreen.value != Screen.HOME) {
                    if (currentScreen.value == Screen.RACE_LEGAL) {
                        pendingEnterRaceAfterLegal = false
                    }
                    navigateBack()
                }

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
                            pendingUploadCount = pendingUploadCount.value,
                            noConnection = serverNoConnection.value,
                            debugErrorText = debugErrorText.value,
                            serviceStatusText = serviceStatusText.value,
                            raceStatusCode = currentRaceStatus,
                            raceStatusDisplayText = raceStatusText.value,
                            raceEvent = raceEvent.value,
                            seriesDisplayMetadata = raceSeriesDisplayMetadata.value,
                            raceStartText = raceStartText.value,
                            raceStopText = raceStopText.value,
                            raceCourseText = raceCourseText.value,
                            raceStartLineText = raceStartLineText.value,
                            raceFinishLineText = raceFinishLineText.value,
                            raceMarksText = raceMarksText.value,
                            currentTargetText = currentTargetText.value,
                            progressText = progressText.value,
                            boatRaceStatusText = boatRaceStatusText.value,
                            retirementReported = retirementReported.value,
                            retirementStatusText = retirementStatusText.value,
                            raceDataReady = raceDataReady.value,
                            dtlText = dtlText.value,
                            ttlText = ttlText.value,
                            ocsText = ocsText.value,
                            raceInfoText = raceInfoText.value,
                            raceShortenedText = raceShortenedText.value,
                            raceShortened = rawRaceCourseShortened,
                            hasRaceInfo = rawRaceInfo.trim().let { it.isNotBlank() && it != "--" },
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
                                    selectedCourseMapView.value = null
                                    currentScreen.value = Screen.MAP
                                }
                            },
                            onOcsPanelClick = {
                                showOcsDecisionDialog.value = true
                            },
                            onResults = {
                                if (raceDataReady.value && currentRaceStatus.equals("finished", ignoreCase = true)) {
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
                                pendingEnterRaceAfterLegal = false
                                navigateBack()
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
                            onBack = ::navigateBack
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
                            onConfirmSetup = { values ->
                                if (isBoatSetupValid(values)) {
                                    val previousValues = currentBoatSetupValues()
                                    val hadConfirmedSetup = setupConfirmed.value
                                    val invalidateRegistration = shouldInvalidateRaceRegistration(
                                        previous = previousValues,
                                        next = values,
                                        hadConfirmedSetup = hadConfirmedSetup
                                    )
                                    val invalidateLegal = shouldInvalidateRaceLegal(
                                        previous = previousValues,
                                        next = values,
                                        hadConfirmedSetup = hadConfirmedSetup
                                    )

                                    boatName.value = values.boatName
                                    skipperName.value = values.skipperName
                                    hullColor.value = values.hullColor
                                    sailNumber.value = values.sailNumber
                                    yardstick.value = values.yardstick
                                    boatType.value = values.boatType
                                    setupConfirmed.value = true
                                    saveBoatSetup()
                                    refreshRetirementReportedState()

                                    if (manualTracking.value) {
                                        startRegattaForegroundService(manualMode = true)
                                    }

                                    if (invalidateRegistration) {
                                        raceRegistered.value = false
                                        registerRaceStatusText.value = ""
                                    }
                                    if (invalidateLegal) {
                                        resetRaceLegalState()
                                    }

                                    currentScreen.value = Screen.HOME
                                }
                            },
                            onBack = ::navigateBack
                        )

                        Screen.RACE -> RaceScreen(
                            inRace = inRace.value,
                            canEnterRace = canEnterRaceNow(),
                            raceLegalAccepted = raceLegalAccepted.value,
                            raceServer = raceServer.value,
                            raceEvent = raceEvent.value,
                            raceSecret = raceSecret.value,
                            raceStatusText = raceStatusText.value,
                            raceStartText = raceStartText.value,
                            raceStartEpochMillis = raceStartEpochMillis,
                            raceEntryNowEpochMillis = raceEntryNowEpochMillis.value,
                            raceStopText = raceStopText.value,
                            raceCourseText = raceCourseText.value,
                            raceStartLineText = raceStartLineText.value,
                            raceFinishLineText = raceFinishLineText.value,
                            raceMarksText = raceMarksText.value,
                            raceRegistered = raceRegistered.value,
                            retirementReported = retirementReported.value,
                            retirementStatusText = retirementStatusText.value,
                            currentTargetText = currentTargetText.value,
                            progressText = progressText.value,
                            raceInfoText = raceInfoText.value,
                            canRegisterRace = setupConfirmed.value &&
                                    raceDataReady.value &&
                                    raceLegalAccepted.value &&
                                    !inRace.value,
                            registerRaceStatusText = registerRaceStatusText.value,
                            raceShortenedText = raceShortenedText.value,
                            raceShortened = rawRaceCourseShortened,
                            seriesDisplayMetadata = raceSeriesDisplayMetadata.value,
                            modifier = Modifier.padding(innerPadding),
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
                            onRefreshRaceData = {
                                fetchRaceDataForDisplay()
                            },
                            onScanQr = {
                                currentScreen.value = Screen.QR_SCANNER
                            },
                            onEnterRace = {
                                requestEnterRaceAfterLocalChecks()
                            },
                            onLeaveRace = {
                                showLeaveRaceOptionsDialog.value = true
                            },
                            onRegisterRace = {
                                registerForRace()
                            },
                            onBack = ::navigateBack
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
                            raceShortened = rawRaceCourseShortened,
                            currentTargetText = currentTargetText.value,
                            courseMapMarks = courseMapMarks.value,
                            onSetCourseProgress = { passedMarks, raceStarted ->
                                setCourseProgressFromUser(
                                    passedMarks = passedMarks,
                                    raceStarted = raceStarted
                                )
                            },
                            onOpenMapDetail = { view ->
                                selectedCourseMapView.value = view
                                currentScreen.value = Screen.MAP
                            },
                            modifier = Modifier.padding(innerPadding),
                            onBack = ::navigateBack
                        )

                        Screen.MAP -> {
                            val selectedMapView = selectedCourseMapView.value
                            MapScreen(
                                mapImageUrl = buildCourseMapUrl(selectedMapView),
                                apiVersion = RegattaTrackingService.API_VERSION,
                                sharedSecret = raceSecret.value,
                                modifier = Modifier.padding(innerPadding),
                                fallbackMapImageUrl = if (selectedMapView != null) {
                                    buildCourseMapUrl()
                                } else {
                                    null
                                },
                                onBack = ::navigateBack
                            )
                        }

                        Screen.QR_SCANNER -> QrScannerScreen(
                            modifier = Modifier.padding(innerPadding),
                            onQrScanned = { raw ->
                                handleRaceQrCode(raw)
                            },
                            onBack = ::navigateBack
                        )

                        Screen.LEGAL -> LegalScreen(
                            modifier = Modifier.padding(innerPadding),
                            onBack = ::navigateBack
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

                if (showLeaveRaceOptionsDialog.value) {
                    LeaveRaceOptionsDialog(
                        retireEnabled = !retirementRequestInFlight.value,
                        onRetire = {
                            showLeaveRaceOptionsDialog.value = false
                            showRetireConfirmDialog.value = true
                        },
                        onLeaveRace = {
                            showLeaveRaceOptionsDialog.value = false
                            leaveRace()
                        },
                        onCancel = {
                            showLeaveRaceOptionsDialog.value = false
                        }
                    )
                }

                if (showRetireConfirmDialog.value) {
                    RetireConfirmDialog(
                        onConfirm = {
                            showRetireConfirmDialog.value = false
                            reportRetirement()
                        },
                        onCancel = {
                            showRetireConfirmDialog.value = false
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

                            requestTrackingConsent(PendingTrackingAction.ENTER_RACE)
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
                            continueRaceAfterDetectedFinish()
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

                if (showEventUpdateRecommendedDialog.value) {
                    EventUpdateRecommendedDialog(
                        onContinue = ::continueAfterRecommendedEventUpdate,
                        onCancel = {
                            cancelEnterRaceServerCheck()
                            pendingEnterRaceAfterLegal = false
                            showEventUpdateRecommendedDialog.value = false
                        }
                    )
                }

                if (showEventUpdateRequiredDialog.value) {
                    EventUpdateRequiredDialog(
                        onDismiss = {
                            cancelEnterRaceServerCheck()
                            pendingEnterRaceAfterLegal = false
                            showEventUpdateRequiredDialog.value = false
                        }
                    )
                }

                if (enterRaceServerCheckInProgress.value) {
                    EnterRaceServerCheckOverlay()
                }

            }
        }

        handleIncomingShareIntent(intent)
        if (currentEventAccessKey() != null) {
            fetchRaceLegalText()
        }
        updateConnectionUiState()
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

        val sharedText = sharedIntent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()

        // Consume the share so an Activity recreation cannot import it a second time.
        sharedIntent.action = null
        sharedIntent.removeExtra(Intent.EXTRA_TEXT)

        val access = sharedText?.let(::parseEventAccessUrl)
        if (access == null) {
            android.widget.Toast.makeText(
                this,
                getString(R.string.shared_text_invalid),
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
                getString(R.string.event_already_loaded),
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
        raceSeriesDisplayMetadata.value = SeriesDisplayMetadata(
            runName = prefs.getString("series_run_name", "").orEmpty(),
            occurrenceNo = prefs.getInt("series_occurrence_no", 0).takeIf { it > 0 },
            plannedRaceCount = prefs.getInt("series_planned_race_count", 0).takeIf { it > 0 }
        )

        raceDataReady.value = prefs.getBoolean("race_data_ready", false)
        if (resolvedEventName.value.isBlank()) {
            raceDataReady.value = false
        }

        if (!raceDataReady.value) {
            currentRaceStatus = ""
            raceStartEpochMillis = null
            return
        }

        val hasRawState =
            prefs.getInt("race_raw_state_version", 0) >= RACE_RAW_STATE_VERSION

        if (hasRawState) {
            rawRaceStatus = prefs.getString("race_status_raw", "").orEmpty()
            rawRaceStart = prefs.getString("race_start_raw", "").orEmpty()
            rawRaceStop = prefs.getString("race_stop_raw", "").orEmpty()
            rawRaceInfo = prefs.getString("race_info_raw", "").orEmpty()
            rawRaceCourseJson = prefs.getString("race_course_json_raw", "").orEmpty()
            rawRaceCourseShortened = prefs.getBoolean("race_course_shortened_raw", false)
            renderRawRaceSetup()
        } else {
            raceStatusText.value = prefs.getString("race_status_text", raceStatusText.value) ?: raceStatusText.value
            raceStartText.value = prefs.getString("race_start_text", raceStartText.value) ?: raceStartText.value
            raceStopText.value = prefs.getString("race_stop_text", raceStopText.value) ?: raceStopText.value
            raceCourseText.value = prefs.getString("race_course_text", raceCourseText.value) ?: raceCourseText.value
            raceStartLineText.value = prefs.getString("race_start_line_text", raceStartLineText.value) ?: raceStartLineText.value
            raceFinishLineText.value = prefs.getString("race_finish_line_text", raceFinishLineText.value) ?: raceFinishLineText.value
            raceMarksText.value = prefs.getString("race_marks_text", raceMarksText.value) ?: raceMarksText.value
            raceInfoText.value = prefs.getString("race_info_text", raceInfoText.value) ?: raceInfoText.value
            raceShortenedText.value = prefs.getString("race_shortened_text", raceShortenedText.value) ?: raceShortenedText.value

            val migratedLegacyState = migrateLegacyRaceDisplayState(
                raceStatusText = raceStatusText.value,
                raceStartText = raceStartText.value,
                raceStopText = raceStopText.value,
                raceInfoText = raceInfoText.value,
                raceShortenedText = raceShortenedText.value,
                raceMarksText = raceMarksText.value
            )
            rawRaceStatus = migratedLegacyState.raceStatus
            rawRaceStart = migratedLegacyState.raceStart
            rawRaceStop = migratedLegacyState.raceStop
            rawRaceInfo = migratedLegacyState.raceInfo
            rawRaceCourseShortened = migratedLegacyState.courseShortened
            courseMapMarks.value = migratedLegacyState.courseMarks.map { mark ->
                CourseMapMark(
                    order = mark.order,
                    label = mark.label,
                    skipped = mark.skipped
                )
            }
            renderMigratedLegacyRaceSetup(migratedLegacyState)
        }
    }

    private fun renderMigratedLegacyRaceSetup(migratedLegacyState: LegacyRaceDisplayState) {
        raceStatusText.value = if (rawRaceStatus.isBlank()) {
            getString(R.string.race_not_loaded)
        } else {
            getString(R.string.race_value, rawRaceStatus)
        }
        raceStartText.value = if (rawRaceStart.isBlank() || rawRaceStart == "--") {
            getString(R.string.start_unknown)
        } else {
            getString(R.string.start_value, rawRaceStart)
        }
        raceStopText.value = if (rawRaceStop.isBlank() || rawRaceStop == "--") {
            getString(R.string.stop_unknown)
        } else {
            getString(R.string.stop_value, rawRaceStop)
        }
        raceInfoText.value = if (rawRaceInfo.isBlank() || rawRaceInfo == "--") {
            getString(R.string.info_unknown)
        } else {
            getString(R.string.info_value, rawRaceInfo)
        }
        raceShortenedText.value = if (rawRaceCourseShortened) {
            getString(R.string.course_shortened_yes)
        } else {
            getString(R.string.course_shortened_no)
        }

        val markCount = Regex("\\d+")
            .find(legacyDisplayPayload(raceCourseText.value))
            ?.value
            ?.toIntOrNull()
        raceCourseText.value = if (markCount != null) {
            getString(R.string.course_mark_count, markCount)
        } else {
            getString(R.string.course_unknown)
        }

        raceStartLineText.value = localizeLegacyCourseLine(
            displayText = raceStartLineText.value,
            unknownRes = R.string.start_line_unknown,
            valueRes = R.string.start_line_value
        )
        raceFinishLineText.value = localizeLegacyCourseLine(
            displayText = raceFinishLineText.value,
            unknownRes = R.string.finish_line_unknown,
            valueRes = R.string.finish_line_value
        )

        raceMarksText.value = if (migratedLegacyState.courseMarks.isEmpty()) {
            getString(R.string.marks_unknown)
        } else {
            getString(
                R.string.marks_value,
                migratedLegacyState.courseMarks.joinToString(", ") { it.label }
            )
        }

        currentRaceStatus = rawRaceStatus
        raceStartEpochMillis = parseServerTimeToMillis(rawRaceStart)
    }

    private fun localizeLegacyCourseLine(
        displayText: String,
        unknownRes: Int,
        valueRes: Int
    ): String {
        val payload = legacyDisplayPayload(displayText)
        if (payload.isBlank() || payload == "--") {
            return getString(unknownRes)
        }

        val parts = payload.split("→", limit = 2).map { it.trim() }
        return if (parts.size == 2 && parts.all { it.isNotBlank() }) {
            getString(valueRes, parts[0], parts[1])
        } else {
            getString(unknownRes)
        }
    }

    private fun renderRawRaceSetup() {
        raceStatusText.value = if (rawRaceStatus.isBlank()) {
            getString(R.string.race_not_loaded)
        } else {
            getString(R.string.race_value, rawRaceStatus)
        }
        raceStartText.value = if (rawRaceStart.isBlank() || rawRaceStart == "--") {
            getString(R.string.start_unknown)
        } else {
            getString(R.string.start_value, rawRaceStart)
        }
        raceStopText.value = if (rawRaceStop.isBlank() || rawRaceStop == "--") {
            getString(R.string.stop_unknown)
        } else {
            getString(R.string.stop_value, rawRaceStop)
        }
        raceInfoText.value = if (rawRaceInfo.isBlank() || rawRaceInfo == "--") {
            getString(R.string.info_unknown)
        } else {
            getString(R.string.info_value, rawRaceInfo)
        }
        raceShortenedText.value = if (rawRaceCourseShortened) {
            getString(R.string.course_shortened_yes)
        } else {
            getString(R.string.course_shortened_no)
        }

        val courseObj = rawRaceCourseJson
            .takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
        val courseSummary = buildCourseSummary(
            courseObj = courseObj,
            courseShortened = rawRaceCourseShortened
        )

        raceCourseText.value = courseSummary.courseText
        raceStartLineText.value = courseSummary.startLineText
        raceFinishLineText.value = courseSummary.finishLineText
        raceMarksText.value = courseSummary.marksText
        courseMapMarks.value = buildCourseMapMarks(
            courseObj = courseObj,
            courseShortened = rawRaceCourseShortened
        )

        currentRaceStatus = rawRaceStatus
        raceStartEpochMillis = parseServerTimeToMillis(rawRaceStart)
    }


    private fun resetRaceLegalState() {
        raceLegalAccepted.value = false
        raceLegalText.value = ""
        raceLegalHash.value = ""
        raceLegalVersion.value = ""
        raceLegalStatusText.value = ""
        raceLegalAcceptStatusText.value = ""
        raceLegalResolvedEventName = ""
        eventLegalFlowState.clearDisplayedDocument()
    }

    private fun resetRunSpecificClientState(clearLegal: Boolean) {
        cancelEnterRaceServerCheck()
        pendingEnterRaceAfterLegal = false
        raceDataReady.value = false
        raceStatusText.value = getString(R.string.race_not_loaded)
        raceStartText.value = getString(R.string.start_unknown)
        raceStopText.value = getString(R.string.stop_unknown)
        raceCourseText.value = getString(R.string.course_unknown)
        raceStartLineText.value = getString(R.string.start_line_unknown)
        raceFinishLineText.value = getString(R.string.finish_line_unknown)
        raceMarksText.value = getString(R.string.marks_unknown)
        raceInfoText.value = getString(R.string.info_unknown)
        raceShortenedText.value = getString(R.string.course_shortened_no)
        raceSeriesDisplayMetadata.value = SeriesDisplayMetadata()
        courseMapMarks.value = emptyList()
        selectedCourseMapView.value = null
        raceStartFlags.value = RaceStartFlags()

        currentTargetText.value = getString(R.string.next_unknown)
        progressText.value = getString(R.string.progress_unknown)
        boatRaceStatusText.value = getString(R.string.boat_status_unknown)
        dtlText.value = getString(R.string.distance_unknown)
        ttlText.value = getString(R.string.ttl_unknown)
        ocsText.value = getString(R.string.ocs_unknown)

        rawRaceStatus = ""
        rawRaceStart = ""
        rawRaceStop = ""
        rawRaceInfo = ""
        rawRaceCourseJson = ""
        rawRaceCourseShortened = false
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

    private fun resetEventCompatibilityState() {
        cancelEnterRaceServerCheck()
        pendingEnterRaceAfterLegal = false
        eventCompatibilityGeneration += 1L
        eventCompatibilityAllowedAccess = null
        eventCompatibilityWarningAccess = null
        eventCompatibilityBlockedAccess = null
        eventCompatibilityCheckAccess = null
        eventCompatibilityCheckGeneration = -1L
        eventCompatibilityEnterRaceGeneration = null
        activeLegalFetchContext = null
        activeLegalFetchEnterRaceGeneration = null
        eventLegalFlowState.invalidate()
        showEventUpdateRecommendedDialog.value = false
        showEventUpdateRequiredDialog.value = false
    }

    private fun clearResolvedEventContextForAccessChange() {
        resetEventCompatibilityState()
        resolvedEventName.value = ""
        retirementReported.value = false
        retirementStatusText.value = ""
        resetRunSpecificClientState(clearLegal = true)

        getSharedPreferences(racePrefsName, Context.MODE_PRIVATE)
            .edit()
            .remove("resolved_event_name")
            .remove("series_run_name")
            .remove("series_occurrence_no")
            .remove("series_planned_race_count")
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
        refreshRetirementReportedState()
    }

    private fun clearRaceSetup() {
        if (inRace.value) {
            raceStatusText.value = getString(R.string.race_cannot_clear_while_running)
            return
        }
        stopRaceDataRefresh()
        resetEventCompatibilityState()
        raceServer.value = ""
        raceEvent.value = ""
        raceSecret.value = ""
        resolvedEventName.value = ""
        retirementReported.value = false
        retirementStatusText.value = ""
        raceLegalResolvedEventName = ""
        raceRegistered.value = false
        registerRaceStatusText.value = ""

        raceStatusText.value = getString(R.string.race_not_loaded)
        raceStartText.value = getString(R.string.start_unknown)
        raceStopText.value = getString(R.string.stop_unknown)
        raceCourseText.value = getString(R.string.course_unknown)
        raceStartLineText.value = getString(R.string.start_line_unknown)
        raceFinishLineText.value = getString(R.string.finish_line_unknown)
        raceMarksText.value = getString(R.string.marks_unknown)
        raceInfoText.value = getString(R.string.info_unknown)
        raceShortenedText.value = getString(R.string.course_shortened_no)
        raceSeriesDisplayMetadata.value = SeriesDisplayMetadata()
        courseMapMarks.value = emptyList()
        selectedCourseMapView.value = null
        raceStartFlags.value = RaceStartFlags()

        currentTargetText.value = getString(R.string.next_unknown)
        progressText.value = getString(R.string.progress_unknown)
        boatRaceStatusText.value = getString(R.string.boat_status_unknown)
        dtlText.value = getString(R.string.distance_unknown)
        ttlText.value = getString(R.string.ttl_unknown)
        ocsText.value = getString(R.string.ocs_unknown)

        rawRaceStatus = ""
        rawRaceStart = ""
        rawRaceStop = ""
        rawRaceInfo = ""
        rawRaceCourseJson = ""
        rawRaceCourseShortened = false
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
            .putString("series_run_name", raceSeriesDisplayMetadata.value.runName)
            .putInt("series_occurrence_no", raceSeriesDisplayMetadata.value.occurrenceNo ?: 0)
            .putInt("series_planned_race_count", raceSeriesDisplayMetadata.value.plannedRaceCount ?: 0)
            .putInt("race_raw_state_version", RACE_RAW_STATE_VERSION)
            .putString("race_status_raw", rawRaceStatus)
            .putString("race_start_raw", rawRaceStart)
            .putString("race_stop_raw", rawRaceStop)
            .putString("race_info_raw", rawRaceInfo)
            .putString("race_course_json_raw", rawRaceCourseJson)
            .putBoolean("race_course_shortened_raw", rawRaceCourseShortened)
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

    private fun currentBoatSetupValues(): BoatSetupValues {
        return BoatSetupValues(
            boatName = boatName.value,
            skipperName = skipperName.value,
            hullColor = hullColor.value,
            sailNumber = sailNumber.value,
            yardstick = yardstick.value,
            boatType = boatType.value
        )
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
        statusText.value = getString(R.string.gps_consent_not_granted)
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

    private fun loadAppState() {
        val prefs = getSharedPreferences(appStatePrefsName, Context.MODE_PRIVATE)

        inRace.value = prefs.getBoolean("in_race", false)
        manualTracking.value = prefs.getBoolean("manual_tracking", false)
        if (inRace.value && manualTracking.value) {
            manualTracking.value = false
            prefs.edit()
                .putBoolean("manual_tracking", false)
                .apply()
        }

        serviceStatusText.value = when {
            inRace.value -> getString(R.string.service_race_running)
            manualTracking.value -> getString(R.string.service_manual_running)
            else -> getString(R.string.service_stopped)
        }

        statusText.value = when {
            inRace.value -> getString(R.string.in_race)
            manualTracking.value -> getString(R.string.manual_tracking_running)
            else -> getString(R.string.tracking_stopped)
        }
    }

    private fun reconcileTrackingState() {
        val prefs = getSharedPreferences(appStatePrefsName, Context.MODE_PRIVATE)
        val persistedInRace = prefs.getBoolean("in_race", false)
        var persistedManual = prefs.getBoolean("manual_tracking", false)

        if (persistedInRace && persistedManual) {
            persistedManual = false
            prefs.edit()
                .putBoolean("manual_tracking", false)
                .apply()
        }

        if (
            inRace.value == persistedInRace &&
            manualTracking.value == persistedManual
        ) {
            return
        }

        inRace.value = persistedInRace
        manualTracking.value = persistedManual

        serviceStatusText.value = when {
            persistedInRace -> getString(R.string.service_race_running)
            persistedManual -> getString(R.string.service_manual_running)
            else -> getString(R.string.service_stopped)
        }

        if (!persistedInRace && !persistedManual) {
            statusText.value = getString(R.string.tracking_stopped)
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
                raceStatusText.value = getString(R.string.qr_code_incomplete)
                currentScreen.value = Screen.RACE
                return
            }

            raceServer.value = server
            raceEvent.value = event
            raceSecret.value = secret
            clearResolvedEventContextForAccessChange()

            raceRegistered.value = false
            registerRaceStatusText.value = ""

            raceStatusText.value = getString(R.string.qr_code_loaded)

            raceLegalAccepted.value = false
            raceLegalText.value = ""
            raceLegalHash.value = ""
            raceLegalVersion.value = ""
            raceLegalStatusText.value = getString(R.string.loading_race_notice)
            raceLegalAcceptStatusText.value = ""

            clearSavedRaceDataReady()

            currentScreen.value = Screen.RACE

            fetchRaceLegalText()

        } catch (_: JSONException) {
            raceStatusText.value = getString(R.string.qr_invalid_json)
            currentScreen.value = Screen.RACE
        } catch (e: Exception) {
            raceStatusText.value = getString(R.string.qr_error, e.message ?: "")
            currentScreen.value = Screen.RACE
        }
    }

    private fun currentEventAccessKey(): EventAccessKey? {
        if (!asyncLifetime.isActive()) return null
        return eventAccessKey(
            server = raceServer.value,
            event = raceEvent.value,
            secret = raceSecret.value
        )
    }

    private fun currentEventCompatibilityContext(access: EventAccessKey): EventCompatibilityContext =
        EventCompatibilityContext(
            access = access,
            generation = eventCompatibilityGeneration
        )

    private fun isCurrentAllowedLegalContext(context: EventCompatibilityContext): Boolean =
        shouldApplyEventLegalResult(
            requestedContext = context,
            currentAccess = currentEventAccessKey(),
            currentGeneration = eventCompatibilityGeneration,
            allowedAccess = eventCompatibilityAllowedAccess
        )

    private fun isCurrentActionableLegalDocument(document: EventLegalDocumentContext): Boolean =
        eventLegalFlowState.displayedDocument == document &&
            canAcceptEventLegal(
                displayedDocument = document,
                currentAccess = currentEventAccessKey(),
                currentGeneration = eventCompatibilityGeneration,
                allowedAccess = eventCompatibilityAllowedAccess,
                currentResolvedEventName = raceLegalResolvedEventName,
                currentLegalHash = raceLegalHash.value
            )

    private fun beginEnterRaceServerCheck(): Long {
        val generation = enterRaceServerCheckState.begin()
        enterRaceServerCheckInProgress.value = true
        handler.postDelayed({
            if (!asyncLifetime.isActive()) {
                return@postDelayed
            }
            if (!enterRaceServerCheckState.isActive(generation)) {
                return@postDelayed
            }

            enterRaceServerCheckState.finish(generation)
            enterRaceServerCheckInProgress.value = false
            if (pendingEnterRaceAfterLegal) {
                continuePendingEnterRaceAfterLegal()
            }
        }, ENTER_RACE_SERVER_CHECK_TIMEOUT_MILLIS)
        return generation
    }

    private fun finishEnterRaceServerCheck(generation: Long?) {
        if (generation == null || !enterRaceServerCheckState.isActive(generation)) return
        enterRaceServerCheckState.finish(generation)
        enterRaceServerCheckInProgress.value = false
    }

    private fun cancelEnterRaceServerCheck() {
        enterRaceServerCheckState.cancel()
        enterRaceServerCheckInProgress.value = false
    }

    private fun continuePendingEnterRaceAfterLegal() {
        if (!pendingEnterRaceAfterLegal) return

        cancelEnterRaceServerCheck()
        pendingEnterRaceAfterLegal = false
        currentScreen.value = Screen.RACE
        showBoatConfirmDialog.value = true
    }

    private fun blockPendingEnterRaceWithLegalError() {
        cancelEnterRaceServerCheck()
        if (!pendingEnterRaceAfterLegal) {
            currentScreen.value = Screen.RACE
            return
        }

        pendingEnterRaceAfterLegal = false
        raceLegalText.value = ""
        eventLegalFlowState.clearDisplayedDocument()
        currentScreen.value = Screen.RACE_LEGAL
    }

    private fun requestEnterRaceAfterLocalChecks() {
        if (enterRaceServerCheckInProgress.value) return

        when {
            !raceDataReady.value -> {
                raceStatusText.value = getString(R.string.race_load_valid_data_first)
            }

            !setupConfirmed.value -> {
                statusText.value = getString(R.string.confirm_boat_setup_first)
            }

            enterRaceLegalStartDecision(raceLegalAccepted.value) ==
                EnterRaceLegalGateDecision.CONTINUE -> {
                showBoatConfirmDialog.value = true
            }

            else -> {
                pendingEnterRaceAfterLegal = true
                val generation = beginEnterRaceServerCheck()
                fetchRaceLegalText(generation)
            }
        }
    }

    private fun baseServerUrlForAccess(access: EventAccessKey): String {
        val server = access.server.trim()
        return if (server.endsWith("/ingest")) {
            server.removeSuffix("/ingest")
        } else {
            server.trimEnd('/')
        }
    }

    private fun continueAfterRecommendedEventUpdate() {
        showEventUpdateRecommendedDialog.value = false
        val access = eventCompatibilityWarningAccess ?: return
        if (access != currentEventAccessKey()) {
            return
        }

        eventCompatibilityWarningAccess = null
        eventCompatibilityAllowedAccess = access
        fetchRaceDataForDisplay()
        startRaceDataRefresh()
        val enterRaceGeneration = if (pendingEnterRaceAfterLegal) {
            beginEnterRaceServerCheck()
        } else {
            null
        }
        fetchRaceLegalTextAfterCompatibility(
            currentEventCompatibilityContext(access),
            enterRaceGeneration
        )
    }

    private fun fetchRaceLegalText(enterRaceServerCheckGeneration: Long? = null) {
        val access = currentEventAccessKey() ?: return
        val generation = eventCompatibilityGeneration
        val compatibilityContext = EventCompatibilityContext(
            access = access,
            generation = generation
        )

        when {
            eventCompatibilityAllowedAccess == access -> {
                fetchRaceDataForDisplay()
                startRaceDataRefresh()
                fetchRaceLegalTextAfterCompatibility(
                    compatibilityContext,
                    enterRaceServerCheckGeneration
                )
                return
            }

            eventCompatibilityBlockedAccess == access -> {
                finishEnterRaceServerCheck(enterRaceServerCheckGeneration)
                pendingEnterRaceAfterLegal = false
                showEventUpdateRequiredDialog.value = true
                return
            }

            eventCompatibilityWarningAccess == access -> {
                finishEnterRaceServerCheck(enterRaceServerCheckGeneration)
                showEventUpdateRecommendedDialog.value = true
                return
            }

            eventCompatibilityCheckAccess == access &&
                eventCompatibilityCheckGeneration == generation -> {
                if (
                    enterRaceServerCheckGeneration != null &&
                    enterRaceServerCheckState.isActive(enterRaceServerCheckGeneration)
                ) {
                    eventCompatibilityEnterRaceGeneration = enterRaceServerCheckGeneration
                }
                return
            }
        }

        eventCompatibilityCheckAccess = access
        eventCompatibilityCheckGeneration = generation
        eventCompatibilityEnterRaceGeneration = enterRaceServerCheckGeneration
            ?.takeIf(enterRaceServerCheckState::isActive)

        thread {
            val metadata = fetchServerMetadata(
                server = access.server,
                eventName = access.event,
                sharedSecret = access.secret
            )
            val status = metadata?.let { serverMetadata ->
                evaluateClientVersionStatus(
                    client = currentClientBuildIdentity(),
                    serverMetadata = serverMetadata
                )
            }
            val decision = eventCompatibilityDecision(status)

            if (!asyncLifetime.isActive()) return@thread
            runOnUiThread {
                if (!asyncLifetime.isActive()) return@runOnUiThread
                if (
                    !shouldApplyEventCompatibilityResult(
                        requestedAccess = access,
                        requestedGeneration = generation,
                        currentAccess = currentEventAccessKey(),
                        currentGeneration = eventCompatibilityGeneration
                    )
                ) {
                    if (
                        eventCompatibilityCheckAccess == access &&
                        eventCompatibilityCheckGeneration == generation
                    ) {
                        eventCompatibilityCheckAccess = null
                        eventCompatibilityCheckGeneration = -1L
                        eventCompatibilityEnterRaceGeneration = null
                    }
                    return@runOnUiThread
                }

                val associatedEnterRaceGeneration = eventCompatibilityEnterRaceGeneration
                    .takeIf {
                        eventCompatibilityCheckAccess == access &&
                            eventCompatibilityCheckGeneration == generation
                    }
                eventCompatibilityCheckAccess = null
                eventCompatibilityCheckGeneration = -1L
                eventCompatibilityEnterRaceGeneration = null

                val effectiveEnterRaceGeneration =
                    associatedEnterRaceGeneration
                        ?: enterRaceServerCheckGeneration
                        ?: enterRaceServerCheckState.currentGeneration()
                            .takeIf { pendingEnterRaceAfterLegal }
                if (
                    effectiveEnterRaceGeneration != null &&
                    !enterRaceServerCheckState.isActive(effectiveEnterRaceGeneration)
                ) {
                    return@runOnUiThread
                }

                when (decision) {
                    EventCompatibilityDecision.PROCEED -> {
                        eventCompatibilityAllowedAccess = access
                        eventCompatibilityWarningAccess = null
                        eventCompatibilityBlockedAccess = null
                        fetchRaceDataForDisplay()
                        startRaceDataRefresh()
                        fetchRaceLegalTextAfterCompatibility(
                            compatibilityContext,
                            effectiveEnterRaceGeneration
                        )
                    }

                    EventCompatibilityDecision.WARN -> {
                        finishEnterRaceServerCheck(effectiveEnterRaceGeneration)
                        eventCompatibilityAllowedAccess = null
                        eventCompatibilityWarningAccess = access
                        eventCompatibilityBlockedAccess = null
                        showEventUpdateRecommendedDialog.value = true
                    }

                    EventCompatibilityDecision.BLOCK -> {
                        finishEnterRaceServerCheck(effectiveEnterRaceGeneration)
                        pendingEnterRaceAfterLegal = false
                        eventCompatibilityAllowedAccess = null
                        eventCompatibilityWarningAccess = null
                        eventCompatibilityBlockedAccess = access
                        showEventUpdateRequiredDialog.value = true
                    }
                }
            }
        }
    }

    private fun effectiveEnterRaceGenerationForLegalFetch(
        compatibilityContext: EventCompatibilityContext,
        requestedGeneration: Long?
    ): Long? = activeLegalFetchEnterRaceGeneration
        .takeIf { activeLegalFetchContext == compatibilityContext }
        ?: requestedGeneration

    private fun fetchRaceLegalTextAfterCompatibility(
        compatibilityContext: EventCompatibilityContext,
        enterRaceServerCheckGeneration: Long? = null
    ) {
        if (!isCurrentAllowedLegalContext(compatibilityContext)) return
        if (!eventLegalFlowState.tryStartFetch(compatibilityContext)) {
            if (
                enterRaceServerCheckGeneration != null &&
                activeLegalFetchContext == compatibilityContext &&
                enterRaceServerCheckState.isActive(enterRaceServerCheckGeneration)
            ) {
                activeLegalFetchEnterRaceGeneration = enterRaceServerCheckGeneration
            }
            return
        }

        activeLegalFetchContext = compatibilityContext
        activeLegalFetchEnterRaceGeneration = enterRaceServerCheckGeneration

        val previouslyAccepted = raceLegalAccepted.value
        val previousLegalEventIdentity = raceLegalResolvedEventName
        val previousLegalHash = raceLegalHash.value
        val access = compatibilityContext.access

        raceLegalStatusText.value = getString(R.string.loading_race_legal)

        thread {
            var serverResponded = false
            try {
                val url = buildNormalApiGetUrl(
                    baseUrl = baseServerUrlForAccess(access),
                    path = "/event/legal",
                    eventName = access.event
                )

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("x-shared-secret", access.secret)
                connection.setRequestProperty("x-api-version", RegattaTrackingService.API_VERSION)

                val responseCode = connection.responseCode
                serverResponded = true
                if (asyncLifetime.isActive()) {
                    ServerConnectionStateStore.markReachable(this, access.server)
                }
                val body = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }

                connection.disconnect()

                if (responseCode !in 200..299) {
                    runOnUiThread {
                        if (!asyncLifetime.isActive()) return@runOnUiThread
                        if (!isCurrentAllowedLegalContext(compatibilityContext)) {
                            return@runOnUiThread
                        }
                        val effectiveEnterRaceGeneration =
                            effectiveEnterRaceGenerationForLegalFetch(
                                compatibilityContext,
                                enterRaceServerCheckGeneration
                            )
                        if (
                            effectiveEnterRaceGeneration != null &&
                            !enterRaceServerCheckState.isActive(effectiveEnterRaceGeneration)
                        ) {
                            return@runOnUiThread
                        }
                        finishEnterRaceServerCheck(effectiveEnterRaceGeneration)
                        raceLegalStatusText.value =
                            getString(R.string.legal_text_failed_code, responseCode, body.take(160))
                        raceLegalAccepted.value = false
                        val decision = enterRaceLegalFetchDecision(
                            serverResponded = true,
                            responseSuccessful = false,
                            documentValid = false,
                            acceptancePreserved = false
                        )
                        if (
                            pendingEnterRaceAfterLegal &&
                            decision == EnterRaceLegalGateDecision.BLOCK
                        ) {
                            blockPendingEnterRaceWithLegalError()
                        } else {
                            currentScreen.value = Screen.RACE
                        }
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
                    if (!asyncLifetime.isActive()) return@runOnUiThread
                    if (!isCurrentAllowedLegalContext(compatibilityContext)) {
                        return@runOnUiThread
                    }
                    val effectiveEnterRaceGeneration =
                        effectiveEnterRaceGenerationForLegalFetch(
                            compatibilityContext,
                            enterRaceServerCheckGeneration
                        )
                    if (
                        effectiveEnterRaceGeneration != null &&
                        !enterRaceServerCheckState.isActive(effectiveEnterRaceGeneration)
                    ) {
                        return@runOnUiThread
                    }
                    finishEnterRaceServerCheck(effectiveEnterRaceGeneration)

                    when {
                        legalResolvedEventName.isBlank() -> {
                            raceLegalStatusText.value = getString(R.string.race_notice_identity_missing)
                            raceLegalAccepted.value = false
                            raceLegalResolvedEventName = ""
                            eventLegalFlowState.clearDisplayedDocument()
                            blockPendingEnterRaceWithLegalError()
                        }

                        legalText.isBlank() -> {
                            raceLegalStatusText.value = getString(R.string.race_notice_empty)
                            raceLegalAccepted.value = false
                            eventLegalFlowState.clearDisplayedDocument()
                            blockPendingEnterRaceWithLegalError()
                        }

                        legalHash.isBlank() -> {
                            raceLegalStatusText.value = getString(R.string.race_notice_hash_missing)
                            raceLegalAccepted.value = false
                            eventLegalFlowState.clearDisplayedDocument()
                            blockPendingEnterRaceWithLegalError()
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
                                getString(R.string.race_notice_accepted)
                            } else {
                                ""
                            }
                            raceLegalAccepted.value = preserveAcceptance
                            eventLegalFlowState.display(
                                EventLegalDocumentContext(
                                    compatibility = compatibilityContext,
                                    resolvedEventName = legalResolvedEventName,
                                    legalHash = legalHash
                                )
                            )

                            when (
                                enterRaceLegalFetchDecision(
                                    serverResponded = true,
                                    responseSuccessful = true,
                                    documentValid = true,
                                    acceptancePreserved = preserveAcceptance
                                )
                            ) {
                                EnterRaceLegalGateDecision.CONTINUE -> {
                                    if (pendingEnterRaceAfterLegal) {
                                        continuePendingEnterRaceAfterLegal()
                                    } else {
                                        currentScreen.value = Screen.RACE_LEGAL
                                    }
                                }

                                EnterRaceLegalGateDecision.SHOW_LEGAL -> {
                                    currentScreen.value = Screen.RACE_LEGAL
                                }

                                EnterRaceLegalGateDecision.FETCH_LEGAL,
                                EnterRaceLegalGateDecision.BLOCK -> {
                                    pendingEnterRaceAfterLegal = false
                                    currentScreen.value = Screen.RACE
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!serverResponded && asyncLifetime.isActive()) {
                    ServerConnectionStateStore.markNoConnection(this, access.server)
                }
                runOnUiThread {
                    if (!asyncLifetime.isActive()) return@runOnUiThread
                    if (!isCurrentAllowedLegalContext(compatibilityContext)) {
                        return@runOnUiThread
                    }
                    val effectiveEnterRaceGeneration =
                        effectiveEnterRaceGenerationForLegalFetch(
                            compatibilityContext,
                            enterRaceServerCheckGeneration
                        )
                    if (
                        effectiveEnterRaceGeneration != null &&
                        !enterRaceServerCheckState.isActive(effectiveEnterRaceGeneration)
                    ) {
                        return@runOnUiThread
                    }
                    finishEnterRaceServerCheck(effectiveEnterRaceGeneration)
                    updateConnectionUiState()
                    raceLegalStatusText.value = if (serverResponded) {
                        getString(R.string.legal_text_failed, e.message ?: "")
                    } else {
                        getString(R.string.status_no_connection)
                    }
                    raceLegalAccepted.value = false

                    val decision = enterRaceLegalFetchDecision(
                        serverResponded = serverResponded,
                        responseSuccessful = false,
                        documentValid = false,
                        acceptancePreserved = false
                    )
                    if (
                        pendingEnterRaceAfterLegal &&
                        decision == EnterRaceLegalGateDecision.CONTINUE
                    ) {
                        continuePendingEnterRaceAfterLegal()
                    } else if (pendingEnterRaceAfterLegal) {
                        blockPendingEnterRaceWithLegalError()
                    } else {
                        currentScreen.value = Screen.RACE
                    }
                }
            } finally {
                runOnUiThread {
                    if (!asyncLifetime.isActive()) return@runOnUiThread
                    if (activeLegalFetchContext == compatibilityContext) {
                        activeLegalFetchContext = null
                        activeLegalFetchEnterRaceGeneration = null
                    }
                    eventLegalFlowState.finishFetch(compatibilityContext)
                }
            }
        }
    }

    private fun acceptRaceLegalAndLoadRaceData() {
        val document = eventLegalFlowState.displayedDocument ?: return
        if (!isCurrentActionableLegalDocument(document)) {
            pendingEnterRaceAfterLegal = false
            raceLegalAccepted.value = false
            raceLegalAcceptStatusText.value = getString(R.string.race_notice_changed)
            currentScreen.value = Screen.RACE
            return
        }
        if (!eventLegalFlowState.tryStartAccept(document)) return

        val access = document.compatibility.access
        val acceptedLegalHash = document.legalHash
        val expectedResolvedEventName = document.resolvedEventName
        val acceptedBoatSetup = currentBoatSetupValues()

        raceLegalAcceptStatusText.value = getString(R.string.accepting_race_notice)

        thread {
            var serverResponded = false
            try {
                val url = "${baseServerUrlForAccess(access)}/event/legal/accept"

                val json = JSONObject().apply {
                    put("event_name", access.event)
                    put("sail_number", acceptedBoatSetup.sailNumber)
                    put("boat_name", acceptedBoatSetup.boatName)
                    put("captain_name", acceptedBoatSetup.skipperName)
                    put("legal_text_hash", acceptedLegalHash)
                }

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.doOutput = true

                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("X-Shared-Secret", access.secret)
                connection.setRequestProperty("x-api-version", RegattaTrackingService.API_VERSION)

                connection.outputStream.use { outputStream ->
                    outputStream.write(json.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                serverResponded = true
                if (asyncLifetime.isActive()) {
                    ServerConnectionStateStore.markReachable(this, access.server)
                }
                val body = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }

                connection.disconnect()

                runOnUiThread {
                    if (!asyncLifetime.isActive()) return@runOnUiThread
                    if (
                        !isCurrentActionableLegalDocument(document) ||
                        !hasSameLegalBoatIdentity(currentBoatSetupValues(), acceptedBoatSetup)
                    ) {
                        return@runOnUiThread
                    }

                    if (responseCode in 200..299) {
                        val acceptedResolvedEventName = try {
                            JSONObject(body).optString("event_name", "").trim()
                        } catch (_: Exception) {
                            ""
                        }

                        if (
                            acceptedResolvedEventName.isBlank() ||
                            acceptedResolvedEventName != expectedResolvedEventName
                        ) {
                            pendingEnterRaceAfterLegal = false
                            resetRaceLegalState()
                            raceLegalAcceptStatusText.value =
                                getString(R.string.race_notice_changed)
                            currentScreen.value = Screen.RACE
                            fetchRaceLegalText()
                        } else {
                            raceLegalAccepted.value = true
                            raceLegalAcceptStatusText.value = getString(R.string.race_notice_accepted)
                            fetchRaceDataForDisplay()
                            startRaceDataRefresh()
                            if (pendingEnterRaceAfterLegal) {
                                continuePendingEnterRaceAfterLegal()
                            } else {
                                currentScreen.value = Screen.RACE
                            }
                        }
                    } else {
                        raceLegalAccepted.value = false
                        raceLegalAcceptStatusText.value =
                            getString(R.string.accept_failed_code, responseCode, body.take(160))
                    }
                }
            } catch (e: Exception) {
                if (!serverResponded && asyncLifetime.isActive()) {
                    ServerConnectionStateStore.markNoConnection(this, access.server)
                }
                runOnUiThread {
                    if (!asyncLifetime.isActive()) return@runOnUiThread
                    if (
                        !isCurrentActionableLegalDocument(document) ||
                        !hasSameLegalBoatIdentity(currentBoatSetupValues(), acceptedBoatSetup)
                    ) {
                        return@runOnUiThread
                    }
                    updateConnectionUiState()
                    raceLegalAccepted.value = false
                    raceLegalAcceptStatusText.value = if (serverResponded) {
                        getString(R.string.accept_failed, e.message ?: "")
                    } else {
                        getString(R.string.status_no_connection)
                    }
                }
            } finally {
                runOnUiThread {
                    if (!asyncLifetime.isActive()) return@runOnUiThread
                    eventLegalFlowState.finishAccept(document)
                }
            }
        }
    }

    private fun updateLocalRaceStatus() {
        val prefs = getSharedPreferences("regatta_local_status", Context.MODE_PRIVATE)
        val currentLocaleTag = resources.configuration.locales[0].toLanguageTag()
        val previousLocaleTag = prefs.getString("display_locale_tag", "").orEmpty()
        if (previousLocaleTag != currentLocaleTag) {
            prefs.edit()
                .remove("debug_error_text")
                .putString("display_locale_tag", currentLocaleTag)
                .apply()
        }

        localIsOcs = prefs.getBoolean("is_ocs", false)
        localRaceFinished = prefs.getBoolean("race_finished", false)
        val raceStarted = prefs.getBoolean("race_started", false)
        val passedMarks = prefs.getInt("passed_marks", 0).coerceAtLeast(0)
        val activeCourseMarks = courseMapMarks.value.filterNot { it.skipped }

        val storedDistanceText = prefs.getString("dtl_text", "").orEmpty()
        val distanceMeters = firstLocalizedNumber(storedDistanceText)
        dtlText.value = if (distanceMeters != null) {
            getString(R.string.distance_meters, distanceMeters)
        } else {
            getString(R.string.distance_unknown)
        }
        ttlText.value = getString(R.string.ttl_unknown)
        ocsText.value = if (localIsOcs) {
            getString(R.string.ocs_yes)
        } else {
            getString(R.string.ocs_no)
        }

        currentTargetText.value = when {
            localRaceFinished -> getString(R.string.next_finished)
            localIsOcs -> getString(R.string.next_return_start)
            !raceStarted -> getString(R.string.next_start_line)
            else -> {
                val mark = activeCourseMarks.getOrNull(passedMarks)
                if (mark != null) {
                    val order = mark.order ?: (passedMarks + 1)
                    val markName = mark.label
                        .removePrefix("$order ")
                        .trim()
                        .ifBlank { mark.label }
                    getString(R.string.next_mark_value, order, markName)
                } else {
                    getString(R.string.next_finish_line)
                }
            }
        }

        progressText.value = buildLocalProgressText(
            totalMarks = activeCourseMarks.size,
            passedMarks = passedMarks,
            raceStarted = raceStarted,
            raceFinished = localRaceFinished,
            markedFormatter = { passed, total, percent ->
                getString(R.string.progress_value, passed, total, percent)
            },
            directFormatter = { percent ->
                getString(R.string.progress_direct_value, percent)
            }
        )

        val boatStatus = when {
            localRaceFinished -> getString(R.string.boat_status_finished)
            localIsOcs -> getString(R.string.boat_status_ocs)
            raceStarted -> getString(R.string.boat_status_racing)
            else -> getString(R.string.boat_status_not_started)
        }
        boatRaceStatusText.value = getString(R.string.boat_status_value, boatStatus)

        val debugError = prefs.getString("debug_error_text", "") ?: ""
        debugErrorText.value = if (debugError.isBlank()) {
            getString(R.string.last_error_unknown)
        } else {
            getString(R.string.last_error_value, debugError)
        }

        updateStartPanelStatus()
        updateAutoLeaveAfterFinish()
    }

    private fun firstLocalizedNumber(value: String): Double? {
        val raw = Regex("-?\\d+(?:[.,]\\d+)?")
            .find(value)
            ?.value
            ?: return null
        return raw.replace(',', '.').toDoubleOrNull()
    }

    private fun continueRaceAfterDetectedFinish() {
        if (!inRace.value) return

        val intent = Intent(this, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_CONTINUE_AFTER_FINISH
        }

        startService(intent)
    }

    private fun setCourseProgressFromUser(
        passedMarks: Int,
        raceStarted: Boolean
    ) {
        if (!inRace.value) return

        val intent = Intent(this, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_SET_COURSE_PROGRESS
            putExtra(RegattaTrackingService.EXTRA_PASSED_MARKS, passedMarks)
            putExtra(RegattaTrackingService.EXTRA_RACE_STARTED, raceStarted)
        }

        startService(intent)
    }

    private fun fallbackTargetText(): String {
        return when (startPanelMode.value) {
            "ocs" -> getString(R.string.next_return_start)
            "countdown" -> getString(R.string.next_start_line)
            "clear" -> getString(R.string.next_start_line)
            "postponed" -> getString(R.string.next_wait)
            "started" -> getString(R.string.next_course)
            else -> getString(R.string.next_unknown)
        }
    }

    private fun registerForRace(
        onSuccess: (() -> Unit)? = null
    ) {
        if (!setupConfirmed.value) {
            registerRaceStatusText.value = getString(R.string.confirm_boat_setup_first_period)
            return
        }

        if (!raceDataReady.value) {
            registerRaceStatusText.value = getString(R.string.load_valid_race_data_first)
            return
        }

        if (!raceLegalAccepted.value) {
            registerRaceStatusText.value = getString(R.string.race_accept_legal_first)
            return
        }

        val registrationTimestamp = RaceRegistrationPolicy.registrationTimestamp(
            rawRaceStart.ifBlank { legacyDisplayPayload(raceStartText.value) }
        )
        if (registrationTimestamp == null) {
            registerRaceStatusText.value = getString(R.string.load_valid_race_start_first)
            return
        }

        val access = currentEventAccessKey() ?: return
        val registrationBoatSetup = currentBoatSetupValues()
        registerRaceStatusText.value = getString(R.string.registering)

        thread {
            var serverResponded = false
            try {
                val url = "${baseServerUrlForAccess(access)}/ingest"

                val json = JSONObject().apply {
                    put("sequence_id", System.currentTimeMillis())
                    put("timestamp", registrationTimestamp)

                    put("boat_name", registrationBoatSetup.boatName)
                    put("captain_name", registrationBoatSetup.skipperName)
                    put("hull_color", registrationBoatSetup.hullColor)
                    put("sail_number", registrationBoatSetup.sailNumber)
                    put("yardstick", registrationBoatSetup.yardstick.toDoubleOrNull() ?: 0.0)
                    put("boat_type", registrationBoatSetup.boatType)

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
                connection.setRequestProperty("x-event-name", access.event)
                connection.setRequestProperty("x-shared-secret", access.secret)
                connection.setRequestProperty("x-api-version", RegattaTrackingService.API_VERSION)

                connection.outputStream.use { outputStream ->
                    outputStream.write(json.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                serverResponded = true
                if (asyncLifetime.isActive()) {
                    ServerConnectionStateStore.markReachable(this, access.server)
                }
                val body = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }

                connection.disconnect()

                runOnUiThread {
                    if (!asyncLifetime.isActive()) return@runOnUiThread
                    if (currentBoatSetupValues() != registrationBoatSetup) {
                        return@runOnUiThread
                    }

                    if (responseCode in 200..299) {
                        raceRegistered.value = true
                        registerRaceStatusText.value = getString(R.string.registered_for_race)
                        onSuccess?.invoke()
                    } else {
                        registerRaceStatusText.value =
                            getString(R.string.registration_failed_code, responseCode, body.take(120))
                    }
                }
            } catch (e: Exception) {
                if (!serverResponded && asyncLifetime.isActive()) {
                    ServerConnectionStateStore.markNoConnection(this, access.server)
                }
                runOnUiThread {
                    if (!asyncLifetime.isActive()) return@runOnUiThread
                    if (currentBoatSetupValues() != registrationBoatSetup) {
                        return@runOnUiThread
                    }
                    updateConnectionUiState()
                    registerRaceStatusText.value = if (serverResponded) {
                        getString(R.string.registration_failed, e.message ?: "")
                    } else {
                        getString(R.string.status_no_connection)
                    }
                }
            }
        }
    }

    private fun updateAutoLeaveAfterFinish() {
        val isFinished = localRaceFinished

        if (inRace.value && isFinished) {
            if (!showFinishDetectedDialog.value) {
                showFinishDetectedDialog.value = true
            }
        } else {
            showFinishDetectedDialog.value = false
        }
    }

    private fun updateStartPanelStatus() {
        val isOcs = localIsOcs

        if (isOcs) {
            val startMillis = raceStartEpochMillis
            val remainingSeconds = if (startMillis != null) {
                (startMillis - System.currentTimeMillis()) / 1000L
            } else {
                null
            }

            if (remainingSeconds != null && remainingSeconds > 0L) {
                val minutes = remainingSeconds / 60L
                val seconds = remainingSeconds % 60L

                startPanelText.value = String.format(Locale.US, "%d:%02d", minutes, seconds)
                startPanelMode.value = "ocs_countdown"
            } else {
                startPanelText.value = getString(R.string.ocs)
                startPanelMode.value = "ocs"
            }
            return
        }

        if (currentRaceStatus.equals("finished", ignoreCase = true)) {
            startPanelText.value = getString(R.string.finished)
            startPanelMode.value = "finished"
            return
        }

        if (currentRaceStatus.equals("postponed", ignoreCase = true)) {
            startPanelText.value = getString(R.string.postponed)
            startPanelMode.value = "postponed"
            return
        }

        val startMillis = raceStartEpochMillis

        if (startMillis == null) {
            startPanelText.value = getString(R.string.app_name)
            startPanelMode.value = "clear"
            return
        }

        val remainingSeconds = (startMillis - System.currentTimeMillis()) / 1000L

        when {
            remainingSeconds > 600L -> {
                startPanelText.value = getString(R.string.app_name)
                startPanelMode.value = "clear"
            }

            remainingSeconds > 0L -> {
                val minutes = remainingSeconds / 60L
                val seconds = remainingSeconds % 60L

                startPanelText.value = getString(R.string.start_in, minutes, seconds)
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

    private fun updateConnectionUiState() {
        val server = raceServer.value
        if (server.isBlank()) {
            serverNoConnection.value = false
            return
        }

        val connectionState = ServerConnectionStateStore.state(this, server)
        if (
            !ServerConnectionStateStore.hasActiveNetwork(this) &&
            connectionState != ServerConnectionState.REACHABLE
        ) {
            ServerConnectionStateStore.markNoConnection(this, server)
        }

        serverNoConnection.value =
            ServerConnectionStateStore.state(this, server) == ServerConnectionState.NO_CONNECTION
    }

    private fun startRaceDataRefresh() {
        if (!asyncLifetime.isActive()) return
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

    private fun storeRaceEntrySample(): Boolean {
        val entry = buildRaceEntrySample(
            rawRaceStart = rawRaceStart.ifBlank { legacyDisplayPayload(raceStartText.value) },
            boatSetup = currentBoatSetupValues()
        ) ?: run {
            statusText.value = getString(R.string.load_valid_race_start_first)
            return false
        }

        val accessContextId = db.getOrCreateAccessContext(
            serverUrl = raceServer.value,
            accessIdentifier = raceEvent.value,
            accessSecret = raceSecret.value
        ) ?: run {
            statusText.value = getString(R.string.race_entry_store_failed)
            return false
        }

        val insertedId = db.insertSample(
            sequenceId = entry.sequenceId,
            timestamp = entry.timestamp,
            boatName = entry.boatName,
            captainName = entry.captainName,
            hullColor = entry.hullColor,
            sailNumber = entry.sailNumber,
            yardstick = entry.yardstick,
            boatType = entry.boatType,
            lat = entry.lat,
            lon = entry.lon,
            accuracy = entry.accuracy,
            cog = entry.cog,
            sog = entry.sog,
            accelX = entry.accelX,
            accelY = entry.accelY,
            accelZ = entry.accelZ,
            gyroX = entry.gyroX,
            gyroY = entry.gyroY,
            gyroZ = entry.gyroZ,
            accessContextId = accessContextId
        )

        if (insertedId == -1L) {
            statusText.value = getString(R.string.race_entry_store_failed)
            return false
        }

        runCatching { TelemetryUploadScheduler.enqueue(this) }
        return true
    }

    private fun enterRace() {
        if (!raceDataReady.value) {
            raceStatusText.value = getString(R.string.race_load_valid_data_first)
            return
        }
        if (!setupConfirmed.value) {
            statusText.value = getString(R.string.confirm_boat_setup_first)
            return
        }
        if (!canEnterRaceNow()) {
            raceStatusText.value = getString(R.string.race_load_valid_data_first)
            return
        }

        if (manualTracking.value) {
            manualTracking.value = false
            saveAppState()
            stopRegattaForegroundService()
        }

        if (!storeRaceEntrySample()) return

        inRace.value = true
        refreshRetirementReportedState()
        statusText.value = getString(R.string.in_race)
        serviceStatusText.value = getString(R.string.service_starting)
        saveAppState()

        fetchRaceDataForDisplay()
        startRaceDataRefresh()
        startRegattaForegroundService(manualMode = false)

        currentScreen.value = Screen.HOME
    }

    private fun currentRetirementIdentity(): ParticipantRetirementIdentity =
        ParticipantRetirementIdentity(
            sailNumber = sailNumber.value,
            boatName = boatName.value,
            captainName = skipperName.value
        )

    private fun refreshRetirementReportedState() {
        val resolved = resolvedEventName.value.trim()
        retirementReported.value =
            resolved.isNotBlank() &&
                ParticipantRetirementStore.matches(
                    context = this,
                    resolvedEventName = resolved,
                    identity = currentRetirementIdentity()
                )
    }

    private fun isCurrentRetirementRequest(
        access: EventAccessKey,
        identity: ParticipantRetirementIdentity
    ): Boolean =
        inRace.value &&
            currentEventAccessKey() == access &&
            currentRetirementIdentity() == identity

    private fun reportRetirement() {
        if (retirementRequestInFlight.value) return

        val access = currentEventAccessKey() ?: return
        val identity = currentRetirementIdentity()
        if (!identity.isComplete()) {
            retirementStatusText.value = getString(R.string.confirm_boat_setup_first_period)
            return
        }

        retirementRequestInFlight.value = true
        retirementStatusText.value = getString(R.string.retire_reporting)

        thread {
            var serverResponded = false
            try {
                val url = "${baseServerUrlForAccess(access)}/event/participant/retire"
                val payload = buildParticipantRetirementPayload(access.event, identity)

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("x-shared-secret", access.secret)
                connection.setRequestProperty("x-api-version", RegattaTrackingService.API_VERSION)

                connection.outputStream.use {
                    it.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                serverResponded = true
                if (asyncLifetime.isActive()) {
                    ServerConnectionStateStore.markReachable(this, access.server)
                }
                val body = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }
                connection.disconnect()

                if (responseCode in 200..299) {
                    val receipt = parseParticipantRetirementReceipt(body, identity)
                    if (receipt != null) {
                        ParticipantRetirementStore.save(applicationContext, receipt)
                    }
                    runOnUiThread {
                        if (!asyncLifetime.isActive()) return@runOnUiThread
                        if (!isCurrentRetirementRequest(access, identity)) return@runOnUiThread
                        if (receipt == null) {
                            retirementStatusText.value = getString(R.string.retire_response_invalid)
                        } else {
                            refreshRetirementReportedState()
                            retirementStatusText.value = ""
                        }
                    }
                } else {
                    runOnUiThread {
                        if (!asyncLifetime.isActive()) return@runOnUiThread
                        if (!isCurrentRetirementRequest(access, identity)) return@runOnUiThread
                        retirementStatusText.value =
                            getString(R.string.retire_failed_code, responseCode, body.take(120))
                    }
                }
            } catch (e: Exception) {
                if (!serverResponded && asyncLifetime.isActive()) {
                    ServerConnectionStateStore.markNoConnection(this, access.server)
                }
                runOnUiThread {
                    if (!asyncLifetime.isActive()) return@runOnUiThread
                    if (!isCurrentRetirementRequest(access, identity)) return@runOnUiThread
                    updateConnectionUiState()
                    retirementStatusText.value = if (serverResponded) {
                        getString(R.string.retire_failed, e.message ?: "")
                    } else {
                        getString(R.string.status_no_connection)
                    }
                }
            } finally {
                runOnUiThread {
                    if (asyncLifetime.isActive()) {
                        retirementRequestInFlight.value = false
                    }
                }
            }
        }
    }

    private fun leaveRace() {


        retirementStatusText.value = ""
        inRace.value = false
        manualTracking.value = false
        saveAppState()

        statusText.value = getString(R.string.race_left)
        serviceStatusText.value = getString(R.string.service_stopped)

        stopRegattaForegroundService()

        currentScreen.value = Screen.HOME
    }

    private fun startManualTracking() {
        if (inRace.value) return

        manualTracking.value = true
        statusText.value = getString(R.string.manual_tracking_running)
        serviceStatusText.value = getString(R.string.service_manual_running)
        saveAppState()
        startRegattaForegroundService(manualMode = true)
    }

    private fun stopManualTracking() {
        manualTracking.value = false
        saveAppState()
        statusText.value = getString(R.string.manual_tracking_stopped)
        serviceStatusText.value = getString(R.string.service_stopped)
        stopRegattaForegroundService()
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
            getString(R.string.service_manual_running)
        } else {
            getString(R.string.service_race_running)
        }
    }

    private fun stopRegattaForegroundService() {
        val intent = Intent(this, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_STOP
        }

        startService(intent)
        serviceStatusText.value = getString(R.string.service_stopped)
    }

    private fun fetchEventResults() {
        if (resultsFetchRunning) return
        val access = currentEventAccessKey() ?: return
        resultsFetchRunning = true

        resultsStatusText.value = getString(R.string.loading_results)

        thread {
            try {
                val url = buildNormalApiGetUrl(
                    baseUrl = baseServerUrlForAccess(access),
                    path = "/event-results",
                    eventName = access.event
                )

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("x-shared-secret", access.secret)
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
                        if (!asyncLifetime.isActive()) return@runOnUiThread
                        resultsStatusText.value = getString(R.string.results_failed_code, responseCode, body.take(160))
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
                    if (!asyncLifetime.isActive()) return@runOnUiThread
                    resultsPublished.value = published
                    resultsPublishedAt.value = publishedAt
                    resultRows.value = rows
                    resultsStatusText.value = if (published) {
                        getString(R.string.results_loaded)
                    } else {
                        getString(R.string.results_not_published)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!asyncLifetime.isActive()) return@runOnUiThread
                    resultsStatusText.value = getString(R.string.results_failed, e.message ?: "")
                    resultsPublished.value = false
                    resultRows.value = emptyList()
                }
            } finally {
                runOnUiThread {
                    if (!asyncLifetime.isActive()) return@runOnUiThread
                    resultsFetchRunning = false
                }
            }
        }
    }

    private fun fetchRaceDataForDisplay() {
        val access = currentEventAccessKey() ?: return
        val request = raceDataRequestGate.tryStart(
            access = access,
            generation = eventCompatibilityGeneration
        ) ?: return
        val snapshotGeneration = RaceEventSnapshotStore.generation(this)

        thread {
            var serverResponded = false
            try {
                val url = buildNormalApiGetUrl(
                    baseUrl = baseServerUrlForAccess(access),
                    path = "/event",
                    eventName = access.event
                )

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.setRequestProperty("accept", "application/json")
                connection.setRequestProperty("x-event-name", access.event)
                connection.setRequestProperty("x-shared-secret", access.secret)
                connection.setRequestProperty(
                    "x-api-version",
                    RegattaTrackingService.API_VERSION
                )

                val responseCode = connection.responseCode
                serverResponded = true

                val body = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }
                connection.disconnect()

                if (responseCode !in 200..299) {
                    runOnUiThread {
                        if (!asyncLifetime.isActive()) return@runOnUiThread
                        if (
                            !raceDataRequestGate.isCurrent(
                                request = request,
                                currentAccess = currentEventAccessKey(),
                                currentGeneration = eventCompatibilityGeneration
                            )
                        ) {
                            return@runOnUiThread
                        }
                        ServerConnectionStateStore.markReachable(this, access.server)
                        updateConnectionUiState()
                        if (shouldInvalidateEventSnapshotForHttpStatus(responseCode)) {
                            raceStatusText.value = getString(R.string.race_error_code, responseCode)
                            clearSavedRaceDataReady()
                        } else if (!raceDataReady.value) {
                            raceStatusText.value = getString(R.string.race_error_code, responseCode)
                        }
                    }
                    return@thread
                }

                val snapshot = parseRaceEventSnapshot(body)
                val json = JSONObject(body)
                val parsedStartFlags = parseRaceStartFlags(json)

                runOnUiThread {
                    if (!asyncLifetime.isActive()) return@runOnUiThread
                    if (
                        !raceDataRequestGate.isCurrent(
                            request = request,
                            currentAccess = currentEventAccessKey(),
                            currentGeneration = eventCompatibilityGeneration
                        )
                    ) {
                        return@runOnUiThread
                    }

                    ServerConnectionStateStore.markReachable(this, access.server)
                    val persisted = RaceEventSnapshotStore.saveIfGenerationUnchanged(
                        context = this,
                        server = access.server,
                        event = access.event,
                        secret = access.secret,
                        snapshot = snapshot,
                        expectedGeneration = snapshotGeneration
                    )
                    val displaySelection = resolveRaceEventDisplaySnapshot(
                        context = this,
                        access = access,
                        incomingSnapshot = snapshot,
                        incomingPersisted = persisted
                    ) ?: return@runOnUiThread
                    val displaySnapshot = displaySelection.snapshot

                    adoptResolvedEventName(displaySnapshot.resolvedEventName)
                    raceSeriesDisplayMetadata.value = displaySnapshot.seriesDisplayMetadata
                    rawRaceStatus = displaySnapshot.status
                    rawRaceStart = displaySnapshot.startRaw
                    rawRaceStop = displaySnapshot.stopRaw
                    rawRaceInfo = displaySnapshot.raceInfo
                    rawRaceCourseJson = displaySnapshot.courseJson
                    rawRaceCourseShortened = displaySnapshot.courseShortened
                    raceDataReady.value = true
                    if (displaySelection.useIncomingStartFlags) {
                        raceStartFlags.value = parsedStartFlags
                    }
                    renderRawRaceSetup()
                    updateStartPanelStatus()
                    updateLocalRaceStatus()
                    updateConnectionUiState()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (!asyncLifetime.isActive()) return@runOnUiThread
                    if (
                        !raceDataRequestGate.isCurrent(
                            request = request,
                            currentAccess = currentEventAccessKey(),
                            currentGeneration = eventCompatibilityGeneration
                        )
                    ) {
                        return@runOnUiThread
                    }
                    if (serverResponded) {
                        ServerConnectionStateStore.markReachable(this, access.server)
                    } else {
                        ServerConnectionStateStore.markNoConnection(this, access.server)
                    }
                    updateConnectionUiState()
                    if (!raceDataReady.value) {
                        raceStatusText.value = if (serverResponded) {
                            getString(R.string.race_response_invalid)
                        } else {
                            getString(R.string.race_first_load_online_required)
                        }
                    }
                }
            } finally {
                runOnUiThread {
                    if (!asyncLifetime.isActive()) return@runOnUiThread
                    raceDataRequestGate.finish(request)
                }
            }
        }
    }

    private fun buildCourseMapUrl(view: CourseMapView? = null): String {
        return buildCourseMapImageUrl(
            baseUrl = getBaseServerUrl(),
            eventName = raceEvent.value,
            view = view
        )
    }

    private fun buildCourseMapMarks(
        courseObj: JSONObject?,
        courseShortened: Boolean
    ): List<CourseMapMark> {
        val marks = courseObj?.optJSONArray("marks") ?: return emptyList()
        val result = mutableListOf<CourseMapMark>()

        for (i in 0 until marks.length()) {
            val mark = marks.optJSONObject(i) ?: continue
            val order = if (mark.has("order") && !mark.isNull("order")) {
                mark.optInt("order").takeIf { it > 0 }
            } else {
                null
            }
            val displayOrder = order ?: (i + 1)
            val name = mark.optString("name", "Mark")
            val skipped = courseShortened && mark.optBoolean("omit_when_shortened", false)

            result.add(
                CourseMapMark(
                    order = order,
                    label = "$displayOrder $name",
                    skipped = skipped
                )
            )
        }

        return result
    }

    private fun buildCourseSummary(
        courseObj: JSONObject?,
        courseShortened: Boolean
    ): CourseSummary {
        if (courseObj == null) {
            return CourseSummary(
                courseText = getString(R.string.course_unknown),
                startLineText = getString(R.string.start_line_unknown),
                finishLineText = getString(R.string.finish_line_unknown),
                marksText = getString(R.string.marks_unknown)
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
                    getString(R.string.mark_skipped_compact, order, name)
                } else {
                    "$order $name"
                }

                markNames.add(label)
            }
        }

        val markCount = marks?.length() ?: 0

        return CourseSummary(
            courseText = getString(R.string.course_mark_count, markCount),
            startLineText = getString(R.string.start_line_value, startRefLabel, startMarkLabel),
            finishLineText = getString(R.string.finish_line_value, finishRefLabel, finishMarkLabel),
            marksText = if (markNames.isEmpty()) {
                getString(R.string.marks_unknown)
            } else {
                getString(R.string.marks_value, markNames.joinToString(", "))
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
            statusText.value = getString(R.string.stop_tracking_before_delete)
            return
        }

        db.deleteAllSamples()
        updateStorageText()
        lastCsvLine.value = getString(R.string.no_csv_line_yet)
        statusText.value = getString(R.string.old_data_deleted)
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
            statusText.value = getString(R.string.no_gps_permission)
        } catch (e: Exception) {
            statusText.value = getString(R.string.gps_error, e.message ?: "")
        }
    }

    private fun updateGpsDisplay(location: Location) {
        val cog = location.bearing
        val sog = location.speed
        val accuracy = location.accuracy

        cogText.value = getString(R.string.cog_value, cog)
        sogText.value = getString(R.string.sog_value, sog)
        gpsAccuracyText.value = getString(R.string.gps_accuracy_value, accuracy)

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

        rowCountText.value = getString(R.string.rows_stored, total)
        pendingUploadCount.value = pending

        uploadStatusText.value = if (pending == 0L) {
            getString(R.string.upload_all_sent)
        } else {
            getString(R.string.upload_pending, pending)
        }
    }

    private fun exportCsvToUri(uri: Uri) {
        try {
            val csv = db.exportAllAsCsv()
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(csv.toByteArray(Charsets.UTF_8))
            }
            statusText.value = getString(R.string.csv_exported)
        } catch (e: Exception) {
            statusText.value = getString(R.string.csv_export_failed, e.message ?: "")
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
        asyncLifetime.invalidate()
        cancelEnterRaceServerCheck()
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
            Text(stringResource(R.string.ocs_detected))
        },
        text = {
            Text(
                text = stringResource(R.string.ocs_detected_message)
            )
        },
        confirmButton = {
            TextButton(onClick = onOk) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onContinueCourse) {
                Text(stringResource(R.string.ignore_continue_course))
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
            Text(stringResource(R.string.finish_detected))
        },
        text = {
            Text(
                text = stringResource(R.string.finish_detected_message)
            )
        },
        confirmButton = {
            TextButton(onClick = onStopTracking) {
                Text(stringResource(R.string.stop_tracking))
            }
        },
        dismissButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.continue_action))
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
            Text(stringResource(R.string.gps_tracking_consent))
        },
        text = {
            Text(
                text = stringResource(R.string.gps_tracking_consent_message)
            )
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.i_agree))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun LeaveRaceOptionsDialog(
    retireEnabled: Boolean,
    onRetire: () -> Unit,
    onLeaveRace: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.leave_race)) },
        text = { Text(stringResource(R.string.leave_race_choice_message)) },
        confirmButton = {
            TextButton(onClick = onRetire, enabled = retireEnabled) {
                Text(stringResource(R.string.retire))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onLeaveRace) {
                    Text(stringResource(R.string.leave_race))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}

@Composable
fun RetireConfirmDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.retire_confirm_title)) },
        text = { Text(stringResource(R.string.retire_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.retire_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
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
            Text(stringResource(R.string.is_this_your_boat))
        },
        text = {
            Text(
                text = stringResource(R.string.confirm_boat_message, boatName, skipperName, sailNumber, boatType, hullColor, yardstick)
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.enter_race))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
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
            Text(stringResource(R.string.clear_race_title))
        },
        text = {
            Text(
                text = stringResource(R.string.clear_race_message)
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.clear_race))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
