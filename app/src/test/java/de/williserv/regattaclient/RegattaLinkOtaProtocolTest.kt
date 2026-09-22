package de.williserv.regattaclient

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class RegattaLinkOtaProtocolTest {

    @Test
    fun throughputThresholds_matchSupportedDevicePolicy() {
        assertEquals(10.0, REGATTALINK_OTA_SLOW_LINK_THROUGHPUT_KIB_S, 0.0)
        assertEquals(4.0, REGATTALINK_OTA_MIN_THROUGHPUT_KIB_S, 0.0)
    }

    @Test
    fun metadataEncoding_matchesWireContract() {
        val image = ByteArray(256) { it.toByte() }
        val artifact = artifact(image, build = 123456uL)

        val metadata = encodeRegattaLinkOtaMetadata(artifact)
        val buffer = ByteBuffer.wrap(metadata).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(REGATTALINK_OTA_METADATA_SIZE, metadata.size)
        assertEquals(REGATTALINK_PRODUCT_ID, buffer.getShort(0).toInt())
        assertEquals(REGATTALINK_PROFILE_ID, buffer.getShort(2).toInt())
        assertEquals(image.size, buffer.getInt(4))
        assertEquals(123456L, buffer.getLong(8))
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest(image),
            metadata.copyOfRange(16, 48)
        )
    }

    @Test
    fun startMetadata_fragmentsRespectNegotiatedMtu() {
        val metadata = ByteArray(REGATTALINK_OTA_METADATA_SIZE) { it.toByte() }
        val request = 0x12345678u

        val small = encodeRegattaLinkOtaStartFragments(request, metadata, 23)
        assertEquals(4, small.size)
        assertTrue(small.all { it.size <= 20 })

        val large = encodeRegattaLinkOtaStartFragments(request, metadata, 247)
        assertEquals(1, large.size)
        assertEquals(55, large.single().size)

        val reconstructed = ByteArray(REGATTALINK_OTA_METADATA_SIZE)
        for (fragment in small) {
            val buffer = ByteBuffer.wrap(fragment).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x02, buffer.get(0).toInt())
            assertEquals(request.toInt(), buffer.getInt(1))
            val offset = buffer.getShort(5).toInt() and 0xffff
            fragment.copyOfRange(7, fragment.size).copyInto(reconstructed, offset)
        }
        assertArrayEquals(metadata, reconstructed)
    }

    @Test
    fun statusAndProgressParsing_matchWireLayout() {
        val raw = ByteBuffer.allocate(REGATTALINK_OTA_STATUS_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(7)
            .putInt(42)
            .putInt(2360)
            .putInt(592112)
            .put(2)
            .put(0)
            .putShort(236)
            .putInt(99)
            .putLong(22862416L)
            .putLong(22862548L)
            .put(1)
            .put(0)
            .putShort(48)
            .array()

        val status = parseRegattaLinkOtaStatus(raw)
        assertEquals(7u, status.revision)
        assertEquals(42u, status.session)
        assertEquals(2360u, status.acceptedOffset)
        assertEquals(592112u, status.totalSize)
        assertEquals(RegattaLinkOtaDeviceState.RECEIVING, status.state)
        assertEquals(236, status.maxDataPayload)
        assertEquals(99u, status.requestId)
        assertEquals(22862416uL, status.runningBuild)
        assertEquals(22862548uL, status.targetBuild)
        assertEquals(RegattaLinkOtaBootResult.VALIDATED, status.bootResult)

        val progress = parseRegattaLinkOtaProgress(raw.copyOfRange(0, 20))
        assertEquals(status.revision, progress.revision)
        assertEquals(status.session, progress.session)
        assertEquals(status.acceptedOffset, progress.acceptedOffset)
        assertEquals(status.totalSize, progress.totalSize)
        assertEquals(status.state, progress.state)
        assertEquals(status.maxDataPayload, progress.maxDataPayload)
    }

    @Test
    fun currentOtaClient_requiresPipelineSnapshotAndTransport() {
        val artifact = artifact(ByteArray(256), build = 200uL)

        assertThrows(IllegalArgumentException::class.java) {
            validateRegattaLinkOtaDevice(
                deviceInfo(capabilities = 0x00000002u),
                artifact
            )
        }

        validateRegattaLinkOtaDevice(
            deviceInfo(
                capabilities =
                    0x00000002u or
                        0x00000010u or
                        0x00000020u or
                        0x00000100u
            ),
            artifact
        )
    }

    @Test
    fun committedProgressMustLandOnAdmittedBoundary() {
        validateRegattaLinkCommittedOffset(
            previousOffset = 100,
            candidateOffset = 200,
            lastAdmittedOffset = 300,
            admittedBoundaries = listOf(200, 300)
        )

        assertThrows(IllegalArgumentException::class.java) {
            validateRegattaLinkCommittedOffset(
                previousOffset = 100,
                candidateOffset = 150,
                lastAdmittedOffset = 300,
                admittedBoundaries = listOf(200, 300)
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            validateRegattaLinkCommittedOffset(
                previousOffset = 200,
                candidateOffset = 400,
                lastAdmittedOffset = 300,
                admittedBoundaries = listOf(300)
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
        val manifest = RegattaLinkFirmwareManifest(
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
        )
        return RegattaLinkFirmwareArtifact(manifest, image)
    }

    private fun deviceInfo(
        capabilities: UInt
    ) = RegattaLinkDeviceInfo(
        protocolMajor = 1,
        protocolMinor = 0,
        capabilities = capabilities,
        stableId = "44:b1:76:48:31:ce",
        productId = REGATTALINK_PRODUCT_ID,
        profileId = REGATTALINK_PROFILE_ID,
        runningBuild = 100uL,
        otaSlotSize = 1_572_864u,
        maxInflightBlocks = 16
    )
}
