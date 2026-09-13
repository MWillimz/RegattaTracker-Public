from pathlib import Path


def read(path):
    return Path(path).read_text(encoding="utf-8")


def write(path, text):
    Path(path).write_text(text, encoding="utf-8")


def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one match, found {count}: {old[:100]!r}")
    write(path, text.replace(old, new, 1))


def replace_between(path, start, end, replacement):
    text = read(path)
    start_idx = text.find(start)
    if start_idx < 0:
        raise RuntimeError(f"{path}: start marker not found: {start!r}")
    end_idx = text.find(end, start_idx)
    if end_idx < 0:
        raise RuntimeError(f"{path}: end marker not found: {end!r}")
    write(path, text[:start_idx] + replacement + text[end_idx:])


MAIN = "app/src/main/java/de/williserv/regattaclient/MainActivity.kt"
HOME = "app/src/main/java/de/williserv/regattaclient/HomeScreen.kt"
SERVICE = "app/src/main/java/de/williserv/regattaclient/RegattaTrackingService.kt"
WORKER = "app/src/main/java/de/williserv/regattaclient/TelemetryUploadWorker.kt"
MANIFEST = "app/src/main/AndroidManifest.xml"
HOME_TEST = "app/src/test/java/de/williserv/regattaclient/HomeUploadStatusTest.kt"

# MainActivity: local readiness is independent from Legal/network.
replace_between(
    MAIN,
    "    private fun canEnterRaceNow(): Boolean {",
    "\n    private fun navigateBack()",
    """    private fun canEnterRaceNow(): Boolean {
        return canEnterRaceWithLocalState(
            raceDataReady = raceDataReady.value,
            setupConfirmed = setupConfirmed.value
        )
    }
""",
)

replace_between(
    MAIN,
    "    private val raceDataRefreshRunnable = object : Runnable {",
    "\n    private val permissionLauncher",
    """    private val raceDataRefreshRunnable = object : Runnable {
        override fun run() {
            if (currentEventAccessKey() != null) {
                fetchRaceDataForDisplay()
                handler.postDelayed(this, 10_000L)
            }
        }
    }
""",
)

replace_once(
    MAIN,
    """            updateStorageText()
            updateLocalRaceStatus()
            handler.postDelayed(this, 1000L)
""",
    """            updateStorageText()
            updateLocalRaceStatus()
            updateConnectionUiState()
            handler.postDelayed(this, 1000L)
""",
)

replace_once(
    MAIN,
    """    private val pendingUploadCount = mutableStateOf(0L)
    private val serviceStatusText = mutableStateOf(\"\")
""",
    """    private val pendingUploadCount = mutableStateOf(0L)
    private val serverNoConnection = mutableStateOf(false)
    private val serviceStatusText = mutableStateOf(\"\")
""",
)

replace_once(
    MAIN,
    """                            pendingUploadCount = pendingUploadCount.value,
                            debugErrorText = debugErrorText.value,
""",
    """                            pendingUploadCount = pendingUploadCount.value,
                            noConnection = serverNoConnection.value,
                            debugErrorText = debugErrorText.value,
""",
)

# Legal no longer gates Enter Race.
replace_once(
    MAIN,
    """                                    !raceLegalAccepted.value -> {
                                        raceStatusText.value = getString(R.string.race_accept_legal_first)
                                    }

                                    else -> {
""",
    """                                    else -> {
""",
)

# Boat confirmation starts the consent/local-entry path directly; no HTTP prerequisite.
replace_once(
    MAIN,
    """                            registerForRace(
                                onSuccess = {
                                    requestTrackingConsent(PendingTrackingAction.ENTER_RACE)
                                }
                            )
""",
    """                            requestTrackingConsent(PendingTrackingAction.ENTER_RACE)
""",
)

# Once compatibility allows the event, load event data independently from Legal.
replace_once(
    MAIN,
    """        eventCompatibilityWarningAccess = null
        eventCompatibilityAllowedAccess = access
        fetchRaceLegalTextAfterCompatibility(currentEventCompatibilityContext(access))
""",
    """        eventCompatibilityWarningAccess = null
        eventCompatibilityAllowedAccess = access
        fetchRaceDataForDisplay()
        startRaceDataRefresh()
        fetchRaceLegalTextAfterCompatibility(currentEventCompatibilityContext(access))
""",
)

