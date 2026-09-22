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
    val gyroZ: Float,
    val batteryPercent: Int? = null,
    val batteryCharging: Boolean? = null,
    val trackingProfile: String? = null,
    val utcOffsetMinutes: Int? = null,
    val measurementsJson: String? = null
)

data class AccessContext(
    val id: Long,
    val serverUrl: String,
    val accessIdentifier: String,
    val accessSecret: String,
    val createdAt: Long,
    val lastUsedAt: Long
)

data class TrackingSession(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val mode: String,
    val accessContextId: Long?,
    val displayName: String,
    val resolvedEventName: String? = null,
    val courseJson: String? = null,
    val courseMapViewportJson: String? = null
)

data class TrackingSessionSummary(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val mode: String,
    val accessContextId: Long?,
    val displayName: String,
    val eventIdentifier: String?,
    val sampleCount: Long
)

data class SessionTrackingSample(
    val localId: Long,
    val timestamp: String,
    val utcOffsetMinutes: Int?,
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
    val gyroZ: Float,
    val raceContextId: Long? = null,
    val resolvedEventName: String? = null,
    val courseJson: String? = null,
    val courseMapViewportJson: String? = null
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
    SQLiteOpenHelper(context, "regatta_tracking.db", null, 11) {

    private val appContext = context.applicationContext
    private var lastBatteryReadAtMs: Long? = null
    private var lastEmittedTrackingProfile: String? = null
    private var lastTrackingProfileAtMs: Long? = null

    fun resetTrackingSessionMetadata() {
        lastEmittedTrackingProfile = null
        lastTrackingProfileAtMs = null
    }

    override fun onCreate(db: SQLiteDatabase) {
        createAccessContextsTable(db)
        createTrackingSessionsTable(db)
        createRaceContextsTable(db)
        createTrackingSamplesTable(db)
        createTrackingSampleIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4 && newVersion >= 4) {
            migrateToVersion4(db)
        }
        if (oldVersion < 5 && newVersion >= 5) {
            migrateToVersion5(db)
        }
        if (oldVersion < 6 && newVersion >= 6) {
            migrateToVersion6(db)
        }
        if (oldVersion < 7 && newVersion >= 7) {
            migrateToVersion7(db)
        }
        if (oldVersion < 8 && newVersion >= 8) {
            migrateToVersion8(db)
        }
        if (oldVersion < 9 && newVersion >= 9) {
            migrateToVersion9(db)
        }
        if (oldVersion < 10 && newVersion >= 10) {
            migrateToVersion10(db)
        }
        if (oldVersion < 11 && newVersion >= 11) {
            migrateToVersion11(db)
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

    fun getOrCreateRaceContext(
        accessContextId: Long,
        resolvedEventName: String,
        courseJson: String?,
        courseMapViewportJson: String?
    ): Long? {
        val normalizedName = resolvedEventName.trim()
        if (normalizedName.isBlank()) return null

        val db = writableDatabase
        val insertValues = ContentValues().apply {
            put("access_context_id", accessContextId)
            put("resolved_event_name", normalizedName)
            putNullableString("course_json", courseJson)
            putNullableString("course_map_viewport_json", courseMapViewportJson)
        }
        db.insertWithOnConflict(
            "race_contexts",
            null,
            insertValues,
            SQLiteDatabase.CONFLICT_IGNORE
        )

        val raceContextId = findRaceContextId(
            db = db,
            accessContextId = accessContextId,
            resolvedEventName = normalizedName
        ) ?: return null

        val updateValues = ContentValues().apply {
            courseJson?.takeIf { it.isNotBlank() }?.let { put("course_json", it) }
            courseMapViewportJson
                ?.takeIf { it.isNotBlank() }
                ?.let { put("course_map_viewport_json", it) }
        }
        if (updateValues.size() > 0) {
            db.update(
                "race_contexts",
                updateValues,
                "id = ?",
                arrayOf(raceContextId.toString())
            )
        }

        return raceContextId
    }

    fun createTrackingSession(
        startedAt: Long,
        mode: String,
        accessContextId: Long?,
        displayName: String,
        resolvedEventName: String? = null,
        courseJson: String? = null,
        courseMapViewportJson: String? = null
    ): Long? {
        require(mode == "race" || mode == "manual")
        if (mode == "race" && accessContextId == null) return null
        if (mode == "manual" && accessContextId != null) return null

        val values = ContentValues().apply {
            put("started_at", startedAt)
            putNull("ended_at")
            put("mode", mode)
            if (accessContextId != null) {
                put("access_context_id", accessContextId)
            } else {
                putNull("access_context_id")
            }
            put("display_name", displayName)
            putNullableString("resolved_event_name", resolvedEventName)
            putNullableString("course_json", courseJson)
            putNullableString("course_map_viewport_json", courseMapViewportJson)
        }

        val insertedId = writableDatabase.insert("tracking_sessions", null, values)
        return insertedId.takeIf { it != -1L }
    }

    fun getTrackingSession(sessionId: Long): TrackingSession? {
        readableDatabase.rawQuery(
            """
            SELECT
                id,
                started_at,
                ended_at,
                mode,
                access_context_id,
                display_name,
                resolved_event_name,
                course_json,
                course_map_viewport_json
            FROM tracking_sessions
            WHERE id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(sessionId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return TrackingSession(
                id = cursor.getLong(0),
                startedAt = cursor.getLong(1),
                endedAt = if (cursor.isNull(2)) null else cursor.getLong(2),
                mode = cursor.getString(3),
                accessContextId = if (cursor.isNull(4)) null else cursor.getLong(4),
                displayName = cursor.getString(5),
                resolvedEventName = if (cursor.isNull(6)) null else cursor.getString(6),
                courseJson = if (cursor.isNull(7)) null else cursor.getString(7),
                courseMapViewportJson = if (cursor.isNull(8)) null else cursor.getString(8)
            )
        }
    }

    fun getTrackingSessionSummaries(): List<TrackingSessionSummary> {
        val result = mutableListOf<TrackingSessionSummary>()
        readableDatabase.rawQuery(
            """
            SELECT
                sessions.id,
                sessions.started_at,
                sessions.ended_at,
                sessions.mode,
                sessions.access_context_id,
                sessions.display_name,
                CASE
                    WHEN COUNT(DISTINCT race_contexts.resolved_event_name) = 1
                        THEN MAX(race_contexts.resolved_event_name)
                    WHEN COUNT(DISTINCT race_contexts.resolved_event_name) > 1
                        THEN contexts.access_identifier
                    ELSE COALESCE(sessions.resolved_event_name, contexts.access_identifier)
                END,
                COUNT(DISTINCT samples.id)
            FROM tracking_sessions AS sessions
            LEFT JOIN access_contexts AS contexts
                ON contexts.id = sessions.access_context_id
            LEFT JOIN tracking_samples AS samples
                ON samples.session_id = sessions.id
            LEFT JOIN race_contexts
                ON race_contexts.id = samples.race_context_id
            GROUP BY
                sessions.id,
                sessions.started_at,
                sessions.ended_at,
                sessions.mode,
                sessions.access_context_id,
                sessions.display_name,
                sessions.resolved_event_name,
                contexts.access_identifier
            ORDER BY sessions.started_at DESC, sessions.id DESC
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    TrackingSessionSummary(
                        id = cursor.getLong(0),
                        startedAt = cursor.getLong(1),
                        endedAt = if (cursor.isNull(2)) null else cursor.getLong(2),
                        mode = cursor.getString(3),
                        accessContextId = if (cursor.isNull(4)) null else cursor.getLong(4),
                        displayName = cursor.getString(5),
                        eventIdentifier = if (cursor.isNull(6)) null else cursor.getString(6),
                        sampleCount = cursor.getLong(7)
                    )
                )
            }
        }
        return result
    }

    fun getTrackingSamplesForSession(sessionId: Long): List<SessionTrackingSample> {
        val result = mutableListOf<SessionTrackingSample>()
        readableDatabase.rawQuery(
            """
            SELECT
                samples.id,
                samples.timestamp,
                samples.utc_offset_minutes,
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
                samples.race_context_id,
                race_contexts.resolved_event_name,
                race_contexts.course_json,
                race_contexts.course_map_viewport_json
            FROM tracking_samples AS samples
            LEFT JOIN race_contexts
                ON race_contexts.id = samples.race_context_id
            WHERE samples.session_id = ?
            ORDER BY samples.id ASC
            """.trimIndent(),
            arrayOf(sessionId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    SessionTrackingSample(
                        localId = cursor.getLong(0),
                        timestamp = cursor.getString(1),
                        utcOffsetMinutes = if (cursor.isNull(2)) null else cursor.getInt(2),
                        lat = cursor.getDouble(3),
                        lon = cursor.getDouble(4),
                        accuracy = cursor.getFloat(5),
                        cog = cursor.getFloat(6),
                        sog = cursor.getFloat(7),
                        accelX = cursor.getFloat(8),
                        accelY = cursor.getFloat(9),
                        accelZ = cursor.getFloat(10),
                        gyroX = cursor.getFloat(11),
                        gyroY = cursor.getFloat(12),
                        gyroZ = cursor.getFloat(13),
                        raceContextId = if (cursor.isNull(14)) null else cursor.getLong(14),
                        resolvedEventName = if (cursor.isNull(15)) null else cursor.getString(15),
                        courseJson = if (cursor.isNull(16)) null else cursor.getString(16),
                        courseMapViewportJson = if (cursor.isNull(17)) null else cursor.getString(17)
                    )
                )
            }
        }
        return result
    }

    fun updateTrackingSessionRaceContext(
        sessionId: Long,
        resolvedEventName: String?,
        courseJson: String?,
        courseMapViewportJson: String?
    ): Boolean {
        val values = ContentValues().apply {
            putNullableString("resolved_event_name", resolvedEventName)
            putNullableString("course_json", courseJson)
            putNullableString("course_map_viewport_json", courseMapViewportJson)
        }
        return writableDatabase.update(
            "tracking_sessions",
            values,
            "id = ? AND mode = 'race'",
            arrayOf(sessionId.toString())
        ) > 0
    }

    fun finishTrackingSession(sessionId: Long, endedAt: Long): Boolean {
        val values = ContentValues().apply {
            put("ended_at", endedAt)
        }
        return writableDatabase.update(
            "tracking_sessions",
            values,
            "id = ? AND ended_at IS NULL",
            arrayOf(sessionId.toString())
        ) > 0
    }

    fun countTrackingSessions(): Long {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM tracking_sessions",
            null
        ).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
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
                samples.battery_percent,
                samples.battery_charging,
                samples.tracking_profile,
                samples.utc_offset_minutes,
                samples.measurements_json,
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
                    id = cursor.getLong(25),
                    serverUrl = cursor.getString(26),
                    accessIdentifier = cursor.getString(27),
                    accessSecret = cursor.getString(28),
                    createdAt = cursor.getLong(29),
                    lastUsedAt = cursor.getLong(30)
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
                        gyroZ = cursor.getFloat(19),
                        batteryPercent = if (cursor.isNull(20)) null else cursor.getInt(20),
                        batteryCharging = if (cursor.isNull(21)) null else cursor.getInt(21) != 0,
                        trackingProfile = if (cursor.isNull(22)) null else cursor.getString(22),
                        utcOffsetMinutes = if (cursor.isNull(23)) null else cursor.getInt(23),
                        measurementsJson = if (cursor.isNull(24)) null else cursor.getString(24)
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
        batteryPercent: Int? = null,
        batteryCharging: Boolean? = null,
        trackingProfile: String? = null,
        accessContextId: Long? = null,
        sessionId: Long? = null,
        raceContextId: Long? = null,
        utcOffsetMinutes: Int? = null,
        measurementsJson: String? = null
    ): Long {
        val nowMs = System.currentTimeMillis()
        val shouldReadBattery = batteryPercent == null &&
            batteryCharging == null &&
            SampleMetadataPolicy.shouldReadBattery(lastBatteryReadAtMs, nowMs)
        val automaticBattery = if (shouldReadBattery) {
            BatteryTelemetry.read(appContext)
        } else {
            null
        }

        val currentProfile = trackingProfile
            ?: TrackingProfileConfig.read(appContext).persistedValue
        val automaticProfile = if (
            trackingProfile != null ||
            SampleMetadataPolicy.shouldEmitTrackingProfile(
                lastEmittedProfile = lastEmittedTrackingProfile,
                lastEmittedAtMs = lastTrackingProfileAtMs,
                currentProfile = currentProfile,
                nowMs = nowMs
            )
        ) {
            currentProfile
        } else {
            null
        }

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

            val effectiveBatteryPercent = batteryPercent ?: automaticBattery?.percent
            val effectiveBatteryCharging = batteryCharging ?: automaticBattery?.charging
            if (effectiveBatteryPercent != null) put("battery_percent", effectiveBatteryPercent) else putNull("battery_percent")
            if (effectiveBatteryCharging != null) put("battery_charging", if (effectiveBatteryCharging) 1 else 0) else putNull("battery_charging")
            if (automaticProfile != null) put("tracking_profile", automaticProfile) else putNull("tracking_profile")
            if (utcOffsetMinutes != null) put("utc_offset_minutes", utcOffsetMinutes) else putNull("utc_offset_minutes")
            putNullableString("measurements_json", measurementsJson)

            if (accessContextId != null) {
                put("access_context_id", accessContextId)
            } else {
                putNull("access_context_id")
            }

            if (sessionId != null) {
                put("session_id", sessionId)
            } else {
                putNull("session_id")
            }

            if (raceContextId != null) {
                put("race_context_id", raceContextId)
            } else {
                putNull("race_context_id")
            }
        }

        val insertedId = writableDatabase.insert("tracking_samples", null, values)
        if (insertedId != -1L) {
            if (shouldReadBattery) {
                lastBatteryReadAtMs = nowMs
            }
            if (automaticProfile != null) {
                lastEmittedTrackingProfile = automaticProfile
                lastTrackingProfileAtMs = nowMs
            }
        }

        return insertedId
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
            db.delete("tracking_sessions", null, null)
            db.delete("race_contexts", null, null)
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

    fun markUploaded(localIds: Collection<Long>) {
        if (localIds.isEmpty()) return

        val db = writableDatabase
        val values = ContentValues().apply {
            put("uploaded", 1)
        }

        db.beginTransaction()
        try {
            localIds.forEach { localId ->
                db.update(
                    "tracking_samples",
                    values,
                    "id = ?",
                    arrayOf(localId.toString())
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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

    fun countUploadablePendingSamples(): Long {
        readableDatabase.rawQuery(
            """
            SELECT COUNT(*)
            FROM tracking_samples AS samples
            INNER JOIN access_contexts AS contexts
                ON contexts.id = samples.access_context_id
            WHERE samples.uploaded = 0
            """.trimIndent(),
            null
        ).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    fun hasUploadablePendingSamples(): Boolean {
        readableDatabase.rawQuery(
            """
            SELECT 1
            FROM tracking_samples AS samples
            INNER JOIN access_contexts AS contexts
                ON contexts.id = samples.access_context_id
            WHERE samples.uploaded = 0
            LIMIT 1
            """.trimIndent(),
            null
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    fun exportAllAsCsv(): String {
        val header =
            "sequence_id,timestamp,utc_offset_minutes,boat_name,captain_name,hull_color,sail_number,yardstick,boat_type,lat,lon,accuracy,cog,sog,accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z\n"

        val builder = StringBuilder()
        builder.append(header)

        readableDatabase.rawQuery(
            """
            SELECT
                sequence_id,
                timestamp,
                utc_offset_minutes,
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
                val utcOffset = if (cursor.isNull(2)) "" else cursor.getInt(2).toString()
                builder.append(
                    String.format(
                        Locale.US,
                        "%d,%s,%s,%s,%s,%s,%s,%.2f,%s,%.7f,%.7f,%.2f,%.2f,%.2f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f\n",
                        cursor.getLong(0),
                        csvEscape(cursor.getString(1)),
                        utcOffset,
                        csvEscape(cursor.getString(3)),
                        csvEscape(cursor.getString(4)),
                        csvEscape(cursor.getString(5)),
                        csvEscape(cursor.getString(6)),
                        cursor.getDouble(7),
                        csvEscape(cursor.getString(8)),
                        cursor.getDouble(9),
                        cursor.getDouble(10),
                        cursor.getDouble(11),
                        cursor.getDouble(12),
                        cursor.getDouble(13),
                        cursor.getDouble(14),
                        cursor.getDouble(15),
                        cursor.getDouble(16),
                        cursor.getDouble(17),
                        cursor.getDouble(18),
                        cursor.getDouble(19)
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

        // Legacy rows keep their existing id, uploaded state and payload untouched.
        // A NULL access_context_id is intentional because their original access cannot be proven.
        if (!columnExists(db, "tracking_samples", "access_context_id")) {
            db.execSQL(
                "ALTER TABLE tracking_samples ADD COLUMN access_context_id INTEGER"
            )
        }
    }

    private fun migrateToVersion5(db: SQLiteDatabase) {
        if (!tableExists(db, "tracking_samples")) {
            createTrackingSamplesTable(db)
            return
        }

        if (!columnExists(db, "tracking_samples", "battery_percent")) {
            db.execSQL("ALTER TABLE tracking_samples ADD COLUMN battery_percent INTEGER")
        }
        if (!columnExists(db, "tracking_samples", "battery_charging")) {
            db.execSQL("ALTER TABLE tracking_samples ADD COLUMN battery_charging INTEGER")
        }
        if (!columnExists(db, "tracking_samples", "tracking_profile")) {
            db.execSQL("ALTER TABLE tracking_samples ADD COLUMN tracking_profile TEXT")
        }
    }

    private fun migrateToVersion6(db: SQLiteDatabase) {
        if (!tableExists(db, "tracking_samples")) {
            createTrackingSamplesTable(db)
            return
        }

        if (!columnExists(db, "tracking_samples", "utc_offset_minutes")) {
            db.execSQL("ALTER TABLE tracking_samples ADD COLUMN utc_offset_minutes INTEGER")
        }
    }

    private fun migrateToVersion7(db: SQLiteDatabase) {
        if (!tableExists(db, "tracking_samples")) {
            createTrackingSamplesTable(db)
        }
        createTrackingSampleIndexes(db)
    }

    private fun migrateToVersion8(db: SQLiteDatabase) {
        createTrackingSessionsTable(db)

        if (!tableExists(db, "tracking_samples")) {
            createTrackingSamplesTable(db)
        } else if (!columnExists(db, "tracking_samples", "session_id")) {
            db.execSQL("ALTER TABLE tracking_samples ADD COLUMN session_id INTEGER")
        }

        createTrackingSampleIndexes(db)
    }

    private fun migrateToVersion9(db: SQLiteDatabase) {
        createTrackingSessionsTable(db)
        if (!columnExists(db, "tracking_sessions", "resolved_event_name")) {
            db.execSQL("ALTER TABLE tracking_sessions ADD COLUMN resolved_event_name TEXT")
        }
        if (!columnExists(db, "tracking_sessions", "course_json")) {
            db.execSQL("ALTER TABLE tracking_sessions ADD COLUMN course_json TEXT")
        }
        if (!columnExists(db, "tracking_sessions", "course_map_viewport_json")) {
            db.execSQL(
                "ALTER TABLE tracking_sessions ADD COLUMN course_map_viewport_json TEXT"
            )
        }
    }

    private fun migrateToVersion11(db: SQLiteDatabase) {
        if (!tableExists(db, "tracking_samples")) {
            createTrackingSamplesTable(db)
            return
        }
        if (!columnExists(db, "tracking_samples", "measurements_json")) {
            db.execSQL(
                "ALTER TABLE tracking_samples ADD COLUMN measurements_json TEXT"
            )
        }
    }

    private fun migrateToVersion10(db: SQLiteDatabase) {
        createRaceContextsTable(db)

        if (!tableExists(db, "tracking_samples")) {
            createTrackingSamplesTable(db)
        } else if (!columnExists(db, "tracking_samples", "race_context_id")) {
            db.execSQL("ALTER TABLE tracking_samples ADD COLUMN race_context_id INTEGER")
        }

        if (
            tableExists(db, "tracking_sessions") &&
            columnExists(db, "tracking_sessions", "resolved_event_name")
        ) {
            db.execSQL(
                """
                INSERT OR IGNORE INTO race_contexts (
                    access_context_id,
                    resolved_event_name,
                    course_json,
                    course_map_viewport_json
                )
                SELECT
                    access_context_id,
                    resolved_event_name,
                    course_json,
                    course_map_viewport_json
                FROM tracking_sessions
                WHERE mode = 'race'
                  AND access_context_id IS NOT NULL
                  AND resolved_event_name IS NOT NULL
                  AND TRIM(resolved_event_name) != ''
                """.trimIndent()
            )
            db.execSQL(
                """
                UPDATE tracking_samples
                SET race_context_id = (
                    SELECT race_contexts.id
                    FROM tracking_sessions
                    INNER JOIN race_contexts
                        ON race_contexts.access_context_id = tracking_sessions.access_context_id
                       AND race_contexts.resolved_event_name = tracking_sessions.resolved_event_name
                    WHERE tracking_sessions.id = tracking_samples.session_id
                    LIMIT 1
                )
                WHERE race_context_id IS NULL
                  AND session_id IS NOT NULL
                """.trimIndent()
            )
        }

        createTrackingSampleIndexes(db)
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

    private fun createTrackingSessionsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tracking_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                mode TEXT NOT NULL,
                access_context_id INTEGER,
                display_name TEXT NOT NULL,
                resolved_event_name TEXT,
                course_json TEXT,
                course_map_viewport_json TEXT
            )
            """.trimIndent()
        )
    }

    private fun createRaceContextsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS race_contexts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                access_context_id INTEGER NOT NULL,
                resolved_event_name TEXT NOT NULL,
                course_json TEXT,
                course_map_viewport_json TEXT,
                UNIQUE(access_context_id, resolved_event_name)
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
                access_context_id INTEGER,
                battery_percent INTEGER,
                battery_charging INTEGER,
                tracking_profile TEXT,
                utc_offset_minutes INTEGER,
                measurements_json TEXT,
                session_id INTEGER,
                race_context_id INTEGER
            )
            """.trimIndent()
        )
    }

    private fun ContentValues.putNullableString(key: String, value: String?) {
        val normalized = value?.takeIf { it.isNotBlank() }
        if (normalized != null) {
            put(key, normalized)
        } else {
            putNull(key)
        }
    }

    private fun createTrackingSampleIndexes(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_tracking_samples_pending_id
            ON tracking_samples(uploaded, id)
            """.trimIndent()
        )
        if (columnExists(db, "tracking_samples", "session_id")) {
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_tracking_samples_session_id
                ON tracking_samples(session_id, id)
                """.trimIndent()
            )
        }
        if (columnExists(db, "tracking_samples", "race_context_id")) {
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_tracking_samples_race_context_id
                ON tracking_samples(race_context_id, id)
                """.trimIndent()
            )
        }
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

    private fun findRaceContextId(
        db: SQLiteDatabase,
        accessContextId: Long,
        resolvedEventName: String
    ): Long? {
        db.rawQuery(
            """
            SELECT id
            FROM race_contexts
            WHERE access_context_id = ?
              AND resolved_event_name = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(accessContextId.toString(), resolvedEventName)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
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
