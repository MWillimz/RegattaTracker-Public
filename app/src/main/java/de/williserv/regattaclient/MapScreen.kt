package de.williserv.regattaclient

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.MaterialTheme

@Composable
fun MapScreen(
    mapImageUrl: String,
    apiVersion: String,
    sharedSecret: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val bitmapState = remember { mutableStateOf<Bitmap?>(null) }
    val errorState = remember { mutableStateOf<String?>(null) }
    val loadingState = remember { mutableStateOf(true) }

    LaunchedEffect(mapImageUrl, apiVersion, sharedSecret) {
        loadingState.value = true
        errorState.value = null
        bitmapState.value = null

        val result = loadMapBitmap(
            mapImageUrl = mapImageUrl,
            apiVersion = apiVersion,
            sharedSecret = sharedSecret
        )

        if (result.bitmap != null) {
            bitmapState.value = result.bitmap
        } else {
            errorState.value = result.error ?: "Map could not be loaded"
        }

        loadingState.value = false
    }

    Column(
        modifier = modifier
            .padding(20.dp)
            .fillMaxSize()
    ) {
        Text(
            text = "Map",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 16.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = bitmapState.value
                val error = errorState.value

                when {
                    bitmap != null -> {
                        val scale = remember { mutableStateOf(1f) }
                        val offset = remember { mutableStateOf(Offset.Zero) }

                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Course map",
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val newScale = (scale.value * zoom).coerceIn(1f, 6f)
                                        scale.value = newScale

                                        if (newScale > 1f) {
                                            offset.value += pan
                                        } else {
                                            offset.value = Offset.Zero
                                        }
                                    }
                                }
                                .graphicsLayer(
                                    scaleX = scale.value,
                                    scaleY = scale.value,
                                    translationX = offset.value.x,
                                    translationY = offset.value.y
                                ),
                            contentScale = ContentScale.Fit
                        )
                    }

                    loadingState.value -> {
                        Text(
                            text = "Loading map...",
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    error != null -> {
                        Text(
                            text = error,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(18.dp)
                        )
                    }

                    else -> {
                        Text(
                            text = "Map unavailable",
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            Text("Back")
        }
    }
}

private data class MapLoadResult(
    val bitmap: Bitmap?,
    val error: String?
)

private suspend fun loadMapBitmap(
    mapImageUrl: String,
    apiVersion: String,
    sharedSecret: String
): MapLoadResult {
    return withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null

        try {
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
                    ?: ""

                return@withContext MapLoadResult(
                    bitmap = null,
                    error = "Map error $responseCode: ${errorBody.take(160)}"
                )
            }

            val bitmap = connection.inputStream.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }

            if (bitmap == null) {
                MapLoadResult(
                    bitmap = null,
                    error = "Map response is not a valid PNG"
                )
            } else {
                MapLoadResult(
                    bitmap = bitmap,
                    error = null
                )
            }
        } catch (e: Exception) {
            MapLoadResult(
                bitmap = null,
                error = "Map load failed: ${e.message}"
            )
        } finally {
            connection?.disconnect()
        }
    }
}
