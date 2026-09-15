package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventRequestGateTest {

    @Test
    fun `same access and generation keeps only one in-flight request`() {
        val gate = EventRequestGate()
        val access = access("A")

        val first = gate.tryStart(access, generation = 1L)
        val duplicate = gate.tryStart(access, generation = 1L)

        assertNotNull(first)
        assertNull(duplicate)
    }

    @Test
    fun `access change can start fresh request while old request is still in flight`() {
        val gate = EventRequestGate()
        val accessA = access("A")
        val accessB = access("B")

        val requestA = requireNotNull(gate.tryStart(accessA, generation = 1L))
        val requestB = gate.tryStart(accessB, generation = 2L)

        assertNotNull(requestB)
        assertFalse(gate.isCurrent(requestA, accessB, currentGeneration = 2L))
        assertTrue(gate.isCurrent(requireNotNull(requestB), accessB, currentGeneration = 2L))
    }

    @Test
    fun `stale request cannot mutate current access even when previous cache was not ready`() {
        val gate = EventRequestGate()
        val accessA = access("A")
        val accessB = access("B")

        val requestA = requireNotNull(gate.tryStart(accessA, generation = 10L))
        val requestB = requireNotNull(gate.tryStart(accessB, generation = 11L))

        assertFalse(gate.isCurrent(requestA, accessB, currentGeneration = 11L))
        assertTrue(gate.isCurrent(requestB, accessB, currentGeneration = 11L))
    }

    @Test
    fun `same server event change still invalidates old request`() {
        val gate = EventRequestGate()
        val accessA = EventAccessKey(
            server = "https://race.example.org",
            event = "Event A",
            secret = "secret-a"
        )
        val accessB = EventAccessKey(
            server = "https://race.example.org",
            event = "Event B",
            secret = "secret-b"
        )

        val requestA = requireNotNull(gate.tryStart(accessA, generation = 20L))
        val requestB = requireNotNull(gate.tryStart(accessB, generation = 21L))

        assertFalse(gate.isCurrent(requestA, accessB, currentGeneration = 21L))
        assertTrue(gate.isCurrent(requestB, accessB, currentGeneration = 21L))
    }

    @Test
    fun `finishing stale request does not release replacement request ownership`() {
        val gate = EventRequestGate()
        val accessA = access("A")
        val accessB = access("B")

        val requestA = requireNotNull(gate.tryStart(accessA, generation = 3L))
        val requestB = requireNotNull(gate.tryStart(accessB, generation = 4L))

        gate.finish(requestA)

        assertTrue(gate.isCurrent(requestB, accessB, currentGeneration = 4L))
        assertNull(gate.tryStart(accessB, generation = 4L))
    }

    @Test
    fun `generation change replaces in-flight request for same access`() {
        val gate = EventRequestGate()
        val access = access("A")

        val oldRequest = requireNotNull(gate.tryStart(access, generation = 7L))
        val newRequest = requireNotNull(gate.tryStart(access, generation = 8L))

        assertFalse(gate.isCurrent(oldRequest, access, currentGeneration = 8L))
        assertTrue(gate.isCurrent(newRequest, access, currentGeneration = 8L))
    }

    private fun access(name: String): EventAccessKey = EventAccessKey(
        server = "https://${name.lowercase()}.example.org",
        event = "Event $name",
        secret = "secret-$name"
    )
}
