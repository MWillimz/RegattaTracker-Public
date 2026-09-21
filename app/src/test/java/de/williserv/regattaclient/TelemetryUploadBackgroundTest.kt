package de.williserv.regattaclient

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
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
class TelemetryUploadBackgroundTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        context.getSharedPreferences(APP_STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        cancelTelemetryRecoveryNotification(context)
    }

    @After
    fun tearDown() {
        cancelTelemetryRecoveryNotification(context)
        context.getSharedPreferences(APP_STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

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
    fun recoveryNotificationUpdate_isThrottledToOneSecondUnlessForced() {
        assertTrue(
            shouldPublishTelemetryRecoveryNotification(
                lastPublishedElapsedMs = null,
                nowElapsedMs = 100L,
                force = false
            )
        )
        assertFalse(
            shouldPublishTelemetryRecoveryNotification(
                lastPublishedElapsedMs = 1_000L,
                nowElapsedMs = 1_999L,
                force = false
            )
        )
        assertTrue(
            shouldPublishTelemetryRecoveryNotification(
                lastPublishedElapsedMs = 1_000L,
                nowElapsedMs = 2_000L,
                force = false
            )
        )
        assertTrue(
            shouldPublishTelemetryRecoveryNotification(
                lastPublishedElapsedMs = 2_000L,
                nowElapsedMs = 2_001L,
                force = true
            )
        )
    }

    @Test
    fun recoveryRemainingEstimate_decrementsOnlyAcknowledgedSamples() {
        assertEquals(
            950L,
            reduceTelemetryRecoveryRemainingEstimate(
                remainingEstimate = 1_000L,
                acknowledgedCount = 50L
            )
        )
        assertEquals(
            0L,
            reduceTelemetryRecoveryRemainingEstimate(
                remainingEstimate = 20L,
                acknowledgedCount = 50L
            )
        )
        assertEquals(
            20L,
            reduceTelemetryRecoveryRemainingEstimate(
                remainingEstimate = 20L,
                acknowledgedCount = -1L
            )
        )
    }

    @Test
    fun trackingActive_cancelsAndSuppressesStandaloneRecoveryNotification() {
        showTelemetryRecoveryNotification(
            context = context,
            remaining = 1_234L
        )
        assertEquals(1, notificationManager.activeNotifications.size)

        context.getSharedPreferences(APP_STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("in_race", true)
            .commit()

        onTelemetryTrackingBecameActive(context)
        assertTrue(notificationManager.activeNotifications.isEmpty())
        assertTrue(isTelemetryTrackingActive(context))

        showTelemetryRecoveryNotification(
            context = context,
            remaining = 1_000L
        )
        assertTrue(notificationManager.activeNotifications.isEmpty())
    }

    @Test
    fun manualTracking_alsoSuppressesStandaloneRecoveryNotification() {
        context.getSharedPreferences(APP_STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("manual_tracking", true)
            .commit()

        assertTrue(isTelemetryTrackingActive(context))
        showTelemetryRecoveryNotification(
            context = context,
            remaining = 100L
        )
        assertTrue(notificationManager.activeNotifications.isEmpty())
    }

    @Test
    fun failedRecoveryPersistence_doesNotPostStaleNotification() {
        handleTelemetryRecoveryPersistenceResult(
            context = context,
            persistenceError = IllegalStateException("test enqueue failure")
        )

        assertTrue(notificationManager.activeNotifications.isEmpty())
    }

    @Test
    fun successfulRecoveryPersistence_postsNormalNonFgsNotification() {
        val helper = TrackingDbHelper(context)
        val accessContextId = helper.getOrCreateAccessContext(
            serverUrl = "https://raceoffice.example.org",
            accessIdentifier = "Event A",
            accessSecret = "secret-a"
        ) ?: error("context id missing")
        helper.insertSample(
            sequenceId = 1L,
            timestamp = "2026-09-21T05:00:00",
            boatName = "Test Boat",
            captainName = "Tester",
            hullColor = "white",
            sailNumber = "GER 185",
            yardstick = 100.0,
            boatType = "Test",
            lat = 53.5,
            lon = 10.0,
            accuracy = 5f,
            cog = 0f,
            sog = 0f,
            accelX = 0f,
            accelY = 0f,
            accelZ = 0f,
            gyroX = 0f,
            gyroY = 0f,
            gyroZ = 0f,
            accessContextId = accessContextId
        )
        helper.close()

        handleTelemetryRecoveryPersistenceResult(
            context = context,
            persistenceError = null
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
    }

    private companion object {
        const val APP_STATE_PREFS = "app_state"
    }
}
