package de.williserv.regattaclient

import android.content.Context
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
class TelemetryBlockedBacklogTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB_NAME)
        clearCompatibilityPrefs()
    }

    @After
    fun tearDown() {
        clearCompatibilityPrefs()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun fullyBlockedBacklog_doesNotRequestContinuationPastFirstBatch() {
        val db = TrackingDbHelper(context)
        try {
            val blockedContextId = requireNotNull(
                db.getOrCreateAccessContext(
                    serverUrl = BLOCKED_SERVER,
                    accessIdentifier = "Event A",
                    accessSecret = "secret-a"
                )
            )

            repeat(75) { index ->
                insertSample(
                    db = db,
                    sequenceId = index.toLong() + 1L,
                    accessContextId = blockedContextId
                )
            }

            val client = ClientBuildIdentity(2322, "release")
            ClientCompatibilityBlockStore.markBlocked(
                context = context,
                serverUrl = BLOCKED_SERVER,
                versionCode = client.versionCode
            )

            val firstPage = getTelemetryUploadPage(
                db = db,
                afterLocalId = 0L,
                limit = 50
            )

            assertEquals(50, firstPage.size)
            assertFalse(
                hasUnblockedUploadablePendingServerAfter(
                    db = db,
                    context = context,
                    afterLocalId = firstPage.last().localId,
                    client = client
                )
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun laterUnblockedServer_keepsContinuationAndKeysetPagingIntact() {
        val db = TrackingDbHelper(context)
        try {
            val blockedContextId = requireNotNull(
                db.getOrCreateAccessContext(
                    serverUrl = BLOCKED_SERVER,
                    accessIdentifier = "Event A",
                    accessSecret = "secret-a"
                )
            )
            val openContextId = requireNotNull(
                db.getOrCreateAccessContext(
                    serverUrl = OPEN_SERVER,
                    accessIdentifier = "Event B",
                    accessSecret = "secret-b"
                )
            )

            repeat(60) { index ->
                insertSample(
                    db = db,
                    sequenceId = index.toLong() + 1L,
                    accessContextId = blockedContextId
                )
            }
            insertSample(
                db = db,
                sequenceId = 61L,
                accessContextId = openContextId
            )

            val client = ClientBuildIdentity(2322, "release")
            ClientCompatibilityBlockStore.markBlocked(
                context = context,
                serverUrl = BLOCKED_SERVER,
                versionCode = client.versionCode
            )

            val firstPage = getTelemetryUploadPage(
                db = db,
                afterLocalId = 0L,
                limit = 50
            )

            assertEquals(50, firstPage.size)
            assertTrue(
                hasUnblockedUploadablePendingServerAfter(
                    db = db,
                    context = context,
                    afterLocalId = firstPage.last().localId,
                    client = client
                )
            )

            val secondPage = getTelemetryUploadPage(
                db = db,
                afterLocalId = firstPage.last().localId,
                limit = 50
            )

            assertEquals(11, secondPage.size)
            assertTrue(secondPage.first().localId > firstPage.last().localId)
            assertEquals(OPEN_SERVER, secondPage.last().accessContext.serverUrl)
        } finally {
            db.close()
        }
    }

    private fun insertSample(
        db: TrackingDbHelper,
        sequenceId: Long,
        accessContextId: Long
    ): Long = db.insertSample(
        sequenceId = sequenceId,
        timestamp = "2026-09-04T20:00:00Z",
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

    private fun clearCompatibilityPrefs() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private companion object {
        const val DB_NAME = "regatta_tracking.db"
        const val PREFS_NAME = "regatta_local_status"
        const val BLOCKED_SERVER = "https://blocked.example.org"
        const val OPEN_SERVER = "https://open.example.org"
    }
}
