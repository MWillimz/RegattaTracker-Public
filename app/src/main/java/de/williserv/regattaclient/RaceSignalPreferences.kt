package de.williserv.regattaclient

import android.content.Context
import android.content.SharedPreferences

internal object RaceSignalPreferences {
    const val RACE_PREFS_NAME = "race_setup"
    const val LOCAL_STATUS_PREFS_NAME = "regatta_local_status"

    const val KEY_ENABLED = "race_acoustic_signals_enabled"
    const val KEY_ENABLED_EVENT = "race_acoustic_signals_event_key"

    private const val KEY_SERVER = "race_server"
    private const val KEY_EVENT = "race_event"
    private const val KEY_SECRET = "race_secret"

    fun isEnabledForEvent(
        context: Context,
        server: String,
        event: String,
        secret: String
    ): Boolean {
        val prefs = context.getSharedPreferences(RACE_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, false) &&
            prefs.getString(KEY_ENABLED_EVENT, null) == eventKey(server, event, secret)
    }

    fun setEnabledForEvent(
        context: Context,
        server: String,
        event: String,
        secret: String,
        enabled: Boolean
    ) {
        val prefs = context.getSharedPreferences(RACE_PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit().putBoolean(KEY_ENABLED, enabled)

        if (enabled) {
            editor.putString(KEY_ENABLED_EVENT, eventKey(server, event, secret))
        } else {
            editor.remove(KEY_ENABLED_EVENT)
        }

        editor.apply()
    }

    fun isEnabledForCurrentRace(prefs: SharedPreferences): Boolean {
        if (!prefs.getBoolean(KEY_ENABLED, false)) return false

        val currentKey = currentEventKey(prefs) ?: return false
        return prefs.getString(KEY_ENABLED_EVENT, null) == currentKey
    }

    fun currentEventKey(prefs: SharedPreferences): String? {
        val server = prefs.getString(KEY_SERVER, "").orEmpty()
        val event = prefs.getString(KEY_EVENT, "").orEmpty()
        val secret = prefs.getString(KEY_SECRET, "").orEmpty()

        if (server.isBlank() || event.isBlank() || secret.isBlank()) return null
        return eventKey(server, event, secret)
    }

    private fun eventKey(server: String, event: String, secret: String): String {
        return listOf(
            server.trim().trimEnd('/'),
            event.trim(),
            secret.trim()
        ).joinToString("\n")
    }
}
