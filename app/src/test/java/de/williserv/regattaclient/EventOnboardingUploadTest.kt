package de.williserv.regattaclient

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventOnboardingUploadTest {
    private lateinit var context: Context
    private lateinit var db: TrackingDbHelper

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("regatta_tracking.db")
        db = TrackingDbHelper(context)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("regatta_tracking.db")
    }

    private fun sample(
        accessId: Long?,
        raceId: Long?,
        sessionId: Long?,
        sequence: Long
    ): Long = db.insertSample(
        sequenceId = sequence,
        timestamp = "2026-10-09T12:00:00Z",
        boatName = "Boat",
        captainName = "Skipper",
        hullColor = "White",
        sailNumber = "123",
        yardstick = 100.0,
        boatType = "Test",
        lat = 54.0,
        lon = 10.0,
        accuracy = 2.0f,
        cog = 0f,
        sog = 1f,
        accessContextId = accessId,
        raceContextId = raceId,
        sessionId = sessionId
    )

    private fun acknowledged(event: String = "Race 1", secret: String = "secret") =
        db.hasConfirmedTrackingUploadForEvent(
            serverUrl = "https://example.test/",
            eventName = "Event A",
            secret = secret,
            resolvedEventName = event
        )

    @Test
    fun onlyAcknowledgedRealSessionSampleOfThisRaceCounts() {
        val accessId = requireNotNull(
            db.getOrCreateAccessContext("https://example.test", "Event A", "secret")
        )
        val raceId = requireNotNull(
            db.getOrCreateRaceContext(accessId, "Race 1", null, null)
        )
        val sessionId = requireNotNull(
            db.createTrackingSession(
                startedAt = 1234L,
                mode = "race",
                accessContextId = accessId,
                displayName = "Event A",
                resolvedEventName = "Race 1"
            )
        )
        assertFalse(acknowledged())

        // Enter Race inserts a row without a tracking session; even
        // uploading it must not finish the onboarding upload check.
        val raceEntry = sample(accessId, raceId, null, 1L)
        db.markUploaded(raceEntry)
        assertFalse(acknowledged())

        // A queued or rejected tracking sample is still not server-ACKed.
        val liveSample = sample(accessId, raceId, sessionId, 2L)
        assertFalse(acknowledged())

        db.markUploaded(liveSample)
        assertTrue(acknowledged())
        assertFalse(acknowledged(secret = "wrong"))
        assertFalse(acknowledged(event = "Race 2"))
    }

    @Test
    fun olderAcknowledgedRaceSessionWithoutRaceContextStillCounts() {
        val accessId = requireNotNull(
            db.getOrCreateAccessContext("https://example.test", "Event A", "secret")
        )
        val sessionId = requireNotNull(
            db.createTrackingSession(100L, "race", accessId, "Event A", "Race 1")
        )
        db.markUploaded(sample(accessId, null, sessionId, 10L))
        assertTrue(acknowledged(event = "Race 1"))
        assertFalse(acknowledged(event = "Race 2"))
    }

    @Test
    fun uploadFromAnotherRaceDoesNotCompleteNewRaceAndPersistsAfterReopen() {
        val accessId = requireNotNull(
            db.getOrCreateAccessContext("https://example.test", "Event A", "secret")
        )
        val oldRaceId = requireNotNull(
            db.getOrCreateRaceContext(accessId, "Race 1", null, null)
        )
        val sessionId = requireNotNull(
            db.createTrackingSession(100L, "race", accessId, "Event A", "Race 1")
        )
        db.markUploaded(sample(accessId, oldRaceId, sessionId, 7L))
        assertTrue(acknowledged())
        assertFalse(acknowledged(event = "Race 2"))

        db.close()
        db = TrackingDbHelper(context)
        assertTrue(acknowledged())
        assertFalse(acknowledged(event = "Race 2"))
    }
}
