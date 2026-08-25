package de.williserv.regattaclient

import android.content.Context
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
    fun schedulingDecision_onlyEnqueuesForUploadableBacklog() {
        assertFalse(shouldEnqueueTelemetryUpload(0L))
        assertTrue(shouldEnqueueTelemetryUpload(1L))
    }

    @Test
    fun workerDecision_retriesTemporaryFailures() {
        assertEquals(
            TelemetryWorkerDecision.RETRY,
            decideTelemetryWorkerCompletion(
                retryNeeded = true,
                madeProgress = true,
                uploadablePendingCount = 5L
            )
        )
    }

    @Test
    fun workerDecision_continuesWhenProgressWasMadeAndBacklogRemains() {
        assertEquals(
            TelemetryWorkerDecision.CONTINUE,
            decideTelemetryWorkerCompletion(
                retryNeeded = false,
                madeProgress = true,
                uploadablePendingCount = 5L
            )
        )
    }

    @Test
    fun workerDecision_stopsImmediateLoopWhenOnlyPermanentFailuresRemain() {
        assertEquals(
            TelemetryWorkerDecision.SUCCESS,
            decideTelemetryWorkerCompletion(
                retryNeeded = false,
                madeProgress = false,
                uploadablePendingCount = 1L
            )
        )
    }

    @Test
    fun workerRequest_requiresConnectedNetwork() {
        val request = TelemetryUploadScheduler.buildRequest()
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
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
            accelX = 0f,
            accelY = 0f,
            accelZ = 0f,
            gyroX = 0f,
            gyroY = 0f,
            gyroZ = 0f,
            accessContextId = accessContextId
        )
    }

    private companion object {
        const val DB_NAME = "regatta_tracking.db"
    }
}
