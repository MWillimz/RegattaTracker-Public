package de.williserv.regattaclient

internal const val REGATTALINK_PRODUCTION_FIRMWARE_BASE_URL =
    "https://rlink.regattatracker.de"

enum class RegattaLinkFirmwareSource {
    STANDARD,
    EVENT
}

sealed interface RegattaLinkFirmwareAuth {
    data class Production(val accessKey: String) : RegattaLinkFirmwareAuth {
        override fun toString(): String = "Production(accessKey=<redacted>)"
    }

    data class Event(
        val eventIdentifier: String,
        val sharedSecret: String
    ) : RegattaLinkFirmwareAuth {
        init {
            require(eventIdentifier.isNotBlank()) {
                "Event firmware access requires an event identifier"
            }
            require(sharedSecret.isNotBlank()) {
                "Event firmware access requires a shared secret"
            }
        }

        override fun toString(): String =
            "Event(eventIdentifier=" + eventIdentifier + ", sharedSecret=<redacted>)"
    }
}

data class RegattaLinkFirmwareEndpoint(
    val source: RegattaLinkFirmwareSource,
    val baseUrl: String,
    val auth: RegattaLinkFirmwareAuth
) {
    init {
        require(
            (source == RegattaLinkFirmwareSource.STANDARD &&
                auth is RegattaLinkFirmwareAuth.Production) ||
                (source == RegattaLinkFirmwareSource.EVENT &&
                    auth is RegattaLinkFirmwareAuth.Event)
        ) {
            "Firmware source and authentication mode must match"
        }
        require(baseUrl.startsWith("https://")) {
            "A configured HTTPS firmware server is required"
        }
    }

    companion object {
        fun production(accessKey: String): RegattaLinkFirmwareEndpoint =
            RegattaLinkFirmwareEndpoint(
                source = RegattaLinkFirmwareSource.STANDARD,
                baseUrl = REGATTALINK_PRODUCTION_FIRMWARE_BASE_URL,
                auth = RegattaLinkFirmwareAuth.Production(accessKey.trim())
            )

        fun event(
            serverUrl: String,
            eventIdentifier: String,
            sharedSecret: String
        ): RegattaLinkFirmwareEndpoint {
            val normalized = normalizeServerBaseUrl(serverUrl)
            require(normalized.startsWith("https://")) {
                "A configured HTTPS event server is required for firmware updates"
            }
            return RegattaLinkFirmwareEndpoint(
                source = RegattaLinkFirmwareSource.EVENT,
                baseUrl = normalized,
                auth = RegattaLinkFirmwareAuth.Event(
                    eventIdentifier = eventIdentifier.trim(),
                    sharedSecret = sharedSecret.trim()
                )
            )
        }
    }
}

internal data class RegattaLinkFirmwareDiscovery(
    val availableSources: Set<RegattaLinkFirmwareSource>,
    val selectedSource: RegattaLinkFirmwareSource,
    val selectedManifest: RegattaLinkFirmwareManifest
)

internal fun chooseRegattaLinkFirmwareSource(
    production: Result<RegattaLinkFirmwareManifest>,
    event: Result<RegattaLinkFirmwareManifest>?,
    preferredSource: RegattaLinkFirmwareSource
): RegattaLinkFirmwareDiscovery {
    val productionManifest = production.getOrNull()
    val eventManifest = event?.getOrNull()

    if (productionManifest != null && eventManifest != null) {
        if (productionManifest.buildNumber == eventManifest.buildNumber) {
            return RegattaLinkFirmwareDiscovery(
                availableSources = setOf(RegattaLinkFirmwareSource.STANDARD),
                selectedSource = RegattaLinkFirmwareSource.STANDARD,
                selectedManifest = productionManifest
            )
        }

        val selected = if (preferredSource == RegattaLinkFirmwareSource.EVENT) {
            RegattaLinkFirmwareSource.EVENT
        } else {
            RegattaLinkFirmwareSource.STANDARD
        }
        return RegattaLinkFirmwareDiscovery(
            availableSources = setOf(
                RegattaLinkFirmwareSource.STANDARD,
                RegattaLinkFirmwareSource.EVENT
            ),
            selectedSource = selected,
            selectedManifest = if (selected == RegattaLinkFirmwareSource.EVENT) {
                eventManifest
            } else {
                productionManifest
            }
        )
    }

    if (productionManifest != null) {
        return RegattaLinkFirmwareDiscovery(
            availableSources = setOf(RegattaLinkFirmwareSource.STANDARD),
            selectedSource = RegattaLinkFirmwareSource.STANDARD,
            selectedManifest = productionManifest
        )
    }

    if (eventManifest != null) {
        return RegattaLinkFirmwareDiscovery(
            availableSources = setOf(RegattaLinkFirmwareSource.EVENT),
            selectedSource = RegattaLinkFirmwareSource.EVENT,
            selectedManifest = eventManifest
        )
    }

    throw production.exceptionOrNull()
        ?: event?.exceptionOrNull()
        ?: IllegalStateException("No RegattaLink firmware source is available")
}

internal fun regattaLinkFirmwareAuthHeaders(
    auth: RegattaLinkFirmwareAuth
): Map<String, String> = when (auth) {
    is RegattaLinkFirmwareAuth.Production -> {
        if (auth.accessKey.isBlank()) {
            emptyMap()
        } else {
            mapOf("X-Regatta-Firmware-Key" to auth.accessKey)
        }
    }

    is RegattaLinkFirmwareAuth.Event -> mapOf(
        "X-Event-Name" to auth.eventIdentifier,
        "X-Shared-Secret" to auth.sharedSecret
    )
}
