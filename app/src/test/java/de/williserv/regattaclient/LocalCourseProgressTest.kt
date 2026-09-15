package de.williserv.regattaclient

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCourseProgressTest {

    private fun buildEnglishProgressText(
        totalMarks: Int,
        passedMarks: Int,
        raceStarted: Boolean,
        raceFinished: Boolean
    ): String = buildLocalProgressText(
        totalMarks = totalMarks,
        passedMarks = passedMarks,
        raceStarted = raceStarted,
        raceFinished = raceFinished,
        markedFormatter = { passed, total, percent ->
            String.format(Locale.US, "Progress: %d/%d marks · %.0f%%", passed, total, percent)
        },
        directFormatter = { percent ->
            String.format(Locale.US, "Progress: %.0f%%", percent)
        }
    )

    @Test
    fun courseSideReference_usesFinishMidpointWhenNoMarksExist() {
        val finishLine = StartLine(
            ref = GeoPoint(lat = 53.1000, lon = 10.1000),
            mark = GeoPoint(lat = 53.1000, lon = 10.2000)
        )

        val reference = resolveCourseSideReference(
            firstCourseMark = null,
            finishLine = finishLine
        )!!

        assertEquals(53.1000, reference.lat, 0.0000001)
        assertEquals(10.1500, reference.lon, 0.0000001)
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
    fun directCourseSide_usesFinishLineAsDirectionReference() {
        val startLine = StartLine(
            ref = GeoPoint(lat = 53.0000, lon = 10.0000),
            mark = GeoPoint(lat = 53.0000, lon = 10.0100)
        )
        val finishLine = StartLine(
            ref = GeoPoint(lat = 53.0100, lon = 10.0000),
            mark = GeoPoint(lat = 53.0100, lon = 10.0100)
        )
        val boatOnCourse = GeoPoint(lat = 53.0050, lon = 10.0050)
        val reference = resolveCourseSideReference(
            firstCourseMark = null,
            finishLine = finishLine
        )!!

        val referenceDistance = StartLineMath.signedDistanceToStartLineM(reference, startLine)
        val boatDistance = StartLineMath.signedDistanceToStartLineM(boatOnCourse, startLine)

        assertTrue(referenceDistance * boatDistance > 0.0)
    }

    @Test
    fun finishApproachReference_usesStartMidpointWhenNoMarksExist() {
        val startLine = StartLine(
            ref = GeoPoint(lat = 53.0000, lon = 10.0000),
            mark = GeoPoint(lat = 53.0000, lon = 10.1000)
        )

        val reference = resolveFinishApproachReference(
            lastCourseMark = null,
            startLine = startLine
        )!!

        assertEquals(53.0000, reference.lat, 0.0000001)
        assertEquals(10.0500, reference.lon, 0.0000001)
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
    fun directCourseFinishSide_isOppositeStartReference() {
        val startLine = StartLine(
            ref = GeoPoint(lat = 53.0000, lon = 10.0000),
            mark = GeoPoint(lat = 53.0000, lon = 10.0100)
        )
        val finishLine = StartLine(
            ref = GeoPoint(lat = 53.0100, lon = 10.0000),
            mark = GeoPoint(lat = 53.0100, lon = 10.0100)
        )
        val boatPastFinish = GeoPoint(lat = 53.0200, lon = 10.0050)
        val approachReference = resolveFinishApproachReference(
            lastCourseMark = null,
            startLine = startLine
        )!!

        val approachDistance = StartLineMath.signedDistanceToStartLineM(
            approachReference,
            finishLine
        )
        val boatDistance = StartLineMath.signedDistanceToStartLineM(
            boatPastFinish,
            finishLine
        )

        assertTrue(approachDistance * boatDistance < 0.0)
    }

    @Test
    fun raceStartGuard_requiresCourseSide() {
        assertTrue(
            shouldMarkRaceStarted(
                isOcs = false,
                raceStarted = false,
                isOnCourseSide = true
            )
        )
        assertFalse(
            shouldMarkRaceStarted(
                isOcs = false,
                raceStarted = false,
                isOnCourseSide = false
            )
        )
    }

    @Test
    fun raceStartGuard_doesNotStartOnOcsReturnCrossing() {
        assertFalse(
            shouldMarkRaceStarted(
                isOcs = false,
                raceStarted = false,
                isOnCourseSide = false
            )
        )
    }

    @Test
    fun raceStartGuard_doesNotRestartExistingRaceOrOcsState() {
        assertFalse(
            shouldMarkRaceStarted(
                isOcs = true,
                raceStarted = false,
                isOnCourseSide = true
            )
        )
        assertFalse(
            shouldMarkRaceStarted(
                isOcs = false,
                raceStarted = true,
                isOnCourseSide = true
            )
        )
    }

    @Test
    fun directCourseProgress_reportsZeroBeforeStart() {
        assertEquals(
            "Progress: 0%",
            buildEnglishProgressText(
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
            buildEnglishProgressText(
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
            buildEnglishProgressText(
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
            buildEnglishProgressText(
                totalMarks = 2,
                passedMarks = 1,
                raceStarted = true,
                raceFinished = false
            )
        )
    }
}
