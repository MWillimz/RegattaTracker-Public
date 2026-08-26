package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLegalTest {

    @Test
    fun buildsImpressumUrlFromConfiguredServer() {
        assertEquals(
            "https://race.example.org/server-legal/impressum",
            buildServerLegalUrl(
                server = "https://race.example.org/",
                kind = ServerLegalKind.IMPRESSUM
            )
        )
    }

    @Test
    fun removesLegacyIngestSuffixBeforeBuildingLegalUrl() {
        assertEquals(
            "https://race.example.org/server-legal/datenschutz",
            buildServerLegalUrl(
                server = "https://race.example.org/ingest",
                kind = ServerLegalKind.DATENSCHUTZ
            )
        )
    }

    @Test
    fun legalUrlNeverContainsEventCredentials() {
        val url = buildServerLegalUrl(
            server = "https://race.example.org",
            kind = ServerLegalKind.IMPRESSUM
        )!!

        assertFalse(url.contains("event"))
        assertFalse(url.contains("secret"))
        assertFalse(url.contains("?"))
    }

    @Test
    fun rejectsInvalidServerUrl() {
        assertNull(
            buildServerLegalUrl(
                server = "not-a-server",
                kind = ServerLegalKind.IMPRESSUM
            )
        )
    }

    @Test
    fun parsesLegalDocument() {
        val document = parseServerLegalDocument(
            """
                {
                  "title": "Impressum",
                  "content": "First line\\nSecond line"
                }
            """.trimIndent()
        )

        assertEquals("Impressum", document?.title)
        assertEquals("First line\nSecond line", document?.content)
    }

    @Test
    fun rejectsBlankLegalContent() {
        assertNull(
            parseServerLegalDocument(
                """{"title":"Impressum","content":"   "}"""
            )
        )
    }

    @Test
    fun usesSameHeaderAuthenticationAsServerMetadata() {
        val headers = buildServerMetadataHeaders(
            eventName = " series:abc ",
            sharedSecret = " series-secret "
        )!!

        assertEquals("v1", headers["x-api-version"])
        assertEquals("series:abc", headers["x-event-name"])
        assertEquals("series-secret", headers["x-shared-secret"])
        assertTrue(headers.keys.none { it.equals("event_name", ignoreCase = true) })
    }
}
