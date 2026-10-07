package de.williserv.regattaclient

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
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
    private var originalRegattaLinkManager: RegattaLinkConnectionManager? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB_NAME)
        clearTrackingPrefs()
        clearLocalStatusPrefs()
        clearStickyRestartPrefs()
        RegattaLinkPhoneGpsRelayStore(context).setEnabled(false)
        TrackingServiceRuntimeState.markStopped()
        TelemetryUploadScheduler.resetLiveWakeupCoalescing()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        originalRegattaLinkManager?.let { original ->
            val application = context.applicationContext as RegattaApplication
            setField(application, "regattaLinkConnectionManager", original)
        }
        originalRegattaLinkManager = null
        shadowOf(Looper.getMainLooper()).idle()
        context.deleteDatabase(DB_NAME)
        clearTrackingPrefs()
        clearLocalStatusPrefs()
        clearStickyRestartPrefs()
        RegattaLinkPhoneGpsRelayStore(context).setEnabled(false)
        TrackingServiceRuntimeState.markStopped()
    }

    @Test
    fun `pending notification recount is throttled and coalesced while in flight`() {
        val gate = TelemetryPendingCountRefreshGate(
            RegattaTrackingService.NOTIFICATION_PENDING_REFRESH_INTERVAL_MS
        )

        assertTrue(gate.tryStart(nowElapsedMs = 0L, force = false))
        assertFalse(gate.tryStart(nowElapsedMs = 1_000L, force = false))
        assertFalse(gate.tryStart(nowElapsedMs = 1_000L, force = true))

        gate.finish()

        assertFalse(gate.tryStart(nowElapsedMs = 9_999L, force = false))
        assertTrue(gate.tryStart(nowElapsedMs = 10_000L, force = false))
        gate.finish()
    }

    @Test
    fun `forced pending notification recount bypasses throttle after current read finishes`() {
        val gate = TelemetryPendingCountRefreshGate(
            RegattaTrackingService.NOTIFICATION_PENDING_REFRESH_INTERVAL_MS
        )

        assertTrue(gate.tryStart(nowElapsedMs = 0L, force = false))
        gate.finish()

        assertTrue(gate.tryStart(nowElapsedMs = 1L, force = true))
        gate.finish()
    }

    @Test
    fun `pending notification reconciliation preserves insert after db snapshot`() {
        val snapshot = requireNotNull(
            telemetryPendingCountSnapshot(
                pending = 12L,
                mutationGenerationBefore = 8L,
                mutationGenerationAfter = 8L,
                raceInsertCount = 20L
            )
        )

        assertEquals(
            13L,
            reconcileTelemetryPendingCount(
                snapshot = snapshot,
                currentRaceInsertCount = 21L
            )
        )
    }

    @Test
    fun `pending notification recount discards snapshot overlapping sample mutation`() {
        assertNull(
            telemetryPendingCountSnapshot(
                pending = 12L,
                mutationGenerationBefore = 8L,
                mutationGenerationAfter = 10L,
                raceInsertCount = 21L
            )
        )
        assertNull(
            telemetryPendingCountSnapshot(
                pending = 12L,
                mutationGenerationBefore = 9L,
                mutationGenerationAfter = 9L,
                raceInsertCount = 21L
            )
        )
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
    fun `start during stop handoff re-arms sampling schedules`() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val helper = getField<TrackingDbHelper>(service, "db")
        val looper = shadowOf(Looper.getMainLooper())

        val startIntent = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_START
            putExtra(RegattaTrackingService.EXTRA_MANUAL_RECORDING, true)
        }
        val stopIntent = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_STOP
        }

        assertEquals(Service.START_STICKY, service.onStartCommand(startIntent, 0, 1))
        assertEquals(1_000L, getField<Long>(service, "activeSampleIntervalMs"))
        assertEquals(1_000L, getField<Long>(service, "activeLocationIntervalMs"))

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stopIntent, 0, 2))
        assertEquals(0L, getField<Long>(service, "activeSampleIntervalMs"))
        assertEquals(0L, getField<Long>(service, "activeLocationIntervalMs"))

        assertEquals(Service.START_STICKY, service.onStartCommand(startIntent, 0, 3))
        assertEquals(1_000L, getField<Long>(service, "activeSampleIntervalMs"))
        assertEquals(1_000L, getField<Long>(service, "activeLocationIntervalMs"))

        looper.idleFor(1, TimeUnit.SECONDS)
        assertEquals(1L, helper.countSamples())

        setField(service, "serviceRunning", false)
        controller.destroy()
        helper.close()
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
    fun `activity startup relay gate requires permission and no tracking`() {
        assertFalse(
            shouldStartPhoneGpsRelayService(
                enabled = true,
                trackingRequested = false,
                locationPermissionGranted = false
            )
        )
        assertFalse(
            shouldStartPhoneGpsRelayService(
                enabled = true,
                trackingRequested = true,
                locationPermissionGranted = true
            )
        )
        assertFalse(
            shouldStartPhoneGpsRelayService(
                enabled = false,
                trackingRequested = false,
                locationPermissionGranted = true
            )
        )
        assertTrue(
            shouldStartPhoneGpsRelayService(
                enabled = true,
                trackingRequested = false,
                locationPermissionGranted = true
            )
        )
    }

    @Test
    fun `phone GPS relay remains persisted until location permission is granted`() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val relayStore = RegattaLinkPhoneGpsRelayStore(context)
        relayStore.setEnabled(true)
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val intent = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_SYNC_PHONE_GPS_RELAY
        }

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1))
        assertTrue(relayStore.isEnabled())
        assertFalse(getField<Boolean>(service, "serviceRunning"))
        assertFalse(TrackingServiceRuntimeState.isActive())

        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        assertEquals(Service.START_STICKY, service.onStartCommand(intent, 0, 2))
        assertTrue(relayStore.isEnabled())
        assertFalse(getField<Boolean>(service, "serviceRunning"))

        relayStore.setEnabled(false)
        service.onStartCommand(intent, 0, 3)
        controller.destroy()
    }

    @Test
    fun `sticky restart does not restore phone GPS relay without location permission`() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val relayStore = RegattaLinkPhoneGpsRelayStore(context)
        relayStore.setEnabled(true)
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(relayStore.isEnabled())
        assertFalse(getField<Boolean>(service, "serviceRunning"))
        assertFalse(TrackingServiceRuntimeState.isActive())
        assertNull(getField<Long?>(service, "activeSessionId"))

        controller.destroy()
    }

    @Test
    fun `phone GPS relay starts without tracking state or samples`() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        RegattaLinkPhoneGpsRelayStore(context).setEnabled(true)
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val helper = getField<TrackingDbHelper>(service, "db")
        val intent = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_SYNC_PHONE_GPS_RELAY
        }

        assertEquals(Service.START_STICKY, service.onStartCommand(intent, 0, 1))
        assertFalse(getField<Boolean>(service, "serviceRunning"))
        assertFalse(TrackingServiceRuntimeState.isActive())
        assertNull(getField<Long?>(service, "activeSessionId"))
        assertFalse(getField<Boolean>(service, "eventPollRunning"))
        assertEquals(0L, getField<Long>(service, "activeSampleIntervalMs"))
        assertEquals(0L, helper.countSamples())

        RegattaLinkPhoneGpsRelayStore(context).setEnabled(false)
        service.onStartCommand(intent, 0, 2)
        controller.destroy()
        helper.close()
    }

    @Test
    fun `sticky restart restores phone GPS relay without restoring tracking`() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        RegattaLinkPhoneGpsRelayStore(context).setEnabled(true)
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()

        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertFalse(getField<Boolean>(service, "serviceRunning"))
        assertFalse(TrackingServiceRuntimeState.isActive())
        assertNull(getField<Long?>(service, "activeSessionId"))
        assertFalse(getField<Boolean>(service, "eventPollRunning"))

        RegattaLinkPhoneGpsRelayStore(context).setEnabled(false)
        val syncIntent = Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_SYNC_PHONE_GPS_RELAY
        }
        service.onStartCommand(syncIntent, 0, 2)
        controller.destroy()
    }

    @Test
    fun `relay only forwards one GPS observation without tracking artifacts`() {
        grantLocationPermission()
        RegattaLinkPhoneGpsRelayStore(context).setEnabled(true)
        val relayClient = installReadyRelayTestManager()
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val helper = getField<TrackingDbHelper>(service, "db")

        assertEquals(
            Service.START_STICKY,
            service.onStartCommand(phoneGpsRelaySyncIntent(), 0, 1)
        )
        assertEquals(
            REGATTALINK_PHONE_GNSS_MIN_INTERVAL_MS,
            getField<Long>(service, "activeLocationIntervalMs")
        )

        simulateGpsLocation()

        assertEquals(1, relayClient.phoneGnssOfferCount)
        assertFalse(TrackingServiceRuntimeState.isActive())
        assertNull(getField<Long?>(service, "activeSessionId"))
        assertFalse(getField<Boolean>(service, "eventPollRunning"))
        assertEquals(0L, getField<Long>(service, "activeSampleIntervalMs"))
        assertTrue(helper.getTrackingSessionSummaries().isEmpty())
        assertEquals(0L, helper.countSamples())
        assertEquals(0L, helper.countUploadablePendingSamples())

        RegattaLinkPhoneGpsRelayStore(context).setEnabled(false)
        service.onStartCommand(phoneGpsRelaySyncIntent(), 0, 2)
        controller.destroy()
        helper.close()
    }

    @Test
    fun `relay only hands off to manual tracking without duplicate GPS forwarding`() {
        grantLocationPermission()
        RegattaLinkPhoneGpsRelayStore(context).setEnabled(true)
        val relayClient = installReadyRelayTestManager()
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val helper = getField<TrackingDbHelper>(service, "db")

        service.onStartCommand(phoneGpsRelaySyncIntent(), 0, 1)

        assertEquals(
            Service.START_STICKY,
            service.onStartCommand(manualTrackingStartIntent(), 0, 2)
        )
        assertTrue(TrackingServiceRuntimeState.isActive())
        val sessionId = requireNotNull(getField<Long?>(service, "activeSessionId"))
        assertEquals("manual", helper.getTrackingSession(sessionId)?.mode)

        simulateGpsLocation()

        assertEquals(1, relayClient.phoneGnssOfferCount)
        assertEquals(
            REGATTALINK_PHONE_GNSS_MIN_INTERVAL_MS,
            getField<Long>(service, "activeLocationIntervalMs")
        )

        setField(service, "serviceRunning", false)
        controller.destroy()
        helper.close()
    }

    @Test
    fun `relay only hands off to race tracking without duplicate GPS forwarding`() {
        grantLocationPermission()
        RegattaLinkPhoneGpsRelayStore(context).setEnabled(true)
        val relayClient = installReadyRelayTestManager()
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val helper = getField<TrackingDbHelper>(service, "db")

        service.onStartCommand(phoneGpsRelaySyncIntent(), 0, 1)
        assertFalse(getField<Boolean>(service, "eventPollRunning"))

        // Keep this lifecycle test local; event polling itself is covered separately.
        setField(service, "eventPollRunning", true)
        assertEquals(
            Service.START_STICKY,
            service.onStartCommand(raceTrackingStartIntent(), 0, 2)
        )
        assertTrue(TrackingServiceRuntimeState.isActive())
        val sessionId = requireNotNull(getField<Long?>(service, "activeSessionId"))
        assertEquals("race", helper.getTrackingSession(sessionId)?.mode)

        simulateGpsLocation()

        assertEquals(1, relayClient.phoneGnssOfferCount)
        assertEquals(
            REGATTALINK_PHONE_GNSS_MIN_INTERVAL_MS,
            getField<Long>(service, "activeLocationIntervalMs")
        )

        setField(service, "serviceRunning", false)
        controller.destroy()
        helper.close()
    }

    @Test
    fun `tracking stop falls back to relay only when persistent relay stays enabled`() {
        grantLocationPermission()
        RegattaLinkPhoneGpsRelayStore(context).setEnabled(true)
        val relayClient = installReadyRelayTestManager()
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()
        val helper = getField<TrackingDbHelper>(service, "db")

        service.onStartCommand(phoneGpsRelaySyncIntent(), 0, 1)
        service.onStartCommand(manualTrackingStartIntent(), 0, 2)
        val sessionId = requireNotNull(getField<Long?>(service, "activeSessionId"))
        assertTrue(TrackingServiceRuntimeState.isActive())

        assertEquals(
            Service.START_STICKY,
            service.onStartCommand(trackingStopIntent(), 0, 3)
        )
        assertFalse(TrackingServiceRuntimeState.isActive())
        assertFalse(getField<Boolean>(service, "serviceRunning"))
        assertNull(getField<Long?>(service, "activeSessionId"))
        assertTrue(helper.getTrackingSession(sessionId)?.endedAt != null)
        assertEquals(0L, getField<Long>(service, "activeSampleIntervalMs"))
        assertFalse(getField<Boolean>(service, "eventPollRunning"))
        assertEquals(
            REGATTALINK_PHONE_GNSS_MIN_INTERVAL_MS,
            getField<Long>(service, "activeLocationIntervalMs")
        )

        simulateGpsLocation()

        assertEquals(1, relayClient.phoneGnssOfferCount)
        assertEquals(0L, helper.countSamples())
        assertEquals(0L, helper.countUploadablePendingSamples())

        RegattaLinkPhoneGpsRelayStore(context).setEnabled(false)
        service.onStartCommand(phoneGpsRelaySyncIntent(), 0, 4)
        controller.destroy()
        helper.close()
    }

    @Test
    fun `disabling persistent relay during tracking keeps tracking forwarding but no relay continuation`() {
        grantLocationPermission()
        RegattaLinkPhoneGpsRelayStore(context).setEnabled(true)
        val relayClient = installReadyRelayTestManager()
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()

        service.onStartCommand(phoneGpsRelaySyncIntent(), 0, 1)
        service.onStartCommand(manualTrackingStartIntent(), 0, 2)

        assertEquals(
            Service.START_STICKY,
            service.onStartCommand(phoneGpsRelayDisableIntent(), 0, 3)
        )
        assertFalse(RegattaLinkPhoneGpsRelayStore(context).isEnabled())
        assertTrue(TrackingServiceRuntimeState.isActive())
        assertTrue(getField<Boolean>(service, "serviceRunning"))

        simulateGpsLocation()
        assertEquals(1, relayClient.phoneGnssOfferCount)

        assertEquals(
            Service.START_NOT_STICKY,
            service.onStartCommand(trackingStopIntent(), 0, 4)
        )
        assertFalse(TrackingServiceRuntimeState.isActive())
        assertEquals(0L, getField<Long>(service, "activeLocationIntervalMs"))

        simulateGpsLocation(latitude = 53.1)

        assertEquals(1, relayClient.phoneGnssOfferCount)

        controller.destroy()
    }

    @Test
    fun `relay only waits for Phone GNSS MTU readiness and resumes when ready`() {
        grantLocationPermission()
        RegattaLinkPhoneGpsRelayStore(context).setEnabled(true)
        val relayClient = installReadyRelayTestManager()
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()

        service.onStartCommand(phoneGpsRelaySyncIntent(), 0, 1)
        assertEquals(
            REGATTALINK_PHONE_GNSS_MIN_INTERVAL_MS,
            getField<Long>(service, "activeLocationIntervalMs")
        )

        relayClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                phoneGnssTransportReady = false
            )
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0L, getField<Long>(service, "activeLocationIntervalMs"))
        simulateGpsLocation()
        assertEquals(0, relayClient.phoneGnssOfferCount)

        relayClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                phoneGnssTransportReady = true
            )
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            REGATTALINK_PHONE_GNSS_MIN_INTERVAL_MS,
            getField<Long>(service, "activeLocationIntervalMs")
        )
        simulateGpsLocation(latitude = 53.2)
        assertEquals(1, relayClient.phoneGnssOfferCount)

        RegattaLinkPhoneGpsRelayStore(context).setEnabled(false)
        service.onStartCommand(phoneGpsRelaySyncIntent(), 0, 2)
        controller.destroy()
    }

    @Test
    fun `relay only pauses GPS work when CAN disappears and resumes when it returns`() {
        grantLocationPermission()
        RegattaLinkPhoneGpsRelayStore(context).setEnabled(true)
        val relayClient = installReadyRelayTestManager()
        val controller = Robolectric.buildService(RegattaTrackingService::class.java).create()
        val service = controller.get()

        service.onStartCommand(phoneGpsRelaySyncIntent(), 0, 1)
        assertEquals(
            REGATTALINK_PHONE_GNSS_MIN_INTERVAL_MS,
            getField<Long>(service, "activeLocationIntervalMs")
        )

        relayClient.emitConfiguration(
            relayConfiguration(
                includeCan = false
            )
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0L, getField<Long>(service, "activeLocationIntervalMs"))
        simulateGpsLocation()
        assertEquals(0, relayClient.phoneGnssOfferCount)

        relayClient.emitConfiguration(relayConfiguration(includeCan = true))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            REGATTALINK_PHONE_GNSS_MIN_INTERVAL_MS,
            getField<Long>(service, "activeLocationIntervalMs")
        )
        simulateGpsLocation(latitude = 53.2)

        assertEquals(1, relayClient.phoneGnssOfferCount)

        RegattaLinkPhoneGpsRelayStore(context).setEnabled(false)
        service.onStartCommand(phoneGpsRelaySyncIntent(), 0, 2)
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

    private fun grantLocationPermission() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private fun phoneGpsRelaySyncIntent(): Intent =
        Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_SYNC_PHONE_GPS_RELAY
        }

    private fun phoneGpsRelayDisableIntent(): Intent =
        Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_DISABLE_PHONE_GPS_RELAY
        }

    private fun manualTrackingStartIntent(): Intent =
        Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_START
            putExtra(RegattaTrackingService.EXTRA_MANUAL_RECORDING, true)
        }

    private fun raceTrackingStartIntent(): Intent =
        Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_START
            putExtra(
                RegattaTrackingService.EXTRA_SERVER_URL,
                "https://raceoffice.example.org"
            )
            putExtra(RegattaTrackingService.EXTRA_EVENT_NAME, "Event A")
            putExtra(RegattaTrackingService.EXTRA_SHARED_SECRET, "secret")
            putExtra(RegattaTrackingService.EXTRA_RESOLVED_EVENT_NAME, "Race 1")
            putExtra(RegattaTrackingService.EXTRA_MANUAL_RECORDING, false)
        }

    private fun trackingStopIntent(): Intent =
        Intent(context, RegattaTrackingService::class.java).apply {
            action = RegattaTrackingService.ACTION_STOP
        }

    private fun relayConfiguration(
        includeCan: Boolean
    ): RegattaLinkConfigurationState {
        var word =
            REGATTALINK_CONFIG_TX_MASTER or REGATTALINK_CONFIG_TX_PHONE_GPS
        if (includeCan) {
            word = word or REGATTALINK_CONFIG_SESSION_CAN
        }
        return RegattaLinkConfigurationState(
            configWordSupported = true,
            configWord = word
        )
    }

    private fun installReadyRelayTestManager(): RelayTestClient {
        val application = context.applicationContext as RegattaApplication
        if (originalRegattaLinkManager == null) {
            originalRegattaLinkManager = application.regattaLinkConnectionManager
        }

        lateinit var relayClient: RelayTestClient
        val manager = RegattaLinkConnectionManager(
            context = context,
            clientFactory = RegattaLinkConnectionClientFactory {
                    _,
                    onStateChanged,
                    _,
                    _,
                    onConfigurationStateChanged,
                    _,
                    _,
                    _,
                    _ ->
                RelayTestClient(
                    onStateChanged = onStateChanged,
                    onConfigurationStateChanged = onConfigurationStateChanged
                ).also { relayClient = it }
            },
            legacyBondedAddressProvider = { null }
        )
        setField(application, "regattaLinkConnectionManager", manager)

        relayClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                phoneGnssTransportReady = true
            )
        )
        relayClient.emitConfiguration(relayConfiguration(includeCan = true))
        shadowOf(Looper.getMainLooper()).idle()
        return relayClient
    }

    private fun simulateGpsLocation(
        latitude: Double = 53.0,
        longitude: Double = 10.0
    ) {
        val locationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            this.latitude = latitude
            this.longitude = longitude
            accuracy = 3f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = System.nanoTime()
        }
        shadowOf(locationManager).simulateLocation(location)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class RelayTestClient(
        private val onStateChanged: (RegattaLinkClientState) -> Unit,
        private val onConfigurationStateChanged: (RegattaLinkConfigurationState) -> Unit
    ) : RegattaLinkConnectionClient {
        var phoneGnssOfferCount: Int = 0
            private set

        override fun startKnownDeviceReconnect(
            deviceAddress: String,
            expectedStableId: String?,
            timeoutMs: Long
        ): Boolean = false

        override fun startKnownDeviceAutoConnect(
            deviceAddress: String,
            expectedStableId: String?
        ): Boolean = false

        override fun onBluetoothAdapterDisabled() = Unit
        override fun onBluetoothAdapterEnabled() = Unit

        override fun startDiscovery(): Boolean = false
        override fun disconnect() = Unit
        override fun startOta(artifact: RegattaLinkFirmwareArtifact) = Unit
        override fun cancelOta() = Unit
        override fun resetOtaState() = Unit
        override fun setDeviceName(name: String): Boolean = false
        override fun setLedBrightness(percent: Int): Boolean = false
        override fun setMotionDamping(seconds: Int): Boolean = false
        override fun refreshPgnInventory(): Boolean = false
        override fun readRawCanFrames(): Boolean = false

        override fun offerPhoneGnss(
            sample: RegattaLinkPhoneGnssSample
        ): Boolean {
            phoneGnssOfferCount += 1
            return true
        }

        fun emitConnection(state: RegattaLinkClientState) {
            onStateChanged(state)
        }

        fun emitConfiguration(state: RegattaLinkConfigurationState) {
            onConfigurationStateChanged(state)
        }
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
