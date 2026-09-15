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
class RaceEventSnapshotSelectionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearRaceSetup()
    }

    @After
    fun tearDown() {
        clearRaceSetup()
    }

    @Test
    fun `successful incoming write remains display winner`() {
        val incoming = snapshot(
            resolvedEventName = "Race A",
            status = "planned",
            runName = "Run A"
        )

        val selection = resolveRaceEventDisplaySnapshot(
            context = context,
            access = ACCESS,
            incomingSnapshot = incoming,
            incomingPersisted = true
        )

        assertNotNull(selection)
        assertEquals(incoming, selection?.snapshot)
        assertTrue(selection?.useIncomingStartFlags == true)
    }

    @Test
    fun `generation conflict adopts matching persisted service winner`() {
        val serviceWinner = snapshot(
            resolvedEventName = "Race B",
            status = "started",
            runName = "Run B"
        )
        RaceEventSnapshotStore.save(
            context = context,
            server = ACCESS.server,
            event = ACCESS.event,
            secret = ACCESS.secret,
            snapshot = serviceWinner
        )

        val staleActivityResponse = snapshot(
            resolvedEventName = "Race A",
            status = "planned",
            runName = "Run A"
        )
        val selection = resolveRaceEventDisplaySnapshot(
            context = context,
            access = ACCESS,
            incomingSnapshot = staleActivityResponse,
            incomingPersisted = false
        )

        assertNotNull(selection)
        assertEquals("Race B", selection?.snapshot?.resolvedEventName)
        assertEquals("started", selection?.snapshot?.status)
        assertEquals("Run B", selection?.snapshot?.seriesDisplayMetadata?.runName)
        assertFalse(selection?.useIncomingStartFlags ?: true)
    }

    @Test
    fun `generation conflict never adopts snapshot from another access`() {
        RaceEventSnapshotStore.save(
            context = context,
            server = "https://other.example.org",
            event = "Other Event",
            secret = "other-secret",
            snapshot = snapshot(
                resolvedEventName = "Other Race",
                status = "started",
                runName = "Other Run"
            )
        )

        val selection = resolveRaceEventDisplaySnapshot(
            context = context,
            access = ACCESS,
            incomingSnapshot = snapshot(
                resolvedEventName = "Race A",
                status = "planned",
                runName = "Run A"
            ),
            incomingPersisted = false
        )

        assertNull(selection)
    }

    private fun snapshot(
        resolvedEventName: String,
        status: String,
        runName: String
    ): RaceEventSnapshot = RaceEventSnapshot(
        resolvedEventName = resolvedEventName,
        status = status,
        startRaw = "2026-09-20T12:00:00Z",
        stopRaw = "2026-09-20T16:00:00Z",
        raceInfo = "",
        courseJson = "{}",
        courseShortened = false,
        seriesDisplayMetadata = SeriesDisplayMetadata(runName = runName)
    )

    private fun clearRaceSetup() {
        context.getSharedPreferences("race_setup", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private companion object {
        val ACCESS = EventAccessKey(
            server = "https://race.example.org",
            event = "Stable Series",
            secret = "secret"
        )
    }
}
