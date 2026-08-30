package de.williserv.regattaclient

import java.util.Locale

internal fun lineMidpoint(line: StartLine): GeoPoint {
    return GeoPoint(
        lat = (line.ref.lat + line.mark.lat) / 2.0,
        lon = (line.ref.lon + line.mark.lon) / 2.0
    )
}

internal fun resolveCourseSideReference(
    firstCourseMark: GeoPoint?,
    finishLine: StartLine?
): GeoPoint? = firstCourseMark ?: finishLine?.let(::lineMidpoint)

internal fun resolveFinishApproachReference(
    lastCourseMark: GeoPoint?,
    startLine: StartLine?
): GeoPoint? = lastCourseMark ?: startLine?.let(::lineMidpoint)

internal fun buildLocalProgressText(
    totalMarks: Int,
    passedMarks: Int,
    raceStarted: Boolean,
    raceFinished: Boolean,
    markedFormatter: (Int, Int, Double) -> String = { passed, total, percent ->
        String.format(Locale.US, "Progress: %d/%d marks · %.0f%%", passed, total, percent)
    },
    directFormatter: (Double) -> String = { percent ->
        String.format(Locale.US, "Progress: %.0f%%", percent)
    }
): String {
    if (totalMarks > 0) {
        val progressPercent = if (raceFinished) 100.0
        else (passedMarks.toDouble() / totalMarks.toDouble()) * 100.0
        return markedFormatter(passedMarks, totalMarks, progressPercent)
    }

    val directCourseProgressPercent = when {
        raceFinished -> 100.0
        raceStarted -> 50.0
        else -> 0.0
    }
    return directFormatter(directCourseProgressPercent)
}
