package de.williserv.regattaclient

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

internal data class RaceEventSnapshot(
    val resolvedEventName: String,
    val status: String,
    val startRaw: String,
    val stopRaw: String,
    val raceInfo: String,
    val courseJson: String,
    val courseShortened: Boolean,
    val seriesDisplayMetadata: SeriesDisplayMetadata = SeriesDisplayMetadata()
)

internal fun canEnterRaceWithLocalState(
    raceDataReady: Boolean,
    setupConfirmed: Boolean,
    cachedSeriesRunObsolete: Boolean = false
): Boolean = raceDataReady && setupConfirmed && !cachedSeriesRunObsolete

internal fun isCachedSeriesRunObsolete(
    isSeriesAccess: Boolean,
    status: String,
    stopEpochMillis: Long?,
    nowEpochMillis: Long
): Boolean {
    if (!isSeriesAccess) return false

    if (
        status.equals("finished", ignoreCase = true) ||
        status.equals("cancelled", ignoreCase = true)
    ) {
        return true
    }

    return stopEpochMillis != null && nowEpochMillis > stopEpochMillis
}

internal fun shouldInvalidateEventSnapshotForHttpStatus(responseCode: Int): Boolean =
    responseCode == 401 || responseCode == 403 || responseCode == 404

internal fun shouldPreserveEventSnapshotForHttpStatus(responseCode: Int): Boolean =
    !shouldInvalidateEventSnapshotForHttpStatus(responseCode)

internal fun isUsableRaceEventSnapshot(snapshot: RaceEventSnapshot): Boolean {
    if (snapshot.resolvedEventName.isBlank()) return false
    return RaceRegistrationPolicy.registrationTimestamp(snapshot.startRaw) != null
}

internal fun parseRaceEventSnapshot(body: String): RaceEventSnapshot {
    val obj = JSONObject(body)
    val resolvedEventName = obj.optString("event_name", "").trim()
    require(resolvedEventName.isNotBlank()) { "/event response is missing event_name" }

    val status = jsonStringAny(
        obj,
        listOf("race_status", "status", "state", "event_status")
    ) ?: "loaded"
    val start = jsonStringAny(
        obj,
        listOf("start_time", "startTime", "tracking_start", "trackingStart", "start")
    ) ?: "--"
    val stop = jsonStringAny(
        obj,
        listOf(
            "stop_time",
            "stopTime",
            "tracking_stop",
            "trackingStop",
            "end_time",
            "endTime",
            "stop"
        )
    ) ?: "--"
    val raceInfo = jsonStringAny(
        obj,
        listOf("race_info", "info", "notice", "message")
    ) ?: "--"
    val course = jsonObjectAnyStrict(
        obj,
        listOf("course", "kurs", "race_course", "track")
    )

    val snapshot = RaceEventSnapshot(
        resolvedEventName = resolvedEventName,
        status = status,
        startRaw = start,
        stopRaw = stop,
        raceInfo = raceInfo,
        courseJson = course?.toString().orEmpty(),
        courseShortened = obj.optBoolean("course_shortened", false),
        seriesDisplayMetadata = parseSeriesDisplayMetadata(obj)
    )

    require(isUsableRaceEventSnapshot(snapshot)) { "/event response is not usable for local tracking" }
    return snapshot
}

internal object RaceEventSnapshotStore {
    private const val PREFS_NAME = "race_setup"
    private const val GENERATION_KEY = "race_snapshot_generation"
    private val writeLock = Any()

