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

        RegattaLinkOtaEngine(
            artifact = artifact,
            initialDeviceInfo = deviceInfo,
            transport = transport,
            cancelled = { false },
            emit = states::add
        ).run()

        assertTrue(transport.stalePreparingDelivered)
        assertEquals(RegattaLinkOtaPhase.SUCCESS, states.last().phase)
        assertEquals(image.size, states.last().committedBytes)
    }

    private class StalePreparingTransport(
        private val initialDeviceInfo: RegattaLinkDeviceInfo,
        private val artifact: RegattaLinkFirmwareArtifact
    ) : RegattaLinkOtaTransport {
        override val mtu: Int = 247

        private val progressQueue = ArrayDeque<RegattaLinkOtaProgress>()
        private var connected = true
        private var requestId = 0u
        private val otaSession = 7u

        var stalePreparingDelivered = false
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

        override fun tuneConnection(info: RegattaLinkDeviceInfo) = Unit

        override fun enableStatusNotifications() = Unit

        override fun snapshot(): RegattaLinkOtaStatus = status

        override fun writeControl(value: ByteArray) {
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
                            revision = 1u,
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
            status = status.copy(
                revision = status.revision + 1u,
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
            connected = false
        }

        override fun reconnectCandidate(
            expectedStableId: String,
            timeoutMs: Long
        ): RegattaLinkDeviceInfo {
            connected = true
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
