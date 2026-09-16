package de.williserv.regattaclient

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

private enum class CourseOverlayKind {
    START,
    FINISH,
    MARK
}

private data class CourseOverlayGeoPoint(
    val lat: Double,
    val lon: Double,
    val kind: CourseOverlayKind,
    val inactive: Boolean = false
)

private data class MapSnapshotContext(
    val server: String,
    val eventName: String
)

@Composable
fun MapScreen(
    mapImageUrl: String,
    apiVersion: String,
    sharedSecret: String,
    modifier: Modifier = Modifier,
    fallbackMapImageUrl: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val bitmapState = remember { mutableStateOf<Bitmap?>(null) }
    val errorState = remember { mutableStateOf<String?>(null) }
    val loadingState = remember { mutableStateOf(true) }
    val overlayEnabledState = remember { mutableStateOf(false) }
    val latestLocation = remember { mutableStateOf<android.location.Location?>(null) }
    val containerSize = remember { mutableStateOf(IntSize.Zero) }
    val scale = remember { mutableStateOf(1f) }
    val offset = remember { mutableStateOf(Offset.Zero) }

    val snapshotContext = remember(mapImageUrl) { parseMapSnapshotContext(mapImageUrl) }
    val snapshot = remember(mapImageUrl, sharedSecret) {
        snapshotContext?.let {
            RaceEventSnapshotStore.loadMatching(
                context = context,
                server = it.server,
                event = it.eventName,
                secret = sharedSecret
            )
        }
    }
    val viewport = snapshot?.courseMapViewport
    val overlayPoints = remember(snapshot?.courseJson, snapshot?.courseShortened) {
        parseCourseOverlayPoints(
            courseJson = snapshot?.courseJson.orEmpty(),
            courseShortened = snapshot?.courseShortened == true
        )
    }
    val generationBoundUrl = remember(mapImageUrl, viewport?.generationId) {
        if (viewport == null) mapImageUrl else bindCourseMapGeneration(mapImageUrl, viewport.generationId)
    }

    val mapCouldNotBeLoaded = stringResource(R.string.map_could_not_be_loaded)

    androidx.compose.runtime.DisposableEffect(context, snapshotContext) {
        if (snapshotContext == null) {
            onDispose { }
        } else {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val permissionGranted =
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!permissionGranted) {
                onDispose { }
            } else {
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: android.location.Location) {
                        latestLocation.value = location
                    }
                }

                val providers = listOf(
                    android.location.LocationManager.GPS_PROVIDER,
                    android.location.LocationManager.NETWORK_PROVIDER
                ).filter { provider ->
                    try {
                        locationManager.isProviderEnabled(provider)
                    } catch (_: Exception) {
                        false
                    }
                }

                try {
                    providers.forEach { provider ->
                        locationManager.requestLocationUpdates(provider, 1_000L, 0f, listener)
                    }
                } catch (_: SecurityException) {
                    // Permission may have been revoked between the check and registration.
                }

                onDispose {
                    try {
                        locationManager.removeUpdates(listener)
                    } catch (_: SecurityException) {
                        // Nothing to clean up if permission was revoked meanwhile.
                    }
                }
            }
        }
    }

    LaunchedEffect(generationBoundUrl, fallbackMapImageUrl, apiVersion, sharedSecret, viewport) {
        loadingState.value = true
        errorState.value = null
        bitmapState.value = null
        overlayEnabledState.value = false
        scale.value = 1f
        offset.value = Offset.Zero

        val primaryResult = loadMapBitmap(
            context = context,
            mapImageUrl = generationBoundUrl,
            apiVersion = apiVersion,
            sharedSecret = sharedSecret
        )

        val generationFallback = viewport != null &&
            generationBoundUrl != mapImageUrl &&
            primaryResult.statusCode == 404

        val result = when {
            generationFallback -> {
                loadMapBitmap(
                    context = context,
                    mapImageUrl = mapImageUrl,
                    apiVersion = apiVersion,
                    sharedSecret = sharedSecret
                )
            }

            shouldFallbackToCourseOverview(primaryResult.statusCode) &&
                !fallbackMapImageUrl.isNullOrBlank() &&
                fallbackMapImageUrl != generationBoundUrl -> {
                loadMapBitmap(
                    context = context,
                    mapImageUrl = fallbackMapImageUrl,
                    apiVersion = apiVersion,
                    sharedSecret = sharedSecret
                )
            }

            else -> primaryResult
        }

        if (result.bitmap != null) {
            bitmapState.value = result.bitmap
            overlayEnabledState.value =
                viewport != null &&
                !generationFallback &&
                generationBoundUrl != mapImageUrl &&
                result.bitmap.width == viewport.widthPx &&
                result.bitmap.height == viewport.heightPx
        } else {
            errorState.value = result.error ?: mapCouldNotBeLoaded
        }

        loadingState.value = false
    }

    Column(
        modifier = modifier
            .padding(20.dp)
            .fillMaxSize()
    ) {
        Text(
            text = stringResource(R.string.map),
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
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .onSizeChanged { containerSize.value = it },
                contentAlignment = Alignment.Center
            ) {
                val bitmap = bitmapState.value
                val error = errorState.value

                when {
                    bitmap != null -> {
                        val activeViewport = viewport
                        val density = LocalDensity.current
                        val anchorRadius = with(density) { 5.dp.toPx() }
                        val ownRadius = with(density) { 7.dp.toPx() }
                        val strokeWidth = with(density) { 2.dp.toPx() }
                        val startColor = MaterialTheme.colorScheme.tertiary
                        val finishColor = MaterialTheme.colorScheme.error
                        val markColor = MaterialTheme.colorScheme.secondary
                        val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
                        val ownColor = MaterialTheme.colorScheme.primary

                        Box(
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
                                )
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.course_map),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )

                            if (
                                overlayEnabledState.value &&
                                activeViewport != null &&
                                containerSize.value.width > 0 &&
                                containerSize.value.height > 0
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    overlayPoints.forEach { geoPoint ->
                                        val imagePoint = projectToCourseMap(
                                            lat = geoPoint.lat,
                                            lon = geoPoint.lon,
                                            viewport = activeViewport
                                        ) ?: return@forEach

                                        if (!isPointInsideCourseMap(imagePoint, activeViewport)) {
                                            return@forEach
                                        }

                                        val fitted = fitCourseMapPoint(
                                            point = imagePoint,
                                            imageWidth = bitmap.width,
                                            imageHeight = bitmap.height,
                                            containerWidth = containerSize.value.width,
                                            containerHeight = containerSize.value.height
                                        ) ?: return@forEach

                                        val color = when {
                                            geoPoint.inactive -> inactiveColor
                                            geoPoint.kind == CourseOverlayKind.START -> startColor
                                            geoPoint.kind == CourseOverlayKind.FINISH -> finishColor
                                            else -> markColor
                                        }

                                        drawCircle(
                                            color = color.copy(alpha = 0.8f),
                                            radius = anchorRadius,
                                            center = Offset(fitted.x.toFloat(), fitted.y.toFloat()),
                                            style = Stroke(width = strokeWidth)
                                        )
                                    }

                                    latestLocation.value?.let { location ->
                                        val imagePoint = projectToCourseMap(
                                            lat = location.latitude,
                                            lon = location.longitude,
                                            viewport = activeViewport
                                        ) ?: return@let

                                        if (!isPointInsideCourseMap(imagePoint, activeViewport)) {
                                            return@let
                                        }

                                        val fitted = fitCourseMapPoint(
                                            point = imagePoint,
                                            imageWidth = bitmap.width,
                                            imageHeight = bitmap.height,
                                            containerWidth = containerSize.value.width,
                                            containerHeight = containerSize.value.height
                                        ) ?: return@let

                                        val center = Offset(fitted.x.toFloat(), fitted.y.toFloat())
                                        drawCircle(
                                            color = Color.White.copy(alpha = 0.95f),
                                            radius = ownRadius + strokeWidth,
                                            center = center
                                        )
                                        drawCircle(
                                            color = ownColor,
                                            radius = ownRadius,
                                            center = center
                                        )
                                        drawCircle(
                                            color = Color.White,
                                            radius = ownRadius * 0.32f,
                                            center = center
                                        )
                                    }
                                }
                            }
                        }
                    }

                    loadingState.value -> {
                        Text(
                            text = stringResource(R.string.loading_map),
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
                            text = stringResource(R.string.map_unavailable),
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
            Text(stringResource(R.string.back))
        }
    }
}

