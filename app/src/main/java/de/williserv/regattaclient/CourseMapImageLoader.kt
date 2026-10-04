package de.williserv.regattaclient

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL

internal data class CourseMapImageLoadResult(
    val bitmap: Bitmap?,
    val statusCode: Int? = null,
    val errorBody: String = "",
    val exceptionMessage: String? = null,
    val invalidPng: Boolean = false
)

internal fun loadCourseMapBitmapBlocking(
    mapImageUrl: String,
    apiVersion: String,
    sharedSecret: String
): CourseMapImageLoadResult {
    var connection: HttpURLConnection? = null

    return try {
        connection = URL(mapImageUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.setRequestProperty("Accept", "image/png")
        connection.setRequestProperty("x-shared-secret", sharedSecret)
        connection.setRequestProperty("x-api-version", apiVersion)

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            CourseMapImageLoadResult(
                bitmap = null,
                statusCode = responseCode,
                errorBody = errorBody
            )
        } else {
            val bitmap = connection.inputStream.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
            if (bitmap == null) {
                CourseMapImageLoadResult(
                    bitmap = null,
                    statusCode = responseCode,
                    invalidPng = true
                )
            } else {
                CourseMapImageLoadResult(
                    bitmap = bitmap,
                    statusCode = responseCode
                )
            }
        }
    } catch (e: Exception) {
        CourseMapImageLoadResult(
            bitmap = null,
            exceptionMessage = e.message.orEmpty()
        )
    } finally {
        connection?.disconnect()
    }
}
