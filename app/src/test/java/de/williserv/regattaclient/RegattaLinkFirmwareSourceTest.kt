package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RegattaLinkFirmwareSourceTest {

    @Test
    fun productionAuthUsesOnlyFirmwareAccessKey() {
        val headers = regattaLinkFirmwareAuthHeaders(
            RegattaLinkFirmwareAuth.Production("p".repeat(64))
        )

        assertEquals(
            mapOf("X-Regatta-Firmware-Key" to "p".repeat(64)),
            headers
        )
        assertFalse(headers.containsKey("X-Event-Name"))
        assertFalse(headers.containsKey("X-Shared-Secret"))
    }

    @Test
    fun eventSourceUsesOnlyEventCredentials() {
        val headers = regattaLinkFirmwareAuthHeaders(
            RegattaLinkFirmwareAuth.Event(
                eventIdentifier = "Weekend Cup",
                sharedSecret = "event-secret"
            )
        )

        assertEquals("Weekend Cup", headers["X-Event-Name"])
        assertEquals("event-secret", headers["X-Shared-Secret"])
        assertFalse(headers.containsKey("X-Regatta-Firmware-Key"))
    }

    @Test
    fun emptyProductionKeyProducesNoHeaderForPublicDevBuilds() {
        assertTrue(
            regattaLinkFirmwareAuthHeaders(
                RegattaLinkFirmwareAuth.Production("")
            ).isEmpty()
        )
    }

    @Test
    fun endpointRejectsMismatchedSourceAndAuth() {
        assertThrows(IllegalArgumentException::class.java) {
            RegattaLinkFirmwareEndpoint(
                source = RegattaLinkFirmwareSource.EVENT,
                baseUrl = "https://race.example",
                auth = RegattaLinkFirmwareAuth.Production("p".repeat(64))
            )
        }
    }

    @Test
    fun sameBuildSuppressesEventChoiceAndForcesStandard() {
        val production = manifest(build = 100uL)
        val event = manifest(build = 100uL)

        val decision = chooseRegattaLinkFirmwareSource(
            production = Result.success(production),
            event = Result.success(event),
            preferredSource = RegattaLinkFirmwareSource.EVENT
        )

        assertEquals(
            setOf(RegattaLinkFirmwareSource.STANDARD),
            decision.availableSources
        )
        assertEquals(
            RegattaLinkFirmwareSource.STANDARD,
            decision.selectedSource
        )
        assertEquals(production, decision.selectedManifest)
    }

    @Test
    fun differingBuildsExposeBothSourcesAndKeepPreferredChoice() {
        val production = manifest(build = 100uL)
        val event = manifest(build = 101uL)

        val decision = chooseRegattaLinkFirmwareSource(
            production = Result.success(production),
            event = Result.success(event),
            preferredSource = RegattaLinkFirmwareSource.EVENT
        )

        assertEquals(
            setOf(
                RegattaLinkFirmwareSource.STANDARD,
                RegattaLinkFirmwareSource.EVENT
            ),
            decision.availableSources
        )
        assertEquals(
            RegattaLinkFirmwareSource.EVENT,
            decision.selectedSource
        )
        assertEquals(event, decision.selectedManifest)
    }

    @Test
    fun failedEventProbeFallsBackToProductionWithoutBetaChoice() {
        val production = manifest(build = 100uL)

        val decision = chooseRegattaLinkFirmwareSource(
            production = Result.success(production),
            event = Result.failure(IllegalStateException("beta unavailable")),
            preferredSource = RegattaLinkFirmwareSource.EVENT
        )

        assertEquals(
            setOf(RegattaLinkFirmwareSource.STANDARD),
            decision.availableSources
        )
        assertEquals(
            RegattaLinkFirmwareSource.STANDARD,
            decision.selectedSource
        )
    }

    @Test
    fun failedProductionProbeCanUseEventAsExplicitAlternative() {
        val event = manifest(build = 101uL)

        val decision = chooseRegattaLinkFirmwareSource(
            production = Result.failure(
                IllegalStateException("production unavailable")
            ),
            event = Result.success(event),
            preferredSource = RegattaLinkFirmwareSource.STANDARD
        )

        assertEquals(
            setOf(RegattaLinkFirmwareSource.EVENT),
            decision.availableSources
        )
        assertEquals(
            RegattaLinkFirmwareSource.EVENT,
            decision.selectedSource
        )
        assertEquals(event, decision.selectedManifest)
    }

    @Test
    fun bothFailedPropagatesFirmwareCheckFailure() {
        assertThrows(IllegalStateException::class.java) {
            chooseRegattaLinkFirmwareSource(
                production = Result.failure(
                    IllegalStateException("production unavailable")
                ),
                event = Result.failure(
                    IllegalStateException("event unavailable")
                ),
                preferredSource = RegattaLinkFirmwareSource.STANDARD
            )
        }
    }

    @Test
    fun eventFirmwareRevealAlwaysWaitsUntilFiveSecondsFromProbeStart() {
        assertEquals(
            5_000L,
            regattaLinkEventFirmwareRevealDelayMs(
                probeStartedAtElapsedMs = 1_000L,
                completedAtElapsedMs = 1_000L
            )
        )
        assertEquals(
            4_000L,
            regattaLinkEventFirmwareRevealDelayMs(
                probeStartedAtElapsedMs = 1_000L,
                completedAtElapsedMs = 2_000L
            )
        )
        assertEquals(
            0L,
            regattaLinkEventFirmwareRevealDelayMs(
                probeStartedAtElapsedMs = 1_000L,
                completedAtElapsedMs = 6_000L
            )
        )
        assertEquals(
            5_000L,
            regattaLinkEventFirmwareRevealDelayMs(
                probeStartedAtElapsedMs = 5_000L,
                completedAtElapsedMs = 4_000L
            )
        )
    }

    @Test
    fun eventEndpointNormalizesIngestSuffixAndRequiresHttps() {
        val endpoint = RegattaLinkFirmwareEndpoint.event(
            serverUrl = "https://race.example/ingest/",
            eventIdentifier = "Race",
            sharedSecret = "secret"
        )

        assertEquals("https://race.example", endpoint.baseUrl)

        assertThrows(IllegalArgumentException::class.java) {
            RegattaLinkFirmwareEndpoint.event(
                serverUrl = "http://race.example",
                eventIdentifier = "Race",
                sharedSecret = "secret"
            )
        }
    }

    private fun manifest(build: ULong) = RegattaLinkFirmwareManifest(
        schemaVersion = 1,
        product = "RegattaLink",
        target = "esp32c3",
        hardwareProfile = "esp32c3-wroom02-4mb",
        buildNumber = build,
        filename = "regattalink.bin",
        size = 256,
        sha256 = "a".repeat(64),
        signed = true,
        signingKeySha256 = "b".repeat(64),
        downloadUrl = REGATTALINK_DOWNLOAD_URL
    )
}
