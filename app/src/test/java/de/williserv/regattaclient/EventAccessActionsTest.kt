package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test

class EventAccessActionsTest {

    @Test
    fun buildEventAccessUrl_encodesEventAndSecretAndNormalizesTrailingSlash() {
        val url = buildEventAccessUrl(
            server = "https://raceoffice.example.org/",
            event = "BCD Langstrecke ä",
            secret = "abc+/=?"
        )

        assertEquals(
            "https://raceoffice.example.org/event-access" +
                    "?event_name=BCD%20Langstrecke%20%C3%A4" +
                    "&secret=abc%2B%2F%3D%3F",
            url
        )
    }

    @Test
    fun buildEventAccessUrl_normalizesLegacyIngestSuffix() {
        val url = buildEventAccessUrl(
            server = "https://raceoffice.example.org/ingest/",
            event = "Example Regatta",
            secret = "example-secret"
        )

        assertEquals(
            "https://raceoffice.example.org/event-access" +
                    "?event_name=Example%20Regatta" +
                    "&secret=example-secret",
            url
        )
    }

    @Test
    fun buildEventQrPayload_keepsExistingServerEventSecretSchema() {
        val payload = buildEventQrPayload(
            server = "https://raceoffice.example.org",
            event = "Example Regatta",
            secret = "example-secret"
        )

        assertEquals(
            "{\"server\":\"https://raceoffice.example.org\"," +
                    "\"event\":\"Example Regatta\"," +
                    "\"secret\":\"example-secret\"}",
            payload
        )
    }

    @Test
    fun buildEventQrPayload_escapesJsonSpecialCharacters() {
        val payload = buildEventQrPayload(
            server = "https://raceoffice.example.org",
            event = "Regatta \"Nord\"",
            secret = "line1\\line2\nsecret"
        )

        assertEquals(
            "{\"server\":\"https://raceoffice.example.org\"," +
                    "\"event\":\"Regatta \\\"Nord\\\"\"," +
                    "\"secret\":\"line1\\\\line2\\nsecret\"}",
            payload
        )
    }
}
