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
class TelemetryTrackingRuntimeStateTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        TrackingServiceRuntimeState.markStopped()
    }

    @After
    fun tearDown() {
        TrackingServiceRuntimeState.markStopped()
        context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `stale race flag does not suppress telemetry recovery`() {
        context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("in_race", true)
            .commit()

        assertFalse(isTelemetryTrackingActive(context))
    }

    @Test
    fun `stale manual flag does not suppress telemetry recovery`() {
        context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("manual_tracking", true)
            .commit()

        assertFalse(isTelemetryTrackingActive(context))
    }

    @Test
    fun `confirmed running service suppresses telemetry recovery`() {
        TrackingServiceRuntimeState.markActive()

        assertTrue(isTelemetryTrackingActive(context))
    }
}
