package de.williserv.regattaclient

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RaceRegistrationPolicyTest {

    @Test
    fun naiveIsoTimeKeepsRaceWallClockSemantics() {
        assertEquals(
            "2026-08-28T14:30:00",
            RaceRegistrationPolicy.registrationTimestamp("2026-08-28T15:00:00")
        )
    }

    @Test
    fun naiveSpaceSeparatedTimesAreSupported() {
        assertEquals(
            "2026-08-28T14:30:00",
            RaceRegistrationPolicy.registrationTimestamp("2026-08-28 15:00:00")
        )
        assertEquals(
            "2026-08-28T14:30:00",
            RaceRegistrationPolicy.registrationTimestamp("2026-08-28 15:00")
        )
    }

    @Test
    fun utcTimeKeepsUtcRepresentation() {
        assertEquals(
            "2026-08-28T14:30:00Z",
            RaceRegistrationPolicy.registrationTimestamp("2026-08-28T15:00:00Z")
        )
    }

    @Test
    fun explicitOffsetIsPreserved() {
        assertEquals(
            "2026-08-28T14:30:00+02:00",
            RaceRegistrationPolicy.registrationTimestamp("2026-08-28T15:00:00+02:00")
        )
    }

    @Test
    fun resultDoesNotDependOnDeviceTimezone() {
        val originalTimezone = TimeZone.getDefault()

        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(
                "2026-08-28T14:30:00+02:00",
                RaceRegistrationPolicy.registrationTimestamp("2026-08-28T15:00:00+02:00")
            )
            assertEquals(
                "2026-08-28T14:30:00",
                RaceRegistrationPolicy.registrationTimestamp("2026-08-28T15:00:00")
            )

            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(
                "2026-08-28T14:30:00+02:00",
                RaceRegistrationPolicy.registrationTimestamp("2026-08-28T15:00:00+02:00")
            )
            assertEquals(
                "2026-08-28T14:30:00",
                RaceRegistrationPolicy.registrationTimestamp("2026-08-28T15:00:00")
            )
        } finally {
            TimeZone.setDefault(originalTimezone)
        }
    }

    @Test
    fun registrationTimestampHandlesDateBoundary() {
        assertEquals(
            "2026-08-27T23:45:00+02:00",
            RaceRegistrationPolicy.registrationTimestamp("2026-08-28T00:15:00+02:00")
        )
    }

    @Test
    fun registrationTimestampRequiresValidServerStartTime() {
        assertNull(RaceRegistrationPolicy.registrationTimestamp(null))
        assertNull(RaceRegistrationPolicy.registrationTimestamp(""))
        assertNull(RaceRegistrationPolicy.registrationTimestamp("--"))
        assertNull(RaceRegistrationPolicy.registrationTimestamp("not-a-time"))
    }

    @Test
    fun startEpochMillisPreservesExplicitOffset() {
        val expected = OffsetDateTime
            .parse("2026-08-28T15:00:00+02:00")
            .toInstant()
            .toEpochMilli()

        assertEquals(
            expected,
            RaceRegistrationPolicy.startEpochMillis(
                "2026-08-28T15:00:00+02:00",
                ZoneId.of("UTC")
            )
        )
    }

    @Test
    fun startEpochMillisUsesProvidedZoneForNaiveTime() {
        val raceZone = ZoneId.of("Europe/Berlin")
        val expected = LocalDateTime
            .parse("2026-08-28T15:00:00")
            .atZone(raceZone)
            .toInstant()
            .toEpochMilli()

        assertEquals(
            expected,
            RaceRegistrationPolicy.startEpochMillis(
                "2026-08-28T15:00:00",
                raceZone
            )
        )
    }

    @Test
    fun startEpochMillisRejectsInvalidStartTime() {
        assertNull(RaceRegistrationPolicy.startEpochMillis(null))
        assertNull(RaceRegistrationPolicy.startEpochMillis("--"))
        assertNull(RaceRegistrationPolicy.startEpochMillis("not-a-time"))
    }
}
