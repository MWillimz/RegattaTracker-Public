package de.williserv.regattaclient

import android.content.Context
import android.database.sqlite.SQLiteStatement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TelemetryLargeBacklogTest {

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
    fun eighteenHourBacklog_usesLinearKeysetPagesAtServerBatchLimit500() {
        val helper = TrackingDbHelper(context)
        try {
            val accessContextId = requireNotNull(
                helper.getOrCreateAccessContext(
                    serverUrl = "https://raceoffice.example.org",
                    accessIdentifier = "Example Series",
                    accessSecret = "secret"
                )
            )

            insertPendingSamples(
                helper = helper,
                accessContextId = accessContextId,
                count = SAMPLE_COUNT
            )

            var afterLocalId = 0L
            var pageCount = 0
            var sampleCount = 0

            while (true) {
                val page = getTelemetryUploadPage(
                    db = helper,
                    afterLocalId = afterLocalId,
                    limit = SERVER_BATCH_LIMIT
                )
                if (page.isEmpty()) break

                pageCount += 1
                sampleCount += page.size
                afterLocalId = page.last().localId
            }

            assertEquals(SAMPLE_COUNT, sampleCount)
            assertEquals(130, pageCount)
        } finally {
            helper.close()
        }
    }

    @Test
    fun uploadPage_preservesCogValidityAndFollowingColumnAlignment() {
        val helper = TrackingDbHelper(context)
        try {
            val accessContextId = requireNotNull(
                helper.getOrCreateAccessContext(
                    serverUrl = "https://raceoffice.example.org",
                    accessIdentifier = "COG Event",
                    accessSecret = "cog-secret"
                )
            )
            helper.insertSample(
                sequenceId = 7L,
                timestamp = "2026-09-28T15:00:00",
                boatName = "COG Boat",
                captainName = "Skipper",
                hullColor = "white",
                sailNumber = "GER 7",
                yardstick = 100.0,
                boatType = "Test",
                lat = 53.5,
                lon = 10.0,
                accuracy = 4f,
                cog = 0f,
                sog = 3.5f,
                cogValid = false,
                batteryPercent = 73,
                batteryCharging = true,
                trackingProfile = "fixed_1s",
                accessContextId = accessContextId,
                utcOffsetMinutes = 120
            )

            val sample = getTelemetryUploadPage(
                db = helper,
                afterLocalId = 0L,
                limit = 10
            ).single()

            assertFalse(requireNotNull(sample.cogValid))
            assertEquals(3.5f, sample.sog, 0.001f)
            assertEquals(73, sample.batteryPercent)
            assertEquals(true, sample.batteryCharging)
            assertEquals("fixed_1s", sample.trackingProfile)
            assertEquals(120, sample.utcOffsetMinutes)
            assertEquals("COG Event", sample.accessContext.accessIdentifier)
            assertEquals("cog-secret", sample.accessContext.accessSecret)
        } finally {
            helper.close()
        }
    }

    private fun insertPendingSamples(
        helper: TrackingDbHelper,
        accessContextId: Long,
        count: Int
    ) {
        val db = helper.writableDatabase
        val statement = db.compileStatement(
            """
            INSERT INTO tracking_samples (
                sequence_id,
                timestamp,
                boat_name,
                captain_name,
                hull_color,
                sail_number,
                yardstick,
                boat_type,
                lat,
                lon,
                accuracy,
                cog,
                sog,
                uploaded,
                access_context_id,
                utc_offset_minutes
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
            """.trimIndent()
        )

        db.beginTransaction()
        try {
            repeat(count) { index ->
                bindSample(
                    statement = statement,
                    sequenceId = index.toLong() + 1L,
                    accessContextId = accessContextId
                )
                statement.executeInsert()
                statement.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            statement.close()
        }
    }

    private fun bindSample(
        statement: SQLiteStatement,
        sequenceId: Long,
        accessContextId: Long
    ) {
        statement.bindLong(1, sequenceId)
        statement.bindString(2, "2026-09-20T10:00:00")
        statement.bindString(3, "Test Boat")
        statement.bindString(4, "Test Skipper")
        statement.bindString(5, "white")
        statement.bindString(6, "GER 123")
        statement.bindDouble(7, 100.0)
        statement.bindString(8, "Test Type")
        statement.bindDouble(9, 53.5)
        statement.bindDouble(10, 10.0)
        statement.bindDouble(11, 4.0)
        statement.bindDouble(12, 180.0)
        statement.bindDouble(13, 3.5)
        statement.bindLong(14, accessContextId)
        statement.bindLong(15, 120)
    }

    private companion object {
        const val DB_NAME = "regatta_tracking.db"
        const val SAMPLE_COUNT = 64_800
        const val SERVER_BATCH_LIMIT = 500
    }
}
