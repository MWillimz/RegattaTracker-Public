package de.williserv.regattaclient

import android.content.Context
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
    fun parseCompatibilityError_rejectsMalformedAndUnknownResponses() {
        assertNull(parseClientCompatibilityError("not-json"))
        assertNull(parseClientCompatibilityError("{}"))
        assertNull(
            parseClientCompatibilityError(
                """{"detail":{"reason":"other","min_client_version_code":2400}}"""
            )
        )
        assertNull(
            parseClientCompatibilityError(
                """{"detail":{"reason":"client_version_too_old","min_client_version_code":2400,"client_version_code":null}}"""
            )
        )
        assertNull(
            parseClientCompatibilityError(
                """{"detail":{"reason":"client_version_too_old","min_client_version_code":"2400","client_version_code":2322}}"""
            )
        )
    }

    @Test
    fun structured426_isClassifiedAsUpdateRequiredOnlyForReleaseClient() {
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
                client = ClientBuildIdentity(DEV_DEBUG_VERSION_CODE, "dev")
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

    private fun clearCompatibilityPrefs() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private companion object {
        const val PREFS_NAME = "regatta_local_status"
    }
}
