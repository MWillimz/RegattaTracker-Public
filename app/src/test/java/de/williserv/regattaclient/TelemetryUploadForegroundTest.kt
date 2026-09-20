package de.williserv.regattaclient

import android.content.Context
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TelemetryUploadForegroundTest {

    @Test
    fun foregroundPromotion_usesExpectedRequestCountAndElapsedFallback() {
        val batch500 = TelemetryBatchCapability(
            kind = TelemetryBatchCapabilityKind.SUPPORTED,
            maxSamples = 500
        )

        assertFalse(
            shouldPromoteTelemetryUploadToForeground(
                remainingPendingCount = 9_000L,
                elapsedMs = 0L,
                capability = batch500
            )
        )
        assertTrue(
            shouldPromoteTelemetryUploadToForeground(
                remainingPendingCount = 10_000L,
                elapsedMs = 0L,
                capability = batch500
            )
        )

        val legacy = TelemetryBatchCapability(
            kind = TelemetryBatchCapabilityKind.UNSUPPORTED
        )
        assertFalse(
            shouldPromoteTelemetryUploadToForeground(
                remainingPendingCount = 99L,
                elapsedMs = 0L,
                capability = legacy
            )
        )
        assertTrue(
            shouldPromoteTelemetryUploadToForeground(
                remainingPendingCount = 100L,
                elapsedMs = 0L,
                capability = legacy
            )
        )

        assertTrue(
            shouldPromoteTelemetryUploadToForeground(
                remainingPendingCount = 1L,
                elapsedMs = TELEMETRY_LONG_RUNNING_ELAPSED_THRESHOLD_MS,
                capability = null
            )
        )
        assertFalse(
            shouldPromoteTelemetryUploadToForeground(
                remainingPendingCount = 0L,
                elapsedMs = TELEMETRY_LONG_RUNNING_ELAPSED_THRESHOLD_MS,
                capability = legacy
            )
        )
    }

    @Test
    fun pendingEstimateRefresh_tracksSamplesAddedDuringWorkerRun() {
        assertTrue(
            shouldRefreshTelemetryPendingEstimate(
                remainingPendingEstimate = 0L,
                elapsedSinceRefreshMs = 0L
            )
        )
        assertFalse(
            shouldRefreshTelemetryPendingEstimate(
                remainingPendingEstimate = 25L,
                elapsedSinceRefreshMs =
                    TELEMETRY_PENDING_ESTIMATE_REFRESH_INTERVAL_MS - 1L
            )
        )
        assertTrue(
            shouldRefreshTelemetryPendingEstimate(
                remainingPendingEstimate = 25L,
                elapsedSinceRefreshMs =
                    TELEMETRY_PENDING_ESTIMATE_REFRESH_INTERVAL_MS
            )
        )
    }

    @Test
    fun notificationProgress_tracksSentAndRemainingSamples() {
        val progress = telemetryUploadNotificationProgress(
            sent = 12_500L,
            remainingPendingCount = 52_300L
        )

        assertEquals(12_500L, progress.sent)
        assertEquals(64_800L, progress.total)
        assertEquals(19, progress.percent)

        val complete = telemetryUploadNotificationProgress(
            sent = 64_800L,
            remainingPendingCount = 0L
        )
        assertEquals(100, complete.percent)
    }

    @Test
    fun foregroundNotificationText_isLocalizedInEverySupportedLanguage() {
        val expected = mapOf(
            "en" to ExpectedText(
                title = "Uploading stored race data",
                progress = "12 of 20 positions sent · Upload continues in the background",
                keepRunning = "Please do not close Regatta Tracker until the upload is complete."
            ),
            "de" to ExpectedText(
                title = "Gespeicherte Regattadaten werden übertragen",
                progress = "12 von 20 Positionen gesendet · Upload läuft im Hintergrund",
                keepRunning = "Bitte Regatta Tracker bis zum Abschluss nicht beenden."
            ),
            "es" to ExpectedText(
                title = "Enviando datos de regata guardados",
                progress = "12 de 20 posiciones enviadas · El envío continúa en segundo plano",
                keepRunning = "No cierres Regatta Tracker hasta que finalice el envío."
            ),
            "fr" to ExpectedText(
                title = "Envoi des données de régate enregistrées",
                progress = "12 positions sur 20 envoyées · L’envoi continue en arrière-plan",
                keepRunning = "Ne fermez pas Regatta Tracker avant la fin de l’envoi."
            ),
            "it" to ExpectedText(
                title = "Invio dei dati di regata salvati",
                progress = "12 di 20 posizioni inviate · L’invio continua in secondo piano",
                keepRunning = "Non chiudere Regatta Tracker fino al completamento dell’invio."
            )
        )

        val baseContext: Context = RuntimeEnvironment.getApplication()

        expected.forEach { (language, text) ->
            val configuration = Configuration(baseContext.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            val localizedContext =
                baseContext.createConfigurationContext(configuration)

            assertEquals(
                text.title,
                localizedContext.getString(
                    R.string.telemetry_upload_notification_title
                )
            )
            assertEquals(
                text.progress,
                localizedContext.getString(
                    R.string.telemetry_upload_notification_progress,
                    "12",
                    "20"
                )
            )
            assertEquals(
                text.keepRunning,
                localizedContext.getString(
                    R.string.telemetry_upload_notification_keep_running
                )
            )
        }
    }

    private data class ExpectedText(
        val title: String,
        val progress: String,
        val keepRunning: String
    )
}
