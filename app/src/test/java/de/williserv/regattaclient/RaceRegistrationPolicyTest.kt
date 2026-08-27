package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RaceRegistrationPolicyTest {

    @Test
    fun registrationTimestampUsesThirtyMinutePrestartWindowForNaiveServerTime() {
        assertEquals(
            "2026-08-28T14:30:00",
            RaceRegistrationPolicy.registrationTimestamp("2026-08-28T15:00:00")
        )
    }

    @Test
    fun registrationTimestampKeepsNaiveServerTimeNaive() {
        assertEquals(
            "2026-08-28T14:30:00",
            RaceRegistrationPolicy.registrationTimestamp("2026-08-28 15:00")
        )
    }

    @Test
    fun registrationTimestampPreservesExplicitOffsetSemantics() {
        assertEquals(
            "2026-08-28T14:30+02:00",
            RaceRegistrationPolicy.registrationTimestamp("2026-08-28T15:00+02:00")
        )
    }

    @Test
    fun registrationTimestampPreservesUtcSemantics() {
        assertEquals(
            "2026-08-28T12:30:00Z",
            RaceRegistrationPolicy.registrationTimestamp("2026-08-28T13:00:00Z")
        )
    }

    @Test
    fun registrationTimestampRequiresResolvedStartTime() {
        assertNull(RaceRegistrationPolicy.registrationTimestamp(null))
        assertNull(RaceRegistrationPolicy.registrationTimestamp("--"))
        assertNull(RaceRegistrationPolicy.registrationTimestamp("invalid"))
    }
}