replace_once(
    MAIN,
    """            eventCompatibilityAllowedAccess == access -> {
                fetchRaceLegalTextAfterCompatibility(compatibilityContext)
                return
            }
""",
    """            eventCompatibilityAllowedAccess == access -> {
                fetchRaceDataForDisplay()
                startRaceDataRefresh()
                fetchRaceLegalTextAfterCompatibility(compatibilityContext)
                return
            }
""",
)

replace_once(
    MAIN,
    """                    EventCompatibilityDecision.PROCEED -> {
                        eventCompatibilityAllowedAccess = access
                        eventCompatibilityWarningAccess = null
                        eventCompatibilityBlockedAccess = null
                        fetchRaceLegalTextAfterCompatibility(compatibilityContext)
                    }
""",
    """                    EventCompatibilityDecision.PROCEED -> {
                        eventCompatibilityAllowedAccess = access
                        eventCompatibilityWarningAccess = null
                        eventCompatibilityBlockedAccess = null
                        fetchRaceDataForDisplay()
                        startRaceDataRefresh()
                        fetchRaceLegalTextAfterCompatibility(compatibilityContext)
                    }
""",
)

# Restore/check a saved event after Activity recreation without making tracking depend on the request.
replace_once(
    MAIN,
    """        handleIncomingShareIntent(intent)
        requestPermissionsForApp()
""",
    """        handleIncomingShareIntent(intent)
        if (currentEventAccessKey() != null) {
            fetchRaceLegalText()
        }
        updateConnectionUiState()
        requestPermissionsForApp()
""",
)

# Enter Race must first create exactly one durable local entry point.
replace_between(
    MAIN,
    "    private fun enterRace() {",
    "\n    private fun leaveRace()",
    """    private fun storeRaceEntrySample(): Boolean {
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

        TelemetryUploadScheduler.enqueue(this)
        return true
    }

    private fun enterRace() {
        if (!storeRaceEntrySample()) return

        inRace.value = true
        statusText.value = getString(R.string.in_race)
        serviceStatusText.value = getString(R.string.service_starting)
        saveAppState()

        fetchRaceDataForDisplay()
        startRaceDataRefresh()
        startRegattaForegroundService(manualMode = false)

        currentScreen.value = Screen.HOME
    }
""",
)

# Last-known-good event semantics and actual-server reachability.
replace_between(
    MAIN,
    "    private fun fetchRaceDataForDisplay() {",
    "\n    private fun buildCourseMapUrl",
    """    private fun fetchRaceDataForDisplay() {
        val access = currentEventAccessKey() ?: return
        if (raceDataFetchRunning) return
        raceDataFetchRunning = true

        thread {
            var serverResponded = false
            try {
                val url = buildNormalApiGetUrl(
                    baseUrl = baseServerUrlForAccess(access),
                    path = \"/event\",
                    eventName = access.event
                )

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = \"GET\"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.setRequestProperty(\"accept\", \"application/json\")
                connection.setRequestProperty(\"x-event-name\", access.event)
                connection.setRequestProperty(\"x-shared-secret\", access.secret)
                connection.setRequestProperty(
                    \"x-api-version\",
                    RegattaTrackingService.API_VERSION
                )

                val responseCode = connection.responseCode
                serverResponded = true
                ServerConnectionStateStore.markReachable(this, access.server)

                val body = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: \"\"
                }
                connection.disconnect()

                if (responseCode !in 200..299) {
                    runOnUiThread {
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
                val parsedSeriesDisplayMetadata = parseSeriesDisplayMetadata(json)
                val parsedStartFlags = parseRaceStartFlags(json)

                runOnUiThread {
                    adoptResolvedEventName(snapshot.resolvedEventName)
                    raceSeriesDisplayMetadata.value = parsedSeriesDisplayMetadata
                    rawRaceStatus = snapshot.status
                    rawRaceStart = snapshot.startRaw
                    rawRaceStop = snapshot.stopRaw
                    rawRaceInfo = snapshot.raceInfo
                    rawRaceCourseJson = snapshot.courseJson
                    rawRaceCourseShortened = snapshot.courseShortened
                    raceDataReady.value = true
                    raceStartFlags.value = parsedStartFlags
                    renderRawRaceSetup()
                    saveRaceSetup()
                    updateStartPanelStatus()
                    updateLocalRaceStatus()
                    updateConnectionUiState()
                }
            } catch (_: Exception) {
                if (!serverResponded) {
                    ServerConnectionStateStore.markNoConnection(this, access.server)
                }
                runOnUiThread {
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
                raceDataFetchRunning = false
            }
        }
    }
""",
)

