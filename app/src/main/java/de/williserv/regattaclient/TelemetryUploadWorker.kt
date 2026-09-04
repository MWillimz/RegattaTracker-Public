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
import androidx.work.workDataOf
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

internal enum class TelemetryUploadAttemptResult {
    SUCCESS,
    TEMPORARY_FAILURE,
    CLIENT_UPDATE_REQUIRED,
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

internal fun classifyTelemetryUploadResponse(
    responseCode: Int,
    errorBody: String,
    client: ClientBuildIdentity
): TelemetryUploadAttemptResult {
    if (shouldTreatAsClientUpdateRequired(responseCode, errorBody, client)) {
        return TelemetryUploadAttemptResult.CLIENT_UPDATE_REQUIRED
    }
    return classifyTelemetryUploadResponseCode(responseCode)
}

internal fun shouldEnqueueTelemetryUpload(uploadablePendingCount: Long): Boolean {
    return uploadablePendingCount > 0L
}

internal fun hasUnblockedUploadablePendingServer(
    context: Context,
    client: ClientBuildIdentity
): Boolean {
    val db = TrackingDbHelper(context.applicationContext)
    return try {
        db.readableDatabase.rawQuery(
            """
            SELECT DISTINCT contexts.server_url
            FROM tracking_samples AS samples
            INNER JOIN access_contexts AS contexts
                ON contexts.id = samples.access_context_id
            WHERE samples.uploaded = 0
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val serverUrl = cursor.getString(0)
                if (
                    !ClientCompatibilityBlockStore.isBlocked(
                        context = context,
                        serverUrl = serverUrl,
                        versionCode = client.versionCode
                    )
                ) {
                    return true
                }
            }
            false
        }
    } finally {
        db.close()
    }
}

internal fun decideTelemetryWorkerCompletion(
    retryNeeded: Boolean,
    hasLaterPendingSamples: Boolean
): TelemetryWorkerDecision {
    return when {
        retryNeeded -> TelemetryWorkerDecision.RETRY
        hasLaterPendingSamples -> TelemetryWorkerDecision.CONTINUE
        else -> TelemetryWorkerDecision.SUCCESS
    }
}

internal fun countUploadablePendingSamplesThrough(
    db: TrackingDbHelper,
    localId: Long
): Long {
    if (localId <= 0L) return 0L

    db.readableDatabase.rawQuery(
        """
        SELECT COUNT(*)
        FROM tracking_samples AS samples
        INNER JOIN access_contexts AS contexts
            ON contexts.id = samples.access_context_id
        WHERE samples.uploaded = 0
          AND samples.id <= ?
        """.trimIndent(),
        arrayOf(localId.toString())
    ).use { cursor ->
        cursor.moveToFirst()
        return cursor.getLong(0)
    }
}

internal fun getTelemetryUploadPage(
    db: TrackingDbHelper,
    afterLocalId: Long,
    limit: Int
): List<PendingTrackingSample> {
    require(limit > 0)

    val rowsToSkip = countUploadablePendingSamplesThrough(db, afterLocalId)
    if (rowsToSkip >= Int.MAX_VALUE - limit) return emptyList()

    return db.getPendingSamples(limit = rowsToSkip.toInt() + limit)
        .drop(rowsToSkip.toInt())
        .take(limit)
}

internal fun buildTelemetryUploadPayload(
    sample: PendingTrackingSample,
    client: ClientBuildIdentity
): JSONObject = JSONObject().apply {
    put("sequence_id", sample.sequenceId)
    put("timestamp", sample.timestamp)
    put("client_version_code", client.versionCode)
    put("client_build_id", client.buildId)
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
    internal const val AFTER_LOCAL_ID_KEY = "after_local_id"

    internal fun buildRequest(afterLocalId: Long = 0L): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        return OneTimeWorkRequest.Builder(TelemetryUploadWorker::class.java)
            .setConstraints(constraints)
            .setInputData(workDataOf(AFTER_LOCAL_ID_KEY to afterLocalId))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()
    }

    fun enqueue(context: Context) {
        TelemetryUploadStatusStore.write(context, TelemetryUploadStatusStore.WAITING)

        val client = currentClientBuildIdentity()
        if (
            ClientCompatibilityBlockStore.hasAnyBlockForVersion(
                context = context.applicationContext,
                versionCode = client.versionCode
            ) &&
            !hasUnblockedUploadablePendingServer(
                context = context.applicationContext,
                client = client
            )
        ) {
            return
        }

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

    fun enqueueContinuation(context: Context, afterLocalId: Long) {
        TelemetryUploadStatusStore.write(context, TelemetryUploadStatusStore.WAITING)
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                buildRequest(afterLocalId = afterLocalId)
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
        val client = currentClientBuildIdentity()
        val afterLocalId = inputData.getLong(TelemetryUploadScheduler.AFTER_LOCAL_ID_KEY, 0L)
        val pendingSamples = getTelemetryUploadPage(
            db = db,
            afterLocalId = afterLocalId,
            limit = BATCH_SIZE
        )

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

        var retryNeeded = false

        for (sample in pendingSamples) {
            if (
                ClientCompatibilityBlockStore.isBlocked(
                    context = applicationContext,
                    serverUrl = sample.accessContext.serverUrl,
                    versionCode = client.versionCode
                )
            ) {
                continue
            }

            when (uploadSampleBlocking(sample, client)) {
                TelemetryUploadAttemptResult.SUCCESS -> {
                    db.markUploaded(sample.localId)
                }

                TelemetryUploadAttemptResult.TEMPORARY_FAILURE -> {
                    retryNeeded = true
                }

                TelemetryUploadAttemptResult.CLIENT_UPDATE_REQUIRED,
                TelemetryUploadAttemptResult.OTHER_FAILURE -> {
                    // Keep this row pending. A known 426 also blocks later requests to this server/version.
                }
            }
        }

        val remaining = db.countUploadablePendingSamples()
        val lastLocalId = pendingSamples.last().localId
        val remainingThroughLast = countUploadablePendingSamplesThrough(db, lastLocalId)
        val hasLaterPendingSamples = remaining > remainingThroughLast

        return when (
            decideTelemetryWorkerCompletion(
                retryNeeded = retryNeeded,
                hasLaterPendingSamples = hasLaterPendingSamples
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
                TelemetryUploadScheduler.enqueueContinuation(
                    applicationContext,
                    afterLocalId = lastLocalId
                )
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

    private fun uploadSampleBlocking(
        sample: PendingTrackingSample,
        client: ClientBuildIdentity
    ): TelemetryUploadAttemptResult {
        val accessContext = sample.accessContext

        return try {
            val json = buildTelemetryUploadPayload(
                sample = sample,
                client = client
            )

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
            val errorBody = if (responseCode in 200..299) {
                ""
            } else {
                connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: ""
            }
            val result = classifyTelemetryUploadResponse(
                responseCode = responseCode,
                errorBody = errorBody,
                client = client
            )

            when (result) {
                TelemetryUploadAttemptResult.SUCCESS -> {
                    ClientCompatibilityBlockStore.clearBlocked(
                        context = applicationContext,
                        serverUrl = accessContext.serverUrl,
                        versionCode = client.versionCode
                    )
                    if (
                        !ClientCompatibilityBlockStore.hasAnyBlockForVersion(
                            context = applicationContext,
                            versionCode = client.versionCode
                        )
                    ) {
                        publishDebugError("")
                    }
                }

                TelemetryUploadAttemptResult.CLIENT_UPDATE_REQUIRED -> {
                    ClientCompatibilityBlockStore.markBlocked(
                        context = applicationContext,
                        serverUrl = accessContext.serverUrl,
                        versionCode = client.versionCode
                    )
                    publishDebugError(
                        applicationContext.getString(
                            R.string.upload_error_code,
                            responseCode,
                            applicationContext.getString(R.string.client_update_required)
                        )
                    )
                }

                else -> {
                    publishDebugError(
                        applicationContext.getString(
                            R.string.upload_error_code,
                            responseCode,
                            errorBody.take(200)
                        )
                    )
                }
            }

            connection.disconnect()
            result
        } catch (e: Exception) {
            publishDebugError(applicationContext.getString(R.string.upload_exception, e.message ?: ""))
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
