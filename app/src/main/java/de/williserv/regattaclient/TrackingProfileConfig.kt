package de.williserv.regattaclient

import android.content.Context

enum class TrackingProfile(val persistedValue: String) {
    NORMAL("normal"),
    BATTERY_SAVER("battery_saver");

    companion object {
        fun fromPersistedValue(value: String?): TrackingProfile {
            return entries.firstOrNull { it.persistedValue == value } ?: NORMAL
        }
    }
}

object TrackingProfileConfig {
    private const val PREFS_NAME = "tracking_config"
    private const val PROFILE_KEY = "tracking_profile"

    fun read(context: Context): TrackingProfile {
        val value = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PROFILE_KEY, TrackingProfile.NORMAL.persistedValue)

        return TrackingProfile.fromPersistedValue(value)
    }

    fun write(context: Context, profile: TrackingProfile) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PROFILE_KEY, profile.persistedValue)
            .apply()
    }
}
