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
    fun buildServerMetadataUrl_neverContainsEventCredentials() {
        val url = buildServerMetadataUrl("https://raceoffice.example.org")

        assertEquals("https://raceoffice.example.org/server-metadata", url)
        assertFalse(url.orEmpty().contains("event"))
        assertFalse(url.orEmpty().contains("secret"))
    }

    @Test
    fun buildServerMetadataHeaders_usesEventAndSecretHeaders() {
        val headers = buildServerMetadataHeaders(
            eventName = " Example Regatta ",
            sharedSecret = " top-secret "
        )

        requireNotNull(headers)
        assertEquals("application/json", headers["Accept"])
        assertEquals(RegattaTrackingService.API_VERSION, headers["x-api-version"])
        assertEquals("Example Regatta", headers["x-event-name"])
        assertEquals("top-secret", headers["x-shared-secret"])
    }

    @Test
    fun buildServerMetadataHeaders_requiresEventAndSecretTogether() {
        assertNull(buildServerMetadataHeaders("", "secret"))
        assertNull(buildServerMetadataHeaders("event", ""))
        assertNull(buildServerMetadataHeaders("   ", "secret"))
        assertNull(buildServerMetadataHeaders("event", "   "))
    }

    @Test
    fun parseServerMetadata_readsCompleteVersionContract() {
        val metadata = parseServerMetadata(
            """
            {
              "operator": "Segelverein Beispiel e.V.",
              "public_url": "https://raceoffice.example.org",
              "contact_email": "raceoffice@example.org",
              "server_build_id": "26.09.04-0712-abcdef1",
              "server_build_number": 21345678,
              "server_build_type": "release",
              "recommended_client_version_code": 2450,
              "min_client_version_code": 2400,
              "production_release": {
                "version_code": 2450,
                "version_name": "26.09.03-2110",
                "source_sha": "0123456789abcdef0123456789abcdef01234567",
                "recorded_at": "2026-09-03T20:12:00+00:00"
              },
              "direct_download_release": {
                "version_code": 2535,
                "version_name": "26.09.04-0735-staging",
                "source_sha": "89abcdef0123456789abcdef0123456789abcdef",
                "uploaded_at": "2026-09-04T05:36:00+00:00",
                "download_url": "/static/downloads/regatta-app.apk"
              }
            }
            """.trimIndent()
        )

        assertEquals("Segelverein Beispiel e.V.", metadata.operator)
        assertEquals("https://raceoffice.example.org", metadata.publicUrl)
        assertEquals("raceoffice@example.org", metadata.contactEmail)
        assertEquals("26.09.04-0712-abcdef1", metadata.serverBuildId)
        assertEquals(21345678, metadata.serverBuildNumber)
        assertEquals("release", metadata.serverBuildType)
        assertEquals(2450, metadata.recommendedClientVersionCode)
        assertEquals(2400, metadata.minClientVersionCode)

        requireNotNull(metadata.productionRelease)
        assertEquals(2450, metadata.productionRelease.versionCode)
        assertEquals("26.09.03-2110", metadata.productionRelease.versionName)
        assertEquals(
            "0123456789abcdef0123456789abcdef01234567",
            metadata.productionRelease.sourceSha
        )
        assertEquals("2026-09-03T20:12:00+00:00", metadata.productionRelease.recordedAt)

        requireNotNull(metadata.directDownloadRelease)
        assertEquals(2535, metadata.directDownloadRelease.versionCode)
        assertEquals("26.09.04-0735-staging", metadata.directDownloadRelease.versionName)
        assertEquals(
            "89abcdef0123456789abcdef0123456789abcdef",
            metadata.directDownloadRelease.sourceSha
        )
        assertEquals("2026-09-04T05:36:00+00:00", metadata.directDownloadRelease.uploadedAt)
        assertEquals(
            "/static/downloads/regatta-app.apk",
            metadata.directDownloadRelease.downloadUrl
        )
        assertTrue(metadata.hasAnyValue())
    }

    @Test
    fun parseServerMetadata_acceptsNullablePolicyAndReleaseFields() {
        val metadata = parseServerMetadata(
            """
            {
              "operator": null,
              "public_url": null,
              "contact_email": null,
              "server_build_id": "26.09.04-0712-abcdef1",
              "server_build_number": 21345678,
              "server_build_type": "release",
              "recommended_client_version_code": null,
              "min_client_version_code": null,
              "production_release": null,
              "direct_download_release": null
            }
            """.trimIndent()
        )

        assertNull(metadata.operator)
        assertNull(metadata.publicUrl)
        assertNull(metadata.contactEmail)
        assertEquals("26.09.04-0712-abcdef1", metadata.serverBuildId)
        assertEquals(21345678, metadata.serverBuildNumber)
        assertEquals("release", metadata.serverBuildType)
        assertNull(metadata.recommendedClientVersionCode)
        assertNull(metadata.minClientVersionCode)
        assertNull(metadata.productionRelease)
        assertNull(metadata.directDownloadRelease)
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
        assertNull(metadata.serverBuildId)
        assertNull(metadata.serverBuildNumber)
        assertNull(metadata.serverBuildType)
        assertNull(metadata.recommendedClientVersionCode)
        assertNull(metadata.minClientVersionCode)
        assertNull(metadata.productionRelease)
        assertNull(metadata.directDownloadRelease)
        assertFalse(metadata.hasAnyValue())
    }

    @Test
    fun parseServerMetadata_emptyObjectHasNoUsableMetadata() {
        assertFalse(parseServerMetadata("{}").hasAnyValue())
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
