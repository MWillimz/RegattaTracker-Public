package de.williserv.regattaclient

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TelemetryUploadPolicyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun responseClassification_matchesRetryPolicy() {
        assertEquals(TelemetryUploadAttemptResult.SUCCESS, classifyTelemetryUploadResponseCode(200))
        assertEquals(TelemetryUploadAttemptResult.SUCCESS, classifyTelemetryUploadResponseCode(299))
        assertEquals(TelemetryUploadAttemptResult.TEMPORARY_FAILURE, classifyTelemetryUploadResponseCode(408))
        assertEquals(TelemetryUploadAttemptResult.TEMPORARY_FAILURE, classifyTelemetryUploadResponseCode(429))
        assertEquals(TelemetryUploadAttemptResult.TEMPORARY_FAILURE, classifyTelemetryUploadResponseCode(503))
        assertEquals(TelemetryUploadAttemptResult.OTHER_FAILURE, classifyTelemetryUploadResponseCode(400))
        assertEquals(TelemetryUploadAttemptResult.OTHER_FAILURE, classifyTelemetryUploadResponseCode(401))
        assertEquals(TelemetryUploadAttemptResult.OTHER_FAILURE, classifyTelemetryUploadResponseCode(404))
    }

    @Test
    fun telemetryPayload_addsClientIdentityWithoutChangingSampleFields() {
        val sample = PendingTrackingSample(
            localId = 99L,
            accessContext = AccessContext(
                id = 4L,
                serverUrl = "https://raceoffice.example.org",
                accessIdentifier = "Event A",
                accessSecret = "secret-a",
                createdAt = 1L,
                lastUsedAt = 2L
            ),
            sequenceId = 7L,
            timestamp = "2026-09-04T18:30:00Z",
            boatName = "Test Boat",
            captainName = "Test Captain",
            hullColor = "white",
            sailNumber = "GER 123",
            yardstick = 100.5,
            boatType = "Test Type",
            lat = 53.4,
            lon = 10.3,
            accuracy = 3.0f,
            cog = 180.0f,
            sog = 5.5f
        )
        val client = ClientBuildIdentity(
            versionCode = 20_871_700,
            buildId = "26.09.04-1830-staging"
        )

        val payload = buildTelemetryUploadPayload(sample, client)

        assertEquals(7L, payload.getLong("sequence_id"))
        assertEquals("2026-09-04T18:30:00Z", payload.getString("timestamp"))
        assertEquals(20_871_700, payload.getInt("client_version_code"))
        assertEquals("26.09.04-1830-staging", payload.getString("client_build_id"))
        assertEquals("Test Boat", payload.getString("boat_name"))
        assertEquals("Test Captain", payload.getString("captain_name"))
        assertEquals("white", payload.getString("hull_color"))
        assertEquals("GER 123", payload.getString("sail_number"))
        assertEquals(100.5, payload.getDouble("yardstick"), 0.0)
        assertEquals("Test Type", payload.getString("boat_type"))
        assertEquals(53.4, payload.getDouble("lat"), 0.0)
        assertEquals(10.3, payload.getDouble("lon"), 0.0)
        assertEquals(3.0, payload.getDouble("accuracy"), 0.0)
        assertEquals(180.0, payload.getDouble("cog"), 0.0)
        assertEquals(5.5, payload.getDouble("sog"), 0.0)
        listOf(
            "accel_x",
            "accel_y",
            "accel_z",
            "gyro_x",
            "gyro_y",
            "gyro_z"
        ).forEach { key ->
            assertFalse(payload.has(key))
        }
    }

    @Test
    fun schedulingPolicies_neverCancelExistingTelemetryWork() {
        assertEquals(
            ExistingWorkPolicy.KEEP,
            telemetryUploadExistingWorkPolicy(
                TelemetryUploadScheduleKind.LIVE_WAKEUP
            )
        )
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            telemetryUploadExistingWorkPolicy(
                TelemetryUploadScheduleKind.RECOVERY
            )
        )
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            telemetryUploadExistingWorkPolicy(
                TelemetryUploadScheduleKind.CONTINUATION
            )
        )

        TelemetryUploadScheduleKind.entries.forEach { kind ->
            assertTrue(
                telemetryUploadExistingWorkPolicy(kind) !=
                    ExistingWorkPolicy.REPLACE
            )
        }
    }

    @Test
    fun schedulingDecision_onlyEnqueuesForUploadableBacklog() {
        assertFalse(shouldEnqueueTelemetryUpload(0L))
        assertTrue(shouldEnqueueTelemetryUpload(1L))
    }

    @Test
    fun workerRequest_requiresConnectedNetworkAndCarriesContinuationCursor() {
        val request = TelemetryUploadScheduler.buildRequest(afterLocalId = 123L)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(
            123L,
            request.workSpec.input.getLong(TelemetryUploadScheduler.AFTER_LOCAL_ID_KEY, 0L)
        )
        assertFalse(request.workSpec.expedited)
        assertFalse(
            request.workSpec.input.getBoolean(
                TelemetryUploadScheduler.SHOW_RECOVERY_NOTIFICATION_KEY,
                false
            )
        )

        val recoveryRequest = TelemetryUploadScheduler.buildRequest(
            showRecoveryNotification = true
        )
        assertFalse(recoveryRequest.workSpec.expedited)
        assertTrue(
            recoveryRequest.workSpec.input.getBoolean(
                TelemetryUploadScheduler.SHOW_RECOVERY_NOTIFICATION_KEY,
                false
            )
        )
    }

    @Test
    fun uploadPage_canAdvancePastFullPageOfStillPendingPermanentFailures() {
        val helper = TrackingDbHelper(context)
        val contextId = helper.getOrCreateAccessContext(
            serverUrl = "https://raceoffice.example.org",
            accessIdentifier = "Event A",
            accessSecret = "secret-a"
        ) ?: error("context id missing")

        repeat(51) { index ->
            insertSample(
                helper = helper,
                sequenceId = index.toLong() + 1L,
                accessContextId = contextId
            )
        }

        val firstPage = getTelemetryUploadPage(helper, afterLocalId = 0L, limit = 50)
        assertEquals(50, firstPage.size)

        val secondPage = getTelemetryUploadPage(
            helper,
            afterLocalId = firstPage.last().localId,
            limit = 50
        )

        assertEquals(1, secondPage.size)
        assertEquals(51L, secondPage.single().sequenceId)
        helper.close()
    }

    @Test
    fun uploadablePendingCount_excludesContextlessLegacyRows() {
        val helper = TrackingDbHelper(context)
        val contextId = helper.getOrCreateAccessContext(
            serverUrl = "https://raceoffice.example.org",
            accessIdentifier = "Event A",
            accessSecret = "secret-a"
        ) ?: error("context id missing")

        insertSample(helper, sequenceId = 1L, accessContextId = null)
        insertSample(helper, sequenceId = 2L, accessContextId = contextId)

        assertEquals(2L, helper.countPendingSamples())
        assertEquals(1L, helper.countUploadablePendingSamples())
        helper.close()
    }

    private fun insertSample(
        helper: TrackingDbHelper,
        sequenceId: Long,
        accessContextId: Long?
    ): Long {
        return helper.insertSample(
            sequenceId = sequenceId,
            timestamp = "2026-08-25T20:00:00",
            boatName = "Test Boat",
            captainName = "Tester",
            hullColor = "white",
            sailNumber = "GER 1",
            yardstick = 100.0,
            boatType = "Test",
            lat = 53.5,
            lon = 10.0,
            accuracy = 5f,
            cog = 0f,
            sog = 0f,
            accessContextId = accessContextId
        )
    }

    private companion object {
        const val DB_NAME = "regatta_tracking.db"
    }
}
