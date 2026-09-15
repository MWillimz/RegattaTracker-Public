package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseMapDetailsTest {

    @Test
    fun overviewUrlRemainsExistingCourseMapEndpoint() {
        assertEquals(
            "https://race.example/course-map?event_name=Series+1",
            buildCourseMapImageUrl(
                baseUrl = "https://race.example/",
                eventName = "Series 1"
            )
        )
    }

    @Test
    fun overviewUrlCanBindExactGeneration() {
        assertEquals(
            "https://race.example/course-map?event_name=Series+1&generation_id=generation%2F42",
            buildCourseMapImageUrl(
                baseUrl = "https://race.example/",
                eventName = "Series 1",
                generationId = "generation/42"
            )
        )
        assertEquals(
            "https://race.example/course-map?event_name=Series+1&generation_id=generation%2F42",
            bindCourseMapGeneration(
                "https://race.example/course-map?event_name=Series+1",
                "generation/42"
            )
        )
    }

    @Test
    fun detailUrlsIgnoreGenerationAndUseExpectedServerContract() {
        assertEquals(
            "https://race.example/course-map-detail?event_name=Series+1&view=start",
            buildCourseMapImageUrl(
                baseUrl = "https://race.example",
                eventName = "Series 1",
                view = CourseMapView.Start,
                generationId = "ignored"
            )
        )
        assertEquals(
            "https://race.example/course-map-detail?event_name=Series+1&view=mark&order=7",
            buildCourseMapImageUrl(
                baseUrl = "https://race.example",
                eventName = "Series 1",
                view = CourseMapView.Mark(order = 7)
            )
        )
        assertEquals(
            "https://race.example/course-map-detail?event_name=Series+1&view=finish",
            buildCourseMapImageUrl(
                baseUrl = "https://race.example",
                eventName = "Series 1",
                view = CourseMapView.Finish
            )
        )
    }

    @Test
    fun only404TriggersCompatibilityFallback() {
        assertTrue(shouldFallbackToCourseOverview(404))
        assertFalse(shouldFallbackToCourseOverview(400))
        assertFalse(shouldFallbackToCourseOverview(401))
        assertFalse(shouldFallbackToCourseOverview(403))
        assertFalse(shouldFallbackToCourseOverview(null))
    }

    @Test
    fun skippedOrOrderlessMarksAreNotClickable() {
        assertTrue(CourseMapMark(order = 3, label = "3 Mark", skipped = false).clickable)
        assertFalse(CourseMapMark(order = 3, label = "3 Mark", skipped = true).clickable)
        assertFalse(CourseMapMark(order = null, label = "Mark", skipped = false).clickable)
    }
}
