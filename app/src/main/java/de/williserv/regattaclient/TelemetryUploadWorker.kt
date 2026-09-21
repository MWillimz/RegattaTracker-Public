package de.williserv.regattaclient

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
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

internal enum class TelemetryBatchCapabilityKind {
    SUPPORTED,
    UNSUPPORTED,
    TEMPORARY_FAILURE
}

internal data class TelemetryBatchCapability(
    val kind: TelemetryBatchCapabilityKind,
    val maxSamples: Int? = null
)

internal enum class TelemetryBatchAttemptKind {
    PROCESSED,
    TEMPORARY_FAILURE,
    UNSUPPORTED,
    PAYLOAD_TOO_LARGE,
    CLIENT_UPDATE_REQUIRED,
    OTHER_FAILURE
}

internal data class TelemetryBatchAttempt(
    val kind: TelemetryBatchAttemptKind,
    val response: ParsedTelemetryBatchResponse? = null
)

internal fun telemetryBatchCapabilityFromMetadata(
    metadata: ServerMetadata?
): TelemetryBatchCapability {
    val maxSamples = metadata?.telemetryBatchMaxSamples
    return if (maxSamples != null && maxSamples > 0) {
        TelemetryBatchCapability(
            kind = TelemetryBatchCapabilityKind.SUPPORTED,
            maxSamples = maxSamples
        )
    } else {
        TelemetryBatchCapability(
            kind = TelemetryBatchCapabilityKind.UNSUPPORTED
        )
    }
}

internal fun classifyTelemetryBatchHttpFailure(
    responseCode: Int,
    errorBody: String,
    client: ClientBuildIdentity
): TelemetryBatchAttemptKind {
    return when {
        responseCode == 404 ||
            responseCode == 405 ||
            responseCode == 501 -> {
            TelemetryBatchAttemptKind.UNSUPPORTED
        }

        responseCode == 413 -> {
            TelemetryBatchAttemptKind.PAYLOAD_TOO_LARGE
        }

        shouldTreatAsClientUpdateRequired(
            responseCode,
            errorBody,
            client
        ) -> {
            TelemetryBatchAttemptKind.CLIENT_UPDATE_REQUIRED
        }

        responseCode == 408 ||
            responseCode == 429 ||
            responseCode in 500..599 -> {
            TelemetryBatchAttemptKind.TEMPORARY_FAILURE
        }

        else -> TelemetryBatchAttemptKind.OTHER_FAILURE
    }
}

