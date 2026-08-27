package de.williserv.regattaclient

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

internal object RaceRegistrationPolicy {
    private const val SERIES_PRESTART_MINUTES = 30L
    private val outputLocalFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private val localParsers = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    )

    fun registrationTimestamp(startTime: String?): String? {
        val value = startTime?.trim().orEmpty()
        if (value.isBlank() || value == "--") return null

        return try {
            when {
                value.endsWith("Z") ->
                    Instant.parse(value).minusSeconds(SERIES_PRESTART_MINUTES * 60L).toString()

                hasExplicitOffset(value) ->
                    OffsetDateTime.parse(value)
                        .minusMinutes(SERIES_PRESTART_MINUTES)
                        .toString()

                else -> parseLocalDateTime(value)
                    ?.minusMinutes(SERIES_PRESTART_MINUTES)
                    ?.format(outputLocalFormatter)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun hasExplicitOffset(value: String): Boolean {
        if (value.length <= 10) return false
        val timeAndZone = value.substring(10)
        return timeAndZone.contains('+') || timeAndZone.drop(1).contains('-')
    }

    private fun parseLocalDateTime(value: String): LocalDateTime? {
        for (formatter in localParsers) {
            try {
                return LocalDateTime.parse(value, formatter)
            } catch (_: Exception) {
                // Try the next supported server format.
            }
        }
        return null
    }
}
