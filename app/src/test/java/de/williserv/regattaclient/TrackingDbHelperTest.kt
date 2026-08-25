package de.williserv.regattaclient

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
class TrackingDbHelperTest {

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
    fun identicalSequenceIds_markOnlyRequestedLocalRowUploaded() {
        val helper = TrackingDbHelper(context)
        val contextId = createAccessContext(helper, "Event A", "secret-a")

        val firstId = insertSample(helper, sequenceId = 1L, accessContextId = contextId)
        val secondId = insertSample(helper, sequenceId = 1L, accessContextId = contextId)

        helper.markUploaded(firstId)

        assertEquals(1, uploadedValue(helper, firstId))
        assertEquals(0, uploadedValue(helper, secondId))
        assertEquals(listOf(secondId), helper.getPendingSamples(10).map { it.localId })
    }

    @Test
    fun pendingBacklog_keepsOriginalAccessContextAfterAnotherAccessIsCreated() {
        val helper = TrackingDbHelper(context)
        val contextA = createAccessContext(helper, "Series A", "secret-a")
        val sampleA = insertSample(helper, sequenceId = 7L, accessContextId = contextA)

        val contextB = createAccessContext(helper, "Event B", "secret-b")
        val sampleB = insertSample(helper, sequenceId = 8L, accessContextId = contextB)

        val pendingById = helper.getPendingSamples(10).associateBy { it.localId }

        assertEquals("Series A", pendingById.getValue(sampleA).accessContext.accessIdentifier)
        assertEquals("secret-a", pendingById.getValue(sampleA).accessContext.accessSecret)
        assertEquals("Event B", pendingById.getValue(sampleB).accessContext.accessIdentifier)
        assertEquals("secret-b", pendingById.getValue(sampleB).accessContext.accessSecret)
    }

    @Test
    fun equivalentAccess_reusesPersistedContext() {
        val helper = TrackingDbHelper(context)

        val firstId = helper.getOrCreateAccessContext(
            serverUrl = " https://raceoffice.example.org/ingest/ ",
            accessIdentifier = " Event A ",
            accessSecret = " shared-secret "
        )
        val secondId = helper.getOrCreateAccessContext(
            serverUrl = "https://raceoffice.example.org/",
            accessIdentifier = "Event A",
            accessSecret = "shared-secret"
        )

        assertEquals(firstId, secondId)
        assertEquals(1L, accessContextCount(helper))
    }

    @Test
    fun version3Upgrade_preservesRowsIdsUploadedStateAndExport() {
        createLegacyVersion3Database(
            LegacyRow(id = 41L, sequenceId = 1L, uploaded = 0, sailNumber = "LEGACY-A"),
            LegacyRow(id = 42L, sequenceId = 2L, uploaded = 1, sailNumber = "LEGACY-B")
        )

        val helper = TrackingDbHelper(context)
        val db = helper.writableDatabase

        assertTrue(columnExists(db, "tracking_samples", "access_context_id"))

        db.rawQuery(
            "SELECT id, uploaded, access_context_id FROM tracking_samples ORDER BY id ASC",
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(41L, cursor.getLong(0))
            assertEquals(0, cursor.getInt(1))
            assertTrue(cursor.isNull(2))

            assertTrue(cursor.moveToNext())
            assertEquals(42L, cursor.getLong(0))
            assertEquals(1, cursor.getInt(1))
            assertTrue(cursor.isNull(2))

            assertFalse(cursor.moveToNext())
        }

        assertEquals(2L, helper.countSamples())
        assertEquals(1L, helper.countPendingSamples())

        val export = helper.exportAllAsCsv()
        assertTrue(export.contains("LEGACY-A"))
        assertTrue(export.contains("LEGACY-B"))
    }

    @Test
    fun legacyPendingRows_withoutContextAreNotQueuedOrMarkedUploaded() {
        createLegacyVersion3Database(
            LegacyRow(id = 77L, sequenceId = 1L, uploaded = 0, sailNumber = "LEGACY-PENDING")
        )

        val helper = TrackingDbHelper(context)

        assertEquals(1L, helper.countPendingSamples())
        assertTrue(helper.getPendingSamples(10).isEmpty())
        assertEquals(0, uploadedValue(helper, 77L))
        assertTrue(helper.exportAllAsCsv().contains("LEGACY-PENDING"))
    }

    @Test
    fun manualRecordingStyleRow_canRemainLocalAndBeMarkedDone() {
        val helper = TrackingDbHelper(context)
        val localId = insertSample(
            helper = helper,
            sequenceId = 12L,
            accessContextId = null,
            sailNumber = "MANUAL"
        )

        helper.markUploaded(localId)

        assertEquals(1, uploadedValue(helper, localId))
        assertEquals(0L, helper.countPendingSamples())
        assertTrue(helper.getPendingSamples(10).isEmpty())
        assertTrue(helper.exportAllAsCsv().contains("MANUAL"))
    }