# Keep UI connection state independent from event validity. An absent active network is a fast
# negative hint; positive state still requires a real response from the configured server.
replace_once(
    MAIN,
    """    private fun startRaceDataRefresh() {
""",
    """    private fun updateConnectionUiState() {
        val server = raceServer.value
        if (server.isBlank()) {
            serverNoConnection.value = false
            return
        }

        if (!ServerConnectionStateStore.hasActiveNetwork(this)) {
            ServerConnectionStateStore.markNoConnection(this, server)
        }

        serverNoConnection.value =
            ServerConnectionStateStore.state(this, server) == ServerConnectionState.NO_CONNECTION
    }

    private fun startRaceDataRefresh() {
""",
)

# HomeScreen: No connection wins over backlog/race status and Legal no longer blocks upload state.
replace_once(
    HOME,
    """    pendingUploadCount: Long,
    debugErrorText: String,
""",
    """    pendingUploadCount: Long,
    noConnection: Boolean = false,
    debugErrorText: String,
""",
)

replace_once(
    HOME,
    """    val uploadColor = uploadStatusColor(
        pendingUploadCount = pendingUploadCount,
        inRace = inRace,
        disabledColor = MaterialTheme.colorScheme.outlineVariant
    )
""",
    """    val uploadColor = uploadStatusColor(
        pendingUploadCount = pendingUploadCount,
        inRace = inRace,
        disabledColor = MaterialTheme.colorScheme.outlineVariant,
        noConnection = noConnection
    )
""",
)

replace_once(
    HOME,
    """                pendingUploadCount = pendingUploadCount,
                inRace = inRace,
                raceStatusCode = raceStatusCode,
""",
    """                pendingUploadCount = pendingUploadCount,
                inRace = inRace,
                raceStatusCode = raceStatusCode,
                noConnection = noConnection,
""",
)

replace_once(
    HOME,
    """                okText = stringResource(R.string.ok)
""",
    """                okText = stringResource(R.string.ok),
                noConnectionText = stringResource(R.string.status_no_connection)
""",
)

replace_between(
    HOME,
    "fun uploadStatusColor(",
    "\nfun shortUploadStatus(",
    """fun uploadStatusColor(
    pendingUploadCount: Long,
    inRace: Boolean,
    disabledColor: Color,
    noConnection: Boolean = false
): Color {
    if (noConnection) return RegattaRed

    if (!inRace && pendingUploadCount == 0L) {
        return disabledColor
    }

    return when {
        pendingUploadCount <= 10L -> RegattaGreen
        pendingUploadCount <= 50L -> RegattaOrange
        else -> RegattaRed
    }
}
""",
)

replace_between(
    HOME,
    "fun shortUploadStatus(",
    "\nfun raceStatusColor(",
    """fun shortUploadStatus(
    pendingUploadCount: Long,
    inRace: Boolean,
    raceStatusCode: String,
    raceDataReady: Boolean,
    raceLegalAccepted: Boolean,
    hasRaceSetup: Boolean,
    pendingText: (Long) -> String,
    blockedText: String,
    offText: String,
    waitingText: String,
    readyText: String,
    idleText: String,
    okText: String,
    noConnection: Boolean = false,
    noConnectionText: String = \"No connection\"
): String {
    if (noConnection) {
        return if (pendingUploadCount > 0L) {
            \"$noConnectionText · ${pendingText(pendingUploadCount)}\"
        } else {
            noConnectionText
        }
    }

    if (!inRace) {
        if (pendingUploadCount > 0L) {
            return pendingText(pendingUploadCount)
        }

        return when {
            !raceDataReady -> offText
            raceStatusCode.equals(\"planned\", ignoreCase = true) -> waitingText
            raceStatusCode.equals(\"racing\", ignoreCase = true) -> readyText
            raceStatusCode.equals(\"started\", ignoreCase = true) -> readyText
            else -> idleText
        }
    }

    return if (pendingUploadCount <= 10L) okText else \"$pendingUploadCount\"
}
""",
)

