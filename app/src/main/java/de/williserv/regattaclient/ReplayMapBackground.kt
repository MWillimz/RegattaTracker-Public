package de.williserv.regattaclient

import android.graphics.Bitmap

internal data class ReplayMapContextKey(
    val raceContextId: Long?,
    val resolvedEventName: String,
    val generationId: String
)

internal data class ReplayMapCandidate(
    val key: ReplayMapContextKey,
    val viewport: CourseMapViewport
)

internal data class ReplayMapBackground(
    val candidate: ReplayMapCandidate,
    val bitmap: Bitmap
)

internal fun replayMapCandidates(
    session: TrackingSession,
    samples: List<SessionTrackingSample>
): List<ReplayMapCandidate> {
    if (session.mode != "race" || session.accessContextId == null) {
        return emptyList()
    }

    val candidates = linkedMapOf<ReplayMapContextKey, ReplayMapCandidate>()

    samples.forEach { sample ->
        val eventName = sample.resolvedEventName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return@forEach
        val viewport = parseCourseMapViewportJson(sample.courseMapViewportJson)
            ?: return@forEach
        val key = ReplayMapContextKey(
            raceContextId = sample.raceContextId,
            resolvedEventName = eventName,
            generationId = viewport.generationId
        )
        candidates.putIfAbsent(
            key,
            ReplayMapCandidate(
                key = key,
                viewport = viewport
            )
        )
    }

    if (candidates.isEmpty() && samples.none { it.raceContextId != null }) {
        val eventName = session.resolvedEventName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val viewport = parseCourseMapViewportJson(session.courseMapViewportJson)
        if (eventName != null && viewport != null) {
            val key = ReplayMapContextKey(
                raceContextId = null,
                resolvedEventName = eventName,
                generationId = viewport.generationId
            )
            candidates[key] = ReplayMapCandidate(
                key = key,
                viewport = viewport
            )
        }
    }

    return candidates.values.toList()
}

internal fun replayMapContextKey(
    sample: SessionTrackingSample
): ReplayMapContextKey? {
    val eventName = sample.resolvedEventName
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val viewport = parseCourseMapViewportJson(sample.courseMapViewportJson)
        ?: return null

    return ReplayMapContextKey(
        raceContextId = sample.raceContextId,
        resolvedEventName = eventName,
        generationId = viewport.generationId
    )
}

internal fun replayMapBackgroundForSample(
    sample: SessionTrackingSample,
    backgrounds: Map<ReplayMapContextKey, ReplayMapBackground>
): ReplayMapBackground? {
    replayMapContextKey(sample)?.let { key ->
        backgrounds[key]?.let { return it }
    }

    return if (sample.raceContextId == null && backgrounds.size == 1) {
        backgrounds.values.single()
    } else {
        null
    }
}
