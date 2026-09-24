package de.williserv.regattaclient

import android.app.Application
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
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
                0 -> 0L
                else -> TELEMETRY_BACKGROUND_SLICE_MS
            }
        }

        var continuationCalls = 0
        var continuationCursor: Long? = null
        worker.continuationPersister = { afterLocalId ->
            continuationCalls += 1
            continuationCursor = afterLocalId
            TelemetryUploadScheduler.appendContinuation(
                context = context,
                afterLocalId = afterLocalId,
                showRecoveryNotification = false
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
                0 -> 0L
                else -> TELEMETRY_BACKGROUND_SLICE_MS
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
    fun recoveryWorker_emptyQueueCancelsRecoveryNotification() {
        val worker = buildWorker(showRecoveryNotification = true)
        val published = mutableListOf<Long>()
        var cancelCalls = 0
        worker.recoveryNotificationPublisher = { remaining ->
            published += remaining
        }
        worker.recoveryNotificationCanceller = {
            cancelCalls += 1
        }

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(published.isEmpty())
        assertTrue(cancelCalls > 0)
        assertEquals(
            TelemetryUploadStatusStore.ALL_SENT,
            currentUploadStatus()
        )
    }

    @Test
    fun recoveryWorker_remainingRowsBeforeCursorKeepRecoveryNotification() {
        insertPendingSample()

        val worker = buildWorker(
            showRecoveryNotification = true,
            afterLocalId = Long.MAX_VALUE
        )
        val published = mutableListOf<Long>()
        var cancelCalls = 0
        worker.recoveryNotificationPublisher = { remaining ->
            published += remaining
        }
        worker.recoveryNotificationCanceller = {
            cancelCalls += 1
        }

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, cancelCalls)
        assertTrue(published.isNotEmpty())
        assertEquals(1L, published.last())
        assertEquals(
            TelemetryUploadStatusStore.WAITING,
            currentUploadStatus()
        )
    }

    @Test
    fun eachRecoveryWorkerStartsFromFreshPendingCount() {
        insertPendingSample()

        val firstPublished = mutableListOf<Long>()
        buildWorker(
            showRecoveryNotification = true,
            afterLocalId = Long.MAX_VALUE
        ).apply {
            recoveryNotificationPublisher = { firstPublished += it }
            recoveryNotificationCanceller = {}
        }.doWork()
        assertEquals(1L, firstPublished.first())

        insertPendingSample(sequenceId = 2L)

        val secondPublished = mutableListOf<Long>()
        buildWorker(
            showRecoveryNotification = true,
            afterLocalId = Long.MAX_VALUE
        ).apply {
            recoveryNotificationPublisher = { secondPublished += it }
            recoveryNotificationCanceller = {}
        }.doWork()

        assertEquals(2L, secondPublished.first())
    }

    private fun buildWorker(
        showRecoveryNotification: Boolean = false,
        afterLocalId: Long = 0L
    ): TelemetryUploadWorker {
        return TestListenableWorkerBuilder<TelemetryUploadWorker>(context)
            .setInputData(
                workDataOf(
                    TelemetryUploadScheduler.SHOW_RECOVERY_NOTIFICATION_KEY to
                        showRecoveryNotification,
                    TelemetryUploadScheduler.AFTER_LOCAL_ID_KEY to afterLocalId
                )
            )
            .build()
    }

    private fun insertPendingSample(sequenceId: Long = 1L) {
        val helper = TrackingDbHelper(context)
        try {
            val accessContextId = helper.getOrCreateAccessContext(
                serverUrl = SERVER_URL,
                accessIdentifier = "Event A",
                accessSecret = "secret-a"
            ) ?: error("context id missing")

            val insertedId = helper.insertSample(
                sequenceId = sequenceId,
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
        const val SERVER_URL = "https://raceoffice.example.org"
    }
}