private fun parseMapSnapshotContext(mapImageUrl: String): MapSnapshotContext? {
    return try {
        val url = URL(mapImageUrl)
        if (!url.path.endsWith("/course-map")) return null

        val eventName = url.query
            ?.split('&')
            ?.mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = part.substring(0, separator)
                val value = part.substring(separator + 1)
                if (key == "event_name") URLDecoder.decode(value, "UTF-8") else null
            }
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

        if (eventName.isBlank()) return null

        val parentPath = url.path.removeSuffix("/course-map").trimEnd('/')
        val server = "${url.protocol}://${url.authority}$parentPath"
        MapSnapshotContext(server = server, eventName = eventName)
    } catch (_: Exception) {
        null
    }
}

private fun parseCourseOverlayPoints(
    courseJson: String,
    courseShortened: Boolean
): List<CourseOverlayGeoPoint> {
    if (courseJson.isBlank()) return emptyList()

    return try {
        val course = JSONObject(courseJson)
        buildList {
            addLineEndpoints(course.optJSONObject("start_line"), CourseOverlayKind.START)
            addLineEndpoints(course.optJSONObject("finish_line"), CourseOverlayKind.FINISH)

            val marks = course.optJSONArray("marks")
            if (marks != null) {
                for (index in 0 until marks.length()) {
                    val mark = marks.optJSONObject(index) ?: continue
                    val point = mark.toCourseOverlayPoint(
                        kind = CourseOverlayKind.MARK,
                        inactive = courseShortened && mark.optBoolean("omit_when_shortened", false)
                    )
                    if (point != null) add(point)
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun MutableList<CourseOverlayGeoPoint>.addLineEndpoints(
    line: JSONObject?,
    kind: CourseOverlayKind
) {
    if (line == null) return
    listOf("ref", "mark").forEach { key ->
        line.optJSONObject(key)?.toCourseOverlayPoint(kind)?.let(::add)
    }
}

private fun JSONObject.toCourseOverlayPoint(
    kind: CourseOverlayKind,
    inactive: Boolean = false
): CourseOverlayGeoPoint? {
    if (!has("lat") || !has("lon")) return null
    return try {
        val lat = getDouble("lat")
        val lon = getDouble("lon")
        if (!lat.isFinite() || !lon.isFinite()) return null
        CourseOverlayGeoPoint(lat = lat, lon = lon, kind = kind, inactive = inactive)
    } catch (_: Exception) {
        null
    }
}

private data class MapLoadResult(
    val bitmap: Bitmap?,
    val error: String?,
    val statusCode: Int? = null
)

private suspend fun loadMapBitmap(
    context: Context,
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
                    error = context.getString(R.string.map_error_code, responseCode, errorBody.take(160)),
                    statusCode = responseCode
                )
            }

            val bitmap = connection.inputStream.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }

            if (bitmap == null) {
                MapLoadResult(
                    bitmap = null,
                    error = context.getString(R.string.map_invalid_png),
                    statusCode = responseCode
                )
            } else {
                MapLoadResult(
                    bitmap = bitmap,
                    error = null,
                    statusCode = responseCode
                )
            }
        } catch (e: Exception) {
            MapLoadResult(
                bitmap = null,
                error = context.getString(R.string.map_load_failed, e.message ?: ""),
                statusCode = null
            )
        } finally {
            connection?.disconnect()
        }
    }
}