# Service: hydrate the existing race_setup snapshot before relying on /event and persist fresh polls
# back into the same cache.
replace_once(
    SERVICE,
    """        serviceRunning = true

        startLocationUpdates()
""",
    """        restoreCachedEventSnapshot()
        serviceRunning = true

        startLocationUpdates()
""",
)

replace_between(
    SERVICE,
    "    private fun pollEvent() {",
    "\n    private fun buildEventUrl()",
    """    private fun pollEvent() {
        if (eventPollRunning) return

        val pollGeneration = synchronized(eventPollLifecycleLock) {
            eventPollGeneration
        }
        eventPollRunning = true

        thread {
            var serverResponded = false
            try {
                val eventUrl = buildEventUrl()
                val connection = URL(eventUrl).openConnection() as HttpURLConnection

                connection.requestMethod = \"GET\"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.setRequestProperty(\"accept\", \"application/json\")
                connection.setRequestProperty(\"x-event-name\", eventName)
                connection.setRequestProperty(\"x-shared-secret\", sharedSecret)
                connection.setRequestProperty(\"x-api-version\", API_VERSION)

                val responseCode = connection.responseCode
                serverResponded = true
                ServerConnectionStateStore.markReachable(this, serverUrl)
                val body = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: \"\"
                }

                connection.disconnect()

                if (responseCode in 200..299) {
                    val applied = synchronized(eventPollLifecycleLock) {
                        if (!serviceRunning || pollGeneration != eventPollGeneration) {
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
""",
)

replace_between(
    SERVICE,
    "    private fun parseEventResponse(body: String) {",
    "\n    private fun parseStartLine(",
    """    private fun restoreCachedEventSnapshot() {
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

        raceStatus = snapshot.status.ifBlank { \"unknown\" }
        courseShortened = snapshot.courseShortened
        raceStartInstant = parseServerInstant(snapshot.startRaw)
        raceStopInstant = parseServerInstant(snapshot.stopRaw)

        startLine = null
        finishLine = null
        courseMarks = emptyList()
        firstCourseMark = null

        val course = snapshot.courseJson
            .takeIf { it.isNotBlank() }
            ?.let { JSONObject(it) }

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
""",
)

# Worker marks a server reachable on every HTTP response, and unavailable only on transport failure.
replace_once(
    WORKER,
    """            val responseCode = connection.responseCode
            val errorBody = if (responseCode in 200..299) {
""",
    """            val responseCode = connection.responseCode
            ServerConnectionStateStore.markReachable(applicationContext, accessContext.serverUrl)
            val errorBody = if (responseCode in 200..299) {
""",
)

replace_once(
    WORKER,
    """        } catch (e: Exception) {
            publishDebugError(applicationContext.getString(R.string.upload_exception, e.message ?: \"\"))
            TelemetryUploadAttemptResult.TEMPORARY_FAILURE
""",
    """        } catch (e: Exception) {
            ServerConnectionStateStore.markNoConnection(applicationContext, accessContext.serverUrl)
            publishDebugError(applicationContext.getString(R.string.upload_exception, e.message ?: \"\"))
            TelemetryUploadAttemptResult.TEMPORARY_FAILURE
""",
)

# Network-state permission is only used for the fast negative hint; server requests remain authoritative.
replace_once(
    MANIFEST,
    """    <uses-permission android:name=\"android.permission.INTERNET\" />
""",
    """    <uses-permission android:name=\"android.permission.INTERNET\" />
    <uses-permission android:name=\"android.permission.ACCESS_NETWORK_STATE\" />
""",
)

