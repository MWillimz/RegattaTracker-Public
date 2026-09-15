package de.williserv.regattaclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityAsyncLifetimeTest {
    @Test
    fun `lifetime is active until invalidated`() {
        val lifetime = ActivityAsyncLifetime()
        assertTrue(lifetime.isActive())
        lifetime.invalidate()
        assertFalse(lifetime.isActive())
    }

    @Test
    fun `invalidate is permanent and idempotent`() {
        val lifetime = ActivityAsyncLifetime()
        lifetime.invalidate()
        lifetime.invalidate()
        assertFalse(lifetime.isActive())
    }

    @Test
    fun `activity lifetimes are independent`() {
        val oldLifetime = ActivityAsyncLifetime()
        val newLifetime = ActivityAsyncLifetime()
        oldLifetime.invalidate()
        assertFalse(oldLifetime.isActive())
        assertTrue(newLifetime.isActive())
    }
}
