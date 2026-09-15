package de.williserv.regattaclient

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

internal fun fitCourseMapPoint(
    point: CourseMapPixelPoint,
    imageWidth: Int,
    imageHeight: Int,
    containerWidth: Int,
    containerHeight: Int
): CourseMapPixelPoint? {
    if (imageWidth <= 0 || imageHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) {
        return null
    }

    val fitScale = min(
        containerWidth.toDouble() / imageWidth.toDouble(),
        containerHeight.toDouble() / imageHeight.toDouble()
    )
    val displayedWidth = imageWidth * fitScale
    val displayedHeight = imageHeight * fitScale
    val letterboxX = (containerWidth - displayedWidth) / 2.0
    val letterboxY = (containerHeight - displayedHeight) / 2.0

    return CourseMapPixelPoint(
        x = letterboxX + point.x * fitScale,
        y = letterboxY + point.y * fitScale
    )
}
