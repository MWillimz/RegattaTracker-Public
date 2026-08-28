package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventAccessImportTest {

    @Test
    fun parseEventAccessUrl_acceptsExactQrPayloadText() {
        val payload = buildEventQrPayload(
            server = "https://raceoffice.example.org/base",
            event = "BCD Langstrecke ä",
            secret = "abc+/=?"
        )

        assertEquals(
            EventAccessCredentials(
                server = "https://raceoffice.example.org/base",
                event = "BCD Langstrecke ä",
                secret = "abc+/=?"
            ),
            parseEventAccessUrl(payload)
        )
    }

    @Test
    fun parseEventAccessUrl_roundTripsExistingShareUrl() {
        val url = buildEventAccessUrl(
            server = "https://raceoffice.example.org/base/",
            event = "BCD Langstrecke ä",
            secret = "abc+/=?"
        )

        assertEquals(
            EventAccessCredentials(
                server = "https://raceoffice.example.org/base",
                event = "BCD Langstrecke ä",
                secret = "abc+/=?"
            ),
            parseEventAccessUrl(url)
        )
    }

    @Test
    fun parseEventAccessUrl_acceptsPortAndAdditionalQueryParameters() {
        assertEquals(
            EventAccessCredentials(
                server = "https://raceoffice.example.org:8443/regatta",
                event = "Test Event",
                secret = "event-secret"
            ),
            parseEventAccessUrl(
                "https://raceoffice.example.org:8443/regatta/event-access" +
                        "?event_name=Test%20Event&secret=event-secret&source=browser"
            )
        )
    }

    @Test
    fun parseEventAccessUrl_acceptsBrowserShareTextWithTitle() {
        assertEquals(
            EventAccessCredentials(
                server = "https://raceoffice.example.org",
                event = "Testserie1 - 2026-08-28 11:30 - Lauf 1",
                secret = "event-secret"
            ),
            parseEventAccessUrl(
                """
                Testserie1 - Lauf 1
                https://raceoffice.example.org/event-access?event_name=Testserie1+-+2026-08-28+11%3A30+-+Lauf+1&secret=event-secret
                """.trimIndent()
            )
        )
    }

    @Test
    fun parseEventAccessUrl_acceptsSingleValidEventLinkInsideSharedText() {
        assertEquals(
            EventAccessCredentials(
                server = "https://raceoffice.example.org/base",
                event = "Test Event",
                secret = "event-secret"
            ),
            parseEventAccessUrl(
                "Open this event: https://raceoffice.example.org/base/event-access" +
                        "?event_name=Test%20Event&secret=event-secret"
            )
        )
    }

    @Test
    fun parseEventAccessUrl_rejectsMultipleValidEventLinks() {
        assertNull(
            parseEventAccessUrl(
                """
                https://raceoffice.example.org/event-access?event_name=First&secret=one
                https://raceoffice.example.org/event-access?event_name=Second&secret=two
                """.trimIndent()
            )
        )
    }

    @Test
    fun parseEventAccessUrl_rejectsNonHttpsAndWrongPath() {
        assertNull(
            parseEventAccessUrl(
                "http://raceoffice.example.org/event-access?event_name=Test&secret=secret"
            )
        )
        assertNull(
            parseEventAccessUrl(
                "https://raceoffice.example.org/event?event_name=Test&secret=secret"
            )
        )
    }

    @Test
    fun parseEventAccessUrl_rejectsMissingEmptyAndDuplicateCredentials() {
        assertNull(
            parseEventAccessUrl(
                "https://raceoffice.example.org/event-access?event_name=Test"
            )
        )
        assertNull(
            parseEventAccessUrl(
                "https://raceoffice.example.org/event-access?event_name=&secret=secret"
            )
        )
        assertNull(
            parseEventAccessUrl(
                "https://raceoffice.example.org/event-access" +
                        "?event_name=Test&event_name=Other&secret=secret"
            )
        )
    }

    @Test
    fun parseEventAccessUrl_rejectsArbitrarySharedText() {
        assertNull(parseEventAccessUrl("hello from WhatsApp"))
        assertNull(
            parseEventAccessUrl(
                "Website: https://raceoffice.example.org/info"
            )
        )
    }

    @Test
    fun shareImportPolicy_allowsOnlyCompletelyClearedEventState() {
        assertFalse(
            shouldBlockSharedEventImport(
                server = "",
                event = "",
                secret = "",
                resolvedEventName = "",
                raceDataReady = false,
                raceRegistered = false,
                inRace = false
            )
        )

        assertTrue(
            shouldBlockSharedEventImport(
                server = "https://raceoffice.example.org",
                event = "Existing Event",
                secret = "existing-secret",
                resolvedEventName = "Existing Event - Race 1",
                raceDataReady = true,
                raceRegistered = true,
                inRace = false
            )
        )
    }

    @Test
    fun shareImportPolicy_blocksResidualOrRunningEventState() {
        assertTrue(
            shouldBlockSharedEventImport(
                server = "",
                event = "Existing Event",
                secret = "",
                resolvedEventName = "",
                raceDataReady = false,
                raceRegistered = false,
                inRace = false
            )
        )
        assertTrue(
            shouldBlockSharedEventImport(
                server = "",
                event = "",
                secret = "",
                resolvedEventName = "",
                raceDataReady = false,
                raceRegistered = false,
                inRace = true
            )
        )
    }
}
