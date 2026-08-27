package de.williserv.regattaclient

import java.time.Instant

internal object RaceRegistrationPolicy {
    private const val SERIES_PRESTART_MINUTES = 30L

    fun registrationTimestamp(startEpochMillis: Long?): String? {
        val start = startEpochMillis ?: return null
        val registrationMillis = start - SERIES_PRESTART_MINUTES * 60_000L
        return Instant.ofEpochMilli(registrationMillis).toString()
    }
}
