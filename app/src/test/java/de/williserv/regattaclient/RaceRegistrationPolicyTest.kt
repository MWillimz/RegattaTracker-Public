package de.williserv.regattaclient

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RaceRegistrationPolicyTest {

    @Test
    fun registrationTimestampUsesThirtyMinutePrestartWindow() {
        val start = Instant.parse("2026-08-28T13:00:00Z").toEpochMilli()

        assertEquals(
            "2026-08-28T12:30:00Z",
            RaceRegistrationPolicy.registrationTimestamp(start)
        )
    }

    @Test
    fun registrationTimestampRequiresResolvedStartTime() {
        assertNull(RaceRegistrationPolicy.registrationTimestamp(null))
    }
}