# Update existing upload-status tests without discarding their regression coverage.
replace_once(
    HOME_TEST,
    """        hasRaceSetup: Boolean
    ): String = shortUploadStatus(
""",
    """        hasRaceSetup: Boolean,
        noConnection: Boolean = false
    ): String = shortUploadStatus(
""",
)
replace_once(
    HOME_TEST,
    """        okText = \"OK\"
    )
""",
    """        okText = \"OK\",
        noConnection = noConnection,
        noConnectionText = \"No connection\"
    )
""",
)
replace_between(
    HOME_TEST,
    "    @Test\n    fun loadedSetupWithoutLegalAcceptance_isBlocked()",
    "\n    @Test\n    fun rawPlannedStatus_isWaiting()",
    """    @Test
    fun loadedSetupWithoutLegalAcceptance_isNotBlocked() {
        assertEquals(\"waiting\", shortUploadStatusEnglish(0L, false, \"planned\", true, false, true))
    }

    @Test
    fun noConnection_hasPriorityAndShowsPendingCount() {
        assertEquals(
            \"No connection · 7 pending\",
            shortUploadStatusEnglish(7L, true, \"racing\", true, false, true, noConnection = true)
        )
        assertEquals(
            \"No connection\",
            shortUploadStatusEnglish(0L, false, \"planned\", true, true, true, noConnection = true)
        )
    }
""",
)

# Localized UI strings.
strings = {
    "app/src/main/res/values/strings.xml": {
        "status_no_connection": "No connection",
        "race_first_load_online_required": "Event data unavailable. Load this event online once before using it without a connection.",
        "race_response_invalid": "Event data could not be updated.",
        "race_entry_store_failed": "Could not store the race entry sample. Race was not started.",
    },
    "app/src/main/res/values-de/strings.xml": {
        "status_no_connection": "Keine Verbindung",
        "race_first_load_online_required": "Eventdaten nicht verfügbar. Lade dieses Event einmal online, bevor du es ohne Verbindung verwendest.",
        "race_response_invalid": "Eventdaten konnten nicht aktualisiert werden.",
        "race_entry_store_failed": "Der Startpunkt konnte nicht lokal gespeichert werden. Die Regatta wurde nicht gestartet.",
    },
    "app/src/main/res/values-fr/strings.xml": {
        "status_no_connection": "Pas de connexion",
        "race_first_load_online_required": "Données de l’événement indisponibles. Chargez cet événement une fois en ligne avant de l’utiliser sans connexion.",
        "race_response_invalid": "Les données de l’événement n’ont pas pu être mises à jour.",
        "race_entry_store_failed": "Le point d’entrée en course n’a pas pu être enregistré localement. La course n’a pas été démarrée.",
    },
    "app/src/main/res/values-it/strings.xml": {
        "status_no_connection": "Nessuna connessione",
        "race_first_load_online_required": "Dati dell’evento non disponibili. Carica questo evento online almeno una volta prima di usarlo senza connessione.",
        "race_response_invalid": "Impossibile aggiornare i dati dell’evento.",
        "race_entry_store_failed": "Impossibile salvare localmente il punto di ingresso in regata. La regata non è stata avviata.",
    },
    "app/src/main/res/values-es/strings.xml": {
        "status_no_connection": "Sin conexión",
        "race_first_load_online_required": "Los datos del evento no están disponibles. Carga este evento en línea una vez antes de usarlo sin conexión.",
        "race_response_invalid": "No se pudieron actualizar los datos del evento.",
        "race_entry_store_failed": "No se pudo guardar localmente el punto de entrada en regata. La regata no se inició.",
    },
}
for path, entries in strings.items():
    text = read(path)
    additions = []
    for name, value in entries.items():
        if f'name="{name}"' not in text:
            additions.append(f'    <string name="{name}">{value}</string>')
    if additions:
        marker = "</resources>"
        idx = text.rfind(marker)
        if idx < 0:
            raise RuntimeError(f"{path}: </resources> not found")
        text = text[:idx] + "\n" + "\n".join(additions) + "\n" + text[idx:]
        write(path, text)

print("Issue #108 source transformation completed")
