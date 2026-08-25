package de.williserv.regattaclient

import android.content.Context
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

object TelemetryUploadScheduler {
    private const val UNIQUE_WORK_NAME = "regatta-telemetry-upload"

    fun enqueue(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequest.Builder(TelemetryUploadWorker::class.java)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
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
        val pendingSamples = db.getPendingSamples(limit = 200)

        for (sample in pendingSamples) {
            val ok = uploadSampleBlocking(sample)

            if (ok) {
                db.markUploaded(sample.localId)
            }
        }

        return Result.success()
    }

    private fun uploadSampleBlocking(sample: PendingTrackingSample): Boolean {
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
            val ok = responseCode in 200..299

            if (ok) {
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
            ok
        } catch (e: Exception) {
            publishDebugError("Upload exception: ${e.message}")
            false
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
}
