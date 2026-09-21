package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerBaseUrlTest {

    @Test
    fun normalizeServerBaseUrl_normalizesSupportedVariants() {
        val expected = "https://raceoffice.example.org"

        listOf(
            "https://raceoffice.example.org",
            "https://raceoffice.example.org/",
            "https://raceoffice.example.org/ingest",
            "https://raceoffice.example.org/ingest/"
        ).forEach { serverUrl ->
            assertEquals(expected, normalizeServerBaseUrl(serverUrl))
        }
    }

    @Test
    fun normalizeServerBaseUrl_keepsNormalAndIngestEndpointsCorrect() {
        val baseUrl = normalizeServerBaseUrl("https://raceoffice.example.org/ingest/")

        assertEquals(
            "https://raceoffice.example.org/event?event_name=Wednesday+Race",
            buildNormalApiGetUrl(
                baseUrl = baseUrl,
                path = "/event",
                eventName = "Wednesday Race"
            )
        )
        assertEquals(
            "https://raceoffice.example.org/ingest",
            "$baseUrl/ingest"
        )
    }
}
