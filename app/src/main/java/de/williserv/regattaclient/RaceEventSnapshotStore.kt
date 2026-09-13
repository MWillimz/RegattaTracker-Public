package de.williserv.regattaclient

import android.content.Context
import org.json.JSONObject

internal data class RaceEventSnapshot(
    val resolvedEventName: String,
    val status: String,
    val startRaw: String,
    val stopRaw: String,
    val raceInfo: String,
    val courseJson: String,
    val courseShortened: Boolean
)

internal fun canEnterRaceWithLocalState(
    raceDataReady: Boolean,
    setupConfirmed: Boolean
): Boolean = raceDataReady && setupConfirmed

internal fun shouldInvalidateEventSnapshotForHttpStatus(responseCode: Int): Boolean =
    responseCode == 401 || responseCode == 403 || responseCode == 404

internal fun shouldPreserveEventSnapshotForHttpStatus(responseCode: Int): Boolean =
    !shouldInvalidateEventSnapshotForHttpStatus(responseCode)

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
    val course = obj.optJSONObject("course")
        ?: obj.optJSONObject("kurs")
        ?: obj.optJSONObject("race_course")
        ?: obj.optJSONObject("track")

    return RaceEventSnapshot(
        resolvedEventName = resolvedEventName,
        status = status,
        startRaw = start,
        stopRaw = stop,
        raceInfo = raceInfo,
        courseJson = course?.toString().orEmpty(),
        courseShortened = obj.optBoolean("course_shortened", false)
    )
}

internal object RaceEventSnapshotStore {
    private const val PREFS_NAME = "race_setup"

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

        return RaceEventSnapshot(
            resolvedEventName = savedResolved,
            status = prefs.getString("race_status_raw", "").orEmpty(),
            startRaw = prefs.getString("race_start_raw", "").orEmpty(),
            stopRaw = prefs.getString("race_stop_raw", "").orEmpty(),
            raceInfo = prefs.getString("race_info_raw", "").orEmpty(),
            courseJson = prefs.getString("race_course_json_raw", "").orEmpty(),
            courseShortened = prefs.getBoolean("race_course_shortened_raw", false)
        )
    }

    fun save(
        context: Context,
        server: String,
        event: String,
        secret: String,
        snapshot: RaceEventSnapshot
    ) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("race_server", server)
            .putString("race_event", event)
            .putString("race_secret", secret)
            .putString("resolved_event_name", snapshot.resolvedEventName)
            .putInt("race_raw_state_version", RACE_RAW_STATE_VERSION)
            .putString("race_status_raw", snapshot.status)
            .putString("race_start_raw", snapshot.startRaw)
            .putString("race_stop_raw", snapshot.stopRaw)
            .putString("race_info_raw", snapshot.raceInfo)
            .putString("race_course_json_raw", snapshot.courseJson)
            .putBoolean("race_course_shortened_raw", snapshot.courseShortened)
            .putBoolean("race_data_ready", true)
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
