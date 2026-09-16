package de.williserv.regattaclient

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

internal data class TelemetrySampleTime(
    val timestamp: String,
    val utcOffsetMinutes: Int
)

internal fun telemetrySampleTime(
    now: OffsetDateTime,
    formatter: DateTimeFormatter
): TelemetrySampleTime {
    return TelemetrySampleTime(
        timestamp = now.toLocalDateTime().format(formatter),
        utcOffsetMinutes = now.offset.totalSeconds / 60
    )
}
