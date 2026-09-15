package de.williserv.regattaclient

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

internal object RaceRegistrationPolicy {
    private const val SERIES_PRESTART_MINUTES = 30L

    private val localOutputFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private val offsetOutputFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    private val localInputFormatters = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    )

    fun registrationTimestamp(serverStartTime: String?): String? {
        val start = serverStartTime
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "--" }
            ?: return null

        parseOffsetDateTime(start)?.let { parsed ->
            return parsed
                .minusMinutes(SERIES_PRESTART_MINUTES)
                .format(offsetOutputFormatter)
        }

        parseLocalDateTime(start)?.let { parsed ->
            return parsed
                .minusMinutes(SERIES_PRESTART_MINUTES)
                .format(localOutputFormatter)
        }

        return null
    }

    private fun parseOffsetDateTime(value: String): OffsetDateTime? {
        return try {
            OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLocalDateTime(value: String): LocalDateTime? {
        for (formatter in localInputFormatters) {
            try {
                return LocalDateTime.parse(value, formatter)
            } catch (_: Exception) {
                // Try the next supported server-time representation.
            }
        }

        return null
    }
}
