package de.williserv.regattaclient

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalCourseProgressTest {

    @Test
    fun courseSideReference_usesFinishMidpointWhenNoMarksExist() {
        val finishLine = StartLine(
            ref = GeoPoint(lat = 53.1000, lon = 10.1000),
            mark = GeoPoint(lat = 53.1000, lon = 10.2000)
        )

        assertEquals(
            GeoPoint(lat = 53.1000, lon = 10.1500),
            resolveCourseSideReference(
                firstCourseMark = null,
                finishLine = finishLine
            )
        )
    }

    @Test
    fun courseSideReference_keepsFirstMarkForExistingCourses() {
        val firstMark = GeoPoint(lat = 53.2000, lon = 10.3000)
        val finishLine = StartLine(
            ref = GeoPoint(lat = 53.4000, lon = 10.4000),
            mark = GeoPoint(lat = 53.4000, lon = 10.5000)
        )

        assertEquals(
            firstMark,
            resolveCourseSideReference(
                firstCourseMark = firstMark,
                finishLine = finishLine
            )
        )
    }

    @Test
    fun finishApproachReference_usesStartMidpointWhenNoMarksExist() {
        val startLine = StartLine(
            ref = GeoPoint(lat = 53.0000, lon = 10.0000),
            mark = GeoPoint(lat = 53.0000, lon = 10.1000)
        )

        assertEquals(
            GeoPoint(lat = 53.0000, lon = 10.0500),
            resolveFinishApproachReference(
                lastCourseMark = null,
                startLine = startLine
            )
        )
    }

    @Test
    fun finishApproachReference_keepsLastMarkForExistingCourses() {
        val lastMark = GeoPoint(lat = 53.5000, lon = 10.6000)
        val startLine = StartLine(
            ref = GeoPoint(lat = 53.0000, lon = 10.0000),
            mark = GeoPoint(lat = 53.0000, lon = 10.1000)
        )

        assertEquals(
            lastMark,
            resolveFinishApproachReference(
                lastCourseMark = lastMark,
                startLine = startLine
            )
        )
    }

    @Test
    fun directCourseProgress_reportsZeroBeforeStart() {
        assertEquals(
            "Progress: 0%",
            buildLocalProgressText(
                totalMarks = 0,
                passedMarks = 0,
                raceStarted = false,
                raceFinished = false
            )
        )
    }

    @Test
    fun directCourseProgress_reportsFiftyWhileRacing() {
        assertEquals(
            "Progress: 50%",
            buildLocalProgressText(
                totalMarks = 0,
                passedMarks = 0,
                raceStarted = true,
                raceFinished = false
            )
        )
    }

    @Test
    fun directCourseProgress_reportsHundredWhenFinished() {
        assertEquals(
            "Progress: 100%",
            buildLocalProgressText(
                totalMarks = 0,
                passedMarks = 0,
                raceStarted = true,
                raceFinished = true
            )
        )
    }

    @Test
    fun markedCourseProgress_keepsExistingFormatAndCalculation() {
        assertEquals(
            "Progress: 1/2 marks · 50%",
            buildLocalProgressText(
                totalMarks = 2,
                passedMarks = 1,
                raceStarted = true,
                raceFinished = false
            )
        )
    }
}
