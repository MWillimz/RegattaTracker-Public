package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

class RegattaLinkFirmwareTest {

    @Test
    fun firmwareDownloadUrl_usesBuildNumberAsCacheBuster() {
        assertEquals(
            "https://regatta.example.org/regattalink/firmware?v=22834262",
            versionedRegattaLinkFirmwareDownloadUrl(
                "https://regatta.example.org/regattalink/firmware",
                22_834_262uL
            )
        )
        assertEquals(
            "https://regatta.example.org/regattalink/firmware?source=metadata&v=22834262",
            versionedRegattaLinkFirmwareDownloadUrl(
                "https://regatta.example.org/regattalink/firmware?source=metadata",
                22_834_262uL
            )
        )
    }

    @Test
    fun validArtifact_matchesDeviceAndClassifiesUpgrade() {
        val image = espApplicationImage(build = 200uL)
        val manifest = manifestFor(image, build = 200uL, signed = true)
        val device = deviceInfo(
            runningBuild = 100uL,
            capabilities = 0x00000006u
        )

        val artifact = validateRegattaLinkFirmwareArtifact(
            manifest,
            image,
            device
        )

        assertEquals(200uL, artifact.manifest.buildNumber)
        assertEquals(
            RegattaLinkFirmwareDirection.UPGRADE,
            validateRegattaLinkFirmwareForDevice(manifest, device)
        )
    }

    @Test
    fun directionSupportsDowngradeAndReinstall() {
        val image = espApplicationImage(build = 100uL)
        val manifest = manifestFor(image, build = 100uL, signed = true)

        assertEquals(
            RegattaLinkFirmwareDirection.DOWNGRADE,
            validateRegattaLinkFirmwareForDevice(
                manifest,
                deviceInfo(runningBuild = 200uL)
            )
        )
        assertEquals(
            RegattaLinkFirmwareDirection.REINSTALL,
            validateRegattaLinkFirmwareForDevice(
                manifest,
                deviceInfo(runningBuild = 100uL)
            )
        )
    }

    @Test
    fun tamperedImageIsRejected() {
        val image = espApplicationImage(build = 200uL)
        val manifest = manifestFor(image, build = 200uL, signed = true)
        image[140] = (image[140].toInt() xor 0x01).toByte()

        assertThrows(IllegalArgumentException::class.java) {
            validateRegattaLinkFirmwareImage(manifest, image)
        }
    }

    @Test
    fun wrongEspProjectIsRejected() {
        val image = espApplicationImage(build = 200uL, project = "other")
        val manifest = manifestFor(image, build = 200uL, signed = true)

        assertThrows(IllegalArgumentException::class.java) {
            validateRegattaLinkFirmwareImage(manifest, image)
        }
    }

    @Test
    fun signedDeviceRejectsUnsignedArtifact() {
        val image = espApplicationImage(build = 200uL)
        val manifest = manifestFor(image, build = 200uL, signed = false)

        assertThrows(IllegalArgumentException::class.java) {
            validateRegattaLinkFirmwareForDevice(
                manifest,
                deviceInfo(
                    runningBuild = 100uL,
                    capabilities = 0x00000006u
                )
            )
        }
    }

    @Test
    fun firmwareLargerThanReportedSlotIsRejected() {
        val image = espApplicationImage(build = 200uL)
        val manifest = manifestFor(image, build = 200uL, signed = true)

        assertThrows(IllegalArgumentException::class.java) {
            validateRegattaLinkFirmwareForDevice(
                manifest,
                deviceInfo(
                    runningBuild = 100uL,
                    otaSlotSize = (image.size - 1).toUInt()
                )
            )
        }
    }

    private fun deviceInfo(
        runningBuild: ULong,
        capabilities: UInt = 0x00000002u,
        otaSlotSize: UInt = 1_572_864u
    ) = RegattaLinkDeviceInfo(
        protocolMajor = 1,
        protocolMinor = 0,
        capabilities = capabilities,
        stableId = "44:b1:76:48:31:b2",
        productId = REGATTALINK_PRODUCT_ID,
        profileId = REGATTALINK_PROFILE_ID,
        runningBuild = runningBuild,
        otaSlotSize = otaSlotSize,
        maxInflightBlocks = 16
    )

    private fun manifestFor(
        image: ByteArray,
        build: ULong,
        signed: Boolean
    ): RegattaLinkFirmwareManifest {
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(image)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return RegattaLinkFirmwareManifest(
            schemaVersion = 1,
            product = "RegattaLink",
            target = "esp32c3",
            hardwareProfile = "esp32c3-wroom02-4mb",
            buildNumber = build,
            filename = "regattalink.bin",
            size = image.size,
            sha256 = sha256,
            signed = signed,
            signingKeySha256 = if (signed) "a".repeat(64) else null,
            downloadUrl = REGATTALINK_DOWNLOAD_URL
        )
    }

    private fun espApplicationImage(
        build: ULong,
        project: String = "regattalink"
    ): ByteArray {
        val image = ByteArray(256)
        image[0] = 0xE9.toByte()
        writeUInt32Le(image, 32, 0xABCD5432u)
        writeCString(image, 48, 32, build.toString())
        writeCString(image, 80, 32, project)
        return image
    }

    private fun writeUInt32Le(bytes: ByteArray, offset: Int, value: UInt) {
        bytes[offset] = (value and 0xffu).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xffu).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xffu).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xffu).toByte()
    }

    private fun writeCString(
        bytes: ByteArray,
        offset: Int,
        maxLength: Int,
        value: String
    ) {
        val encoded = value.toByteArray(Charsets.US_ASCII)
        require(encoded.size < maxLength)
        encoded.copyInto(bytes, offset)
        bytes[offset + encoded.size] = 0
    }
}