internal fun reducedTelemetryBatchLimitAfter413(
    attemptedSize: Int,
    refreshedCapability: TelemetryBatchCapability
): Int? {
    if (attemptedSize <= 1) return null

    return refreshedCapability.maxSamples
        ?.takeIf { it < attemptedSize }
        ?: (attemptedSize / 2).coerceAtLeast(1)
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

internal enum class TelemetryUploadScheduleKind {
    LIVE_WAKEUP,
    RECOVERY,
    CONTINUATION
}

internal fun telemetryUploadExistingWorkPolicy(
    kind: TelemetryUploadScheduleKind
): ExistingWorkPolicy {
    return when (kind) {
        TelemetryUploadScheduleKind.LIVE_WAKEUP -> ExistingWorkPolicy.KEEP
        TelemetryUploadScheduleKind.RECOVERY,
        TelemetryUploadScheduleKind.CONTINUATION -> ExistingWorkPolicy.APPEND_OR_REPLACE
    }
}

internal fun shouldEnqueueTelemetryUpload(uploadablePendingCount: Long): Boolean {
    return uploadablePendingCount > 0L
}

internal const val TELEMETRY_BACKGROUND_SLICE_MS = 5 * 60_000L

internal fun shouldYieldTelemetryUpload(elapsedMs: Long): Boolean {
    return elapsedMs >= TELEMETRY_BACKGROUND_SLICE_MS
}

internal fun shouldSuppressTelemetryUploadEnqueue(
    context: Context,
    serverUrl: String,
    client: ClientBuildIdentity
): Boolean {
    if (serverUrl.isBlank()) return false
    return ClientCompatibilityBlockStore.isBlocked(
        context = context,
        serverUrl = serverUrl,
        versionCode = client.versionCode
    )
}

internal fun getTelemetryUploadPage(
    db: TrackingDbHelper,
    afterLocalId: Long,
    limit: Int
): List<PendingTrackingSample> {
    require(limit > 0)

    val result = mutableListOf<PendingTrackingSample>()

    db.readableDatabase.rawQuery(
        """
        SELECT
            samples.id,
            samples.sequence_id,
            samples.timestamp,
            samples.boat_name,
            samples.captain_name,
            samples.hull_color,
            samples.sail_number,
            samples.yardstick,
            samples.boat_type,
            samples.lat,
            samples.lon,
            samples.accuracy,
            samples.cog,
            samples.sog,
            samples.accel_x,
            samples.accel_y,
            samples.accel_z,
            samples.gyro_x,
            samples.gyro_y,
            samples.gyro_z,
            samples.battery_percent,
            samples.battery_charging,
            samples.tracking_profile,
            samples.utc_offset_minutes,
            contexts.id,
            contexts.server_url,
            contexts.access_identifier,
            contexts.access_secret,
            contexts.created_at,
            contexts.last_used_at
        FROM tracking_samples AS samples
        INNER JOIN access_contexts AS contexts
            ON contexts.id = samples.access_context_id
        WHERE samples.uploaded = 0
          AND samples.id > ?
        ORDER BY samples.id ASC
        LIMIT ?
        """.trimIndent(),
        arrayOf(afterLocalId.toString(), limit.toString())
    ).use { cursor ->
        while (cursor.moveToNext()) {
            val accessContext = AccessContext(
                id = cursor.getLong(24),
                serverUrl = cursor.getString(25),
                accessIdentifier = cursor.getString(26),
                accessSecret = cursor.getString(27),
                createdAt = cursor.getLong(28),
                lastUsedAt = cursor.getLong(29)
            )

            result += PendingTrackingSample(
                localId = cursor.getLong(0),
                accessContext = accessContext,
                sequenceId = cursor.getLong(1),
                timestamp = cursor.getString(2),
                boatName = cursor.getString(3),
                captainName = cursor.getString(4),
                hullColor = cursor.getString(5),
                sailNumber = cursor.getString(6),
                yardstick = cursor.getDouble(7),
                boatType = cursor.getString(8),
                lat = cursor.getDouble(9),
                lon = cursor.getDouble(10),
                accuracy = cursor.getFloat(11),
                cog = cursor.getFloat(12),
                sog = cursor.getFloat(13),
                accelX = cursor.getFloat(14),
                accelY = cursor.getFloat(15),
                accelZ = cursor.getFloat(16),
                gyroX = cursor.getFloat(17),
                gyroY = cursor.getFloat(18),
                gyroZ = cursor.getFloat(19),
                batteryPercent = if (cursor.isNull(20)) null else cursor.getInt(20),
                batteryCharging = if (cursor.isNull(21)) null else cursor.getInt(21) != 0,
                trackingProfile = if (cursor.isNull(22)) null else cursor.getString(22),
                utcOffsetMinutes = if (cursor.isNull(23)) null else cursor.getInt(23)
            )
        }
    }

    return result
}

internal fun buildTelemetryUploadPayload(
    sample: PendingTrackingSample,
    client: ClientBuildIdentity
): JSONObject = JSONObject().apply {
    put("sequence_id", sample.sequenceId)
    put("timestamp", sample.timestamp)
    sample.utcOffsetMinutes?.let { put("utc_offset_minutes", it) }
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
    sample.batteryPercent?.let { put("battery_percent", it) }
    sample.batteryCharging?.let { put("battery_charging", it) }
    sample.trackingProfile?.let { put("tracking_profile", it) }
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
    internal const val UNIQUE_WORK_NAME = "regatta-telemetry-upload"
    private const val RACE_SETUP_PREFS_NAME = "race_setup"
    private const val RACE_SERVER_KEY = "race_server"
    internal const val AFTER_LOCAL_ID_KEY = "after_local_id"
    internal const val SHOW_RECOVERY_NOTIFICATION_KEY =
        "show_recovery_notification"

    internal fun buildRequest(
        afterLocalId: Long = 0L,
        showRecoveryNotification: Boolean = false
    ): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        return OneTimeWorkRequest.Builder(TelemetryUploadWorker::class.java)
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    AFTER_LOCAL_ID_KEY to afterLocalId,
                    SHOW_RECOVERY_NOTIFICATION_KEY to showRecoveryNotification
                )
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()
    }

    fun enqueueWakeup(context: Context) {
        TelemetryUploadStatusStore.write(context, TelemetryUploadStatusStore.WAITING)

        if (context is RegattaTrackingService) {
            val appContext = context.applicationContext
            val serverUrl = appContext
                .getSharedPreferences(RACE_SETUP_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(RACE_SERVER_KEY, "")
                .orEmpty()
            if (
                shouldSuppressTelemetryUploadEnqueue(
                    context = appContext,
                    serverUrl = serverUrl,
                    client = currentClientBuildIdentity()
                )
            ) {
                return
            }
        }

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                telemetryUploadExistingWorkPolicy(
                    TelemetryUploadScheduleKind.LIVE_WAKEUP
                ),
                buildRequest()
            )
    }

    fun enqueueRecoveryIfNeeded(context: Context): Operation? {
        return enqueueSerialRecoveryIfNeeded(context)
    }

    fun enqueueShutdownHandoffIfNeeded(context: Context): Operation? {
        return enqueueSerialRecoveryIfNeeded(context)
    }

    internal fun appendContinuation(
        context: Context,
        afterLocalId: Long,
        showRecoveryNotification: Boolean
    ): Operation {
        TelemetryUploadStatusStore.write(context, TelemetryUploadStatusStore.WAITING)
        return WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                telemetryUploadExistingWorkPolicy(
                    TelemetryUploadScheduleKind.CONTINUATION
                ),
                buildRequest(
                    afterLocalId = afterLocalId,
                    showRecoveryNotification = showRecoveryNotification
                )
            )
    }

    private fun enqueueSerialRecoveryIfNeeded(context: Context): Operation? {
        val appContext = context.applicationContext
        val db = TrackingDbHelper(appContext)
        val uploadablePendingCount = try {
            db.countUploadablePendingSamples()
        } finally {
            db.close()
        }

        if (!shouldEnqueueTelemetryUpload(uploadablePendingCount)) {
            TelemetryUploadStatusStore.write(
                appContext,
                TelemetryUploadStatusStore.ALL_SENT
            )
            cancelTelemetryRecoveryNotification(appContext)
            return null
        }

        TelemetryUploadStatusStore.write(
            appContext,
            TelemetryUploadStatusStore.WAITING
        )
        showTelemetryRecoveryNotification(
            context = appContext,
            remaining = uploadablePendingCount
        )
        return WorkManager.getInstance(appContext)
            .enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                telemetryUploadExistingWorkPolicy(
                    TelemetryUploadScheduleKind.RECOVERY
                ),
                buildRequest(showRecoveryNotification = true)
            )
    }
}

