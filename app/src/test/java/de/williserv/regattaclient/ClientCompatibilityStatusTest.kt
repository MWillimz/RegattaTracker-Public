package de.williserv.regattaclient

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClientCompatibilityStatusTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearCompatibilityPrefs()
    }

    @After
    fun tearDown() {
        clearCompatibilityPrefs()
    }

    @Test
    fun parseCompatibilityError_acceptsTooOldContract() {
        val error = parseClientCompatibilityError(
            """
            {
              "detail": {
                "message": "Client version is no longer supported. Please update the app.",
                "reason": "client_version_too_old",
                "min_client_version_code": 2400,
                "client_version_code": 2322
              }
            }
            """.trimIndent()
        )

        requireNotNull(error)
        assertEquals(CLIENT_VERSION_TOO_OLD_REASON, error.reason)
        assertEquals(2400, error.minimumVersionCode)
        assertEquals(2322, error.clientVersionCode)
    }

    @Test
    fun parseCompatibilityError_acceptsVersionRequiredContract() {
        val error = parseClientCompatibilityError(
            """
            {
              "detail": {
                "reason": "client_version_required",
                "min_client_version_code": 2400,
                "client_version_code": null
              }
            }
            """.trimIndent()
        )

        requireNotNull(error)
        assertEquals(CLIENT_VERSION_REQUIRED_REASON, error.reason)
        assertEquals(2400, error.minimumVersionCode)
        assertNull(error.clientVersionCode)
    }

    @Test
    fun parseCompatibilityError_rejectsMalformedUnknownAndContradictoryResponses() {
        assertNull(parseClientCompatibilityError("not-json"))
        assertNull(parseClientCompatibilityError("{}"))
        assertNull(
            parseClientCompatibilityError(
                """{"detail":{"reason":"other","min_client_version_code":2400}}"""
            )
        )
        assertNull(
            parseClientCompatibilityError(
                """{"detail":{"reason":"client_version_required","min_client_version_code":2400}}"""
            )
        )
        assertNull(
            parseClientCompatibilityError(
                """{"detail":{"reason":"client_version_too_old","min_client_version_code":2400,"client_version_code":null}}"""
            )
        )
        assertNull(
            parseClientCompatibilityError(
                """{"detail":{"reason":"client_version_required","min_client_version_code":2400,"client_version_code":2322}}"""
            )
        )
        assertNull(
            parseClientCompatibilityError(
                """{"detail":{"reason":"client_version_too_old","min_client_version_code":"2400","client_version_code":2322}}"""
            )
        )
    }

    @Test
    fun structured426_isClassifiedAsUpdateRequiredOnlyForMatchingReleaseClient() {
        val body =
            """{"detail":{"reason":"client_version_too_old","min_client_version_code":2400,"client_version_code":2322}}"""

        assertEquals(
            TelemetryUploadAttemptResult.CLIENT_UPDATE_REQUIRED,
            classifyTelemetryUploadResponse(
                responseCode = 426,
                errorBody = body,
                client = ClientBuildIdentity(2322, "release")
            )
        )
        assertEquals(
            TelemetryUploadAttemptResult.OTHER_FAILURE,
            classifyTelemetryUploadResponse(
                responseCode = 426,
                errorBody = body,
                client = ClientBuildIdentity(2300, "different-release")
            )
        )
        assertEquals(
            TelemetryUploadAttemptResult.OTHER_FAILURE,
            classifyTelemetryUploadResponse(
                responseCode = 426,
                errorBody = body,
                client = ClientBuildIdentity(DEV_DEBUG_VERSION_CODE, "dev")
            )
        )
    }

    @Test
    fun contradictoryTooOldThreshold_isNotPersistableCompatibilityState() {
        val body =
            """{"detail":{"reason":"client_version_too_old","min_client_version_code":2300,"client_version_code":2322}}"""

        assertEquals(
            TelemetryUploadAttemptResult.OTHER_FAILURE,
            classifyTelemetryUploadResponse(
                responseCode = 426,
                errorBody = body,
                client = ClientBuildIdentity(2322, "release")
            )
        )
    }

    @Test
    fun malformed426_remainsNormalPermanentFailure() {
        assertEquals(
            TelemetryUploadAttemptResult.OTHER_FAILURE,
            classifyTelemetryUploadResponse(
                responseCode = 426,
                errorBody = "{}",
                client = ClientBuildIdentity(2322, "release")
            )
        )
    }

    @Test
    fun compatibilityBlock_isScopedToServerAndInstalledVersion() {
        ClientCompatibilityBlockStore.markBlocked(
            context = context,
            serverUrl = "https://raceoffice.example.org/ingest/",
            versionCode = 2322
        )

        assertTrue(
            ClientCompatibilityBlockStore.isBlocked(
                context,
                "https://raceoffice.example.org",
                2322
            )
        )
        assertFalse(
            ClientCompatibilityBlockStore.isBlocked(
                context,
                "https://other.example.org",
                2322
            )
        )
        assertFalse(
            ClientCompatibilityBlockStore.isBlocked(
                context,
                "https://raceoffice.example.org",
                2450
            )
        )
        assertTrue(ClientCompatibilityBlockStore.hasAnyBlockForVersion(context, 2322))
        assertFalse(ClientCompatibilityBlockStore.hasAnyBlockForVersion(context, 2450))
    }

    @Test
    fun raceAndEventStatePreferenceClears_doNotRemoveCompatibilityBlock() {
        val blockedAt = 10_000L
        ClientCompatibilityBlockStore.markBlocked(
            context = context,
            serverUrl = "https://raceoffice.example.org",
            versionCode = 2322,
            blockedAtMillis = blockedAt
        )

        val raceLocalPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        raceLocalPrefs.edit().putString("debug_error_text", "old error").commit()
        raceLocalPrefs.edit().clear().commit()

        assertFalse(raceLocalPrefs.contains("debug_error_text"))
        assertTrue(
            ClientCompatibilityBlockStore.isBlocked(
                context = context,
                serverUrl = "https://raceoffice.example.org",
                versionCode = 2322,
                nowMillis = blockedAt + 1L
            )
        )
        assertTrue(ClientCompatibilityBlockStore.hasAnyBlockForVersion(context, 2322))

        raceLocalPrefs.edit().putString("display_locale_tag", "de").commit()
        raceLocalPrefs.edit().clear().commit()

        assertFalse(raceLocalPrefs.contains("display_locale_tag"))
        assertTrue(
            ClientCompatibilityBlockStore.isBlocked(
                context = context,
                serverUrl = "https://raceoffice.example.org",
                versionCode = 2322,
                nowMillis = blockedAt + 2L
            )
        )
    }

    @Test
    fun legacyCompatibilityBlock_isMigratedToDedicatedPreferences() {
        val blockedAt = 20_000L
        val entry = "2322\nhttps://raceoffice.example.org\n$blockedAt"
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        legacyPrefs.edit()
            .putStringSet(BLOCKED_SERVER_VERSIONS_KEY, setOf(entry))
            .commit()

        assertTrue(
            ClientCompatibilityBlockStore.isBlocked(
                context = context,
                serverUrl = "https://raceoffice.example.org",
                versionCode = 2322,
                nowMillis = blockedAt + 1L
            )
        )
        assertFalse(legacyPrefs.contains(BLOCKED_SERVER_VERSIONS_KEY))
        assertTrue(
            context.getSharedPreferences(COMPATIBILITY_PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(BLOCKED_SERVER_VERSIONS_KEY, emptySet())
                .orEmpty()
                .contains(entry)
        )
    }

    @Test
    fun automaticEnqueueSuppression_isServerAndVersionSpecific() {
        val client = ClientBuildIdentity(2322, "release")
        ClientCompatibilityBlockStore.markBlocked(
            context = context,
            serverUrl = "https://blocked.example.org",
            versionCode = client.versionCode
        )

        assertTrue(
            shouldSuppressTelemetryUploadEnqueue(
                context = context,
                serverUrl = "https://blocked.example.org/ingest/",
                client = client
            )
        )
        assertFalse(
            shouldSuppressTelemetryUploadEnqueue(
                context = context,
                serverUrl = "https://open.example.org",
                client = client
            )
        )
        assertFalse(
            shouldSuppressTelemetryUploadEnqueue(
                context = context,
                serverUrl = "",
                client = client
            )
        )
        assertFalse(
            shouldSuppressTelemetryUploadEnqueue(
                context = context,
                serverUrl = "https://blocked.example.org",
                client = ClientBuildIdentity(2450, "newer-release")
            )
        )
    }

    @Test
    fun compatibilityBlock_allowsHourlyRecheckAndCanClearAfterRecovery() {
        val blockedAt = 10_000L
        ClientCompatibilityBlockStore.markBlocked(
            context = context,
            serverUrl = "https://raceoffice.example.org",
            versionCode = 2322,
            blockedAtMillis = blockedAt
        )

        assertTrue(
            ClientCompatibilityBlockStore.isBlocked(
                context = context,
                serverUrl = "https://raceoffice.example.org",
                versionCode = 2322,
                nowMillis = blockedAt + CLIENT_COMPATIBILITY_RECHECK_INTERVAL_MILLIS - 1L
            )
        )
        assertFalse(
            ClientCompatibilityBlockStore.isBlocked(
                context = context,
                serverUrl = "https://raceoffice.example.org",
                versionCode = 2322,
                nowMillis = blockedAt + CLIENT_COMPATIBILITY_RECHECK_INTERVAL_MILLIS
            )
        )
        assertTrue(ClientCompatibilityBlockStore.hasAnyBlockForVersion(context, 2322))

        ClientCompatibilityBlockStore.clearBlocked(
            context = context,
            serverUrl = "https://raceoffice.example.org",
            versionCode = 2322
        )

        assertFalse(ClientCompatibilityBlockStore.hasAnyBlockForVersion(context, 2322))
    }

    @Test
    fun devDebugVersion_isNeverPersistedAsBlocked() {
        ClientCompatibilityBlockStore.markBlocked(
            context = context,
            serverUrl = "https://raceoffice.example.org",
            versionCode = DEV_DEBUG_VERSION_CODE
        )

        assertFalse(
            ClientCompatibilityBlockStore.isBlocked(
                context,
                "https://raceoffice.example.org",
                DEV_DEBUG_VERSION_CODE
            )
        )
        assertFalse(
            ClientCompatibilityBlockStore.hasAnyBlockForVersion(
                context,
                DEV_DEBUG_VERSION_CODE
            )
        )
    }

    @Test
    fun clientUpdateRequiredMessage_isLocalizedInAllSupportedLocales() {
        val app = RuntimeEnvironment.getApplication()
        val expectedPrefixes = linkedMapOf(
            "en" to "Update required:",
            "de" to "Update erforderlich:",
            "fr" to "Mise à jour requise",
            "it" to "Aggiornamento richiesto:",
            "es" to "Actualización necesaria:"
        )

        expectedPrefixes.forEach { (languageTag, expectedPrefix) ->
            val configuration = Configuration(app.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(languageTag))
            }
            val resources = app.createConfigurationContext(configuration).resources
            assertTrue(
                "$languageTag update-required message is not localized",
                resources.getString(R.string.client_update_required).startsWith(expectedPrefix)
            )
        }
    }

    private fun clearCompatibilityPrefs() {
        listOf(LEGACY_PREFS_NAME, COMPATIBILITY_PREFS_NAME).forEach { prefsName ->
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    private companion object {
        const val LEGACY_PREFS_NAME = "regatta_local_status"
        const val COMPATIBILITY_PREFS_NAME = "client_compatibility_status"
        const val BLOCKED_SERVER_VERSIONS_KEY = "client_update_required_server_versions"
    }
}
