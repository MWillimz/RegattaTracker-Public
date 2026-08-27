package de.williserv.regattaclient

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object RaceRegistrationPolicy {
    private const val SERIES_PRESTART_MINUTES = 30L
    private val outputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun registrationTimestamp(startEpochMillis: Long?): String? {
        val start = startEpochMillis ?: return null

        // Race start values without an explicit offset are interpreted elsewhere
        // in the device zone. Convert through that same zone so the synthetic
        // point stays in the server-provided race wall-clock window instead of
        // accidentally changing it to UTC.
        return Instant.ofEpochMilli(start)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .minusMinutes(SERIES_PRESTART_MINUTES)
            .format(outputFormatter)
    }
}
