package de.williserv.regattaclient

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
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
class TelemetryUtcOffsetPersistenceTest {

    private lateinit var context: Context
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB_NAME)
        originalTimeZone = TimeZone.getDefault()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `fresh database contains nullable utc offset column`() {
        val helper = TrackingDbHelper(context)

        assertTrue(columnExists(helper.writableDatabase, "tracking_samples", "utc_offset_minutes"))
    }

    @Test
    fun `version 5 migration preserves pending row and leaves historical offset unknown`() {
        createVersion5Database()

        val helper = TrackingDbHelper(context)
        val pending = helper.getPendingSamples(10).single()

        assertTrue(columnExists(helper.writableDatabase, "tracking_samples", "utc_offset_minutes"))
        assertEquals(41L, pending.localId)
        assertEquals("Event A", pending.accessContext.accessIdentifier)
        assertNull(pending.utcOffsetMinutes)
        assertTrue(helper.exportAllAsCsv().contains("LEGACY-A"))
        assertTrue(helper.exportAllAsCsv().contains("\"2026-08-24T12:00:00\",,"))
    }

    @Test
    fun `stored offset survives queue reads and later device timezone changes`() {
        val helper = TrackingDbHelper(context)
        val accessContextId = requireNotNull(
            helper.getOrCreateAccessContext(
                serverUrl = "https://raceoffice.example.org",
                accessIdentifier = "Event A",
                accessSecret = "secret-a"
            )
        )

        val localId = insertSample(
            helper = helper,
            accessContextId = accessContextId,
            utcOffsetMinutes = 120
        )

        TimeZone.setDefault(TimeZone.getTimeZone("GMT+01:00"))

        val pending = helper.getPendingSamples(10).single()
        val uploadPending = getTelemetryUploadPage(helper, afterLocalId = 0L, limit = 10).single()

        assertEquals(localId, pending.localId)
        assertEquals(120, pending.utcOffsetMinutes)
        assertEquals(120, uploadPending.utcOffsetMinutes)
        assertEquals(
            120,
            buildTelemetryUploadPayload(
                sample = uploadPending,
                client = ClientBuildIdentity(versionCode = 1, buildId = "test")
            ).getInt("utc_offset_minutes")
        )
        assertTrue(helper.exportAllAsCsv().contains("\"2026-10-25T02:59:00\",120,"))
    }

    private fun insertSample(
        helper: TrackingDbHelper,
        accessContextId: Long,
        utcOffsetMinutes: Int
    ): Long {
        return helper.insertSample(
            sequenceId = 1L,
            timestamp = "2026-10-25T02:59:00",
            boatName = "Test Boat",
            captainName = "Test Skipper",
            hullColor = "white",
            sailNumber = "GER 1234",
            yardstick = 100.0,
            boatType = "Test Type",
            lat = 54.0,
            lon = 10.0,
            accuracy = 5f,
            cog = 90f,
            sog = 3f,
            accessContextId = accessContextId,
            utcOffsetMinutes = utcOffsetMinutes
        )
    }

    private fun createVersion5Database() {
        val dbFile = context.getDatabasePath(DB_NAME)
        dbFile.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE access_contexts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    server_url TEXT NOT NULL,
                    access_identifier TEXT NOT NULL,
                    access_secret TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    last_used_at INTEGER NOT NULL,
                    UNIQUE(server_url, access_identifier, access_secret)
                )
                """.trimIndent()
            )
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
                    uploaded INTEGER NOT NULL DEFAULT 0,
                    access_context_id INTEGER,
                    battery_percent INTEGER,
                    battery_charging INTEGER,
                    tracking_profile TEXT
                )
                """.trimIndent()
            )

            db.insertOrThrow(
                "access_contexts",
                null,
                ContentValues().apply {
                    put("id", 7L)
                    put("server_url", "https://raceoffice.example.org")
                    put("access_identifier", "Event A")
                    put("access_secret", "secret-a")
                    put("created_at", 1L)
                    put("last_used_at", 1L)
                }
            )
            db.insertOrThrow(
                "tracking_samples",
                null,
                ContentValues().apply {
                    put("id", 41L)
                    put("sequence_id", 1L)
                    put("timestamp", "2026-08-24T12:00:00")
                    put("boat_name", "Legacy Boat")
                    put("captain_name", "Legacy Skipper")
                    put("hull_color", "blue")
                    put("sail_number", "LEGACY-A")
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
                    put("uploaded", 0)
                    put("access_context_id", 7L)
                }
            )

            db.version = 5
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

    private companion object {
        const val DB_NAME = "regatta_tracking.db"
    }
}
