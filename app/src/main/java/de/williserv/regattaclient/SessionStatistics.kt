package de.williserv.regattaclient

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class SessionStatistics(
    val sampleCount: Long,
    val durationMs: Long,
    val distanceM: Double,
    val averageSogMps: Double?,
    val maxSogMps: Double?
)

internal fun calculateSessionStatistics(
    session: TrackingSession,
    samples: List<SessionTrackingSample>,
    nowMillis: Long = System.currentTimeMillis()
): SessionStatistics {
    val effectiveEnd = session.endedAt ?: nowMillis
    val durationMs = (effectiveEnd - session.startedAt).coerceAtLeast(0L)

    var distanceM = 0.0
    var previousValidPoint: GeoPoint? = null

    for (sample in samples) {
        if (!sample.hasUsableGpsPosition()) {
            previousValidPoint = null
            continue
        }

        val current = GeoPoint(sample.lat, sample.lon)
        previousValidPoint?.let { previous ->
            distanceM += StartLineMath.distanceBetweenMeters(previous, current)
        }
        previousValidPoint = current
    }

    val validSogValues = samples
        .asSequence()
        .map { it.sog.toDouble() }
        .filter { it.isFinite() && it >= 0.0 }
        .toList()
    val maxSog = validSogValues.maxOrNull()

    var weightedSogSum = 0.0
    var weightedDurationSeconds = 0.0

    for (index in 0 until samples.lastIndex) {
        val first = samples[index]
        val second = samples[index + 1]
        val firstSog = first.sog.toDouble()
        val secondSog = second.sog.toDouble()

        if (
            !firstSog.isFinite() ||
            !secondSog.isFinite() ||
            firstSog < 0.0 ||
            secondSog < 0.0
        ) {
            continue
        }

        val firstInstantMs = first.sampleEpochMillis() ?: continue
        val secondInstantMs = second.sampleEpochMillis() ?: continue
        val deltaMs = secondInstantMs - firstInstantMs
        if (deltaMs <= 0L) continue

        val deltaSeconds = deltaMs / 1000.0
        weightedSogSum += ((firstSog + secondSog) / 2.0) * deltaSeconds
        weightedDurationSeconds += deltaSeconds
    }

    val averageSog = if (weightedDurationSeconds > 0.0) {
        weightedSogSum / weightedDurationSeconds
    } else {
        null
    }

    return SessionStatistics(
        sampleCount = samples.size.toLong(),
        durationMs = durationMs,
        distanceM = distanceM,
        averageSogMps = averageSog,
        maxSogMps = maxSog
    )
}

private fun SessionTrackingSample.hasUsableGpsPosition(): Boolean {
    return lat.isFinite() &&
        lon.isFinite() &&
        lat in -90.0..90.0 &&
        lon in -180.0..180.0 &&
        accuracy.isFinite() &&
        accuracy >= 0f &&
        accuracy <= 25f
}

private fun SessionTrackingSample.sampleEpochMillis(): Long? {
    val offsetMinutes = utcOffsetMinutes ?: return null
    return runCatching {
        val localDateTime = LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val offset = ZoneOffset.ofTotalSeconds(offsetMinutes * 60)
        localDateTime.toInstant(offset).toEpochMilli()
    }.getOrNull()
}
