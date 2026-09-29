package de.williserv.regattaclient

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionHistoryDbTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun summaries_includeRaceAndManualNewestFirst_withoutLegacyRows() {
        val helper = TrackingDbHelper(context)
        val accessContextId = requireNotNull(
            helper.getOrCreateAccessContext(
                serverUrl = "https://raceoffice.example.org",
                accessIdentifier = "Wednesday Race",
                accessSecret = "secret-value"
            )
        )

        val raceSessionId = requireNotNull(
            helper.createTrackingSession(
                startedAt = 1_000L,
                mode = "race",
                accessContextId = accessContextId,
                displayName = "Race session",
                resolvedEventName = "Wednesday Race - Run 2"
            )
        )
        val manualSessionId = requireNotNull(
            helper.createTrackingSession(
                startedAt = 2_000L,
                mode = "manual",
                accessContextId = null,
                displayName = "Manual session"
            )
        )

        insertSample(helper, sequenceId = 1L, sessionId = raceSessionId, accessContextId = accessContextId)
        insertSample(helper, sequenceId = 2L, sessionId = raceSessionId, accessContextId = accessContextId)
        insertSample(helper, sequenceId = 3L, sessionId = manualSessionId, accessContextId = null)
        insertSample(helper, sequenceId = 4L, sessionId = null, accessContextId = null)

        val summaries = helper.getTrackingSessionSummaries()

        assertEquals(2, summaries.size)
        assertEquals(manualSessionId, summaries[0].id)
        assertEquals("manual", summaries[0].mode)
        assertNull(summaries[0].eventIdentifier)
        assertEquals(1L, summaries[0].sampleCount)

        assertEquals(raceSessionId, summaries[1].id)
        assertEquals("race", summaries[1].mode)
        assertEquals("Wednesday Race - Run 2", summaries[1].eventIdentifier)
        assertEquals(2L, summaries[1].sampleCount)

        helper.close()
    }

    @Test
    fun multipleResolvedRuns_shareOneSession_butKeepPerSampleRaceContext() {
        val helper = TrackingDbHelper(context)
        val accessContextId = requireNotNull(
            helper.getOrCreateAccessContext(
                serverUrl = "https://raceoffice.example.org",
                accessIdentifier = "Wednesday Race",
                accessSecret = "secret-value"
            )
        )
        val sessionId = requireNotNull(
            helper.createTrackingSession(
                startedAt = 1_000L,
                mode = "race",
                accessContextId = accessContextId,
                displayName = "Race session",
                resolvedEventName = "Wednesday Race - Run 1"
            )
        )
        val run1ContextId = requireNotNull(
            helper.getOrCreateRaceContext(
                accessContextId = accessContextId,
                resolvedEventName = "Wednesday Race - Run 1",
                courseJson = """{"marks":[1]}""",
                courseMapViewportJson = null
            )
        )
        val run2ContextId = requireNotNull(
            helper.getOrCreateRaceContext(
                accessContextId = accessContextId,
                resolvedEventName = "Wednesday Race - Run 2",
                courseJson = """{"marks":[1,2]}""",
                courseMapViewportJson = null
            )
        )

        insertSample(
            helper = helper,
            sequenceId = 1L,
            sessionId = sessionId,
            accessContextId = accessContextId,
            raceContextId = run1ContextId
        )
        insertSample(
            helper = helper,
            sequenceId = 2L,
            sessionId = sessionId,
            accessContextId = accessContextId,
            raceContextId = run2ContextId
        )

        val samples = helper.getTrackingSamplesForSession(sessionId)
        assertEquals(
            listOf("Wednesday Race - Run 1", "Wednesday Race - Run 2"),
            samples.map { it.resolvedEventName }
        )
        assertEquals(
            listOf("""{"marks":[1]}""", """{"marks":[1,2]}"""),
            samples.map { it.courseJson }
        )

        val summary = helper.getTrackingSessionSummaries().single()
        assertEquals("Wednesday Race", summary.eventIdentifier)
        assertEquals(2L, summary.sampleCount)

        helper.close()
    }

    @Test
    fun samplesForSession_areIsolatedAndReturnedInLocalRowOrder() {
        val helper = TrackingDbHelper(context)
        val firstSession = requireNotNull(
            helper.createTrackingSession(
                startedAt = 1_000L,
                mode = "manual",
                accessContextId = null,
                displayName = "First"
            )
        )
        val secondSession = requireNotNull(
            helper.createTrackingSession(
                startedAt = 2_000L,
                mode = "manual",
                accessContextId = null,
                displayName = "Second"
            )
        )

        val firstRow = insertSample(
            helper,
            sequenceId = 50L,
            sessionId = firstSession,
            accessContextId = null,
            lat = 54.1
        )
        insertSample(
            helper,
            sequenceId = 1L,
            sessionId = secondSession,
            accessContextId = null,
            lat = 55.0
        )
        val secondRow = insertSample(
            helper,
            sequenceId = 10L,
            sessionId = firstSession,
            accessContextId = null,
            lat = 54.2
        )

        val samples = helper.getTrackingSamplesForSession(firstSession)

        assertEquals(listOf(firstRow, secondRow), samples.map { it.localId })
        assertEquals(listOf(54.1, 54.2), samples.map { it.lat })
        assertTrue(samples.all { it.localId != 0L })

        helper.close()
    }

    private fun insertSample(
        helper: TrackingDbHelper,
        sequenceId: Long,
        sessionId: Long?,
        accessContextId: Long?,
        lat: Double = 54.0,
        raceContextId: Long? = null
    ): Long {
        return helper.insertSample(
            sequenceId = sequenceId,
            timestamp = "2026-09-22T10:00:00",
            boatName = "Test Boat",
            captainName = "Test Skipper",
            hullColor = "white",
            sailNumber = "GER 195",
            yardstick = 100.0,
            boatType = "Test",
            lat = lat,
            lon = 10.0,
            accuracy = 5f,
            cog = 90f,
            sog = 3f,
            accessContextId = accessContextId,
            sessionId = sessionId,
            raceContextId = raceContextId,
            utcOffsetMinutes = 120
        )
    }

    private companion object {
        const val DB_NAME = "regatta_tracking.db"
    }
}
