package de.williserv.regattaclient

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
