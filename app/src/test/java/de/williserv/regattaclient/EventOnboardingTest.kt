package de.williserv.regattaclient

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventOnboardingTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val start = 1_800_000_000_000L

    private fun state(
        now: Long = start - 96 * 60 * 60_000L,
        boat: Boolean = false,
        registered: Boolean = false,
        entered: Boolean = false,
        uploaded: Boolean = false,
        event: Boolean = true,
        canEnter: Boolean = true,
        knownStart: Long? = start
    ) = eventOnboardingState(
        boatSetupConfirmed = boat,
        eventKnown = event,
        registered = registered,
        enteredRace = entered,
        trackingUploadConfirmed = uploaded,
        raceStartEpochMillis = knownStart,
        canEnterRace = canEnter,
        nowEpochMillis = now,
        zoneId = zone
    )

    @Test
    fun completedStepsNeverDependOnPageVisitsOrQueueState() {
        val pending = state(boat = true, registered = true, entered = true)
        assertEquals(3, pending.completedCount)
        assertEquals(OnboardingStatus.FUTURE, pending.statuses[3])
        assertFalse(pending.complete)
        assertTrue(state(
            boat = true, registered = true, entered = true, uploaded = true
        ).complete)
    }

    @Test
    fun laterStepsStayFutureAndCurrentStepBecomesOrangeOnlyAtThreshold() {
        assertEquals(OnboardingStatus.FUTURE, state().statuses[1])
        assertEquals(OnboardingStatus.CURRENT, state(boat = true).statuses[1])
        assertEquals(
            OnboardingStatus.URGENT,
            state(now = start - 72 * 60 * 60_000L, boat = true).statuses[1]
        )
        assertEquals(
            OnboardingStatus.CURRENT,
            state(
                now = start - 60 * 60_000L - 1L,
                boat = true, registered = true
            ).statuses[2]
        )
        assertEquals(
            OnboardingStatus.URGENT,
            state(
                now = start - 60 * 60_000L,
                boat = true, registered = true
            ).statuses[2]
        )
        assertEquals(
            OnboardingStatus.CURRENT,
            state(
                now = start - 60 * 60_000L,
                boat = true, registered = true, canEnter = false
            ).statuses[2]
        )
    }

    @Test
    fun trackingUploadNeverWarnsBeforeStartAndIsFutureBeforeTenMinutes() {
        val before = state(
            now = start - 10 * 60_000L - 1L,
            boat = true, registered = true, entered = true
        )
        assertEquals(OnboardingStatus.FUTURE, before.statuses[3])
        assertEquals(
            OnboardingStatus.CURRENT,
            state(
                now = start - 10 * 60_000L,
                boat = true, registered = true, entered = true
            ).statuses[3]
        )
        assertEquals(
            OnboardingStatus.URGENT,
            state(
                now = start,
                boat = true, registered = true, entered = true
            ).statuses[3]
        )
    }

    @Test
    fun noOfficialStartDoesNotInventUrgency() {
        assertEquals(
            OnboardingStatus.CURRENT,
            state(boat = true, knownStart = null).statuses[1]
        )
        assertEquals(
            OnboardingStatus.FUTURE,
            state(boat = true, registered = true, entered = true, knownStart = null)
                .statuses[3]
        )
        assertEquals(
            OnboardingStatus.CURRENT,
            state(boat = true, event = false).statuses[1]
        )
    }

    @Test
    fun eventIdentityChangesWithEventCredentialsOrResolvedRace() {
        val a = eventOnboardingKey("https://example.test/", "Event A", "s1", "Race 1")
        assertNotNull(a)
        assertEquals(
            a,
            eventOnboardingKey("https://example.test", "Event A", "s1", "Race 1")
        )
        assertNotEquals(
            a, eventOnboardingKey("https://example.test", "Event B", "s1", "Race 1")
        )
        assertNotEquals(
            a, eventOnboardingKey("https://example.test", "Event A", "s2", "Race 1")
        )
        assertNotEquals(
            a, eventOnboardingKey("https://example.test", "Event A", "s1", "Race 2")
        )
        assertFalse(requireNotNull(a).contains("s1"))
    }

    @Test
    fun completionTransitionIsOnlyNewlyFinishedSteps() {
        assertEquals(0b0100, eventOnboardingNewlyCompleted(0b0011, 0b0111))
        assertEquals(0, eventOnboardingNewlyCompleted(0b0111, 0b0111))
        assertEquals(0, eventOnboardingNewlyCompleted(0b0111, 0b0011))
        assertEquals(0b1010, eventOnboardingNewlyCompleted(0b0101, 0b1111))
    }
}
