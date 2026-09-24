package de.williserv.regattaclient

import android.app.Application
import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    manifest = Config.NONE,
    application = Application::class
)
class TelemetryUploadSchedulerChainTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB_NAME)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun shutdownHandoff_appendsBehindKeepWakeupWithoutCancellingIt() {
        val helper = TrackingDbHelper(context)
        val contextId = helper.getOrCreateAccessContext(
            serverUrl = "https://raceoffice.example.org",
            accessIdentifier = "Event A",
            accessSecret = "secret-a"
        ) ?: error("context id missing")

        insertSample(helper, sequenceId = 1L, accessContextId = contextId)

        TelemetryUploadScheduler.enqueueWakeup(context)
        var infos = workManager.getWorkInfosForUniqueWork(TelemetryUploadScheduler.UNIQUE_WORK_NAME).get()
        assertEquals(1, infos.size)
        val firstId = infos.single().id
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)

        insertSample(helper, sequenceId = 2L, accessContextId = contextId)

        // This is the #168 edge: KEEP may legitimately ignore the wake-up
        // because the first unique request is still unfinished.
        TelemetryUploadScheduler.enqueueWakeup(context)
        infos = workManager.getWorkInfosForUniqueWork(TelemetryUploadScheduler.UNIQUE_WORK_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(firstId, infos.single().id)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)

        val handoff =
            TelemetryUploadScheduler.enqueueShutdownHandoffIfNeeded(context)
        assertNotNull(handoff)
        handoff!!.result.get()

        infos = workManager.getWorkInfosForUniqueWork(TelemetryUploadScheduler.UNIQUE_WORK_NAME).get()
        assertEquals(2, infos.size)

        val first = infos.single { it.id == firstId }
        val child = infos.single { it.id != firstId }

        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        assertEquals(WorkInfo.State.BLOCKED, child.state)
        assertTrue(first.state != WorkInfo.State.CANCELLED)
        assertTrue(child.state != WorkInfo.State.CANCELLED)

        helper.close()
    }

    private fun insertSample(
        helper: TrackingDbHelper,
        sequenceId: Long,
        accessContextId: Long
    ): Long {
        return helper.insertSample(
            sequenceId = sequenceId,
            timestamp = "2026-09-20T20:00:00",
            boatName = "Test Boat",
            captainName = "Tester",
            hullColor = "white",
            sailNumber = "GER 175",
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
