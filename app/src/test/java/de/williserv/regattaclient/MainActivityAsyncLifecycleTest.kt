package de.williserv.regattaclient

import androidx.compose.runtime.MutableState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityAsyncLifecycleTest {
    @Test
    fun `storage count refresh gate throttles and queues one forced follow up`() {
        val gate = StorageCountRefreshGate(STORAGE_COUNTS_REFRESH_INTERVAL_MS)

        assertTrue(gate.tryStart(nowElapsedMs = 0L, force = false))
        assertFalse(gate.tryStart(nowElapsedMs = 1_000L, force = false))
        assertFalse(gate.tryStart(nowElapsedMs = 1_000L, force = true))
        assertTrue(gate.finishAndTakeForcedFollowUp())

        assertTrue(gate.tryStart(nowElapsedMs = 1_000L, force = true))
        assertFalse(gate.finishAndTakeForcedFollowUp())

        assertFalse(gate.tryStart(nowElapsedMs = 9_999L, force = false))
        assertTrue(gate.tryStart(nowElapsedMs = 11_000L, force = false))
        assertFalse(gate.finishAndTakeForcedFollowUp())
    }

    @Test
    fun `storage counts cannot be applied after activity destroy`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        val rowCount = getField<MutableState<String>>(activity, "rowCountText")
        val pending = getField<MutableState<Long>>(activity, "pendingUploadCount")

        controller.destroy()
        rowCount.value = "sentinel"
        pending.value = 7L

        activity.javaClass.getDeclaredMethod(
            "applyStorageCounts",
            TrackingStorageCounts::class.java
        ).apply {
            isAccessible = true
            invoke(
                activity,
                TrackingStorageCounts(total = 999L, pending = 999L)
            )
        }

        assertEquals("sentinel", rowCount.value)
        assertEquals(7L, pending.value)
    }

    @Test
    fun `destroy invalidates only the destroyed activity lifetime`() {
        val oldController = Robolectric.buildActivity(MainActivity::class.java).create()
        val oldActivity = oldController.get()
        val oldLifetime = getField<ActivityAsyncLifetime>(oldActivity, "asyncLifetime")
        assertTrue(oldLifetime.isActive())

        oldController.destroy()
        assertFalse(oldLifetime.isActive())

        val newController = Robolectric.buildActivity(MainActivity::class.java).create()
        val newLifetime = getField<ActivityAsyncLifetime>(newController.get(), "asyncLifetime")
        assertTrue(newLifetime.isActive())
        newController.destroy()
    }

    @Test
    fun `activity owned refresh runnables are no ops after destroy`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        controller.destroy()

        val uiRefresh = getField<Runnable>(activity, "uiRefreshRunnable")
        val raceRefresh = getField<Runnable>(activity, "raceDataRefreshRunnable")
        uiRefresh.run()
        raceRefresh.run()

        assertFalse(getField<ActivityAsyncLifetime>(activity, "asyncLifetime").isActive())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).let { field ->
            field.isAccessible = true
            field.get(target) as T
        }
}
