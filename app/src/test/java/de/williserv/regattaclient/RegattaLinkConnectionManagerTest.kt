package de.williserv.regattaclient

import android.Manifest
import android.content.Context
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RegattaLinkConnectionManagerTest {
    private lateinit var context: Context
    private lateinit var fakeClient: FakeConnectionClient
    private lateinit var manager: RegattaLinkConnectionManager

    private val configured = RegattaLinkConfiguredDevice(
        stableId = "0011223344556677",
        deviceAddress = "44:B1:76:48:31:B2",
        deviceName = "RegattaLink-31B2"
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        context.getSharedPreferences(
            RegattaLinkConfiguredDeviceStore.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        RegattaLinkConfiguredDeviceStore(context).save(configured)

        manager = createManager()
    }

    private fun createManager(
        legacyBondedAddressProvider: (Context) -> String? = { null }
    ): RegattaLinkConnectionManager =
        RegattaLinkConnectionManager(
            context = context,
            clientFactory = RegattaLinkConnectionClientFactory {
                    _,
                    onStateChanged,
                    onOtaStateChanged,
                    onTelemetryStateChanged,
                    onConfigurationStateChanged,
                    onNmeaStateChanged,
                    onUnexpectedDisconnect ->
                FakeConnectionClient(
                    onStateChanged = onStateChanged,
                    onOtaStateChanged = onOtaStateChanged,
                    onTelemetryStateChanged = onTelemetryStateChanged,
                    onConfigurationStateChanged = onConfigurationStateChanged,
                    onNmeaStateChanged = onNmeaStateChanged,
                    onUnexpectedDisconnect = onUnexpectedDisconnect
                ).also { fakeClient = it }
            },
            legacyBondedAddressProvider = legacyBondedAddressProvider
        )

    @After
    fun tearDown() {
        context.getSharedPreferences(
            RegattaLinkConfiguredDeviceStore.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }

    @Test
    fun rejectedDiscoveryDoesNotBlockConfiguredReconnect() {
        fakeClient.discoveryAccepted = false

        assertFalse(manager.startDiscovery())
        assertTrue(manager.reconnectConfigured())

        assertEquals(1, fakeClient.discoveryCalls)
        assertEquals(1, fakeClient.reconnectCalls)
        assertEquals(configured.deviceAddress, fakeClient.lastReconnectAddress)
        assertEquals(configured.stableId, fakeClient.lastReconnectStableId)
    }

    @Test
    fun activeOtaSuppressesGenericDiscoveryAndReconnect() {
        fakeClient.emitOta(
            RegattaLinkOtaUiState(
                phase = RegattaLinkOtaPhase.TRANSFERRING
            )
        )

        assertFalse(manager.startDiscovery())
        assertFalse(manager.reconnectConfigured())

        assertEquals(0, fakeClient.discoveryCalls)
        assertEquals(0, fakeClient.reconnectCalls)
    }

    @Test
    fun terminalOtaStateReleasesGenericConnectionActions() {
        fakeClient.emitOta(
            RegattaLinkOtaUiState(
                phase = RegattaLinkOtaPhase.TRANSFERRING
            )
        )
        assertFalse(manager.startDiscovery())

        fakeClient.emitOta(
            RegattaLinkOtaUiState(
                phase = RegattaLinkOtaPhase.SUCCESS
            )
        )

        assertTrue(manager.startDiscovery())
        assertEquals(1, fakeClient.discoveryCalls)
    }

    @Test
    fun unexpectedDisconnectReconnectsOnlyOutsideOtaOwnership() {
        fakeClient.emitUnexpectedDisconnect()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, fakeClient.reconnectCalls)

        fakeClient.emitOta(
            RegattaLinkOtaUiState(
                phase = RegattaLinkOtaPhase.RECONNECTING
            )
        )
        fakeClient.emitUnexpectedDisconnect()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, fakeClient.reconnectCalls)
    }

    @Test
    fun intentionalDisconnectDoesNotStartReconnect() {
        manager.disconnect()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, fakeClient.disconnectCalls)
        assertEquals(0, fakeClient.reconnectCalls)
    }

    @Test
    fun failedExplicitSearchPreservesPreviouslyConfiguredDevice() {
        assertTrue(manager.startDiscovery())

        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.ERROR,
                error = "search failed"
            )
        )

        assertEquals(configured, manager.configuredDevice())
        assertTrue(manager.reconnectConfigured())
        assertEquals(1, fakeClient.reconnectCalls)
    }

    @Test
    fun legacyBondedDeviceBootstrapsConfiguredIdentityAfterValidatedConnection() {
        context.getSharedPreferences(
            RegattaLinkConfiguredDeviceStore.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        manager = createManager { configured.deviceAddress }

        assertTrue(manager.reconnectConfigured())
        assertEquals(1, fakeClient.reconnectCalls)
        assertEquals(configured.deviceAddress, fakeClient.lastReconnectAddress)
        assertEquals(null, fakeClient.lastReconnectStableId)

        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                deviceName = configured.deviceName,
                deviceAddress = configured.deviceAddress,
                deviceInfo = RegattaLinkDeviceInfo(
                    protocolMajor = REGATTALINK_PROTOCOL_MAJOR,
                    protocolMinor = 0,
                    capabilities = 0u,
                    stableId = configured.stableId,
                    productId = REGATTALINK_PRODUCT_ID,
                    profileId = REGATTALINK_PROFILE_ID,
                    runningBuild = 1uL,
                    otaSlotSize = 1u,
                    maxInflightBlocks = 1
                )
            )
        )

        assertEquals(configured, manager.configuredDevice())
    }

    @Test
    fun missingConfiguredDeviceDoesNotGuessWhenLegacyBondIsAmbiguous() {
        context.getSharedPreferences(
            RegattaLinkConfiguredDeviceStore.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        manager = createManager { null }

        assertFalse(manager.reconnectConfigured())
        assertEquals(0, fakeClient.reconnectCalls)
        assertEquals(null, manager.configuredDevice())
    }

    @Test
    fun configurationNameRefreshUpdatesPersistedAndDisplayedName() {
        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                deviceName = configured.deviceName,
                deviceAddress = configured.deviceAddress,
                deviceInfo = RegattaLinkDeviceInfo(
                    protocolMajor = REGATTALINK_PROTOCOL_MAJOR,
                    protocolMinor = 0,
                    capabilities = 0u,
                    stableId = configured.stableId,
                    productId = REGATTALINK_PRODUCT_ID,
                    profileId = REGATTALINK_PROFILE_ID,
                    runningBuild = 1uL,
                    otaSlotSize = 1u,
                    maxInflightBlocks = 1
                )
            )
        )

        var displayedName = ""
        manager.addListener(
            object : RegattaLinkConnectionListener {
                override fun onConnectionStateChanged(state: RegattaLinkClientState) {
                    displayedName = state.deviceName
                }
            }
        )
        shadowOf(Looper.getMainLooper()).idle()

        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                deviceNameSupported = true,
                deviceName = "Race-Link"
            )
        )

        assertEquals("Race-Link", manager.configuredDevice()?.deviceName)
        assertEquals("Race-Link", displayedName)
    }

    @Test
    fun activeOtaSuppressesConfigurationAndDiagnosticActions() {
        fakeClient.emitOta(
            RegattaLinkOtaUiState(
                phase = RegattaLinkOtaPhase.TRANSFERRING
            )
        )

        assertFalse(manager.setDeviceName("Race-Link"))
        assertFalse(manager.setLedBrightness(75))
        assertFalse(manager.refreshPgnInventory())
        assertFalse(manager.readRawCanFrames())

        assertEquals(0, fakeClient.setNameCalls)
        assertEquals(0, fakeClient.setBrightnessCalls)
        assertEquals(0, fakeClient.refreshPgnCalls)
        assertEquals(0, fakeClient.rawReadCalls)
    }

    @Test
    fun newlyAttachedListenerReceivesCurrentManagerState() {
        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.SCANNING
            )
        )
        fakeClient.emitOta(
            RegattaLinkOtaUiState(
                phase = RegattaLinkOtaPhase.VERIFYING
            )
        )

        var connectionStatus: RegattaLinkConnectionStatus? = null
        var otaPhase: RegattaLinkOtaPhase? = null
        manager.addListener(
            object : RegattaLinkConnectionListener {
                override fun onConnectionStateChanged(state: RegattaLinkClientState) {
                    connectionStatus = state.status
                }

                override fun onOtaStateChanged(state: RegattaLinkOtaUiState) {
                    otaPhase = state.phase
                }
            }
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(RegattaLinkConnectionStatus.SCANNING, connectionStatus)
        assertEquals(RegattaLinkOtaPhase.VERIFYING, otaPhase)
    }

    private class FakeConnectionClient(
        private val onStateChanged: (RegattaLinkClientState) -> Unit,
        private val onOtaStateChanged: (RegattaLinkOtaUiState) -> Unit,
        @Suppress("UNUSED_PARAMETER")
        private val onTelemetryStateChanged: (RegattaLinkTelemetryState) -> Unit,
        private val onConfigurationStateChanged: (RegattaLinkConfigurationState) -> Unit,
        @Suppress("UNUSED_PARAMETER")
        private val onNmeaStateChanged: (RegattaLinkNmeaState) -> Unit,
        private val onUnexpectedDisconnect: () -> Unit
    ) : RegattaLinkConnectionClient {
        var discoveryAccepted = true
        var reconnectAccepted = true
        var discoveryCalls = 0
        var reconnectCalls = 0
        var disconnectCalls = 0
        var setNameCalls = 0
        var setBrightnessCalls = 0
        var refreshPgnCalls = 0
        var rawReadCalls = 0
        var lastReconnectAddress: String? = null
        var lastReconnectStableId: String? = null

        override fun startKnownDeviceReconnect(
            deviceAddress: String,
            expectedStableId: String?,
            timeoutMs: Long
        ): Boolean {
            reconnectCalls += 1
            lastReconnectAddress = deviceAddress
            lastReconnectStableId = expectedStableId
            return reconnectAccepted
        }

        override fun startDiscovery(): Boolean {
            discoveryCalls += 1
            return discoveryAccepted
        }

        override fun disconnect() {
            disconnectCalls += 1
        }

        override fun startOta(artifact: RegattaLinkFirmwareArtifact) = Unit

        override fun cancelOta() = Unit

        override fun resetOtaState() = Unit

        override fun setDeviceName(name: String): Boolean {
            setNameCalls += 1
            return true
        }

        override fun setLedBrightness(percent: Int): Boolean {
            setBrightnessCalls += 1
            return true
        }

        override fun refreshPgnInventory(): Boolean {
            refreshPgnCalls += 1
            return true
        }

        override fun readRawCanFrames(): Boolean {
            rawReadCalls += 1
            return true
        }

        fun emitConnection(state: RegattaLinkClientState) {
            onStateChanged(state)
        }

        fun emitOta(state: RegattaLinkOtaUiState) {
            onOtaStateChanged(state)
        }

        fun emitConfiguration(state: RegattaLinkConfigurationState) {
            onConfigurationStateChanged(state)
        }

        fun emitUnexpectedDisconnect() {
            onUnexpectedDisconnect()
        }
    }
}
