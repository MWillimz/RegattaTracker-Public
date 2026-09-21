package de.williserv.regattaclient

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class ParticipantRetirementTest {
    private lateinit var context: Context
    private val identity = ParticipantRetirementIdentity("GER 147", "Test Boat", "Test Skipper")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ParticipantRetirementStore.clearForTests(context)
    }

    @After
    fun tearDown() {
        ParticipantRetirementStore.clearForTests(context)
    }

    @Test
    fun `payload keeps stable access event and participant identity`() {
        val payload = buildParticipantRetirementPayload("series-access", identity)

        assertEquals("series-access", payload.getString("event_name"))
        assertEquals("GER 147", payload.getString("sail_number"))
        assertEquals("Test Boat", payload.getString("boat_name"))
        assertEquals("Test Skipper", payload.getString("captain_name"))
    }

    @Test
    fun `valid ret response produces concrete run receipt`() {
        val receipt = parseParticipantRetirementReceipt(
            """{"status":"ret","event_name":"Series Race 3","sail_number":"GER 147","boat_name":"Test Boat","captain_name":"Test Skipper","reported_at":"2026-09-20T11:30:00+00:00"}""",
            identity
        )

        assertNotNull(receipt)
        assertEquals("Series Race 3", receipt?.resolvedEventName)
        assertEquals(identity, receipt?.identity)
    }

    @Test
    fun `response must be ret and match requested identity`() {
        assertNull(
            parseParticipantRetirementReceipt(
                """{"status":"finished","event_name":"Series Race 3","sail_number":"GER 147","boat_name":"Test Boat","captain_name":"Test Skipper","reported_at":"2026-09-20T11:30:00+00:00"}""",
                identity
            )
        )
        assertNull(
            parseParticipantRetirementReceipt(
                """{"status":"ret","event_name":"Series Race 3","sail_number":"GER 999","boat_name":"Test Boat","captain_name":"Test Skipper","reported_at":"2026-09-20T11:30:00+00:00"}""",
                identity
            )
        )
    }

    @Test
    fun `stored self report is bound to server concrete run and full identity`() {
        ParticipantRetirementStore.save(
            context = context,
            serverUrl = "https://raceoffice.example.org/ingest/",
            receipt = ParticipantRetirementReceipt(
                "Series Race 3",
                identity,
                "2026-09-20T11:30:00+00:00"
            )
        )

        assertTrue(
            ParticipantRetirementStore.matches(
                context = context,
                serverUrl = "https://raceoffice.example.org",
                resolvedEventName = "Series Race 3",
                identity = identity
            )
        )
        assertFalse(
            ParticipantRetirementStore.matches(
                context = context,
                serverUrl = "https://other.example.org",
                resolvedEventName = "Series Race 3",
                identity = identity
            )
        )
        assertFalse(
            ParticipantRetirementStore.matches(
                context = context,
                serverUrl = "https://raceoffice.example.org",
                resolvedEventName = "Series Race 4",
                identity = identity
            )
        )
        assertFalse(
            ParticipantRetirementStore.matches(
                context = context,
                serverUrl = "https://raceoffice.example.org",
                resolvedEventName = "Series Race 3",
                identity = identity.copy(boatName = "Other Boat")
            )
        )
    }
}
