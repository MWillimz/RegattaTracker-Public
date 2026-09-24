package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.ArrayDeque

class RegattaLinkOtaEngineTest {

    @Test
    fun stalePreparingNotificationAfterReceivingSnapshot_isIgnored() {
        val image = ByteArray(32) { it.toByte() }
        val artifact = artifact(image, build = 22880000uL)
        val deviceInfo = deviceInfo(runningBuild = 22865706uL)
        val transport = StalePreparingTransport(deviceInfo, artifact)
        val states = mutableListOf<RegattaLinkOtaUiState>()
        var terminalDisconnectCleanupCount = 0

        RegattaLinkOtaEngine(
            artifact = artifact,
            initialDeviceInfo = deviceInfo,
            transport = transport,
            cancelled = { false },
            emit = states::add,
            onTerminalDisconnect = { terminalDisconnectCleanupCount += 1 }
        ).run()

        assertTrue(transport.stalePreparingDelivered)
        assertEquals(RegattaLinkOtaPhase.SUCCESS, states.last().phase)
        assertEquals(image.size, states.last().committedBytes)
        assertEquals(2, transport.reconnectCandidateCount)
        assertEquals(listOf("reconnect-1", "tune", "reconnect-2", "tune"), transport.lifecycleEvents)
        assertEquals(1, transport.closeCurrentConnectionCount)
        assertEquals(0, terminalDisconnectCleanupCount)
    }

    @Test
    fun freshReconnectFailureStopsBeforeOtaStart() {
        val image = ByteArray(32) { it.toByte() }
        val artifact = artifact(image, build = 22880000uL)
        val deviceInfo = deviceInfo(runningBuild = 22865706uL)
        val transport = StalePreparingTransport(
            initialDeviceInfo = deviceInfo,
            artifact = artifact,
            failPreTransferReconnect = true
        )
        val states = mutableListOf<RegattaLinkOtaUiState>()
        var terminalDisconnectCleanupCount = 0

        RegattaLinkOtaEngine(
            artifact = artifact,
            initialDeviceInfo = deviceInfo,
            transport = transport,
            cancelled = { false },
            emit = states::add,
            onTerminalDisconnect = { terminalDisconnectCleanupCount += 1 }
        ).run()

        assertEquals(1, transport.reconnectCandidateCount)
        assertEquals(listOf("reconnect-1"), transport.lifecycleEvents)
        assertEquals(0, transport.writeControlCalls)
        assertEquals(RegattaLinkOtaPhase.ERROR, states.last().phase)
        assertTrue(states.last().error.contains("fresh BLE connection"))
        assertEquals(1, transport.closeCurrentConnectionCount)
        assertEquals(1, terminalDisconnectCleanupCount)
    }

    @Test
    fun newerPreparingNotificationDuringTransfer_remainsFatal() {
        val image = ByteArray(32) { it.toByte() }
        val artifact = artifact(image, build = 22880000uL)
        val deviceInfo = deviceInfo(runningBuild = 22865706uL)
        val transport = StalePreparingTransport(
            initialDeviceInfo = deviceInfo,
            artifact = artifact,
            queuedPreparingRevision = 3u
        )
        val states = mutableListOf<RegattaLinkOtaUiState>()
        val terminalEvents = mutableListOf<String>()

        RegattaLinkOtaEngine(
            artifact = artifact,
            initialDeviceInfo = deviceInfo,
            transport = transport,
            cancelled = { false },
            emit = { state ->
                states += state
                if (state.phase == RegattaLinkOtaPhase.ERROR) {
                    terminalEvents += "emit-error"
                }
            },
            onTerminalDisconnect = {
                assertTrue(!transport.isConnected())
                terminalEvents += "cleanup"
            }
        ).run()

        assertEquals(RegattaLinkOtaPhase.ERROR, states.last().phase)
        assertTrue(
            states.last().error.contains(
                "Unexpected OTA state during transfer: PREPARING"
            )
        )
        assertEquals(1, transport.closeCurrentConnectionCount)
        assertEquals(listOf("cleanup", "emit-error"), terminalEvents)
    }

