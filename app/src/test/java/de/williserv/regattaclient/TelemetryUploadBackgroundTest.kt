package de.williserv.regattaclient

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TelemetryUploadBackgroundTest {

    @Test
    fun backgroundSlice_yieldsOnlyAfterBoundedRuntime() {
        assertFalse(
            shouldYieldTelemetryUpload(
                elapsedMs = TELEMETRY_BACKGROUND_SLICE_MS - 1L
            )
        )
        assertTrue(
            shouldYieldTelemetryUpload(
                elapsedMs = TELEMETRY_BACKGROUND_SLICE_MS
            )
        )
        assertTrue(
            shouldYieldTelemetryUpload(
                elapsedMs = TELEMETRY_BACKGROUND_SLICE_MS * 2L
            )
        )
    }

    @Test
    fun recoveryStatus_usesNormalNotificationNotForegroundServiceNotification() {
        val context: Context = RuntimeEnvironment.getApplication()
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        cancelTelemetryRecoveryNotification(context)
        showTelemetryRecoveryNotification(
            context = context,
            remaining = 1_234L
        )

        val active = notificationManager.activeNotifications
        assertEquals(1, active.size)
        assertEquals(
            0,
            active.single().notification.flags and
                Notification.FLAG_FOREGROUND_SERVICE
        )
        assertEquals(
            context.getString(R.string.telemetry_upload_notification_title),
            active.single().notification.extras.getCharSequence(
                Notification.EXTRA_TITLE
            )
        )

        cancelTelemetryRecoveryNotification(context)
        assertTrue(notificationManager.activeNotifications.isEmpty())
    }
}