    fun loadMatching(
        context: Context,
        server: String,
        event: String,
        secret: String,
        expectedResolvedEventName: String? = null
    ): RaceEventSnapshot? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("race_data_ready", false)) return null
        if (prefs.getInt("race_raw_state_version", 0) < RACE_RAW_STATE_VERSION) return null

        val savedServer = normalizeServer(prefs.getString("race_server", "").orEmpty())
        val savedEvent = prefs.getString("race_event", "").orEmpty().trim()
        val savedSecret = prefs.getString("race_secret", "").orEmpty().trim()
        val savedResolved = prefs.getString("resolved_event_name", "").orEmpty().trim()

        if (savedServer != normalizeServer(server)) return null
        if (savedEvent != event.trim()) return null
        if (savedSecret != secret.trim()) return null
        if (savedResolved.isBlank()) return null
        if (!expectedResolvedEventName.isNullOrBlank() && savedResolved != expectedResolvedEventName.trim()) {
            return null
        }

        val snapshot = RaceEventSnapshot(
            resolvedEventName = savedResolved,
            status = prefs.getString("race_status_raw", "").orEmpty(),
            startRaw = prefs.getString("race_start_raw", "").orEmpty(),
            stopRaw = prefs.getString("race_stop_raw", "").orEmpty(),
            raceInfo = prefs.getString("race_info_raw", "").orEmpty(),
            courseJson = prefs.getString("race_course_json_raw", "").orEmpty(),
            courseShortened = prefs.getBoolean("race_course_shortened_raw", false),
            seriesDisplayMetadata = SeriesDisplayMetadata(
                runName = prefs.getString("series_run_name", "").orEmpty(),
                occurrenceNo = prefs.getInt("series_occurrence_no", 0).takeIf { it > 0 },
                plannedRaceCount = prefs.getInt("series_planned_race_count", 0).takeIf { it > 0 }
            )
        )

        return snapshot.takeIf(::isUsableRaceEventSnapshot)
    }

    fun generation(context: Context): Long = synchronized(writeLock) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(GENERATION_KEY, 0L)
    }

    fun save(
        context: Context,
        server: String,
        event: String,
        secret: String,
        snapshot: RaceEventSnapshot
    ) {
        if (!isUsableRaceEventSnapshot(snapshot)) return

        synchronized(writeLock) {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val nextGeneration = prefs.getLong(GENERATION_KEY, 0L) + 1L
            writeSnapshot(
                prefs = prefs,
                server = server,
                event = event,
                secret = secret,
                snapshot = snapshot,
                generation = nextGeneration
            )
        }
    }

    fun saveIfGenerationUnchanged(
        context: Context,
        server: String,
        event: String,
        secret: String,
        snapshot: RaceEventSnapshot,
        expectedGeneration: Long
    ): Boolean {
        if (!isUsableRaceEventSnapshot(snapshot)) return false

        return synchronized(writeLock) {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getLong(GENERATION_KEY, 0L) != expectedGeneration) {
                return@synchronized false
            }

            writeSnapshot(
                prefs = prefs,
                server = server,
                event = event,
                secret = secret,
                snapshot = snapshot,
                generation = expectedGeneration + 1L
            )
            true
        }
    }

    private fun writeSnapshot(
        prefs: SharedPreferences,
        server: String,
        event: String,
        secret: String,
        snapshot: RaceEventSnapshot,
        generation: Long
    ) {
        prefs.edit()
            .putString("race_server", server)
            .putString("race_event", event)
            .putString("race_secret", secret)
            .putString("resolved_event_name", snapshot.resolvedEventName)
            .putString("series_run_name", snapshot.seriesDisplayMetadata.runName)
            .putInt("series_occurrence_no", snapshot.seriesDisplayMetadata.occurrenceNo ?: 0)
            .putInt("series_planned_race_count", snapshot.seriesDisplayMetadata.plannedRaceCount ?: 0)
            .putInt("race_raw_state_version", RACE_RAW_STATE_VERSION)
            .putString("race_status_raw", snapshot.status)
            .putString("race_start_raw", snapshot.startRaw)
            .putString("race_stop_raw", snapshot.stopRaw)
            .putString("race_info_raw", snapshot.raceInfo)
            .putString("race_course_json_raw", snapshot.courseJson)
            .putBoolean("race_course_shortened_raw", snapshot.courseShortened)
            .putBoolean("race_data_ready", true)
            .putLong(GENERATION_KEY, generation)
            .apply()
    }

    private fun normalizeServer(server: String): String {
        val trimmed = server.trim().trimEnd('/')
        return if (trimmed.endsWith("/ingest")) trimmed.removeSuffix("/ingest") else trimmed
    }
}

private fun jsonStringAny(json: JSONObject, keys: List<String>): String? {
    for (key in keys) {
        if (json.has(key) && !json.isNull(key)) {
            return json.optString(key)
        }
    }
    return null
}

private fun jsonObjectAnyStrict(json: JSONObject, keys: List<String>): JSONObject? {
    for (key in keys) {
        if (!json.has(key) || json.isNull(key)) continue
        return json.optJSONObject(key)
            ?: throw IllegalArgumentException("/event field '$key' must be an object")
    }
    return null
}