    @Test
    fun cancellationCleansDisconnectedStateBeforeTerminalEmit() {
        val image = ByteArray(32) { it.toByte() }
        val artifact = artifact(image, build = 22880000uL)
        val deviceInfo = deviceInfo(runningBuild = 22865706uL)
        val transport = StalePreparingTransport(deviceInfo, artifact)
        val states = mutableListOf<RegattaLinkOtaUiState>()
        val terminalEvents = mutableListOf<String>()

        RegattaLinkOtaEngine(
            artifact = artifact,
            initialDeviceInfo = deviceInfo,
            transport = transport,
            cancelled = { true },
            emit = { state ->
                states += state
                if (state.phase == RegattaLinkOtaPhase.CANCELLED) {
                    terminalEvents += "emit-cancelled"
                }
            },
            onTerminalDisconnect = {
                assertTrue(!transport.isConnected())
                terminalEvents += "cleanup"
            }
        ).run()

        assertEquals(RegattaLinkOtaPhase.CANCELLED, states.last().phase)
        assertEquals(1, transport.closeCurrentConnectionCount)
        assertEquals(listOf("cleanup", "emit-cancelled"), terminalEvents)
    }

    private class StalePreparingTransport(
        private val initialDeviceInfo: RegattaLinkDeviceInfo,
        private val artifact: RegattaLinkFirmwareArtifact,
        private val queuedPreparingRevision: UInt = 1u,
        private val failPreTransferReconnect: Boolean = false
    ) : RegattaLinkOtaTransport {
        override val mtu: Int = 247

        private val progressQueue = ArrayDeque<RegattaLinkOtaProgress>()
        private var connected = true
        private var requestId = 0u
        private val otaSession = 7u

        var stalePreparingDelivered = false
            private set

        var reconnectCandidateCount = 0
            private set

        var writeControlCalls = 0
            private set

        val lifecycleEvents = mutableListOf<String>()

        var closeCurrentConnectionCount = 0
            private set

        private var status = RegattaLinkOtaStatus(
            revision = 0u,
            session = 0u,
            acceptedOffset = 0u,
            totalSize = 0u,
            state = RegattaLinkOtaDeviceState.IDLE,
            error = 0,
            maxDataPayload = 236,
            requestId = 0u,
            runningBuild = initialDeviceInfo.runningBuild,
            targetBuild = 0uL,
            bootResult = RegattaLinkOtaBootResult.VALIDATED,
            assembling = false,
            assembledBytes = 0
        )

        override fun isConnected(): Boolean = connected

        override fun tuneConnection(info: RegattaLinkDeviceInfo) {
            lifecycleEvents += "tune"
        }

        override fun enableStatusNotifications() = Unit

        override fun snapshot(): RegattaLinkOtaStatus = status

        override fun writeControl(value: ByteArray) {
            writeControlCalls += 1
            val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
            when (value[0].toInt() and 0xff) {
                0x01 -> {
                    requestId = buffer.getInt(1).toUInt()
                }

                0x03 -> {
                    status = status.copy(
                        revision = 2u,
                        session = otaSession,
                        acceptedOffset = 0u,
                        totalSize = artifact.image.size.toUInt(),
                        state = RegattaLinkOtaDeviceState.RECEIVING,
                        error = 0,
                        requestId = requestId,
                        targetBuild = artifact.manifest.buildNumber,
                        assembling = false,
                        assembledBytes = 0
                    )
                    progressQueue.addLast(
                        RegattaLinkOtaProgress(
                            revision = queuedPreparingRevision,
                            session = otaSession,
                            acceptedOffset = 0u,
                            totalSize = artifact.image.size.toUInt(),
                            state = RegattaLinkOtaDeviceState.PREPARING,
                            error = 0,
                            maxDataPayload = status.maxDataPayload
                        )
                    )
                }

                0x04 -> {
                    status = status.copy(
                        revision = status.revision + 1u,
                        state = RegattaLinkOtaDeviceState.READY_TO_REBOOT
                    )
                }
            }
        }

        override fun canWriteDataWithoutResponse(valueSize: Int): Boolean =
            valueSize <= mtu - 3

        override fun canWriteDataWithResponse(valueSize: Int): Boolean = false

        override fun submitDataWithoutResponse(value: ByteArray): RegattaLinkOtaSubmitResult {
            val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(otaSession, buffer.getInt(0).toUInt())
            val offset = buffer.getInt(4)
            assertEquals(status.acceptedOffset.toInt(), offset)

            val committed = offset + value.size - 8
            val nextRevision =
                if (isRegattaLinkOtaRevisionNewer(queuedPreparingRevision, status.revision)) {
                    queuedPreparingRevision + 1u
                } else {
                    status.revision + 1u
                }
            status = status.copy(
                revision = nextRevision,
                acceptedOffset = committed.toUInt(),
                state = RegattaLinkOtaDeviceState.RECEIVING
            )
            progressQueue.addLast(
                RegattaLinkOtaProgress(
                    revision = status.revision,
                    session = otaSession,
                    acceptedOffset = status.acceptedOffset,
                    totalSize = status.totalSize,
                    state = status.state,
                    error = status.error,
                    maxDataPayload = status.maxDataPayload
                )
            )
            return RegattaLinkOtaSubmitResult.ACCEPTED
        }

        override fun writeDataWithResponse(value: ByteArray) {
            throw AssertionError("write-with-response is not expected in this regression")
        }

        override fun pollProgress(timeoutMs: Long): RegattaLinkOtaProgress? {
            if (progressQueue.isEmpty()) return null
            return progressQueue.removeFirst().also {
                if (it.state == RegattaLinkOtaDeviceState.PREPARING) {
                    stalePreparingDelivered = true
                }
            }
        }

        override fun consumeDataTransportError(): String? = null

        override fun closeCurrentConnection() {
            closeCurrentConnectionCount += 1
            connected = false
        }

        override fun reconnectCandidate(
            expectedStableId: String,
            timeoutMs: Long
        ): RegattaLinkDeviceInfo? {
            reconnectCandidateCount += 1
            lifecycleEvents += "reconnect-$reconnectCandidateCount"
            connected = true

            if (reconnectCandidateCount == 1) {
                if (failPreTransferReconnect) {
                    connected = false
                    return null
                }
                return initialDeviceInfo.copy(stableId = expectedStableId)
            }

            status = RegattaLinkOtaStatus(
                revision = 1u,
                session = 0u,
                acceptedOffset = 0u,
                totalSize = 0u,
                state = RegattaLinkOtaDeviceState.IDLE,
                error = 0,
                maxDataPayload = 236,
                requestId = 0u,
                runningBuild = artifact.manifest.buildNumber,
                targetBuild = 0uL,
                bootResult = RegattaLinkOtaBootResult.VALIDATED,
                assembling = false,
                assembledBytes = 0
            )
            return initialDeviceInfo.copy(
                stableId = expectedStableId,
                runningBuild = artifact.manifest.buildNumber
            )
        }
    }

