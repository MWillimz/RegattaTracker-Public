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
                    onFactoryResetRecoveryStateChanged,
                    onUnexpectedDisconnect ->
                FakeConnectionClient(
                    onStateChanged = onStateChanged,
                    onOtaStateChanged = onOtaStateChanged,
                    onTelemetryStateChanged = onTelemetryStateChanged,
                    onConfigurationStateChanged = onConfigurationStateChanged,
                    onNmeaStateChanged = onNmeaStateChanged,
                    onFactoryResetRecoveryStateChanged =
                        onFactoryResetRecoveryStateChanged,
                    onUnexpectedDisconnect = onUnexpectedDisconnect
                ).also { fakeClient = it }
            },
            legacyBondedAddressProvider = legacyBondedAddressProvider
        )

    private fun testFirmwareArtifact(): RegattaLinkFirmwareArtifact =
        RegattaLinkFirmwareArtifact(
            manifest = RegattaLinkFirmwareManifest(
                schemaVersion = 1,
                product = "RegattaLink",
                target = "esp32c3",
                hardwareProfile = "esp32c3-wroom02-4mb",
                buildNumber = 1uL,
                filename = "regattalink.bin",
                size = 1,
                sha256 = "00".repeat(32),
                signed = false,
                signingKeySha256 = null,
                downloadUrl = "/regattalink/firmware"
            ),
            image = byteArrayOf(0)
        )

    @After
    fun tearDown() {
        context.getSharedPreferences(
            RegattaLinkConfiguredDeviceStore.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        context.cacheDir
            .resolve("regattalink-captures")
            .deleteRecursively()
    }

    @Test
    fun updatingConfiguredNamePreservesResetRecoveryMarker() {
        val store = RegattaLinkConfiguredDeviceStore(context)
        store.markResetRecoveryPending()

        store.updateName(configured.stableId, "RegattaLink-Renamed")

        assertTrue(store.requiresNewPairing())
        assertEquals(
            "RegattaLink-Renamed",
            store.load()?.deviceName
        )
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
    fun factoryResetClearsAssociationAndSuppressesLegacyBondReconnect() {
        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                deviceAddress = configured.deviceAddress
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(deviceControlSupported = true)
        )
        assertTrue(manager.executeDeviceControl(RegattaLinkDeviceControlOpcode.FACTORY_RESET, 0))
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                deviceControlSupported = true,
                deviceControlBusy = true,
                deviceControlAcceptedOpcode = RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                deviceControlAcceptedRequestId = 7u
            )
        )
        fakeClient.emitConnection(RegattaLinkClientState())

        assertEquals(null, RegattaLinkConfiguredDeviceStore(context).load())
        assertFalse(manager.reconnectConfigured())
    }

    @Test
    fun provisionalFactoryResetDisconnectClearsAssociationWithoutOutageReconnect() {
        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                deviceAddress = configured.deviceAddress
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(deviceControlSupported = true)
        )
        assertTrue(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                0
            )
        )

        fakeClient.emitFactoryResetRecovery(true)
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                deviceControlSupported = true,
                deviceControlBusy = true,
                factoryResetWriteAcceptedRequestId = 41u
            )
        )

        val store = RegattaLinkConfiguredDeviceStore(context)
        assertTrue(store.requiresNewPairing())
        assertEquals(configured, store.load())

        fakeClient.emitConnection(RegattaLinkClientState())

        assertEquals(null, store.load())
        assertTrue(store.requiresNewPairing())
        assertFalse(manager.reconnectConfigured())
        assertEquals(0, fakeClient.reconnectCalls)
    }

    @Test
    fun processRestartDuringProvisionalFactoryResetSuppressesConfiguredReconnect() {
        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                deviceAddress = configured.deviceAddress
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(deviceControlSupported = true)
        )
        assertTrue(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                0
            )
        )
        fakeClient.emitFactoryResetRecovery(true)
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                deviceControlSupported = true,
                deviceControlBusy = true,
                factoryResetWriteAcceptedRequestId = 42u
            )
        )

        val store = RegattaLinkConfiguredDeviceStore(context)
        assertTrue(store.requiresNewPairing())
        assertEquals(configured, store.load())

        manager = createManager()

        assertFalse(manager.reconnectConfigured())
        assertEquals(0, fakeClient.reconnectCalls)
        assertTrue(manager.startDiscovery())
        assertEquals(1, fakeClient.discoveryCalls)
    }

    @Test
    fun rejectedProvisionalFactoryResetReleasesPersistentRecoveryMarker() {
        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                deviceAddress = configured.deviceAddress
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(deviceControlSupported = true)
        )
        assertTrue(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                0
            )
        )
        fakeClient.emitFactoryResetRecovery(true)
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                deviceControlSupported = true,
                deviceControlBusy = true,
                factoryResetWriteAcceptedRequestId = 43u
            )
        )
        assertTrue(RegattaLinkConfiguredDeviceStore(context).requiresNewPairing())

        fakeClient.emitFactoryResetRecovery(false)
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                deviceControlSupported = true,
                deviceControlBusy = false,
                deviceControlStatus = RegattaLinkDeviceControlStatus(
                    opcode = RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                    phase = RegattaLinkDeviceControlPhase.ERROR,
                    result = RegattaLinkDeviceControlResult.BUSY,
                    requestId = 43u,
                    forwardTrimDeg = 0,
                    heelTrimDeg = 0,
                    pitchTrimDeg = 0,
                    boatFrameValid = false,
                    gyroBiasValid = false,
                    mountingEpoch = 0u,
                    applicationErrorCode = 3
                ),
                deviceControlError = "RegattaLink Device Control is busy"
            )
        )

        val store = RegattaLinkConfiguredDeviceStore(context)
        assertFalse(store.requiresNewPairing())
        assertEquals(configured, store.load())
    }

    @Test
    fun factoryResetMotionRejectKeepsExpectedDisconnectOwnership() {
        assertFactoryResetCalibrationRejectKeepsExpectedDisconnectOwnership(
            RegattaLinkDeviceControlResult.MOTION_REJECT
        )
    }

    @Test
    fun factoryResetOrientationRejectKeepsExpectedDisconnectOwnership() {
        assertFactoryResetCalibrationRejectKeepsExpectedDisconnectOwnership(
            RegattaLinkDeviceControlResult.ORIENTATION_REJECT
        )
    }

    private fun assertFactoryResetCalibrationRejectKeepsExpectedDisconnectOwnership(
        result: RegattaLinkDeviceControlResult
    ) {
        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                deviceAddress = configured.deviceAddress
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(deviceControlSupported = true)
        )
        assertTrue(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                0
            )
        )

        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                deviceControlSupported = true,
                deviceControlBusy = true,
                deviceControlAcceptedOpcode = RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                deviceControlAcceptedRequestId = 55u
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                deviceControlSupported = true,
                deviceControlBusy = true,
                factoryResetAwaitingDisconnect = true,
                deviceControlStatus = RegattaLinkDeviceControlStatus(
                    opcode = RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                    phase = RegattaLinkDeviceControlPhase.ERROR,
                    result = result,
                    requestId = 55u,
                    forwardTrimDeg = 0,
                    heelTrimDeg = 0,
                    pitchTrimDeg = 0,
                    boatFrameValid = false,
                    gyroBiasValid = false,
                    mountingEpoch = 9u
                ),
                deviceControlError = regattaLinkDeviceControlFailureText(result)
            )
        )

        assertEquals(configured, manager.configuredDevice())
        fakeClient.emitUnexpectedDisconnect()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, fakeClient.reconnectCalls)

        fakeClient.emitConnection(RegattaLinkClientState())
        assertEquals(null, manager.configuredDevice())
        assertFalse(manager.reconnectConfigured())
        assertEquals(0, fakeClient.disconnectCalls)
    }

    @Test
    fun queuedButUnacceptedFactoryResetStillAllowsManualDisconnect() {
        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                deviceAddress = configured.deviceAddress
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(deviceControlSupported = true)
        )
        assertTrue(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                0
            )
        )

        manager.disconnect()

        assertEquals(1, fakeClient.disconnectCalls)
        assertEquals(configured, manager.configuredDevice())
    }

    @Test
    fun acceptedFactoryResetBlocksManualDisconnectUntilFirmwareDisconnects() {
        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                deviceAddress = configured.deviceAddress
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(deviceControlSupported = true)
        )
        assertTrue(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                0
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                deviceControlSupported = true,
                deviceControlBusy = true,
                deviceControlAcceptedOpcode = RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                deviceControlAcceptedRequestId = 59u
            )
        )

        manager.disconnect()

        assertEquals(0, fakeClient.disconnectCalls)
        assertEquals(configured, manager.configuredDevice())

        fakeClient.emitConnection(RegattaLinkClientState())

        assertEquals(null, manager.configuredDevice())
        assertFalse(manager.reconnectConfigured())
    }

    @Test
    fun acceptedFactoryResetRediscoveryDoesNotClearConfiguredAssociation() {
        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                deviceAddress = configured.deviceAddress
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(deviceControlSupported = true)
        )
        assertTrue(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                0
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                deviceControlSupported = true,
                deviceControlBusy = true,
                deviceControlAcceptedOpcode = RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                deviceControlAcceptedRequestId = 61u
            )
        )

        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.DISCOVERING,
                deviceAddress = configured.deviceAddress
            )
        )
        assertEquals(configured, manager.configuredDevice())

        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.READING_DEVICE_INFO,
                deviceAddress = configured.deviceAddress
            )
        )
        assertEquals(configured, manager.configuredDevice())
        assertFalse(manager.startDiscovery())
        assertEquals(0, fakeClient.discoveryCalls)
        assertEquals(0, fakeClient.disconnectCalls)
    }

    @Test
    fun confirmedFactoryResetBondWipeClearsAssociationBeforeDisconnect() {
        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                deviceAddress = configured.deviceAddress
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(deviceControlSupported = true)
        )
        assertTrue(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                0
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                deviceControlSupported = true,
                deviceControlBusy = true,
                deviceControlAcceptedOpcode = RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                deviceControlAcceptedRequestId = 71u
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                deviceControlSupported = true,
                deviceControlBusy = true,
                factoryResetAwaitingDisconnect = true,
                deviceControlStatus = RegattaLinkDeviceControlStatus(
                    opcode = RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                    phase = RegattaLinkDeviceControlPhase.SUCCESS,
                    result = RegattaLinkDeviceControlResult.OK,
                    requestId = 71u,
                    forwardTrimDeg = 0,
                    heelTrimDeg = 0,
                    pitchTrimDeg = 0,
                    boatFrameValid = false,
                    gyroBiasValid = false,
                    mountingEpoch = 12u,
                    factoryResetBondsCleared = true
                )
            )
        )

        assertEquals(null, manager.configuredDevice())
        assertFalse(manager.reconnectConfigured())
        assertEquals(0, fakeClient.disconnectCalls)
    }

    @Test
    fun factoryResetSuccessWaitsForFirmwareDisconnectAndObservesLateBondResetError() {
        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                deviceAddress = configured.deviceAddress
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(deviceControlSupported = true)
        )
        assertTrue(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                0
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                deviceControlSupported = true,
                deviceControlBusy = true,
                deviceControlAcceptedOpcode = RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                deviceControlAcceptedRequestId = 73u
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                deviceControlSupported = true,
                deviceControlBusy = true,
                factoryResetAwaitingDisconnect = true,
                deviceControlStatus = RegattaLinkDeviceControlStatus(
                    opcode = RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                    phase = RegattaLinkDeviceControlPhase.SUCCESS,
                    result = RegattaLinkDeviceControlResult.OK,
                    requestId = 73u,
                    forwardTrimDeg = 0,
                    heelTrimDeg = 0,
                    pitchTrimDeg = 0,
                    boatFrameValid = true,
                    gyroBiasValid = true,
                    mountingEpoch = 11u
                )
            )
        )

        assertEquals(configured, manager.configuredDevice())
        assertEquals(0, fakeClient.disconnectCalls)

        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                deviceControlSupported = true,
                deviceControlBusy = false,
                factoryResetAwaitingDisconnect = false,
                deviceControlStatus = RegattaLinkDeviceControlStatus(
                    opcode = RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                    phase = RegattaLinkDeviceControlPhase.ERROR,
                    result = RegattaLinkDeviceControlResult.BOND_RESET_ERROR,
                    requestId = 73u,
                    forwardTrimDeg = 0,
                    heelTrimDeg = 0,
                    pitchTrimDeg = 0,
                    boatFrameValid = true,
                    gyroBiasValid = true,
                    mountingEpoch = 11u
                ),
                deviceControlError = regattaLinkDeviceControlFailureText(
                    RegattaLinkDeviceControlResult.BOND_RESET_ERROR
                )
            )
        )

        assertEquals(configured, manager.configuredDevice())
        assertEquals(0, fakeClient.disconnectCalls)

        fakeClient.emitUnexpectedDisconnect()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, fakeClient.reconnectCalls)
    }

    @Test
    fun factoryResetQueuedButNotAcceptedDoesNotOwnDisconnectOrClearAssociation() {
        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED,
                deviceAddress = configured.deviceAddress
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(deviceControlSupported = true)
        )

        assertTrue(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                0
            )
        )

        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.DISCOVERING,
                deviceAddress = configured.deviceAddress
            )
        )
        assertEquals(configured, manager.configuredDevice())

        fakeClient.emitUnexpectedDisconnect()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, fakeClient.reconnectCalls)
        assertEquals(configured, manager.configuredDevice())
    }

    @Test
    fun acceptedFactoryResetStillBlocksOtaAfterPollingFinishes() {
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(deviceControlSupported = true)
        )
        assertTrue(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                0
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                deviceControlSupported = true,
                deviceControlBusy = true,
                deviceControlAcceptedOpcode =
                    RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                deviceControlAcceptedRequestId = 81u
            )
        )
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                deviceControlSupported = true,
                deviceControlBusy = false,
                factoryResetAwaitingDisconnect = true,
                deviceControlStatus = RegattaLinkDeviceControlStatus(
                    opcode = RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                    phase = RegattaLinkDeviceControlPhase.SUCCESS,
                    result = RegattaLinkDeviceControlResult.OK,
                    requestId = 81u,
                    forwardTrimDeg = 0,
                    heelTrimDeg = 0,
                    pitchTrimDeg = 0,
                    boatFrameValid = false,
                    gyroBiasValid = false,
                    mountingEpoch = 3u,
                    factoryResetBondsCleared = true
                )
            )
        )

        manager.startOta(testFirmwareArtifact())

        assertEquals(0, fakeClient.otaStartCalls)
    }

    @Test
    fun factoryResetQueuedButNotAcceptedDoesNotClaimOtaLifecycle() {
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(deviceControlSupported = true)
        )

        assertTrue(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                0
            )
        )
        manager.startOta(testFirmwareArtifact())

        assertEquals(1, fakeClient.otaStartCalls)
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
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                diagnosticLogSupported = true,
                deviceControlSupported = true
            )
        )
        fakeClient.emitOta(
            RegattaLinkOtaUiState(
                phase = RegattaLinkOtaPhase.TRANSFERRING
            )
        )

        assertFalse(manager.setDeviceName("Race-Link"))
        assertFalse(manager.setLedBrightness(75))
        assertFalse(manager.drainDiagnosticLog())
        assertFalse(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.SET_UPRIGHT,
                0
            )
        )
        assertFalse(manager.refreshPgnInventory())
        assertFalse(manager.readRawCanFrames())

        assertEquals(0, fakeClient.setNameCalls)
        assertEquals(0, fakeClient.setBrightnessCalls)
        assertEquals(0, fakeClient.diagnosticDrainCalls)
        assertEquals(0, fakeClient.deviceControlCalls)
        assertEquals(0, fakeClient.refreshPgnCalls)
        assertEquals(0, fakeClient.rawReadCalls)
    }

    @Test
    fun idleManagerDelegatesDiagnosticAndNonDestructiveDeviceControl() {
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                diagnosticLogSupported = true,
                deviceControlSupported = true
            )
        )

        assertTrue(manager.drainDiagnosticLog())
        assertTrue(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.SET_UPRIGHT,
                0
            )
        )

        assertEquals(1, fakeClient.diagnosticDrainCalls)
        assertEquals(1, fakeClient.deviceControlCalls)
        assertEquals(
            RegattaLinkDeviceControlOpcode.SET_UPRIGHT,
            fakeClient.lastDeviceControlOpcode
        )
        assertEquals(0, fakeClient.lastDeviceControlValue)
    }

    @Test
    fun diagnosticAndDeviceControlBusyStatesAreMutuallyExclusiveAtManager() {
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                diagnosticLogSupported = true,
                deviceControlSupported = true,
                deviceControlBusy = true
            )
        )
        assertFalse(manager.drainDiagnosticLog())

        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                diagnosticLogSupported = true,
                deviceControlSupported = true,
                diagnosticLogLoading = true
            )
        )
        assertFalse(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.ADJUST_HEEL,
                1
            )
        )
    }

    @Test
    fun trimCommandsRequireValidBoatFrameAtManagerBoundary() {
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                deviceControlSupported = true,
                deviceControlStatus = RegattaLinkDeviceControlStatus(
                    opcode = null,
                    phase = RegattaLinkDeviceControlPhase.IDLE,
                    result = RegattaLinkDeviceControlResult.NONE,
                    requestId = 0u,
                    forwardTrimDeg = 0,
                    heelTrimDeg = 0,
                    pitchTrimDeg = 0,
                    boatFrameValid = false,
                    gyroBiasValid = true,
                    mountingEpoch = 1u
                )
            )
        )

        assertFalse(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.ADJUST_FORWARD,
                1
            )
        )
        assertEquals(0, fakeClient.deviceControlCalls)

        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                deviceControlSupported = true,
                deviceControlStatus = RegattaLinkDeviceControlStatus(
                    opcode = null,
                    phase = RegattaLinkDeviceControlPhase.IDLE,
                    result = RegattaLinkDeviceControlResult.NONE,
                    requestId = 0u,
                    forwardTrimDeg = 0,
                    heelTrimDeg = 0,
                    pitchTrimDeg = 0,
                    boatFrameValid = true,
                    gyroBiasValid = true,
                    mountingEpoch = 1u
                )
            )
        )

        assertTrue(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.ADJUST_FORWARD,
                1
            )
        )
        assertEquals(1, fakeClient.deviceControlCalls)
    }

    @Test
    fun deviceControlBusyPreventsOtaStartAtManagerBoundary() {
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(deviceControlBusy = true)
        )

        manager.startOta(testFirmwareArtifact())

        assertEquals(0, fakeClient.otaStartCalls)
    }

    @Test
    fun oldFirmwareWithout0007Or0008RejectsOnlyThoseOptionalActions() {
        assertFalse(manager.drainDiagnosticLog())
        assertFalse(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.SET_UPRIGHT,
                0
            )
        )
        assertEquals(0, fakeClient.diagnosticDrainCalls)
        assertEquals(0, fakeClient.deviceControlCalls)
    }

    @Test
    fun firstHalfDoesNotExposeFactoryResetBeforeDisconnectLifecycleExists() {
        assertFalse(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.FACTORY_RESET,
                0
            )
        )
        assertEquals(0, fakeClient.deviceControlCalls)
    }

    @Test
    fun rawCaptureStreamsFramesAndFinalizesCsv() {
        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED
            )
        )
        fakeClient.emitNmea(
            RegattaLinkNmeaState(rawCanSupported = true)
        )

        assertTrue(manager.startRawCanCapture())
        assertEquals(
            RegattaLinkRawCapturePhase.FLUSHING,
            manager.currentRawCaptureState().phase
        )

        fakeClient.captureRecordingStarted?.invoke()
        assertEquals(
            RegattaLinkRawCapturePhase.CAPTURING,
            manager.currentRawCaptureState().phase
        )

        fakeClient.captureFrame?.invoke(
            RegattaLinkRawCanFrame(
                timestampUsLow = 42L,
                canId = (3L shl 26) or (129025L shl 8) or 0x45L,
                dlc = 2,
                data = byteArrayOf(0x12, 0x34, 0, 0, 0, 0, 0, 0)
            )
        )
        fakeClient.captureFinished?.invoke(
            RegattaLinkRawCaptureEndReason.USER_STOP,
            ""
        )

        val state = manager.currentRawCaptureState()
        assertEquals(RegattaLinkRawCapturePhase.COMPLETED, state.phase)
        assertEquals(1, state.frameCount)
        val file = java.io.File(requireNotNull(state.filePath))
        assertTrue(file.exists())
        val lines = file.readLines(Charsets.UTF_8)
        assertEquals(REGATTALINK_RAW_CAPTURE_CSV_HEADER, lines[0])
        assertEquals(
            "42,234357061,129025,3,69,,2,1234",
            lines[1]
        )

        assertTrue(manager.discardRawCanCapture())
        assertFalse(file.exists())
        assertEquals(
            RegattaLinkRawCapturePhase.IDLE,
            manager.currentRawCaptureState().phase
        )
    }

    @Test
    fun activeRawCaptureBlocksOtherOptionalGattActionsAndDisconnectInterrupts() {
        fakeClient.emitConfiguration(
            RegattaLinkConfigurationState(
                diagnosticLogSupported = true,
                deviceControlSupported = true
            )
        )
        fakeClient.emitConnection(
            RegattaLinkClientState(
                status = RegattaLinkConnectionStatus.CONNECTED
            )
        )
        fakeClient.emitNmea(
            RegattaLinkNmeaState(rawCanSupported = true)
        )

        assertTrue(manager.startRawCanCapture())
        assertFalse(manager.setDeviceName("Race-Link"))
        assertFalse(manager.setLedBrightness(75))
        assertFalse(manager.drainDiagnosticLog())
        assertFalse(
            manager.executeDeviceControl(
                RegattaLinkDeviceControlOpcode.ADJUST_FORWARD,
                1
            )
        )
        assertFalse(manager.refreshPgnInventory())
        assertFalse(manager.readRawCanFrames())

        manager.disconnect()

        assertEquals(
            RegattaLinkRawCaptureStopReason.INTERRUPTED,
            fakeClient.lastCaptureStopReason
        )
        assertEquals(
            RegattaLinkRawCapturePhase.INTERRUPTED,
            manager.currentRawCaptureState().phase
        )
        assertEquals(1, fakeClient.disconnectCalls)
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
        private val onFactoryResetRecoveryStateChanged: (Boolean) -> Unit,
        private val onUnexpectedDisconnect: () -> Unit
    ) : RegattaLinkConnectionClient {
        var discoveryAccepted = true
        var reconnectAccepted = true
        var discoveryCalls = 0
        var reconnectCalls = 0
        var disconnectCalls = 0
        var otaStartCalls = 0
        var setNameCalls = 0
        var setBrightnessCalls = 0
        var diagnosticDrainCalls = 0
        var deviceControlCalls = 0
        var refreshPgnCalls = 0
        var rawReadCalls = 0
        var captureStartCalls = 0
        var captureRecordingStarted: (() -> Unit)? = null
        var captureFrame: ((RegattaLinkRawCanFrame) -> Unit)? = null
        var captureFinished:
            ((RegattaLinkRawCaptureEndReason, String) -> Unit)? = null
        var lastCaptureStopReason: RegattaLinkRawCaptureStopReason? = null
        var lastDeviceControlOpcode: RegattaLinkDeviceControlOpcode? = null
        var lastDeviceControlValue: Int? = null
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

        override fun startOta(artifact: RegattaLinkFirmwareArtifact) {
            otaStartCalls += 1
        }

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

        override fun drainDiagnosticLog(): Boolean {
            diagnosticDrainCalls += 1
            return true
        }

        override fun executeDeviceControl(
            opcode: RegattaLinkDeviceControlOpcode,
            value: Int
        ): Boolean {
            deviceControlCalls += 1
            lastDeviceControlOpcode = opcode
            lastDeviceControlValue = value
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

        override fun startRawCanCapture(
            onRecordingStarted: () -> Unit,
            onFrame: (RegattaLinkRawCanFrame) -> Unit,
            onFinished: (RegattaLinkRawCaptureEndReason, String) -> Unit
        ): Boolean {
            captureStartCalls += 1
            captureRecordingStarted = onRecordingStarted
            captureFrame = onFrame
            captureFinished = onFinished
            return true
        }

        override fun stopRawCanCapture(
            reason: RegattaLinkRawCaptureStopReason
        ) {
            lastCaptureStopReason = reason
            captureFinished?.invoke(
                if (reason == RegattaLinkRawCaptureStopReason.USER) {
                    RegattaLinkRawCaptureEndReason.USER_STOP
                } else {
                    RegattaLinkRawCaptureEndReason.INTERRUPTED
                },
                ""
            )
            captureFinished = null
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

        fun emitNmea(state: RegattaLinkNmeaState) {
            onNmeaStateChanged(state)
        }

        fun emitFactoryResetRecovery(pending: Boolean) {
            onFactoryResetRecoveryStateChanged(pending)
        }

        fun emitUnexpectedDisconnect() {
            onUnexpectedDisconnect()
        }
    }
}