    @Test
    fun deleteAllSamples_removesOrphanContextsButKeepsRacePreferences() {
        val helper = TrackingDbHelper(context)
        val accessContextId = createAccessContext(helper, "Event A", "secret-a")
        insertSample(helper, sequenceId = 3L, accessContextId = accessContextId)

        context.getSharedPreferences("race_setup", Context.MODE_PRIVATE)
            .edit()
            .putString("event", "Event A")
            .commit()

        helper.deleteAllSamples()

        assertEquals(0L, helper.countSamples())
        assertNull(helper.getAccessContext(accessContextId))
        assertEquals(
            "Event A",
            context.getSharedPreferences("race_setup", Context.MODE_PRIVATE)
                .getString("event", null)
        )
    }

    private fun createAccessContext(
        helper: TrackingDbHelper,
        accessIdentifier: String,
        secret: String
    ): Long {
        return requireNotNull(
            helper.getOrCreateAccessContext(
                serverUrl = "https://raceoffice.example.org",
                accessIdentifier = accessIdentifier,
                accessSecret = secret
            )
        )
    }

    private fun insertSample(
        helper: TrackingDbHelper,
        sequenceId: Long,
        accessContextId: Long?,
        sailNumber: String = "GER 1234"
    ): Long {
        return helper.insertSample(
            sequenceId = sequenceId,
            timestamp = "2026-08-25T12:00:00",
            boatName = "Test Boat",
            captainName = "Test Skipper",
            hullColor = "white",
            sailNumber = sailNumber,
            yardstick = 100.0,
            boatType = "Test Type",
            lat = 54.0,
            lon = 10.0,
            accuracy = 5f,
            cog = 90f,
            sog = 3f,
            accelX = 0.1f,
            accelY = 0.2f,
            accelZ = 9.8f,
            gyroX = 0.01f,
            gyroY = 0.02f,
            gyroZ = 0.03f,
            accessContextId = accessContextId
        )
    }

    private fun uploadedValue(helper: TrackingDbHelper, localId: Long): Int {
        helper.readableDatabase.rawQuery(
            "SELECT uploaded FROM tracking_samples WHERE id = ?",
            arrayOf(localId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getInt(0)
        }
    }

    private fun accessContextCount(helper: TrackingDbHelper): Long {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM access_contexts",
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getLong(0)
        }
    }

    private fun createLegacyVersion3Database(vararg rows: LegacyRow) {
        val dbFile = context.getDatabasePath(DB_NAME)
        dbFile.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE tracking_samples (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    sequence_id INTEGER NOT NULL,
                    timestamp TEXT NOT NULL,
                    boat_name TEXT NOT NULL,
                    captain_name TEXT NOT NULL,
                    hull_color TEXT NOT NULL,
                    sail_number TEXT NOT NULL,
                    yardstick REAL NOT NULL,
                    boat_type TEXT NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    accuracy REAL NOT NULL,
                    cog REAL NOT NULL,
                    sog REAL NOT NULL,
                    accel_x REAL NOT NULL,
                    accel_y REAL NOT NULL,
                    accel_z REAL NOT NULL,
                    gyro_x REAL NOT NULL,
                    gyro_y REAL NOT NULL,
                    gyro_z REAL NOT NULL,
                    uploaded INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )

            for (row in rows) {
                val values = ContentValues().apply {
                    put("id", row.id)
                    put("sequence_id", row.sequenceId)
                    put("timestamp", "2026-08-24T12:00:00")
                    put("boat_name", "Legacy Boat")
                    put("captain_name", "Legacy Skipper")
                    put("hull_color", "blue")
                    put("sail_number", row.sailNumber)
                    put("yardstick", 100.0)
                    put("boat_type", "Legacy Type")
                    put("lat", 54.0)
                    put("lon", 10.0)
                    put("accuracy", 5.0)
                    put("cog", 90.0)
                    put("sog", 3.0)
                    put("accel_x", 0.1)
                    put("accel_y", 0.2)
                    put("accel_z", 9.8)
                    put("gyro_x", 0.01)
                    put("gyro_y", 0.02)
                    put("gyro_z", 0.03)
                    put("uploaded", row.uploaded)
                }
                db.insertOrThrow("tracking_samples", null, values)
            }

            db.version = 3
        }
    }

    private fun columnExists(
        db: SQLiteDatabase,
        tableName: String,
        columnName: String
    ): Boolean {
        db.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == columnName) {
                    return true
                }
            }
        }
        return false
    }

    private data class LegacyRow(
        val id: Long,
        val sequenceId: Long,
        val uploaded: Int,
        val sailNumber: String
    )

    private companion object {
        const val DB_NAME = "regatta_tracking.db"
    }
}
