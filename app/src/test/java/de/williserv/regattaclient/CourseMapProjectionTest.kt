package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseMapProjectionTest {

    private val viewport = CourseMapViewport(
        projection = "web_mercator",
        zoom = 1,
        leftPx = 100.0,
        topPx = 150.0,
        widthPx = 400,
        heightPx = 300,
        generationId = "g1"
    )

    @Test
    fun webMercatorProjectionMatchesServerWorldPixelFormula() {
        val point = projectToCourseMap(0.0, 0.0, viewport)
        assertNotNull(point)
        assertEquals(156.0, point!!.x, 0.000001)
        assertEquals(106.0, point.y, 0.000001)
        assertTrue(isPointInsideCourseMap(point, viewport))
    }

    @Test
    fun pointsOutsidePublishedPngAreDetectedWithoutClamping() {
        val point = CourseMapPixelPoint(x = -1.0, y = 20.0)
        assertFalse(isPointInsideCourseMap(point, viewport))
    }

    @Test
    fun contentScaleFitAccountsForVerticalLetterboxing() {
        val fitted = fitCourseMapPoint(
            point = CourseMapPixelPoint(250.0, 100.0),
            imageWidth = 1000,
            imageHeight = 500,
            containerWidth = 1000,
            containerHeight = 1000
        )

        assertNotNull(fitted)
        assertEquals(250.0, fitted!!.x, 0.000001)
        assertEquals(350.0, fitted.y, 0.000001)
    }

    @Test
    fun contentScaleFitAccountsForScaledImageAndLetterboxing() {
        val fitted = fitCourseMapPoint(
            point = CourseMapPixelPoint(250.0, 100.0),
            imageWidth = 1000,
            imageHeight = 500,
            containerWidth = 500,
            containerHeight = 500
        )

        assertNotNull(fitted)
        assertEquals(125.0, fitted!!.x, 0.000001)
        assertEquals(175.0, fitted.y, 0.000001)
    }

    @Test
    fun unsupportedProjectionDoesNotProduceCoordinates() {
        assertEquals(
            null,
            projectToCourseMap(
                0.0,
                0.0,
                viewport.copy(projection = "other")
            )
        )
    }


    @Test
    fun bitmapMustExactlyMatchPublishedViewportDimensions() {
        assertTrue(courseMapBitmapMatchesViewport(400, 300, viewport))
        assertFalse(courseMapBitmapMatchesViewport(401, 300, viewport))
        assertFalse(courseMapBitmapMatchesViewport(400, 299, viewport))
    }

    @Test
    fun fitRectAndPointUseSameContentScaleFitGeometry() {
        val rect = fitCourseMapRect(
            imageWidth = 1000,
            imageHeight = 500,
            containerWidth = 500,
            containerHeight = 500
        )
        assertNotNull(rect)
        assertEquals(0.0, rect!!.left, 0.000001)
        assertEquals(125.0, rect.top, 0.000001)
        assertEquals(500.0, rect.width, 0.000001)
        assertEquals(250.0, rect.height, 0.000001)
        assertEquals(0.5, rect.scale, 0.000001)

        val fitted = fitCourseMapPoint(
            point = CourseMapPixelPoint(250.0, 100.0),
            imageWidth = 1000,
            imageHeight = 500,
            containerWidth = 500,
            containerHeight = 500
        )
        assertNotNull(fitted)
        assertEquals(rect.left + 250.0 * rect.scale, fitted!!.x, 0.000001)
        assertEquals(rect.top + 100.0 * rect.scale, fitted.y, 0.000001)
    }

}
