package de.williserv.regattaclient

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
class TrackingMetadataPersistenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB_NAME)
        context.getSharedPreferences("tracking_config", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
        context.getSharedPreferences("tracking_config", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `explicit sample metadata remains unchanged after profile setting changes`() {
        val helper = TrackingDbHelper(context)
        val accessContextId = requireNotNull(
            helper.getOrCreateAccessContext(
                serverUrl = "https://raceoffice.example.org",
                accessIdentifier = "Event A",
                accessSecret = "secret"
            )
        )

        val localId = insertSample(
            helper = helper,
            accessContextId = accessContextId,
            batteryPercent = 41,
            batteryCharging = true,
            trackingProfile = "normal"
        )

        TrackingProfileConfig.write(context, TrackingProfile.BATTERY_SAVER)

        val pending = helper.getPendingSamples(10).single { it.localId == localId }
        assertEquals(41, pending.batteryPercent)
        assertEquals(true, pending.batteryCharging)
        assertEquals("normal", pending.trackingProfile)
        helper.close()
    }

    @Test
    fun `version four migration adds nullable metadata columns without changing rows`() {
        createVersion4Database()

        val helper = TrackingDbHelper(context)
        val db = helper.writableDatabase

        assertTrue(columnExists(db, "tracking_samples", "battery_percent"))
        assertTrue(columnExists(db, "tracking_samples", "battery_charging"))
        assertTrue(columnExists(db, "tracking_samples", "tracking_profile"))

        db.rawQuery(
            "SELECT id, battery_percent, battery_charging, tracking_profile FROM tracking_samples WHERE id = 17",
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(17L, cursor.getLong(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
        }
        helper.close()
    }

    @Test
    fun `new helper emits profile on first inserted sample`() {
        TrackingProfileConfig.write(context, TrackingProfile.BATTERY_SAVER)
        val helper = TrackingDbHelper(context)
        val accessContextId = requireNotNull(
            helper.getOrCreateAccessContext(
                serverUrl = "https://raceoffice.example.org",
                accessIdentifier = "Event A",
                accessSecret = "secret"
            )
        )

        val localId = insertSample(
            helper = helper,
            accessContextId = accessContextId,
            batteryPercent = null,
            batteryCharging = null,
            trackingProfile = null
        )

        val pending = helper.getPendingSamples(10).single { it.localId == localId }
        assertEquals("battery_saver", pending.trackingProfile)
        helper.close()
    }

    private fun insertSample(
        helper: TrackingDbHelper,
        accessContextId: Long,
        batteryPercent: Int?,
        batteryCharging: Boolean?,
        trackingProfile: String?
    ): Long {
        return helper.insertSample(
            sequenceId = 1L,
            timestamp = "2026-09-05T00:00:00",
            boatName = "Test Boat",
            captainName = "Test Captain",
            hullColor = "white",
            sailNumber = "GER 1",
            yardstick = 100.0,
            boatType = "Test",
            lat = 53.0,
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
            batteryPercent = batteryPercent,
            batteryCharging = batteryCharging,
            trackingProfile = trackingProfile,
            accessContextId = accessContextId
        )
    }

    private fun createVersion4Database() {
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
                    access_context_id INTEGER
                )
                """.trimIndent()
            )

            val values = ContentValues().apply {
                put("id", 17L)
                put("sequence_id", 1L)
                put("timestamp", "2026-09-04T00:00:00")
                put("boat_name", "Legacy Boat")
                put("captain_name", "Legacy Captain")
                put("hull_color", "white")
                put("sail_number", "GER 17")
                put("yardstick", 100.0)
                put("boat_type", "Legacy")
                put("lat", 53.0)
                put("lon", 10.0)
                put("accuracy", 5.0)
                put("cog", 0.0)
                put("sog", 0.0)
                put("accel_x", 0.0)
                put("accel_y", 0.0)
                put("accel_z", 0.0)
                put("gyro_x", 0.0)
                put("gyro_y", 0.0)
                put("gyro_z", 0.0)
                put("uploaded", 0)
                putNull("access_context_id")
            }
            db.insertOrThrow("tracking_samples", null, values)
            db.version = 4
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
                if (cursor.getString(nameIndex) == columnName) return true
            }
        }
        return false
    }

    private companion object {
        const val DB_NAME = "regatta_tracking.db"
    }
}
