package de.williserv.regattaclient

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

data class TrackingStorageCounts(
    val total: Long,
    val pending: Long
)

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
    val cogValid: Boolean? = null,
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
    val cogValid: Boolean? = null,
    val measurementsJson: String? = null,
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
    SQLiteOpenHelper(context, "regatta_tracking.db", null, 14) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

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
        if (oldVersion < 12 && newVersion >= 12) {
            migrateToVersion12(db)
        }
        if (oldVersion < 13 && newVersion >= 13) {
            migrateToVersion13(db)
        }
        if (oldVersion < 14 && newVersion >= 14) {
            migrateToVersion14(db)
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

        val normalizedCourseJson = courseJson?.takeIf { it.isNotBlank() }
        val normalizedViewportJson = courseMapViewportJson?.takeIf { it.isNotBlank() }
        val db = writableDatabase

        /*
         * Calls without snapshot data are lookup-only. This is used while
         * sampling if the service has restored the resolved event name before
         * a fresh /event snapshot has been applied.
         */
        if (normalizedCourseJson == null && normalizedViewportJson == null) {
            return findLatestRaceContextId(
                db = db,
                accessContextId = accessContextId,
                resolvedEventName = normalizedName
            )
        }

        val contextKey = raceContextVersionKey(
            courseJson = normalizedCourseJson,
            courseMapViewportJson = normalizedViewportJson
        )
        val insertValues = ContentValues().apply {
            put("access_context_id", accessContextId)
            put("resolved_event_name", normalizedName)
            putNullableString("course_json", normalizedCourseJson)
            putNullableString("course_map_viewport_json", normalizedViewportJson)
            put("context_key", contextKey)
        }
        db.insertWithOnConflict(
            "race_contexts",
            null,
            insertValues,
            SQLiteDatabase.CONFLICT_IGNORE
        )

        return findRaceContextId(
            db = db,
            accessContextId = accessContextId,
            resolvedEventName = normalizedName,
            contextKey = contextKey
        )
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

    fun deleteTrackingSession(sessionId: Long): Boolean {
        val db = writableDatabase
        val args = arrayOf(sessionId.toString())

        db.beginTransaction()
        try {
            val isFinished = db.rawQuery(
                """
                SELECT ended_at
                FROM tracking_sessions
                WHERE id = ?
                LIMIT 1
                """.trimIndent(),
                args
            ).use { cursor ->
                cursor.moveToFirst() && !cursor.isNull(0)
            }
            if (!isFinished) {
                return false
            }

            db.delete(
                "tracking_samples",
                "session_id = ?",
                args
            )
            val deletedSessions = db.delete(
                "tracking_sessions",
                "id = ? AND ended_at IS NOT NULL",
                args
            )
            if (deletedSessions != 1) {
                return false
            }

            deleteOrphanedRaceContexts(db)
            deleteOrphanedAccessContexts(db)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
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
                samples.measurements_json,
                samples.race_context_id,
                race_contexts.resolved_event_name,
                race_contexts.course_json,
                race_contexts.course_map_viewport_json,
                samples.cog_valid
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
                        measurementsJson = if (cursor.isNull(8)) null else cursor.getString(8),
                        raceContextId = if (cursor.isNull(9)) null else cursor.getLong(9),
                        resolvedEventName = if (cursor.isNull(10)) null else cursor.getString(10),
                        courseJson = if (cursor.isNull(11)) null else cursor.getString(11),
                        courseMapViewportJson = if (cursor.isNull(12)) null else cursor.getString(12),
                        cogValid = if (cursor.isNull(13)) null else cursor.getInt(13) != 0
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
                samples.cog_valid,
                samples.sog,
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
                        cogValid = if (cursor.isNull(13)) null else cursor.getInt(13) != 0,
                        sog = cursor.getFloat(14),
                        batteryPercent = if (cursor.isNull(15)) null else cursor.getInt(15),
                        batteryCharging = if (cursor.isNull(16)) null else cursor.getInt(16) != 0,
                        trackingProfile = if (cursor.isNull(17)) null else cursor.getString(17),
                        utcOffsetMinutes = if (cursor.isNull(18)) null else cursor.getInt(18),
                        measurementsJson = if (cursor.isNull(19)) null else cursor.getString(19)
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
        cogValid: Boolean? = null,
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
            if (cogValid != null) {
                put("cog_valid", if (cogValid) 1 else 0)
            } else {
                putNull("cog_valid")
            }

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

    fun getStorageCounts(): TrackingStorageCounts {
        readableDatabase.rawQuery(
            """
            SELECT
                (SELECT COUNT(*) FROM tracking_samples) AS total,
                (
                    SELECT COUNT(*)
                    FROM tracking_samples
                    WHERE uploaded = 0
                ) AS pending
            """.trimIndent(),
            null
        ).use { cursor ->
            cursor.moveToFirst()
            return TrackingStorageCounts(
                total = cursor.getLong(0),
                pending = cursor.getLong(1)
            )
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

    fun exportAllAsCsv(): String = buildString {
        exportAllAsCsv(this)
    }

    fun exportAllAsCsv(output: Appendable) {
        val db = readableDatabase
        val snapshotMaxId = csvExportSnapshotMaxId(db)
        val measurementKeys = discoverCsvMeasurementKeys(
            db = db,
            snapshotMaxId = snapshotMaxId
        )

        val baseHeaderColumns = listOf(
            "sequence_id",
            "timestamp",
            "utc_offset_minutes",
            "boat_name",
            "captain_name",
            "hull_color",
            "sail_number",
            "yardstick",
            "boat_type",
            "lat",
            "lon",
            "accuracy",
            "cog",
            "sog"
        )
        val measurementColumns = buildCsvMeasurementColumns(
            measurementKeys = measurementKeys,
            reservedHeaders = baseHeaderColumns.toSet()
        )
        val headerColumns =
            baseHeaderColumns + measurementColumns.map { it.second }

        output.append(headerColumns.joinToString(","))
        output.append('\n')

        db.rawQuery(
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
                measurements_json
            FROM tracking_samples
            WHERE id <= ?
            ORDER BY id ASC
            """.trimIndent(),
            arrayOf(snapshotMaxId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val measurements = parseCsvMeasurements(
                    if (cursor.isNull(14)) null else cursor.getString(14)
                )
                val row = mutableListOf(
                    cursor.getLong(0).toString(),
                    csvEscape(cursor.getString(1)),
                    csvNullableInt(cursor, 2),
                    csvEscape(cursor.getString(3)),
                    csvEscape(cursor.getString(4)),
                    csvEscape(cursor.getString(5)),
                    csvEscape(cursor.getString(6)),
                    String.format(Locale.US, "%.2f", cursor.getDouble(7)),
                    csvEscape(cursor.getString(8)),
                    String.format(Locale.US, "%.7f", cursor.getDouble(9)),
                    String.format(Locale.US, "%.7f", cursor.getDouble(10)),
                    String.format(Locale.US, "%.2f", cursor.getDouble(11)),
                    String.format(Locale.US, "%.2f", cursor.getDouble(12)),
                    String.format(Locale.US, "%.2f", cursor.getDouble(13))
                )

                measurementColumns.forEach { (key, _) ->
                    row += csvMeasurementValue(measurements, key)
                }

                output.append(row.joinToString(","))
                output.append('\n')
            }
        }
    }

    private fun csvExportSnapshotMaxId(db: SQLiteDatabase): Long {
        db.rawQuery(
            "SELECT COALESCE(MAX(id), 0) FROM tracking_samples",
            null
        ).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    private fun discoverCsvMeasurementKeys(
        db: SQLiteDatabase,
        snapshotMaxId: Long
    ): List<String> {
        val keys = mutableSetOf<String>()
        db.rawQuery(
            """
            SELECT measurements_json
            FROM tracking_samples
            WHERE id <= ?
              AND measurements_json IS NOT NULL
            ORDER BY id ASC
            """.trimIndent(),
            arrayOf(snapshotMaxId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val measurements = parseCsvMeasurements(cursor.getString(0)) ?: continue
                val iterator = measurements.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    val measurement = measurements.optJSONObject(key) ?: continue
                    if (!measurement.has("value")) continue
                    keys += key
                }
            }
        }
        return keys.toList()
    }

    private fun buildCsvMeasurementColumns(
        measurementKeys: List<String>,
        reservedHeaders: Set<String>
    ): List<Pair<String, String>> {
        val usedHeaders = reservedHeaders.toMutableSet()
        return measurementKeys
            .sortedWith(
                compareBy<String> { csvMeasurementColumnName(it) }
                    .thenBy { it }
            )
            .map { key ->
                val baseName = csvMeasurementColumnName(key)
                var header = baseName
                var suffix = 2
                while (!usedHeaders.add(header)) {
                    header = "${baseName}_${suffix++}"
                }
                key to header
            }
    }

    private fun parseCsvMeasurements(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun csvMeasurementColumnName(key: String): String {
        val visibleKey = when {
            key.startsWith("nmea.") ->
                "boat_data." + key.removePrefix("nmea.")
            key.startsWith("regattalink.motion.") ->
                "imu." + key.removePrefix("regattalink.motion.")
            else -> key
        }
        return visibleKey.replace('.', '_')
    }

    private fun csvMeasurementValue(
        measurements: JSONObject?,
        key: String
    ): String {
        val measurement = measurements?.optJSONObject(key) ?: return ""
        if (!measurement.has("value")) return ""
        val value = measurement.opt("value")
        if (value == null || value === JSONObject.NULL) return ""

        return when (value) {
            is Number -> value.toString()
            is Boolean -> if (value) "1" else "0"
            else -> csvEscape(value.toString())
        }
    }

    private fun csvNullableInt(
        cursor: android.database.Cursor,
        index: Int
    ): String = if (cursor.isNull(index)) "" else cursor.getInt(index).toString()

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

    private fun migrateToVersion12(db: SQLiteDatabase) {
        if (!tableExists(db, "tracking_samples")) {
            createTrackingSamplesTable(db)
            createTrackingSampleIndexes(db)
            return
        }

        db.execSQL("DROP TABLE IF EXISTS tracking_samples_v12")
        createTrackingSamplesTable(db, "tracking_samples_v12")

        db.execSQL(
            """
            INSERT INTO tracking_samples_v12 (
                id,
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
                battery_percent,
                battery_charging,
                tracking_profile,
                utc_offset_minutes,
                measurements_json,
                session_id,
                race_context_id
            )
            SELECT
                id,
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
                battery_percent,
                battery_charging,
                tracking_profile,
                utc_offset_minutes,
                measurements_json,
                session_id,
                race_context_id
            FROM tracking_samples
            """.trimIndent()
        )

        db.execSQL("DROP TABLE tracking_samples")
        db.execSQL("ALTER TABLE tracking_samples_v12 RENAME TO tracking_samples")
        createTrackingSampleIndexes(db)
    }

    private fun migrateToVersion13(db: SQLiteDatabase) {
        if (!tableExists(db, "tracking_samples")) {
            createTrackingSamplesTable(db)
            return
        }
        if (!columnExists(db, "tracking_samples", "cog_valid")) {
            db.execSQL("ALTER TABLE tracking_samples ADD COLUMN cog_valid INTEGER")
        }
    }

    private fun migrateToVersion14(db: SQLiteDatabase) {
        if (!tableExists(db, "race_contexts")) {
            createRaceContextsTable(db)
            return
        }
        if (columnExists(db, "race_contexts", "context_key")) {
            return
        }

        db.execSQL("DROP TABLE IF EXISTS race_contexts_v14")
        createRaceContextsTable(db, tableName = "race_contexts_v14")
        db.execSQL(
            """
            INSERT INTO race_contexts_v14 (
                id,
                access_context_id,
                resolved_event_name,
                course_json,
                course_map_viewport_json,
                context_key
            )
            SELECT
                id,
                access_context_id,
                resolved_event_name,
                course_json,
                course_map_viewport_json,
                'legacy:' || id
            FROM race_contexts
            """.trimIndent()
        )
        db.execSQL("DROP TABLE race_contexts")
        db.execSQL("ALTER TABLE race_contexts_v14 RENAME TO race_contexts")
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
                    course_map_viewport_json,
                    context_key
                )
                SELECT
                    access_context_id,
                    resolved_event_name,
                    course_json,
                    course_map_viewport_json,
                    'migrated-session:' || id
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

    private fun createRaceContextsTable(
        db: SQLiteDatabase,
        tableName: String = "race_contexts"
    ) {
        require(tableName == "race_contexts" || tableName == "race_contexts_v14")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $tableName (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                access_context_id INTEGER NOT NULL,
                resolved_event_name TEXT NOT NULL,
                course_json TEXT,
                course_map_viewport_json TEXT,
                context_key TEXT NOT NULL,
                UNIQUE(access_context_id, resolved_event_name, context_key)
            )
            """.trimIndent()
        )
    }

    private fun createTrackingSamplesTable(
        db: SQLiteDatabase,
        tableName: String = "tracking_samples"
    ) {
        require(tableName == "tracking_samples" || tableName == "tracking_samples_v12")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $tableName (
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
                cog_valid INTEGER,
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

    private fun deleteOrphanedRaceContexts(db: SQLiteDatabase) {
        db.execSQL(
            """
            DELETE FROM race_contexts
            WHERE NOT EXISTS (
                SELECT 1
                FROM tracking_samples
                WHERE tracking_samples.race_context_id = race_contexts.id
            )
              AND NOT EXISTS (
                SELECT 1
                FROM tracking_sessions
                WHERE tracking_sessions.access_context_id = race_contexts.access_context_id
                  AND tracking_sessions.resolved_event_name = race_contexts.resolved_event_name
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
              AND NOT EXISTS (
                SELECT 1
                FROM tracking_sessions
                WHERE tracking_sessions.access_context_id = access_contexts.id
            )
              AND NOT EXISTS (
                SELECT 1
                FROM race_contexts
                WHERE race_contexts.access_context_id = access_contexts.id
            )
            """.trimIndent()
        )
    }

    private fun findRaceContextId(
        db: SQLiteDatabase,
        accessContextId: Long,
        resolvedEventName: String,
        contextKey: String
    ): Long? {
        db.rawQuery(
            """
            SELECT id
            FROM race_contexts
            WHERE access_context_id = ?
              AND resolved_event_name = ?
              AND context_key = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(accessContextId.toString(), resolvedEventName, contextKey)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun findLatestRaceContextId(
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
            ORDER BY id DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(accessContextId.toString(), resolvedEventName)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun raceContextVersionKey(
        courseJson: String?,
        courseMapViewportJson: String?
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(
                buildString {
                    append(courseJson.orEmpty())
                    append('\u0000')
                    append(courseMapViewportJson.orEmpty())
                }.toByteArray(Charsets.UTF_8)
            )

        val hex = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
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