private const val TELEMETRY_RECOVERY_NOTIFICATION_CHANNEL_ID =
    "regatta_telemetry_upload_channel"
private const val TELEMETRY_RECOVERY_NOTIFICATION_ID = 1002

internal fun showTelemetryRecoveryNotification(
    context: Context,
    remaining: Long
) {
    val appContext = context.applicationContext
    val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    notificationManager.createNotificationChannel(
        NotificationChannel(
            TELEMETRY_RECOVERY_NOTIFICATION_CHANNEL_ID,
            appContext.getString(R.string.telemetry_upload_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
    )

    val locale = appContext.resources.configuration.locales[0]
    val numberFormat = java.text.NumberFormat.getIntegerInstance(locale)
    val progressText = appContext.getString(
        R.string.telemetry_upload_notification_remaining,
        numberFormat.format(remaining.coerceAtLeast(0L))
    )

    val launchIntent = appContext.packageManager
        .getLaunchIntentForPackage(appContext.packageName)
    val contentIntent = launchIntent?.let {
        PendingIntent.getActivity(
            appContext,
            TELEMETRY_RECOVERY_NOTIFICATION_ID,
            it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    val notification = NotificationCompat.Builder(
        appContext,
        TELEMETRY_RECOVERY_NOTIFICATION_CHANNEL_ID
    )
        .setContentTitle(
            appContext.getString(R.string.telemetry_upload_notification_title)
        )
        .setContentText(progressText)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .apply {
            contentIntent?.let { setContentIntent(it) }
        }
        .build()

    try {
        notificationManager.notify(
            TELEMETRY_RECOVERY_NOTIFICATION_ID,
            notification
        )
    } catch (e: SecurityException) {
        Log.w(
            "TelemetryUploadWorker",
            "Recovery notification permission unavailable",
            e
        )
    }
}

internal fun cancelTelemetryRecoveryNotification(context: Context) {
    val notificationManager =
        context.applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.cancel(TELEMETRY_RECOVERY_NOTIFICATION_ID)
}

class TelemetryUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    private val db = TrackingDbHelper(appContext)
    private val localStatusPrefsName = "regatta_local_status"

    private var uploadStartedAtElapsedMs = 0L
    private val showRecoveryNotification =
        inputData.getBoolean(
            TelemetryUploadScheduler.SHOW_RECOVERY_NOTIFICATION_KEY,
            false
        )

    internal var elapsedRealtimeProvider: () -> Long = {
        SystemClock.elapsedRealtime()
    }
    internal var continuationPersister: (Long) -> Unit = { afterLocalId ->
        TelemetryUploadScheduler.appendContinuation(
            context = applicationContext,
            afterLocalId = afterLocalId,
            showRecoveryNotification = showRecoveryNotification
        ).result.get()
    }
    override fun doWork(): Result {
        uploadStartedAtElapsedMs = elapsedRealtimeProvider()

        if (showRecoveryNotification) {
            updateRecoveryNotification()
        }

        val client = currentClientBuildIdentity()
        var afterLocalId = inputData.getLong(
            TelemetryUploadScheduler.AFTER_LOCAL_ID_KEY,
            0L
        )
        var nextPageLimit = DISCOVERY_PAGE_SIZE
        val batchCapabilities = mutableMapOf<Long, TelemetryBatchCapability>()

        while (true) {
            var pendingSamples = getTelemetryUploadPage(
                db = db,
                afterLocalId = afterLocalId,
                limit = nextPageLimit
            )

            if (pendingSamples.isEmpty()) {
                break
            }

            TelemetryUploadStatusStore.write(
                applicationContext,
                TelemetryUploadStatusStore.ACTIVE
            )

            val firstSample = pendingSamples.first()
            val accessContext = firstSample.accessContext

            val elapsedMs =
                (elapsedRealtimeProvider() - uploadStartedAtElapsedMs)
                    .coerceAtLeast(0L)
            if (
                shouldYieldTelemetryUpload(elapsedMs = elapsedMs)
            ) {
                return handOffToContinuation(afterLocalId)
            }

            if (
                ClientCompatibilityBlockStore.isBlocked(
                    context = applicationContext,
                    serverUrl = accessContext.serverUrl,
                    versionCode = client.versionCode
                )
            ) {
                if (nextPageLimit < LOCAL_SCAN_PAGE_SIZE) {
                    pendingSamples = getTelemetryUploadPage(
                        db = db,
                        afterLocalId = afterLocalId,
                        limit = LOCAL_SCAN_PAGE_SIZE
                    )
                }

                val blockedPrefix = pendingSamples.takeWhile {
                    it.accessContext.serverUrl == accessContext.serverUrl
                }
                afterLocalId = blockedPrefix.last().localId
                nextPageLimit = LOCAL_SCAN_PAGE_SIZE
                continue
            }

            var sameAccessPrefix = pendingSamples.takeWhile {
                it.accessContext.id == accessContext.id
            }
            val cachedCapability = batchCapabilities[accessContext.id]

            if (cachedCapability == null && sameAccessPrefix.size < 2) {
                when (uploadSampleBlocking(firstSample, client)) {
                    TelemetryUploadAttemptResult.SUCCESS -> {
                        db.markUploaded(firstSample.localId)
                        updateRecoveryNotification()
                    }

                    TelemetryUploadAttemptResult.TEMPORARY_FAILURE -> {
                        return temporaryFailure()
                    }

                    TelemetryUploadAttemptResult.CLIENT_UPDATE_REQUIRED,
                    TelemetryUploadAttemptResult.OTHER_FAILURE -> {
                        // Keep the row pending. CLIENT_UPDATE_REQUIRED also blocks
                        // later requests to this server/version.
                    }
                }

                afterLocalId = firstSample.localId
                nextPageLimit = DISCOVERY_PAGE_SIZE
                continue
            }

            val capability = cachedCapability
                ?: fetchTelemetryBatchCapabilityBlocking(accessContext).also { discovered ->
                    if (discovered.kind != TelemetryBatchCapabilityKind.TEMPORARY_FAILURE) {
                        batchCapabilities[accessContext.id] = discovered
                    }
                }

            when (capability.kind) {
                TelemetryBatchCapabilityKind.TEMPORARY_FAILURE -> {
                    return temporaryFailure()
                }

                TelemetryBatchCapabilityKind.UNSUPPORTED -> {
                    if (nextPageLimit != LEGACY_PAGE_SIZE) {
                        pendingSamples = getTelemetryUploadPage(
                            db = db,
                            afterLocalId = afterLocalId,
                            limit = LEGACY_PAGE_SIZE
                        )
                        sameAccessPrefix = pendingSamples.takeWhile {
                            it.accessContext.id == accessContext.id
                        }
                    }

                    val sequentialOutcome = uploadSequentialPrefix(
                        samples = sameAccessPrefix,
                        client = client
                    )
                    if (sequentialOutcome.uploadedCount > 0) {
                        updateRecoveryNotification()
                    }

                    if (
                        sequentialOutcome.result ==
                        TelemetryUploadAttemptResult.TEMPORARY_FAILURE
                    ) {
                        return temporaryFailure()
                    }

                    afterLocalId = sameAccessPrefix.last().localId
                    nextPageLimit = LEGACY_PAGE_SIZE
                }

                TelemetryBatchCapabilityKind.SUPPORTED -> {
                    val maxSamples = requireNotNull(capability.maxSamples)
                    if (nextPageLimit != maxSamples) {
                        pendingSamples = getTelemetryUploadPage(
                            db = db,
                            afterLocalId = afterLocalId,
                            limit = maxSamples
                        )
                        sameAccessPrefix = pendingSamples.takeWhile {
                            it.accessContext.id == accessContext.id
                        }
                    }

                    val batch = sameAccessPrefix.take(maxSamples)
                    val attempt = uploadBatchBlocking(
                        samples = batch,
                        client = client
                    )

                    when (attempt.kind) {
                        TelemetryBatchAttemptKind.PROCESSED -> {
                            val response = requireNotNull(attempt.response)
                            db.markUploaded(response.acceptedLocalIds)
                            if (response.acceptedLocalIds.isNotEmpty()) {
                                updateRecoveryNotification()
                            }

                            if (response.clientUpdateRequired) {
                                markClientUpdateRequired(
                                    accessContext = accessContext,
                                    client = client,
                                    responseCode = 426
                                )
                            } else if (response.acceptedLocalIds.isNotEmpty()) {
                                clearClientCompatibilityBlock(
                                    accessContext = accessContext,
                                    client = client
                                )
                            }

                            response.firstOtherRejectionCode?.let { code ->
                                publishDebugError(
                                    applicationContext.getString(
                                        R.string.upload_error_code,
                                        200,
                                        code
                                    )
                                )
                            }

                            afterLocalId = batch.last().localId
                            nextPageLimit = maxSamples

                            if (response.hasTemporaryRejection) {
                                return temporaryFailure()
                            }
                        }

                        TelemetryBatchAttemptKind.UNSUPPORTED -> {
                            batchCapabilities[accessContext.id] =
                                TelemetryBatchCapability(
                                    kind = TelemetryBatchCapabilityKind.UNSUPPORTED
                                )
                            nextPageLimit = LEGACY_PAGE_SIZE
                        }

                        TelemetryBatchAttemptKind.PAYLOAD_TOO_LARGE -> {
                            if (batch.size <= 1) {
                                return temporaryFailure()
                            }

                            val refreshed = fetchTelemetryBatchCapabilityBlocking(
                                accessContext
                            )
                            if (
                                refreshed.kind ==
                                TelemetryBatchCapabilityKind.TEMPORARY_FAILURE
                            ) {
                                return temporaryFailure()
                            }

                            val reducedLimit = reducedTelemetryBatchLimitAfter413(
                                attemptedSize = batch.size,
                                refreshedCapability = refreshed
                            ) ?: return temporaryFailure()

                            batchCapabilities[accessContext.id] =
                                TelemetryBatchCapability(
                                    kind = TelemetryBatchCapabilityKind.SUPPORTED,
                                    maxSamples = reducedLimit
                                )
                            nextPageLimit = reducedLimit
                        }

                        TelemetryBatchAttemptKind.CLIENT_UPDATE_REQUIRED -> {
                            markClientUpdateRequired(
                                accessContext = accessContext,
                                client = client,
                                responseCode = 426
                            )
                            afterLocalId = batch.last().localId
                            nextPageLimit = LOCAL_SCAN_PAGE_SIZE
                        }

                        TelemetryBatchAttemptKind.TEMPORARY_FAILURE -> {
                            return temporaryFailure()
                        }

                        TelemetryBatchAttemptKind.OTHER_FAILURE -> {
                            afterLocalId = batch.last().localId
                            nextPageLimit = maxSamples
                        }
                    }
                }
            }
        }

        TelemetryUploadStatusStore.write(
            applicationContext,
            if (db.hasUploadablePendingSamples()) {
                TelemetryUploadStatusStore.WAITING
            } else {
                TelemetryUploadStatusStore.ALL_SENT
            }
        )
        if (showRecoveryNotification) {
            cancelTelemetryRecoveryNotification(applicationContext)
        }
        return Result.success()
    }

    private fun updateRecoveryNotification() {
        if (!showRecoveryNotification) return

        showTelemetryRecoveryNotification(
            context = applicationContext,
            remaining = db.countUploadablePendingSamples()
        )
    }

    private fun handOffToContinuation(afterLocalId: Long): Result {
        return try {
            continuationPersister(afterLocalId)
            TelemetryUploadStatusStore.write(
                applicationContext,
                TelemetryUploadStatusStore.WAITING
            )
            Result.success()
        } catch (e: Exception) {
            Log.w(
                TELEMETRY_UPLOAD_LOG_TAG,
                "Could not persist telemetry continuation; retrying current work",
                e
            )
            temporaryFailure()
        }
    }

    private data class SequentialUploadOutcome(
        val result: TelemetryUploadAttemptResult,
        val uploadedCount: Int
    )

    private fun uploadSequentialPrefix(
        samples: List<PendingTrackingSample>,
        client: ClientBuildIdentity
    ): SequentialUploadOutcome {
        var uploadedCount = 0

        for (sample in samples) {
            if (
                ClientCompatibilityBlockStore.isBlocked(
                    context = applicationContext,
                    serverUrl = sample.accessContext.serverUrl,
                    versionCode = client.versionCode
                )
            ) {
                break
            }

            when (val result = uploadSampleBlocking(sample, client)) {
                TelemetryUploadAttemptResult.SUCCESS -> {
                    db.markUploaded(sample.localId)
                    uploadedCount += 1
                }

                TelemetryUploadAttemptResult.TEMPORARY_FAILURE -> {
                    return SequentialUploadOutcome(
                        result = result,
                        uploadedCount = uploadedCount
                    )
                }

                TelemetryUploadAttemptResult.CLIENT_UPDATE_REQUIRED -> {
                    break
                }

                TelemetryUploadAttemptResult.OTHER_FAILURE -> {
                    // Keep this row pending and continue with later rows.
                }
            }
        }

        return SequentialUploadOutcome(
            result = TelemetryUploadAttemptResult.SUCCESS,
            uploadedCount = uploadedCount
        )
    }

    private fun fetchTelemetryBatchCapabilityBlocking(
        accessContext: AccessContext
    ): TelemetryBatchCapability {
        val endpoint = buildServerMetadataUrl(accessContext.serverUrl)
            ?: return TelemetryBatchCapability(
                kind = TelemetryBatchCapabilityKind.UNSUPPORTED
            )
        val headers = buildServerMetadataHeaders(
            eventName = accessContext.accessIdentifier,
            sharedSecret = accessContext.accessSecret
        ) ?: return TelemetryBatchCapability(
            kind = TelemetryBatchCapabilityKind.UNSUPPORTED
        )

        var connection: HttpURLConnection? = null
        var serverResponded = false

        return try {
            connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            headers.forEach { (name, value) ->
                connection.setRequestProperty(name, value)
            }

            val responseCode = connection.responseCode
            serverResponded = true
            ServerConnectionStateStore.markReachable(
                applicationContext,
                accessContext.serverUrl
            )

            when {
                responseCode in 200..299 -> {
                    val body = connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }
                    val metadata = runCatching {
                        parseServerMetadata(body)
                    }.getOrNull()

                    telemetryBatchCapabilityFromMetadata(metadata)
                }

                responseCode == 408 ||
                    responseCode == 429 ||
                    responseCode in 500..599 -> {
                    TelemetryBatchCapability(
                        kind = TelemetryBatchCapabilityKind.TEMPORARY_FAILURE
                    )
                }

                else -> {
                    TelemetryBatchCapability(
                        kind = TelemetryBatchCapabilityKind.UNSUPPORTED
                    )
                }
            }
        } catch (_: Exception) {
            if (!serverResponded) {
                ServerConnectionStateStore.markNoConnection(
                    applicationContext,
                    accessContext.serverUrl
                )
            }
            TelemetryBatchCapability(
                kind = TelemetryBatchCapabilityKind.TEMPORARY_FAILURE
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun uploadBatchBlocking(
        samples: List<PendingTrackingSample>,
        client: ClientBuildIdentity
    ): TelemetryBatchAttempt {
        require(samples.isNotEmpty())

        val accessContext = samples.first().accessContext
        require(samples.all { it.accessContext.id == accessContext.id })

        var connection: HttpURLConnection? = null
        var serverResponded = false

        return try {
            val json = buildTelemetryBatchUploadPayload(
                samples = samples,
                client = client
            )

            connection = URL(buildBatchIngestUrl(accessContext))
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "x-event-name",
                accessContext.accessIdentifier
            )
            connection.setRequestProperty(
                "x-shared-secret",
                accessContext.accessSecret
            )
            connection.setRequestProperty(
                "x-api-version",
                RegattaTrackingService.API_VERSION
            )

            connection.outputStream.use { outputStream ->
                outputStream.write(
                    json.toString().toByteArray(Charsets.UTF_8)
                )
            }

            val responseCode = connection.responseCode
            serverResponded = true
            ServerConnectionStateStore.markReachable(
                applicationContext,
                accessContext.serverUrl
            )

            if (responseCode in 200..299) {
                val responseBody = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }
                val parsed = parseTelemetryBatchUploadResponse(
                    body = responseBody,
                    samples = samples
                )

                if (parsed == null) {
                    publishDebugError(
                        applicationContext.getString(
                            R.string.upload_error_code,
                            responseCode,
                            "invalid batch response"
                        )
                    )
                    TelemetryBatchAttempt(
                        kind = TelemetryBatchAttemptKind.TEMPORARY_FAILURE
                    )
                } else {
                    TelemetryBatchAttempt(
                        kind = TelemetryBatchAttemptKind.PROCESSED,
                        response = parsed
                    )
                }
            } else {
                val errorBody = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: ""

                val kind = classifyTelemetryBatchHttpFailure(
                    responseCode = responseCode,
                    errorBody = errorBody,
                    client = client
                )

                if (
                    kind != TelemetryBatchAttemptKind.UNSUPPORTED &&
                    kind != TelemetryBatchAttemptKind.PAYLOAD_TOO_LARGE
                ) {
                    publishDebugError(
                        applicationContext.getString(
                            R.string.upload_error_code,
                            responseCode,
                            errorBody.take(200)
                        )
                    )
                }

                TelemetryBatchAttempt(kind = kind)
            }
        } catch (e: Exception) {
            if (!serverResponded) {
                ServerConnectionStateStore.markNoConnection(
                    applicationContext,
                    accessContext.serverUrl
                )
            }
            publishDebugError(
                applicationContext.getString(
                    R.string.upload_exception,
                    e.message ?: ""
                )
            )
            TelemetryBatchAttempt(
                kind = TelemetryBatchAttemptKind.TEMPORARY_FAILURE
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun temporaryFailure(): Result {
        TelemetryUploadStatusStore.write(
            applicationContext,
            TelemetryUploadStatusStore.TEMPORARY_ERROR
        )
        return Result.retry()
    }

    private fun markClientUpdateRequired(
        accessContext: AccessContext,
        client: ClientBuildIdentity,
        responseCode: Int
    ) {
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

    private fun clearClientCompatibilityBlock(
        accessContext: AccessContext,
        client: ClientBuildIdentity
    ) {
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

    private fun uploadSampleBlocking(
        sample: PendingTrackingSample,
        client: ClientBuildIdentity
    ): TelemetryUploadAttemptResult {
        val accessContext = sample.accessContext

        var serverResponded = false
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
            serverResponded = true
            ServerConnectionStateStore.markReachable(applicationContext, accessContext.serverUrl)
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
            if (!serverResponded) {
                ServerConnectionStateStore.markNoConnection(applicationContext, accessContext.serverUrl)
            }
            publishDebugError(applicationContext.getString(R.string.upload_exception, e.message ?: ""))
            TelemetryUploadAttemptResult.TEMPORARY_FAILURE
        }
    }

    private fun buildIngestUrl(accessContext: AccessContext): String {
        return "${accessContext.serverUrl.trimEnd('/')}/ingest"
    }

    private fun buildBatchIngestUrl(accessContext: AccessContext): String {
        return "${accessContext.serverUrl.trimEnd('/')}/ingest/batch"
    }

    private fun publishDebugError(message: String) {
        applicationContext
            .getSharedPreferences(localStatusPrefsName, Context.MODE_PRIVATE)
            .edit()
            .putString("debug_error_text", message)
            .apply()
    }

    private companion object {
        const val TELEMETRY_UPLOAD_LOG_TAG = "TelemetryUploadWorker"
        const val DISCOVERY_PAGE_SIZE = 2
        const val LEGACY_PAGE_SIZE = 50
        const val LOCAL_SCAN_PAGE_SIZE = 1000
    }
}
