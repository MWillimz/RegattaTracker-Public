package de.williserv.regattaclient

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

internal enum class TelemetryUploadAttemptResult {
    SUCCESS,
    TEMPORARY_FAILURE,
    OTHER_FAILURE
}

internal enum class TelemetryWorkerDecision {
    SUCCESS,
    RETRY,
    CONTINUE
}

internal fun classifyTelemetryUploadResponseCode(responseCode: Int): TelemetryUploadAttemptResult {
    return when {
        responseCode in 200..299 -> TelemetryUploadAttemptResult.SUCCESS
        responseCode == 408 || responseCode == 429 || responseCode in 500..599 -> {
            TelemetryUploadAttemptResult.TEMPORARY_FAILURE
        }
        else -> TelemetryUploadAttemptResult.OTHER_FAILURE
    }
}

internal fun shouldEnqueueTelemetryUpload(uploadablePendingCount: Long): Boolean {
    return uploadablePendingCount > 0L
}

internal fun decideTelemetryWorkerCompletion(
    retryNeeded: Boolean,
    madeProgress: Boolean,
    uploadablePendingCount: Long
): TelemetryWorkerDecision {
    return when {
        retryNeeded -> TelemetryWorkerDecision.RETRY
        madeProgress && uploadablePendingCount > 0L -> TelemetryWorkerDecision.CONTINUE
        else -> TelemetryWorkerDecision.SUCCESS
    }
}

internal object TelemetryUploadStatusStore {
    private const val PREFS_NAME = "regatta_local_status"
    const val STATUS_KEY = "upload_status_text"

    const val ACTIVE = "active"
    const val WAITING = "waiting"
    const val TEMPORARY_ERROR = "temporary error"
    const val ALL_SENT = "all sent"

    fun write(context: Context, status: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(STATUS_KEY, status)
            .apply()
    }
}

object TelemetryUploadScheduler {
    private const val UNIQUE_WORK_NAME = "regatta-telemetry-upload"

    internal fun buildRequest(): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        return OneTimeWorkRequest.Builder(TelemetryUploadWorker::class.java)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()
    }

    fun enqueue(context: Context) {
        TelemetryUploadStatusStore.write(context, TelemetryUploadStatusStore.WAITING)
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                buildRequest()
            )
    }

    fun enqueueIfNeeded(context: Context) {
        val db = TrackingDbHelper(context.applicationContext)
        val uploadablePendingCount = try {
            db.countUploadablePendingSamples()
        } finally {
            db.close()
        }

        if (shouldEnqueueTelemetryUpload(uploadablePendingCount)) {
            enqueue(context)
        } else {
            TelemetryUploadStatusStore.write(context, TelemetryUploadStatusStore.ALL_SENT)
        }
    }

    fun enqueueContinuation(context: Context) {
        TelemetryUploadStatusStore.write(context, TelemetryUploadStatusStore.WAITING)
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                buildRequest()
            )
    }
}

class TelemetryUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    private val db = TrackingDbHelper(appContext)
    private val localStatusPrefsName = "regatta_local_status"

    override fun doWork(): Result {
        val pendingSamples = db.getPendingSamples(limit = BATCH_SIZE)

        if (pendingSamples.isEmpty()) {
            TelemetryUploadStatusStore.write(
                applicationContext,
                if (db.countUploadablePendingSamples() == 0L) {
                    TelemetryUploadStatusStore.ALL_SENT
                } else {
                    TelemetryUploadStatusStore.WAITING
                }
            )
            return Result.success()
        }

        TelemetryUploadStatusStore.write(applicationContext, TelemetryUploadStatusStore.ACTIVE)

        var madeProgress = false
        var retryNeeded = false

        for (sample in pendingSamples) {
            when (uploadSampleBlocking(sample)) {
                TelemetryUploadAttemptResult.SUCCESS -> {
                    db.markUploaded(sample.localId)
                    madeProgress = true
                }

                TelemetryUploadAttemptResult.TEMPORARY_FAILURE -> {
                    retryNeeded = true
                }

                TelemetryUploadAttemptResult.OTHER_FAILURE -> {
                    // Keep this row pending, but continue with later rows in this batch.
                }
            }
        }

        val remaining = db.countUploadablePendingSamples()

        return when (
            decideTelemetryWorkerCompletion(
                retryNeeded = retryNeeded,
                madeProgress = madeProgress,
                uploadablePendingCount = remaining
            )
        ) {
            TelemetryWorkerDecision.RETRY -> {
                TelemetryUploadStatusStore.write(
                    applicationContext,
                    TelemetryUploadStatusStore.TEMPORARY_ERROR
                )
                Result.retry()
            }

            TelemetryWorkerDecision.CONTINUE -> {
                TelemetryUploadScheduler.enqueueContinuation(applicationContext)
                Result.success()
            }

            TelemetryWorkerDecision.SUCCESS -> {
                TelemetryUploadStatusStore.write(
                    applicationContext,
                    if (remaining == 0L) {
                        TelemetryUploadStatusStore.ALL_SENT
                    } else {
                        TelemetryUploadStatusStore.WAITING
                    }
                )
                Result.success()
            }
        }
    }

    private fun uploadSampleBlocking(sample: PendingTrackingSample): TelemetryUploadAttemptResult {
        val accessContext = sample.accessContext

        return try {
            val json = JSONObject().apply {
                put("sequence_id", sample.sequenceId)
                put("timestamp", sample.timestamp)
                put("boat_name", sample.boatName)
                put("captain_name", sample.captainName)
                put("hull_color", sample.hullColor)
                put("sail_number", sample.sailNumber)
                put("yardstick", sample.yardstick)
                put("boat_type", sample.boatType)
                put("lat", sample.lat)
                put("lon", sample.lon)
                put("accuracy", sample.accuracy)
                put("cog", sample.cog)
                put("sog", sample.sog)
                put("accel_x", sample.accelX)
                put("accel_y", sample.accelY)
                put("accel_z", sample.accelZ)
                put("gyro_x", sample.gyroX)
                put("gyro_y", sample.gyroY)
                put("gyro_z", sample.gyroZ)
            }

            val connection = URL(buildIngestUrl(accessContext)).openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.doOutput = true

            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("x-event-name", accessContext.accessIdentifier)
            connection.setRequestProperty("x-shared-secret", accessContext.accessSecret)
            connection.setRequestProperty("x-api-version", RegattaTrackingService.API_VERSION)

            connection.outputStream.use { outputStream ->
                outputStream.write(json.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val result = classifyTelemetryUploadResponseCode(responseCode)

            if (result == TelemetryUploadAttemptResult.SUCCESS) {
                publishDebugError("")
            } else {
                val errorBody = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: ""

                publishDebugError(
                    "Upload error $responseCode: ${errorBody.take(200)}"
                )
            }

            connection.disconnect()
            result
        } catch (e: Exception) {
            publishDebugError("Upload exception: ${e.message}")
            TelemetryUploadAttemptResult.TEMPORARY_FAILURE
        }
    }

    private fun buildIngestUrl(accessContext: AccessContext): String {
        return "${accessContext.serverUrl.trimEnd('/')}/ingest"
    }

    private fun publishDebugError(message: String) {
        applicationContext
            .getSharedPreferences(localStatusPrefsName, Context.MODE_PRIVATE)
            .edit()
            .putString("debug_error_text", message)
            .apply()
    }

    private companion object {
        const val BATCH_SIZE = 50
    }
}
