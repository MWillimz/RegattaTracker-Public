package de.williserv.regattaclient

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RaceRegistrationPolicyTest {

    @Test
    fun registrationTimestampUsesThirtyMinutePrestartWindowInRaceWallClockTime() {
        val start = LocalDateTime.parse("2026-08-28T15:00:00")
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        assertEquals(
            "2026-08-28T14:30:00",
            RaceRegistrationPolicy.registrationTimestamp(start)
        )
    }

    @Test
    fun registrationTimestampRequiresResolvedStartTime() {
        assertNull(RaceRegistrationPolicy.registrationTimestamp(null))
    }
}