    private fun artifact(
        image: ByteArray,
        build: ULong
    ): RegattaLinkFirmwareArtifact {
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(image)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return RegattaLinkFirmwareArtifact(
            manifest = RegattaLinkFirmwareManifest(
                schemaVersion = 1,
                product = "RegattaLink",
                target = "esp32c3",
                hardwareProfile = "esp32c3-wroom02-4mb",
                buildNumber = build,
                filename = "regattalink.bin",
                size = image.size,
                sha256 = sha,
                signed = true,
                signingKeySha256 = "a".repeat(64),
                downloadUrl = REGATTALINK_DOWNLOAD_URL
            ),
            image = image
        )
    }

    private fun deviceInfo(runningBuild: ULong): RegattaLinkDeviceInfo =
        RegattaLinkDeviceInfo(
            protocolMajor = 1,
            protocolMinor = 0,
            capabilities =
                0x00000002u or
                    0x00000004u or
                    0x00000010u or
                    0x00000020u or
                    0x00000100u,
            stableId = "44:b1:76:48:31:ce",
            productId = REGATTALINK_PRODUCT_ID,
            profileId = REGATTALINK_PROFILE_ID,
            runningBuild = runningBuild,
            otaSlotSize = 1_572_864u,
            maxInflightBlocks = 16
        )
}
