package de.williserv.regattaclient

import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

data class CourseMapViewport(
    val projection: String,
    val zoom: Int,
    val leftPx: Double,
    val topPx: Double,
    val widthPx: Int,
    val heightPx: Int,
    val generationId: String
)

data class CourseMapPixelPoint(
    val x: Double,
    val y: Double
)

data class CourseMapFitRect(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
    val scale: Double
)

internal fun parseCourseMapViewportJson(raw: String?): CourseMapViewport? {
    if (raw.isNullOrBlank()) return null

    return try {
        val obj = JSONObject(raw)
        if (
            !obj.has("zoom") ||
            !obj.has("left_px") ||
            !obj.has("top_px") ||
            !obj.has("width_px") ||
            !obj.has("height_px") ||
            !obj.has("generation_id")
        ) {
            return null
        }

        val projection = obj.optString("projection", "").trim()
        val generationId = obj.optString("generation_id", "").trim()
        val zoom = obj.getInt("zoom")
        val leftPx = obj.getDouble("left_px")
        val topPx = obj.getDouble("top_px")
        val widthPx = obj.getInt("width_px")
        val heightPx = obj.getInt("height_px")

        if (
            projection != "web_mercator" ||
            generationId.isBlank() ||
            zoom < 0 ||
            widthPx <= 0 ||
            heightPx <= 0 ||
            !leftPx.isFinite() ||
            !topPx.isFinite()
        ) {
            null
        } else {
            CourseMapViewport(
                projection = projection,
                zoom = zoom,
                leftPx = leftPx,
                topPx = topPx,
                widthPx = widthPx,
                heightPx = heightPx,
                generationId = generationId
            )
        }
    } catch (_: Exception) {
        null
    }
}

internal fun projectToCourseMap(
    lat: Double,
    lon: Double,
    viewport: CourseMapViewport
): CourseMapPixelPoint? {
    if (viewport.projection != "web_mercator") return null
    if (viewport.zoom < 0 || viewport.widthPx <= 0 || viewport.heightPx <= 0) return null
    if (!lat.isFinite() || !lon.isFinite()) return null

    val clampedLat = lat.coerceIn(-85.05112878, 85.05112878)
    val sinLat = sin(Math.toRadians(clampedLat))
    val scale = 256.0 * 2.0.pow(viewport.zoom.toDouble())

    val worldX = (lon + 180.0) / 360.0 * scale
    val worldY = (
        0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * Math.PI)
    ) * scale

    return CourseMapPixelPoint(
        x = worldX - viewport.leftPx,
        y = worldY - viewport.topPx
    )
}

internal fun isPointInsideCourseMap(
    point: CourseMapPixelPoint,
    viewport: CourseMapViewport
): Boolean =
    point.x >= 0.0 &&
        point.y >= 0.0 &&
        point.x <= viewport.widthPx.toDouble() &&
        point.y <= viewport.heightPx.toDouble()

internal fun courseMapBitmapMatchesViewport(
    bitmapWidth: Int,
    bitmapHeight: Int,
    viewport: CourseMapViewport
): Boolean =
    bitmapWidth == viewport.widthPx &&
        bitmapHeight == viewport.heightPx

internal fun fitCourseMapRect(
    imageWidth: Int,
    imageHeight: Int,
    containerWidth: Int,
    containerHeight: Int
): CourseMapFitRect? {
    if (imageWidth <= 0 || imageHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) {
        return null
    }

    val fitScale = min(
        containerWidth.toDouble() / imageWidth.toDouble(),
        containerHeight.toDouble() / imageHeight.toDouble()
    )
    val displayedWidth = imageWidth * fitScale
    val displayedHeight = imageHeight * fitScale

    return CourseMapFitRect(
        left = (containerWidth - displayedWidth) / 2.0,
        top = (containerHeight - displayedHeight) / 2.0,
        width = displayedWidth,
        height = displayedHeight,
        scale = fitScale
    )
}

internal fun fitCourseMapPoint(
    point: CourseMapPixelPoint,
    imageWidth: Int,
    imageHeight: Int,
    containerWidth: Int,
    containerHeight: Int
): CourseMapPixelPoint? {
    val fit = fitCourseMapRect(
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        containerWidth = containerWidth,
        containerHeight = containerHeight
    ) ?: return null

    return CourseMapPixelPoint(
        x = fit.left + point.x * fit.scale,
        y = fit.top + point.y * fit.scale
    )
}
