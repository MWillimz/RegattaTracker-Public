package de.williserv.regattaclient

import android.app.Application
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = Application::class
)
class TelemetryUploadWorkerHandoffTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB_NAME)
        context.getSharedPreferences(STATUS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        context.deleteDatabase(DB_NAME)
        context.getSharedPreferences(STATUS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun sliceBoundary_persistsExactlyOneContinuationBeforeSuccess() {
        insertPendingSample()

        val worker = buildWorker()
        var clockCall = 0
        worker.elapsedRealtimeProvider = {
            when (clockCall++) {
                0, 1 -> 0L
                else -> TELEMETRY_NON_FOREGROUND_SLICE_MS
            }
        }

        var continuationCalls = 0
        var continuationCursor: Long? = null
        worker.continuationPersister = { afterLocalId ->
            continuationCalls += 1
            continuationCursor = afterLocalId
            TelemetryUploadScheduler.appendContinuation(
                context = context,
                afterLocalId = afterLocalId
            ).result.get()
        }

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, continuationCalls)
        assertEquals(0L, continuationCursor)

        val infos = workManager
            .getWorkInfosForUniqueWork(TelemetryUploadScheduler.UNIQUE_WORK_NAME)
            .get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }

    @Test
    fun emptyPage_finishesWithoutContinuation() {
        val worker = buildWorker()
        var continuationCalls = 0
        worker.continuationPersister = {
            continuationCalls += 1
        }

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, continuationCalls)
        assertTrue(
            workManager
                .getWorkInfosForUniqueWork(TelemetryUploadScheduler.UNIQUE_WORK_NAME)
                .get()
                .isEmpty()
        )
        assertEquals(
            TelemetryUploadStatusStore.ALL_SENT,
            currentUploadStatus()
        )
    }

    @Test
    fun continuationPersistenceFailure_retriesCurrentWorker() {
        insertPendingSample()

        val worker = buildWorker()
        var clockCall = 0
        worker.elapsedRealtimeProvider = {
            when (clockCall++) {
                0, 1 -> 0L
                else -> TELEMETRY_NON_FOREGROUND_SLICE_MS
            }
        }
        worker.continuationPersister = {
            throw IllegalStateException("test continuation persistence failure")
        }

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(
            TelemetryUploadStatusStore.TEMPORARY_ERROR,
            currentUploadStatus()
        )
    }

    @Test
    fun foregroundPromotionDenied_fallsBackToBoundedSliceAndContinuation() {
        insertPendingSample()

        val worker = buildWorker()
        var clockCall = 0
        worker.elapsedRealtimeProvider = {
            when (clockCall++) {
                0 -> 0L
                1 -> TELEMETRY_LONG_RUNNING_ELAPSED_THRESHOLD_MS
                else -> TELEMETRY_NON_FOREGROUND_SLICE_MS
            }
        }

        var promotionCalls = 0
        worker.foregroundPromoter = {
            promotionCalls += 1
            throw IllegalStateException("test foreground start denied")
        }

        var continuationCalls = 0
        worker.continuationPersister = {
            continuationCalls += 1
        }

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, promotionCalls)
        assertEquals(1, continuationCalls)
    }

    private fun buildWorker(): TelemetryUploadWorker {
        return TestListenableWorkerBuilder<TelemetryUploadWorker>(context).build()
    }

    private fun insertPendingSample() {
        val helper = TrackingDbHelper(context)
        try {
            val accessContextId = helper.getOrCreateAccessContext(
                serverUrl = "https://raceoffice.example.org",
                accessIdentifier = "Event A",
                accessSecret = "secret-a"
            ) ?: error("context id missing")

            val insertedId = helper.insertSample(
                sequenceId = 1L,
                timestamp = "2026-09-21T05:00:00",
                boatName = "Test Boat",
                captainName = "Tester",
                hullColor = "white",
                sailNumber = "GER 172",
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
            assertTrue(insertedId > 0L)
        } finally {
            helper.close()
        }
    }

    private fun currentUploadStatus(): String? {
        return context
            .getSharedPreferences(STATUS_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(TelemetryUploadStatusStore.STATUS_KEY, null)
    }

    private companion object {
        const val DB_NAME = "regatta_tracking.db"
        const val STATUS_PREFS_NAME = "regatta_local_status"
    }
}
