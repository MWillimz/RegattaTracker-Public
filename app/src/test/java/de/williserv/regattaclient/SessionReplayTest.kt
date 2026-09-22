package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SessionReplayTest {

    @Test
    fun sampleFractions_useElapsedTimeInsteadOfSampleIndex() {
        val samples = listOf(
            sample(id = 1, seconds = 0),
            sample(id = 2, seconds = 10),
            sample(id = 3, seconds = 100)
        )

        val fractions = replaySampleFractions(samples)

        assertEquals(0f, fractions[0], 0.0001f)
        assertEquals(0.1f, fractions[1], 0.0001f)
        assertEquals(1f, fractions[2], 0.0001f)
    }

    @Test
    fun sampleSelection_followsTimelinePosition() {
        val samples = listOf(
            sample(id = 1, seconds = 0),
            sample(id = 2, seconds = 90),
            sample(id = 3, seconds = 100)
        )
        val fractions = replaySampleFractions(samples)

        assertEquals(1, replaySampleIndexForFraction(fractions, 0.8f))
        assertEquals(2, replaySampleIndexForFraction(fractions, 0.98f))
    }

    @Test
    fun speedFraction_isRelativeToSessionMaximumAndClamped() {
        assertEquals(0f, replaySpeedFraction(0.0, 10.0), 0.0001f)
        assertEquals(0.5f, replaySpeedFraction(5.0, 10.0), 0.0001f)
        assertEquals(1f, replaySpeedFraction(20.0, 10.0), 0.0001f)
        assertEquals(0f, replaySpeedFraction(Double.NaN, 10.0), 0.0001f)
    }

    private fun sample(id: Long, seconds: Long): SessionTrackingSample {
        val timestamp = LocalDateTime.of(2026, 9, 22, 10, 0)
            .plusSeconds(seconds)
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        return SessionTrackingSample(
            localId = id,
            timestamp = timestamp,
            utcOffsetMinutes = 0,
            lat = 51.0 + id * 0.0001,
            lon = 12.0 + id * 0.0001,
            accuracy = 5f,
            cog = 90f,
            sog = 4f,
            accelX = 0f,
            accelY = 0f,
            accelZ = 0f,
            gyroX = 0f,
            gyroY = 0f,
            gyroZ = 0f
        )
    }
}
