package de.williserv.regattaclient

import java.net.URLEncoder

sealed class CourseMapView {
    object Start : CourseMapView()
    data class Mark(val order: Int) : CourseMapView()
    object Finish : CourseMapView()
}

data class CourseMapMark(
    val order: Int?,
    val label: String,
    val skipped: Boolean
) {
    val clickable: Boolean
        get() = order != null && !skipped
}

internal fun buildCourseMapImageUrl(
    baseUrl: String,
    eventName: String,
    view: CourseMapView? = null,
    generationId: String? = null
): String {
    val normalizedBaseUrl = baseUrl.trimEnd('/')
    val encodedEvent = URLEncoder.encode(eventName, "UTF-8")
    var base = "$normalizedBaseUrl/${if (view == null) "course-map" else "course-map-detail"}?event_name=$encodedEvent"

    if (view == null && !generationId.isNullOrBlank()) {
        base += "&generation_id=${URLEncoder.encode(generationId, "UTF-8")}"
    }

    return when (view) {
        null -> base
        CourseMapView.Start -> "$base&view=start"
        is CourseMapView.Mark -> "$base&view=mark&order=${view.order}"
        CourseMapView.Finish -> "$base&view=finish"
    }
}

internal fun bindCourseMapGeneration(
    mapImageUrl: String,
    generationId: String?
): String {
    if (generationId.isNullOrBlank()) return mapImageUrl
    val separator = if ('?' in mapImageUrl) '&' else '?'
    return "$mapImageUrl$separator" +
        "generation_id=${URLEncoder.encode(generationId, "UTF-8")}"
}

internal fun shouldFallbackToCourseOverview(statusCode: Int?): Boolean {
    return statusCode == 404
}
