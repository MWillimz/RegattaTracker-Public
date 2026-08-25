package de.williserv.regattaclient

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

data class PendingTrackingSample(
    val sequenceId: Long,
    val timestamp: String,
    val boatName: String,
    val captainName: String,
    val hullColor: String,
    val sailNumber: String,
    val yardstick: Double,
    val boatType: String,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val cog: Float,
    val sog: Float,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float
)

class TrackingDbHelper(context: Context) :
    SQLiteOpenHelper(context, "regatta_tracking.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS tracking_samples")
        onCreate(db)
    }

    fun getPendingSamples(limit: Int): List<PendingTrackingSample> {
        val result = mutableListOf<PendingTrackingSample>()

        readableDatabase.rawQuery(
            """
            SELECT 
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
                accel_x,
                accel_y,
                accel_z,
                gyro_x,
                gyro_y,
                gyro_z
            FROM tracking_samples
            WHERE uploaded = 0
            ORDER BY sequence_id ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    PendingTrackingSample(
                        sequenceId = cursor.getLong(0),
                        timestamp = cursor.getString(1),
                        boatName = cursor.getString(2),
                        captainName = cursor.getString(3),
                        hullColor = cursor.getString(4),
                        sailNumber = cursor.getString(5),
                        yardstick = cursor.getDouble(6),
                        boatType = cursor.getString(7),
                        lat = cursor.getDouble(8),
                        lon = cursor.getDouble(9),
                        accuracy = cursor.getFloat(10),
                        cog = cursor.getFloat(11),
                        sog = cursor.getFloat(12),
                        accelX = cursor.getFloat(13),
                        accelY = cursor.getFloat(14),
                        accelZ = cursor.getFloat(15),
                        gyroX = cursor.getFloat(16),
                        gyroY = cursor.getFloat(17),
                        gyroZ = cursor.getFloat(18)
                    )
                )
            }
        }

        return result
    }

    fun insertSample(
        sequenceId: Long,
        timestamp: String,
        boatName: String,
        captainName: String,
        hullColor: String,
        sailNumber: String,
        yardstick: Double,
        boatType: String,
        lat: Double,
        lon: Double,
        accuracy: Float,
        cog: Float,
        sog: Float,
        accelX: Float,
        accelY: Float,
        accelZ: Float,
        gyroX: Float,
        gyroY: Float,
        gyroZ: Float
    ): Long {
        val values = ContentValues().apply {
            put("sequence_id", sequenceId)
            put("timestamp", timestamp)
            put("boat_name", boatName)
            put("captain_name", captainName)
            put("hull_color", hullColor)
            put("sail_number", sailNumber)
            put("yardstick", yardstick)
            put("boat_type", boatType)
            put("lat", lat)
            put("lon", lon)
            put("accuracy", accuracy)
            put("cog", cog)
            put("sog", sog)
            put("accel_x", accelX)
            put("accel_y", accelY)
            put("accel_z", accelZ)
            put("gyro_x", gyroX)
            put("gyro_y", gyroY)
            put("gyro_z", gyroZ)
        }

        return writableDatabase.insert("tracking_samples", null, values)
    }

    fun countSamples(): Long {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM tracking_samples",
            null
        ).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    fun deleteAllSamples() {
        writableDatabase.delete("tracking_samples", null, null)
    }

    fun markUploaded(sequenceId: Long) {
        val values = ContentValues().apply {
            put("uploaded", 1)
        }

        writableDatabase.update(
            "tracking_samples",
            values,
            "sequence_id = ?",
            arrayOf(sequenceId.toString())
        )
    }

    fun countPendingSamples(): Long {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM tracking_samples WHERE uploaded = 0",
            null
        ).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    fun exportAllAsCsv(): String {
        val header =
            "sequence_id,timestamp,boat_name,captain_name,hull_color,sail_number,yardstick,boat_type,lat,lon,accuracy,cog,sog,accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z\n"

        val builder = StringBuilder()
        builder.append(header)

        readableDatabase.rawQuery(
            """
            SELECT 
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
                accel_x,
                accel_y,
                accel_z,
                gyro_x,
                gyro_y,
                gyro_z
            FROM tracking_samples
            ORDER BY sequence_id ASC
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                builder.append(
                    String.format(
                        Locale.US,
                        "%d,%s,%s,%s,%s,%s,%.2f,%s,%.7f,%.7f,%.2f,%.2f,%.2f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f\n",
                        cursor.getLong(0),
                        csvEscape(cursor.getString(1)),
                        csvEscape(cursor.getString(2)),
                        csvEscape(cursor.getString(3)),
                        csvEscape(cursor.getString(4)),
                        csvEscape(cursor.getString(5)),
                        cursor.getDouble(6),
                        csvEscape(cursor.getString(7)),
                        cursor.getDouble(8),
                        cursor.getDouble(9),
                        cursor.getDouble(10),
                        cursor.getDouble(11),
                        cursor.getDouble(12),
                        cursor.getDouble(13),
                        cursor.getDouble(14),
                        cursor.getDouble(15),
                        cursor.getDouble(16),
                        cursor.getDouble(17),
                        cursor.getDouble(18)
                    )
                )
            }
        }

        return builder.toString()
    }

    private fun csvEscape(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}