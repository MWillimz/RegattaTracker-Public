package de.williserv.regattaclient

import java.util.concurrent.atomic.AtomicBoolean

/** Instance-local guard for asynchronous work owned by one MainActivity. */
internal class ActivityAsyncLifetime {
    private val active = AtomicBoolean(true)

    fun isActive(): Boolean = active.get()

    fun invalidate() {
        active.set(false)
    }
}
