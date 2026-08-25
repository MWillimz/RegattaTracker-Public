package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServerMetadataTest {

    @Test
    fun buildServerMetadataUrl_normalizesTrailingSlashAndIngestSuffix() {
        assertEquals(
            "https://raceoffice.example.org/server-metadata",
            buildServerMetadataUrl("https://raceoffice.example.org/")
        )
        assertEquals(
            "https://raceoffice.example.org/server-metadata",
            buildServerMetadataUrl("https://raceoffice.example.org/ingest")
        )
    }

    @Test
    fun buildServerMetadataUrl_rejectsBlankOrUnsupportedServer() {
        assertNull(buildServerMetadataUrl(""))
        assertNull(buildServerMetadataUrl("ftp://raceoffice.example.org"))
        assertNull(buildServerMetadataUrl("not a url"))
    }

    @Test
    fun parseServerMetadata_readsAllOptionalFields() {
        val metadata = parseServerMetadata(
            """
            {
              "operator": "Segelverein Beispiel e.V.",
              "public_url": "https://raceoffice.example.org",
              "contact_email": "raceoffice@example.org"
            }
            """.trimIndent()
        )

        assertEquals("Segelverein Beispiel e.V.", metadata.operator)
        assertEquals("https://raceoffice.example.org", metadata.publicUrl)
        assertEquals("raceoffice@example.org", metadata.contactEmail)
        assertTrue(metadata.hasAnyValue())
    }

    @Test
    fun parseServerMetadata_treatsMissingNullAndBlankValuesAsAbsent() {
        val metadata = parseServerMetadata(
            """
            {
              "operator": null,
              "public_url": "   "
            }
            """.trimIndent()
        )

        assertNull(metadata.operator)
        assertNull(metadata.publicUrl)
        assertNull(metadata.contactEmail)
        assertFalse(metadata.hasAnyValue())
    }

    @Test
    fun isHttpOrHttpsUrl_acceptsOnlyWebLinksWithHost() {
        assertTrue(isHttpOrHttpsUrl("https://raceoffice.example.org"))
        assertTrue(isHttpOrHttpsUrl("http://raceoffice.example.org/info"))
        assertFalse(isHttpOrHttpsUrl("mailto:raceoffice@example.org"))
        assertFalse(isHttpOrHttpsUrl("javascript:alert(1)"))
        assertFalse(isHttpOrHttpsUrl("https:///missing-host"))
    }
}
