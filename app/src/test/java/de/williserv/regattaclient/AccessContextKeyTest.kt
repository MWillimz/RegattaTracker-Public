package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessContextKeyTest {

    @Test
    fun normalizeAccessContextKey_reusesEquivalentServerForms() {
        val fromIngestUrl = normalizeAccessContextKey(
            serverUrl = " https://raceoffice.example.org/ingest/ ",
            accessIdentifier = " Series A ",
            accessSecret = " shared-secret "
        )
        val fromBaseUrl = normalizeAccessContextKey(
            serverUrl = "https://raceoffice.example.org/",
            accessIdentifier = "Series A",
            accessSecret = "shared-secret"
        )

        assertEquals(fromBaseUrl, fromIngestUrl)
        assertEquals(
            AccessContextKey(
                serverUrl = "https://raceoffice.example.org",
                accessIdentifier = "Series A",
                accessSecret = "shared-secret"
            ),
            fromIngestUrl
        )
    }

    @Test
    fun normalizeAccessContextKey_rejectsIncompleteAccess() {
        assertNull(
            normalizeAccessContextKey(
                serverUrl = "https://raceoffice.example.org",
                accessIdentifier = "",
                accessSecret = "shared-secret"
            )
        )
        assertNull(
            normalizeAccessContextKey(
                serverUrl = "",
                accessIdentifier = "Event A",
                accessSecret = "shared-secret"
            )
        )
        assertNull(
            normalizeAccessContextKey(
                serverUrl = "https://raceoffice.example.org",
                accessIdentifier = "Event A",
                accessSecret = "   "
            )
        )
    }
}
