package de.williserv.regattaclient

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

data class PendingTrackingSample(
    val localId: Long,
    val accessContext: AccessContext,
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

data class AccessContext(
    val id: Long,
    val serverUrl: String,
    val accessIdentifier: String,
    val accessSecret: String,
    val createdAt: Long,
    val lastUsedAt: Long
)

internal data class AccessContextKey(
    val serverUrl: String,
    val accessIdentifier: String,
    val accessSecret: String
)

internal fun normalizeAccessContextKey(
    serverUrl: String,
    accessIdentifier: String,
    accessSecret: String
): AccessContextKey? {
    val trimmedServerUrl = serverUrl.trim().trimEnd('/')
    val normalizedServerUrl = if (trimmedServerUrl.endsWith("/ingest")) {
        trimmedServerUrl.removeSuffix("/ingest")
    } else {
        trimmedServerUrl
    }
    val normalizedIdentifier = accessIdentifier.trim()
    val normalizedSecret = accessSecret.trim()

    if (
        normalizedServerUrl.isBlank() ||
        normalizedIdentifier.isBlank() ||
        normalizedSecret.isBlank()
    ) {
        return null
    }

    return AccessContextKey(
        serverUrl = normalizedServerUrl,
        accessIdentifier = normalizedIdentifier,
        accessSecret = normalizedSecret
    )
}

class TrackingDbHelper(context: Context) :
    SQLiteOpenHelper(context, "regatta_tracking.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        createAccessContextsTable(db)
        createTrackingSamplesTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4 && newVersion >= 4) {
            migrateToVersion4(db)
        }
    }

    fun getOrCreateAccessContext(
        serverUrl: String,
        accessIdentifier: String,
        accessSecret: String
    ): Long? {
        val key = normalizeAccessContextKey(
            serverUrl = serverUrl,
            accessIdentifier = accessIdentifier,
            accessSecret = accessSecret
        ) ?: return null

        val db = writableDatabase
        val now = System.currentTimeMillis()

        db.beginTransaction()
        try {
            val existingId = findAccessContextId(db, key)
            if (existingId != null) {
                val values = ContentValues().apply {
                    put("last_used_at", now)
                }
                db.update(
                    "access_contexts",
                    values,
                    "id = ?",
                    arrayOf(existingId.toString())
                )
                db.setTransactionSuccessful()
                return existingId
            }

            val values = ContentValues().apply {
                put("server_url", key.serverUrl)
                put("access_identifier", key.accessIdentifier)
                put("access_secret", key.accessSecret)
                put("created_at", now)
                put("last_used_at", now)
            }

            val insertedId = db.insertWithOnConflict(
                "access_contexts",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
            )

            val contextId = if (insertedId != -1L) {
                insertedId
            } else {
                findAccessContextId(db, key)
            }

            if (contextId != null) {
                db.setTransactionSuccessful()
            }

            return contextId
        } finally {
            db.endTransaction()
        }
    }

    fun getAccessContext(accessContextId: Long): AccessContext? {
        readableDatabase.rawQuery(
            """
            SELECT
                id,
                server_url,
                access_identifier,
                access_secret,
                created_at,
                last_used_at
            FROM access_contexts
            WHERE id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(accessContextId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }

            return AccessContext(
                id = cursor.getLong(0),
                serverUrl = cursor.getString(1),
                accessIdentifier = cursor.getString(2),
                accessSecret = cursor.getString(3),
                createdAt = cursor.getLong(4),
                lastUsedAt = cursor.getLong(5)
            )
        }
    }

    fun getPendingSamples(limit: Int): List<PendingTrackingSample> {
        val result = mutableListOf<PendingTrackingSample>()

        readableDatabase.rawQuery(
            """
            SELECT
                samples.id,
                samples.sequence_id,
                samples.timestamp,
                samples.boat_name,
                samples.captain_name,
                samples.hull_color,
                samples.sail_number,
                samples.yardstick,
                samples.boat_type,
                samples.lat,
                samples.lon,
                samples.accuracy,
                samples.cog,
                samples.sog,
                samples.accel_x,
                samples.accel_y,
                samples.accel_z,
                samples.gyro_x,
                samples.gyro_y,
                samples.gyro_z,
                contexts.id,
                contexts.server_url,
                contexts.access_identifier,
                contexts.access_secret,
                contexts.created_at,
                contexts.last_used_at
            FROM tracking_samples AS samples
            INNER JOIN access_contexts AS contexts
                ON contexts.id = samples.access_context_id
            WHERE samples.uploaded = 0
            ORDER BY samples.id ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val accessContext = AccessContext(
                    id = cursor.getLong(20),
                    serverUrl = cursor.getString(21),
                    accessIdentifier = cursor.getString(22),
                    accessSecret = cursor.getString(23),
                    createdAt = cursor.getLong(24),
                    lastUsedAt = cursor.getLong(25)
                )

                result.add(
                    PendingTrackingSample(
                        localId = cursor.getLong(0),
                        accessContext = accessContext,
                        sequenceId = cursor.getLong(1),
                        timestamp = cursor.getString(2),
                        boatName = cursor.getString(3),
                        captainName = cursor.getString(4),
                        hullColor = cursor.getString(5),
                        sailNumber = cursor.getString(6),
                        yardstick = cursor.getDouble(7),
                        boatType = cursor.getString(8),
                        lat = cursor.getDouble(9),
                        lon = cursor.getDouble(10),
                        accuracy = cursor.getFloat(11),
                        cog = cursor.getFloat(12),
                        sog = cursor.getFloat(13),
                        accelX = cursor.getFloat(14),
                        accelY = cursor.getFloat(15),
                        accelZ = cursor.getFloat(16),
                        gyroX = cursor.getFloat(17),
                        gyroY = cursor.getFloat(18),
                        gyroZ = cursor.getFloat(19)
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
        gyroZ: Float,
        accessContextId: Long? = null
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

            if (accessContextId != null) {
                put("access_context_id", accessContextId)
            } else {
                putNull("access_context_id")
            }
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
        val db = writableDatabase

        db.beginTransaction()
        try {
            db.delete("tracking_samples", null, null)
            deleteOrphanedAccessContexts(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun markUploaded(localId: Long) {
        val values = ContentValues().apply {
            put("uploaded", 1)
        }

        writableDatabase.update(
            "tracking_samples",
            values,
            "id = ?",
            arrayOf(localId.toString())
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

    private fun migrateToVersion4(db: SQLiteDatabase) {
        createAccessContextsTable(db)

        if (!tableExists(db, "tracking_samples")) {
            createTrackingSamplesTable(db)
            return
        }

        if (!columnExists(db, "tracking_samples", "access_context_id")) {
            db.execSQL(
                "ALTER TABLE tracking_samples ADD COLUMN access_context_id INTEGER"
            )
        }
    }

    private fun createAccessContextsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS access_contexts (
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
    }

    private fun createTrackingSamplesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tracking_samples (
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
    }

    private fun deleteOrphanedAccessContexts(db: SQLiteDatabase) {
        db.execSQL(
            """
            DELETE FROM access_contexts
            WHERE NOT EXISTS (
                SELECT 1
                FROM tracking_samples
                WHERE tracking_samples.access_context_id = access_contexts.id
            )
            """.trimIndent()
        )
    }

    private fun findAccessContextId(
        db: SQLiteDatabase,
        key: AccessContextKey
    ): Long? {
        db.rawQuery(
            """
            SELECT id
            FROM access_contexts
            WHERE server_url = ?
              AND access_identifier = ?
              AND access_secret = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(key.serverUrl, key.accessIdentifier, key.accessSecret)
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                cursor.getLong(0)
            } else {
                null
            }
        }
    }

    private fun tableExists(db: SQLiteDatabase, tableName: String): Boolean {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(tableName)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun columnExists(
        db: SQLiteDatabase,
        tableName: String,
        columnName: String
    ): Boolean {
        db.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex) == columnName) {
                    return true
                }
            }
        }
        return false
    }

    private fun csvEscape(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
