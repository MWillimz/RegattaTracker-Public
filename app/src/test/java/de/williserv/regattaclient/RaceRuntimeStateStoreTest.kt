package de.williserv.regattaclient

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RaceRuntimeStateStoreTest {
    @After
    fun tearDown() {
        RaceRuntimeStateStore.resetForTests()
    }

    @Test
    fun publishedStateIsScopedToTheAccessKey() {
        RaceRuntimeStateStore.publish(
            server = "https://race.example.org/",
            event = "Thursday Series",
            secret = "secret-a",
            resolvedEventName = "run-1",
            status = "scheduled",
            startEpochMillis = 1_000L,
            stopEpochMillis = 2_000L,
            updatedAtMillis = 10L
        )

        val matching = RaceRuntimeStateStore.snapshotFor(
            server = "https://race.example.org",
            event = "Thursday Series",
            secret = "secret-a"
        )
        val otherAccess = RaceRuntimeStateStore.snapshotFor(
            server = "https://race.example.org",
            event = "Thursday Series",
            secret = "secret-b"
        )

        assertEquals("run-1", matching?.resolvedEventName)
        assertEquals(1_000L, matching?.startEpochMillis)
        assertNull(otherAccess)
    }

    @Test
    fun newerServicePollReplacesStartAndStatusAndNotifiesListeners() {
        val observed = mutableListOf<RaceRuntimeState?>()
        val listener: (RaceRuntimeState?) -> Unit = { observed.add(it) }
        RaceRuntimeStateStore.addListener(listener)

        RaceRuntimeStateStore.publish(
            server = "https://race.example.org",
            event = "Thursday Series",
            secret = "secret",
            resolvedEventName = "run-1",
            status = "scheduled",
            startEpochMillis = 1_000L,
            stopEpochMillis = null,
            updatedAtMillis = 10L
        )
        RaceRuntimeStateStore.publish(
            server = "https://race.example.org",
            event = "Thursday Series",
            secret = "secret",
            resolvedEventName = "run-1",
            status = "postponed",
            startEpochMillis = 11_000L,
            stopEpochMillis = null,
            updatedAtMillis = 20L
        )

        val latest = RaceRuntimeStateStore.snapshotFor(
            server = "https://race.example.org",
            event = "Thursday Series",
            secret = "secret"
        )

        assertEquals("postponed", latest?.status)
        assertEquals(11_000L, latest?.startEpochMillis)
        assertSame(latest, observed.last())
    }

    @Test
    fun clearingOneAccessDoesNotClearAnotherAccessState() {
        RaceRuntimeStateStore.publish(
            server = "https://race.example.org",
            event = "Event B",
            secret = "secret-b",
            resolvedEventName = "Event B",
            status = "scheduled",
            startEpochMillis = 5_000L,
            stopEpochMillis = null
        )

        RaceRuntimeStateStore.clearFor(
            server = "https://race.example.org",
            event = "Event A",
            secret = "secret-a"
        )

        assertEquals(
            5_000L,
            RaceRuntimeStateStore.snapshotFor(
                server = "https://race.example.org",
                event = "Event B",
                secret = "secret-b"
            )?.startEpochMillis
        )
    }
}
