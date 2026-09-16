package de.williserv.regattaclient

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetrySampleTimeTest {

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    @Test
    fun `positive offset is captured with unchanged local timestamp`() {
        val sampleTime = telemetrySampleTime(
            OffsetDateTime.parse("2026-09-06T14:23:17+02:00"),
            formatter
        )

        assertEquals("2026-09-06T14:23:17", sampleTime.timestamp)
        assertEquals(120, sampleTime.utcOffsetMinutes)
    }

    @Test
    fun `utc offset is captured as zero`() {
        val sampleTime = telemetrySampleTime(
            OffsetDateTime.parse("2026-09-06T12:23:17Z"),
            formatter
        )

        assertEquals("2026-09-06T12:23:17", sampleTime.timestamp)
        assertEquals(0, sampleTime.utcOffsetMinutes)
    }

    @Test
    fun `negative offset is captured in minutes`() {
        val sampleTime = telemetrySampleTime(
            OffsetDateTime.parse("2026-09-06T07:23:17-05:00"),
            formatter
        )

        assertEquals("2026-09-06T07:23:17", sampleTime.timestamp)
        assertEquals(-300, sampleTime.utcOffsetMinutes)
    }

    @Test
    fun `timestamp and offset are derived from the same supplied snapshot`() {
        val beforeFallback = telemetrySampleTime(
            OffsetDateTime.parse("2026-10-25T02:59:00+02:00"),
            formatter
        )
        val afterFallback = telemetrySampleTime(
            OffsetDateTime.parse("2026-10-25T02:00:00+01:00"),
            formatter
        )

        assertEquals("2026-10-25T02:59:00", beforeFallback.timestamp)
        assertEquals(120, beforeFallback.utcOffsetMinutes)
        assertEquals("2026-10-25T02:00:00", afterFallback.timestamp)
        assertEquals(60, afterFallback.utcOffsetMinutes)
    }
}
