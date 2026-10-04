package de.williserv.regattaclient

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

internal const val MPS_TO_KNOTS = 1.9438444924406
internal const val DEFAULT_GPS_MANEUVER_SMOOTHING_SECONDS = 3.0
internal const val DEFAULT_GPS_MANEUVER_WINDOW_SECONDS = 5.0
internal const val DEFAULT_GPS_MANEUVER_THRESHOLD_DEG = 20.0
internal const val DEFAULT_ANALYSIS_RECOVERY_SECONDS = 5.0
internal const val DEFAULT_IMU_STEADY_ATTITUDE_RATE_DPS = 1.5
internal const val ANALYSIS_ACCELERATION_LOOKBACK_MS = 5_000L
internal const val ANALYSIS_AGGREGATION_WINDOW_MS = 10_000L

enum class AnalysisMetricUse {
    ANGLE,
    RADIUS,
    COLOR,
    FILTER
}

enum class AnalysisAngleKind {
    COMPASS,
    RELATIVE,
    CIRCULAR
}

enum class AnalysisMetricSource {
    GPS_COG,
    GPS_SOG,
    MEASUREMENT,
    DERIVED_VMG,
    DERIVED_ACCELERATION
}

data class AnalysisMetric(
    val id: String,
    val label: String,
    val unit: String?,
    val source: AnalysisMetricSource,
    val measurementKey: String? = null,
    val angleKind: AnalysisAngleKind? = null,
    val recommendedUses: Set<AnalysisMetricUse> = emptySet(),
    val displayScale: Double = 1.0
) {
    fun isRecommendedFor(use: AnalysisMetricUse): Boolean =
        use in recommendedUses
}

data class PreparedAnalysisSample(
    val timestampMs: Long?,
    val cogDeg: Double,
    val sogMps: Double,
    val measurements: Map<String, Double>,
    val sourceIndex: Int = -1,
    val acceleration5sMps2: Double? = null
)

sealed interface AnalysisSampleFilter

data class GpsManeuverAnalysisFilter(
    val smoothingSeconds: Double = DEFAULT_GPS_MANEUVER_SMOOTHING_SECONDS,
    val changeWindowSeconds: Double = DEFAULT_GPS_MANEUVER_WINDOW_SECONDS,
    val changeThresholdDeg: Double = DEFAULT_GPS_MANEUVER_THRESHOLD_DEG,
    val recoverySeconds: Double = DEFAULT_ANALYSIS_RECOVERY_SECONDS
) : AnalysisSampleFilter

data class ImuStabilityAnalysisFilter(
    val maxAttitudeRateDps: Double =
        DEFAULT_IMU_STEADY_ATTITUDE_RATE_DPS,
    val recoverySeconds: Double = DEFAULT_ANALYSIS_RECOVERY_SECONDS
) : AnalysisSampleFilter

data class AnalysisRangeFilter(
    val metricId: String,
    val min: Double,
    val max: Double
)

data class AnalysisTimeRangeFilter(
    val startMs: Long,
    val endMs: Long
)

data class AnalysisPoint(
    val angleDeg: Double,
    val radius: Double,
    val colorValue: Double?
)

data class SessionAnalysisDataset(
    val points: List<AnalysisPoint>,
    val radiusMax: Double,
    val colorMin: Double?,
    val colorMax: Double?
)

data class SessionAnalysisCapabilities(
    val metrics: List<AnalysisMetric>,
    val angleMetrics: List<AnalysisMetric>,
    val radiusMetrics: List<AnalysisMetric>,
    val colorMetrics: List<AnalysisMetric>,
    val filterMetrics: List<AnalysisMetric>,
    val defaultAngleId: String,
    val defaultRadiusId: String,
    val gpsManeuverFilterAvailable: Boolean = false,
    val imuStabilityFilterAvailable: Boolean = false
)

internal fun prepareAnalysisSamples(
    samples: List<SessionTrackingSample>
): List<PreparedAnalysisSample> {
    val prepared = samples.mapIndexed { index, sample ->
        PreparedAnalysisSample(
            timestampMs = analysisSampleTimestampMs(sample),
            cogDeg = analysisCogDegrees(samples, index),
            sogMps = sample.sog.toDouble(),
            measurements = sessionNumericMeasurementValues(sample),
            sourceIndex = index
        )
    }
    return prepared.mapIndexed { index, sample ->
        sample.copy(
            acceleration5sMps2 =
                analysisAccelerationOverLookback(prepared, index)
        )
    }
}

private fun analysisCogDegrees(
    samples: List<SessionTrackingSample>,
    index: Int
): Double {
    val sample = samples[index]

    /*
     * 0° is a valid northbound COG. Only an explicitly unavailable Android
     * bearing uses the adjacent GPS track as a fallback. Legacy samples with
     * no validity bit keep their persisted COG.
     */
    if (sample.cogValid != false) {
        return sample.cog.toDouble()
    }

    return analysisTrackBearingDegrees(samples, index) ?: Double.NaN
}

private fun analysisTrackBearingDegrees(
    samples: List<SessionTrackingSample>,
    index: Int
): Double? {
    val selected = samples.getOrNull(index)
        ?.takeIf { it.hasUsableGpsPosition() }
        ?: return null

    fun bearing(
        from: SessionTrackingSample,
        to: SessionTrackingSample
    ): Double? {
        if (!from.hasUsableGpsPosition() || !to.hasUsableGpsPosition()) {
            return null
        }
        if (!areSessionSamplesContiguous(from, to)) return null
        if (from.lat == to.lat && from.lon == to.lon) return null

        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val deltaLon = Math.toRadians(to.lon - from.lon)
        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) -
            sin(lat1) * cos(lat2) * cos(deltaLon)

        if (abs(x) < 1e-12 && abs(y) < 1e-12) return null
        return normalizeAnalysisAngle(
            Math.toDegrees(atan2(y, x)),
            AnalysisAngleKind.COMPASS
        )
    }

    samples.getOrNull(index + 1)?.let { next ->
        bearing(selected, next)?.let { return it }
    }
    samples.getOrNull(index - 1)?.let { previous ->
        bearing(previous, selected)?.let { return it }
    }
    return null
}

private fun analysisSampleTimestampMs(sample: SessionTrackingSample): Long? {
    return runCatching {
        val local = LocalDateTime.parse(sample.timestamp)
        val offsetSeconds = (sample.utcOffsetMinutes ?: 0) * 60
        local.toInstant(ZoneOffset.ofTotalSeconds(offsetSeconds)).toEpochMilli()
    }.getOrNull()
}

private fun analysisSamplesAreContinuous(
    previous: PreparedAnalysisSample,
    current: PreparedAnalysisSample
): Boolean {
    val previousTime = previous.timestampMs ?: return false
    val currentTime = current.timestampMs ?: return false
    if (currentTime <= previousTime) return false

    return previous.sourceIndex < 0 ||
        current.sourceIndex < 0 ||
        current.sourceIndex == previous.sourceIndex + 1
}

/*
 * Use the already-persisted GPS SOG series for the analysis acceleration
 * signal. This is gravity-free by construction and avoids reintroducing
 * RegattaLink Fast Motion into normal session analysis.
 */
private fun analysisAccelerationOverLookback(
    samples: List<PreparedAnalysisSample>,
    index: Int,
    lookbackMs: Long = ANALYSIS_ACCELERATION_LOOKBACK_MS
): Double? {
    if (index !in samples.indices || lookbackMs <= 0L) return null
    val currentTime = samples[index].timestampMs ?: return null
    val targetTime = currentTime - lookbackMs
    var cursor = index
    var maxAcceleration = 0.0

    while (cursor > 0) {
        val current = samples[cursor]
        val previous = samples[cursor - 1]
        if (!analysisSamplesAreContinuous(previous, current)) return null

        val currentSampleTime = current.timestampMs ?: return null
        val previousTime = previous.timestampMs ?: return null
        val deltaMs = currentSampleTime - previousTime
        if (deltaMs > lookbackMs) return null
        val dtSeconds = deltaMs / 1_000.0
        if (
            !current.sogMps.isFinite() ||
            !previous.sogMps.isFinite() ||
            dtSeconds <= 0.0
        ) {
            return null
        }

        maxAcceleration = max(
            maxAcceleration,
            abs(current.sogMps - previous.sogMps) / dtSeconds
        )
        if (previousTime <= targetTime) {
            return maxAcceleration
        }
        cursor -= 1
    }

    return null
}

internal fun analysisObservedTimeRange(
    samples: List<PreparedAnalysisSample>
): LongRange? {
    val timestamps = samples.mapNotNull { it.timestampMs }
    val start = timestamps.minOrNull() ?: return null
    val end = timestamps.maxOrNull() ?: return null
    return start..end
}

internal fun analysisTimeFilterFromFraction(
    samples: List<PreparedAnalysisSample>,
    fractionRange: ClosedFloatingPointRange<Float>
): AnalysisTimeRangeFilter? {
    val observed = analysisObservedTimeRange(samples) ?: return null
    val startFraction = minOf(
        fractionRange.start,
        fractionRange.endInclusive
    ).coerceIn(0f, 1f)
    val endFraction = maxOf(
        fractionRange.start,
        fractionRange.endInclusive
    ).coerceIn(0f, 1f)
    val spanMs = observed.last - observed.first

    return AnalysisTimeRangeFilter(
        startMs = observed.first +
            (spanMs.toDouble() * startFraction.toDouble()).toLong(),
        endMs = observed.first +
            (spanMs.toDouble() * endFraction.toDouble()).toLong()
    )
}

internal fun applyAnalysisTimeFilter(
    samples: List<PreparedAnalysisSample>,
    filter: AnalysisTimeRangeFilter?
): List<PreparedAnalysisSample> {
    if (filter == null) return samples
    val start = minOf(filter.startMs, filter.endMs)
    val end = maxOf(filter.startMs, filter.endMs)
    return samples.filter { sample ->
        sample.timestampMs?.let { it in start..end } == true
    }
}

internal fun applyAnalysisRangeFilters(
    samples: List<PreparedAnalysisSample>,
    filters: List<AnalysisRangeFilter>,
    metricsById: Map<String, AnalysisMetric>
): List<PreparedAnalysisSample> {
    if (filters.isEmpty()) return samples
    return samples.filter { sample ->
        filters.all { filter ->
            val metric = metricsById[filter.metricId] ?: return@all false
            val value = metricValue(metric, sample) ?: return@all false
            value in filter.min..filter.max
        }
    }
}

internal fun applyAnalysisSampleFilters(
    samples: List<PreparedAnalysisSample>,
    filters: List<AnalysisSampleFilter>
): List<PreparedAnalysisSample> {
    if (samples.isEmpty() || filters.isEmpty()) return samples

    val excluded = BooleanArray(samples.size)
    filters.forEach { filter ->
        val mask = when (filter) {
            is GpsManeuverAnalysisFilter ->
                gpsManeuverExclusionMask(samples, filter)
            is ImuStabilityAnalysisFilter ->
                imuStabilityExclusionMask(samples, filter)
        }
        for (index in excluded.indices) {
            excluded[index] = excluded[index] || mask[index]
        }
    }

    return samples.filterIndexed { index, _ -> !excluded[index] }
}

internal fun hasGpsManeuverFilterData(
    samples: List<PreparedAnalysisSample>
): Boolean =
    samples.count { it.timestampMs != null && it.cogDeg.isFinite() } >= 2

internal fun hasImuStabilityFilterData(
    samples: List<PreparedAnalysisSample>
): Boolean =
    samples.count { sample ->
        sample.timestampMs != null &&
            sample.measurements[REGATTALINK_MOTION_HEEL_KEY]?.isFinite() == true
    } >= 2

private fun gpsManeuverExclusionMask(
    samples: List<PreparedAnalysisSample>,
    filter: GpsManeuverAnalysisFilter
): BooleanArray {
    require(filter.smoothingSeconds > 0.0)
    require(filter.changeWindowSeconds > 0.0)
    require(filter.changeThresholdDeg > 0.0)
    require(filter.recoverySeconds >= 0.0)

    val excluded = BooleanArray(samples.size)
    val smoothingMs = (filter.smoothingSeconds * 1_000.0).toLong()
    val changeWindowMs = (filter.changeWindowSeconds * 1_000.0).toLong()
    val smoothedCog = DoubleArray(samples.size) { Double.NaN }

    samples.indices.forEach { index ->
        val time = samples[index].timestampMs
        val cog = samples[index].cogDeg
        if (time == null || !cog.isFinite()) {
            excluded[index] = true
            return@forEach
        }

        var sumSin = 0.0
        var sumCos = 0.0
        var count = 0
        var cursor = index
        while (cursor >= 0) {
            val candidateTime = samples[cursor].timestampMs ?: break
            if (candidateTime > time || time - candidateTime > smoothingMs) break
            val candidateCog = samples[cursor].cogDeg
            if (candidateCog.isFinite()) {
                val radians = normalizeAnalysisAngle(
                    candidateCog,
                    AnalysisAngleKind.COMPASS
                ) * PI / 180.0
                sumSin += sin(radians)
                sumCos += cos(radians)
                count += 1
            }
            cursor -= 1
        }

        if (count > 0 && (sumSin != 0.0 || sumCos != 0.0)) {
            val degrees = atan2(sumSin, sumCos) * 180.0 / PI
            smoothedCog[index] = normalizeAnalysisAngle(
                degrees,
                AnalysisAngleKind.COMPASS
            )
        }
    }

    samples.indices.forEach { index ->
        val time = samples[index].timestampMs ?: return@forEach
        val currentCog = smoothedCog[index]
        if (!currentCog.isFinite()) {
            excluded[index] = true
            return@forEach
        }

        val targetTime = time - changeWindowMs
        var baselineIndex = index - 1
        while (baselineIndex >= 0) {
            val baselineTime = samples[baselineIndex].timestampMs
            if (baselineTime == null) {
                baselineIndex -= 1
                continue
            }
            if (baselineTime <= targetTime) break
            baselineIndex -= 1
        }
        if (baselineIndex < 0) return@forEach

        val baselineCog = smoothedCog[baselineIndex]
        if (!baselineCog.isFinite()) return@forEach

        if (
            abs(shortestAnalysisAngleDeltaDeg(currentCog, baselineCog)) >=
            filter.changeThresholdDeg
        ) {
            for (affected in baselineIndex..index) {
                excluded[affected] = true
            }
        }
    }

    extendAnalysisExclusionForward(
        excluded = excluded,
        samples = samples,
        recoverySeconds = filter.recoverySeconds
    )
    return excluded
}

private fun imuStabilityExclusionMask(
    samples: List<PreparedAnalysisSample>,
    filter: ImuStabilityAnalysisFilter
): BooleanArray {
    require(filter.maxAttitudeRateDps > 0.0)
    require(filter.recoverySeconds >= 0.0)

    val excluded = BooleanArray(samples.size)
    var previousIndex: Int? = null

    samples.forEachIndexed { index, sample ->
        val time = sample.timestampMs
        val heel = sample.measurements[REGATTALINK_MOTION_HEEL_KEY]
        if (time == null || heel == null || !heel.isFinite()) {
            excluded[index] = true
            previousIndex = null
            return@forEachIndexed
        }

        val previous = previousIndex
        if (previous != null) {
            val previousSample = samples[previous]
            val previousTime = previousSample.timestampMs
            val previousHeel = previousSample.measurements[REGATTALINK_MOTION_HEEL_KEY]
            if (
                previousTime != null &&
                previousHeel != null &&
                previousHeel.isFinite()
            ) {
                val dtSeconds = (time - previousTime) / 1_000.0
                if (dtSeconds > 0.0 && dtSeconds <= 3.0) {
                    val heelRate = abs(
                        shortestAnalysisAngleDeltaDeg(heel, previousHeel)
                    ) / dtSeconds

                    val pitch =
                        sample.measurements[REGATTALINK_MOTION_PITCH_KEY]
                    val previousPitch =
                        previousSample.measurements[REGATTALINK_MOTION_PITCH_KEY]
                    val pitchRate =
                        if (
                            pitch != null &&
                            previousPitch != null &&
                            pitch.isFinite() &&
                            previousPitch.isFinite()
                        ) {
                            abs(pitch - previousPitch) / dtSeconds
                        } else {
                            0.0
                        }

                    if (
                        max(heelRate, pitchRate) >
                        filter.maxAttitudeRateDps
                    ) {
                        /*
                         * The transition spans both 1 Hz Motion samples.
                         * Exclude both endpoints, then apply the configured
                         * recovery tail below.
                         */
                        excluded[previous] = true
                        excluded[index] = true
                    }
                } else {
                    excluded[index] = true
                }
            }
        }

        previousIndex = index
    }

    extendAnalysisExclusionForward(
        excluded = excluded,
        samples = samples,
        recoverySeconds = filter.recoverySeconds
    )
    return excluded
}

private fun extendAnalysisExclusionForward(
    excluded: BooleanArray,
    samples: List<PreparedAnalysisSample>,
    recoverySeconds: Double
) {
    if (recoverySeconds <= 0.0) return
    val recoveryMs = (recoverySeconds * 1_000.0).toLong()
    val sourceExclusions = excluded.copyOf()
    var blockedUntil = Long.MIN_VALUE

    samples.indices.forEach { index ->
        val time = samples[index].timestampMs
        if (time == null) {
            excluded[index] = true
            return@forEach
        }
        if (sourceExclusions[index]) {
            blockedUntil = max(blockedUntil, time + recoveryMs)
        }
        if (time <= blockedUntil) {
            excluded[index] = true
        }
    }
}

internal fun shortestAnalysisAngleDeltaDeg(
    firstDeg: Double,
    secondDeg: Double
): Double {
    val first = normalizeAnalysisAngle(firstDeg, AnalysisAngleKind.COMPASS)
    val second = normalizeAnalysisAngle(secondDeg, AnalysisAngleKind.COMPASS)
    if (!first.isFinite() || !second.isFinite()) return Double.NaN
    return ((first - second + 540.0) % 360.0) - 180.0
}

internal fun discoverSessionAnalysisCapabilities(
    sourceSamples: List<SessionTrackingSample>,
    preparedSamples: List<PreparedAnalysisSample> = prepareAnalysisSamples(sourceSamples)
): SessionAnalysisCapabilities {
    val discovered = discoverSessionNumericMeasurements(sourceSamples)
    val presentKeys = discovered.mapTo(mutableSetOf()) { it.key }

    val metrics = mutableListOf<AnalysisMetric>()
    metrics += AnalysisMetric(
        id = "gps.cog",
        label = "COG",
        unit = "deg",
        source = AnalysisMetricSource.GPS_COG,
        angleKind = AnalysisAngleKind.COMPASS,
        recommendedUses = setOf(AnalysisMetricUse.ANGLE)
    )
    metrics += AnalysisMetric(
        id = "gps.sog",
        label = "SOG",
        unit = "kn",
        source = AnalysisMetricSource.GPS_SOG,
        recommendedUses = setOf(AnalysisMetricUse.RADIUS),
        displayScale = MPS_TO_KNOTS
    )

    val known = listOf(
        knownMetric(
            key = "nmea.heading_magnetic_deg",
            label = "MAG",
            unit = "deg",
            angleKind = AnalysisAngleKind.COMPASS,
            recommended = setOf(AnalysisMetricUse.ANGLE)
        ),
        knownMetric(
            key = "nmea.awa_deg",
            label = "AWA",
            unit = "deg",
            angleKind = AnalysisAngleKind.RELATIVE,
            recommended = setOf(AnalysisMetricUse.ANGLE)
        ),
        knownMetric(
            key = "nmea.twa_deg",
            label = "TWA",
            unit = "deg",
            angleKind = AnalysisAngleKind.RELATIVE,
            recommended = setOf(AnalysisMetricUse.ANGLE)
        ),
        knownMetric(
            key = "nmea.stw_mps",
            label = "STW",
            unit = "kn",
            recommended = setOf(AnalysisMetricUse.RADIUS),
            displayScale = MPS_TO_KNOTS
        ),
        knownMetric(
            key = "nmea.aws_mps",
            label = "AWS",
            unit = "kn",
            recommended = setOf(
                AnalysisMetricUse.COLOR,
                AnalysisMetricUse.FILTER
            ),
            displayScale = MPS_TO_KNOTS
        ),
        knownMetric(
            key = "nmea.tws_mps",
            label = "TWS",
            unit = "kn",
            recommended = setOf(
                AnalysisMetricUse.COLOR,
                AnalysisMetricUse.FILTER
            ),
            displayScale = MPS_TO_KNOTS
        ),
        knownMetric(
            key = REGATTALINK_MOTION_HEEL_KEY,
            label = "Heel",
            unit = "deg",
            recommended = setOf(AnalysisMetricUse.COLOR)
        ),
        knownMetric(
            key = REGATTALINK_MOTION_PITCH_KEY,
            label = "Pitch",
            unit = "deg",
            recommended = setOf(AnalysisMetricUse.COLOR)
        ),
        knownMetric(
            key = "nmea.depth_m",
            label = "Depth",
            unit = "m"
        ),
        knownMetric(
            key = "nmea.water_temperature_c",
            label = "Water temperature",
            unit = "°C"
        )
    )

    known.filterTo(metrics) { metric ->
        metric.measurementKey?.let(presentKeys::contains) == true
    }

    val knownKeys = known.mapNotNullTo(mutableSetOf()) { it.measurementKey }
    discovered
        .filterNot { it.key in knownKeys }
        .forEach { measurement ->
            metrics += AnalysisMetric(
                id = "measurement:${measurement.key}",
                label = measurement.label,
                unit = measurement.unit,
                source = AnalysisMetricSource.MEASUREMENT,
                measurementKey = measurement.key
            )
        }

    val canDeriveVmg = preparedSamples.any { sample ->
        val stw = sample.measurements["nmea.stw_mps"]
        val twa = sample.measurements["nmea.twa_deg"]
        stw != null && stw.isFinite() && twa != null && twa.isFinite()
    }
    if (canDeriveVmg) {
        metrics += AnalysisMetric(
            id = "derived.vmg",
            label = "VMG",
            unit = "kn",
            source = AnalysisMetricSource.DERIVED_VMG,
            recommendedUses = setOf(AnalysisMetricUse.RADIUS)
        )
    }

    if (preparedSamples.any { it.acceleration5sMps2?.isFinite() == true }) {
        metrics += AnalysisMetric(
            id = "derived.acceleration_5s",
            label = "Acceleration (5 s)",
            unit = "m/s²",
            source = AnalysisMetricSource.DERIVED_ACCELERATION,
            recommendedUses = setOf(AnalysisMetricUse.FILTER)
        )
    }

    val angleMetrics = metrics.filter { metric ->
        metric.source == AnalysisMetricSource.GPS_COG ||
            metric.angleKind != null
    }

    val radiusMetrics = metrics.filter { metric ->
        metric.source in setOf(
            AnalysisMetricSource.GPS_SOG,
            AnalysisMetricSource.DERIVED_VMG
        ) ||
            metric.measurementKey != null
    }

    val colorMetrics = metrics.filter { metric ->
        metric.source != AnalysisMetricSource.DERIVED_ACCELERATION &&
            (
                metric.source != AnalysisMetricSource.DERIVED_VMG ||
                    preparedSamples.any { metricValue(metric, it) != null }
                )
    }

    val filterMetrics = (
        colorMetrics +
            metrics.filter {
                it.source == AnalysisMetricSource.DERIVED_ACCELERATION
            }
        ).distinctBy { it.id }

    val hasTwa = angleMetrics.any { it.id == "measurement:nmea.twa_deg" }
    val hasAwa = angleMetrics.any { it.id == "measurement:nmea.awa_deg" }
    val hasStw = radiusMetrics.any { it.id == "measurement:nmea.stw_mps" }

    val defaultAngle = when {
        hasTwa && hasStw -> "measurement:nmea.twa_deg"
        hasAwa && hasStw -> "measurement:nmea.awa_deg"
        else -> "gps.cog"
    }
    val defaultRadius = if (hasStw && (hasTwa || hasAwa)) {
        "measurement:nmea.stw_mps"
    } else {
        "gps.sog"
    }

    return SessionAnalysisCapabilities(
        metrics = metrics.distinctBy { it.id },
        angleMetrics = angleMetrics.distinctBy { it.id },
        radiusMetrics = radiusMetrics.distinctBy { it.id },
        colorMetrics = colorMetrics.distinctBy { it.id },
        filterMetrics = filterMetrics.distinctBy { it.id },
        defaultAngleId = defaultAngle,
        defaultRadiusId = defaultRadius,
        gpsManeuverFilterAvailable = hasGpsManeuverFilterData(preparedSamples),
        imuStabilityFilterAvailable =
            hasImuStabilityFilterData(preparedSamples)
    )
}

private fun knownMetric(
    key: String,
    label: String,
    unit: String,
    angleKind: AnalysisAngleKind? = null,
    recommended: Set<AnalysisMetricUse> = emptySet(),
    displayScale: Double = 1.0
): AnalysisMetric = AnalysisMetric(
    id = "measurement:$key",
    label = label,
    unit = unit,
    source = AnalysisMetricSource.MEASUREMENT,
    measurementKey = key,
    angleKind = angleKind,
    recommendedUses = recommended,
    displayScale = displayScale
)

internal fun metricValue(
    metric: AnalysisMetric,
    sample: PreparedAnalysisSample
): Double? {
    val raw = when (metric.source) {
        AnalysisMetricSource.GPS_COG -> sample.cogDeg
        AnalysisMetricSource.GPS_SOG -> sample.sogMps
        AnalysisMetricSource.MEASUREMENT ->
            metric.measurementKey?.let(sample.measurements::get)
        AnalysisMetricSource.DERIVED_VMG -> {
            val stw = sample.measurements["nmea.stw_mps"]
            val twa = sample.measurements["nmea.twa_deg"]
            if (stw == null || twa == null || !stw.isFinite() || !twa.isFinite()) {
                null
            } else {
                abs(stw * cos(twa * PI / 180.0)) * MPS_TO_KNOTS
            }
        }
        AnalysisMetricSource.DERIVED_ACCELERATION ->
            sample.acceleration5sMps2
    } ?: return null

    if (!raw.isFinite()) return null
    return if (metric.source == AnalysisMetricSource.DERIVED_VMG) {
        raw
    } else {
        raw * metric.displayScale
    }
}

internal fun normalizeAnalysisAngle(
    degrees: Double,
    kind: AnalysisAngleKind
): Double {
    if (!degrees.isFinite()) return Double.NaN
    val normalized = ((degrees % 360.0) + 360.0) % 360.0
    return when (kind) {
        AnalysisAngleKind.COMPASS,
        AnalysisAngleKind.CIRCULAR -> normalized
        AnalysisAngleKind.RELATIVE ->
            if (normalized > 180.0) normalized - 360.0 else normalized
    }
}

internal fun projectAnalysisAngleDegrees(
    degrees: Double,
    kind: AnalysisAngleKind
): Pair<Double, Double> {
    val normalized = normalizeAnalysisAngle(degrees, kind)
    if (!normalized.isFinite()) return Double.NaN to Double.NaN
    val radians = normalized * PI / 180.0
    return sin(radians) to -cos(radians)
}

private data class EligibleAnalysisPoint(
    val sample: PreparedAnalysisSample,
    val angleDeg: Double,
    val radius: Double,
    val colorValue: Double?
)

internal fun buildSessionAnalysisDataset(
    samples: List<PreparedAnalysisSample>,
    angleMetric: AnalysisMetric,
    radiusMetric: AnalysisMetric,
    colorMetric: AnalysisMetric?,
    filters: List<AnalysisRangeFilter>,
    metricsById: Map<String, AnalysisMetric>,
    colorUseAbsoluteValue: Boolean = false,
    aggregationWindowMs: Long = 0L
): SessionAnalysisDataset {
    val kind = angleMetric.angleKind
    val filteredSamples = applyAnalysisRangeFilters(
        samples = samples,
        filters = filters,
        metricsById = metricsById
    )
    val eligible = buildList {
        filteredSamples.forEach { sample ->
            val rawAngle = metricValue(angleMetric, sample) ?: return@forEach
            val angleKind = kind ?: return@forEach
            val angle = normalizeAnalysisAngle(rawAngle, angleKind)
            if (!angle.isFinite()) return@forEach

            val radius = metricValue(radiusMetric, sample) ?: return@forEach
            if (!radius.isFinite() || radius < 0.0) return@forEach

            val color = colorMetric?.let {
                sessionColorValue(
                    value = metricValue(it, sample),
                    useAbsoluteValue = colorUseAbsoluteValue
                )
            }
            add(
                EligibleAnalysisPoint(
                    sample = sample,
                    angleDeg = angle,
                    radius = radius,
                    colorValue = color?.takeIf { it.isFinite() }
                )
            )
        }
    }

    val points =
        if (aggregationWindowMs > 0L && kind != null) {
            aggregateAnalysisPoints(
                eligible = eligible,
                angleKind = kind,
                windowMs = aggregationWindowMs,
                colorRequired = colorMetric != null
            )
        } else {
            eligible.map {
                AnalysisPoint(
                    angleDeg = it.angleDeg,
                    radius = it.radius,
                    colorValue = it.colorValue
                )
            }
        }

    val observedRadius = points.maxOfOrNull { it.radius } ?: 0.0
    val radiusMax = niceAnalysisRadiusMax(observedRadius)

    val colors = points.mapNotNull { it.colorValue }
    return SessionAnalysisDataset(
        points = points,
        radiusMax = radiusMax,
        colorMin = colors.minOrNull(),
        colorMax = colors.maxOrNull()
    )
}

private fun analysisSamplesAreContinuousForAggregation(
    previous: PreparedAnalysisSample,
    current: PreparedAnalysisSample,
    windowMs: Long
): Boolean {
    if (!analysisSamplesAreContinuous(previous, current)) return false
    val previousTime = previous.timestampMs ?: return false
    val currentTime = current.timestampMs ?: return false

    /*
     * A gap as large as the aggregation window contains no evidence for the
     * missing part of that window. This matters for the adaptive Normal and
     * Battery Saver profiles, whose sampling cadence can change mid-session.
     */
    return currentTime - previousTime < windowMs
}

private fun aggregateAnalysisPoints(
    eligible: List<EligibleAnalysisPoint>,
    angleKind: AnalysisAngleKind,
    windowMs: Long,
    colorRequired: Boolean
): List<AnalysisPoint> {
    if (eligible.isEmpty() || windowMs <= 0L) return emptyList()

    val points = mutableListOf<AnalysisPoint>()
    var segmentStart = 0
    while (segmentStart < eligible.size) {
        var segmentEnd = segmentStart + 1
        while (
            segmentEnd < eligible.size &&
            analysisSamplesAreContinuousForAggregation(
                previous = eligible[segmentEnd - 1].sample,
                current = eligible[segmentEnd].sample,
                windowMs = windowMs
            )
        ) {
            segmentEnd += 1
        }

        aggregateContinuousAnalysisSegment(
            segment = eligible.subList(segmentStart, segmentEnd),
            angleKind = angleKind,
            windowMs = windowMs,
            colorRequired = colorRequired,
            destination = points
        )
        segmentStart = segmentEnd
    }
    return points
}

private fun aggregateContinuousAnalysisSegment(
    segment: List<EligibleAnalysisPoint>,
    angleKind: AnalysisAngleKind,
    windowMs: Long,
    colorRequired: Boolean,
    destination: MutableList<AnalysisPoint>
) {
    var start = 0
    while (start < segment.size) {
        val startTime = segment[start].sample.timestampMs ?: break
        val windowEnd = startTime + windowMs
        var boundary = start + 1
        while (boundary < segment.size) {
            val time = segment[boundary].sample.timestampMs ?: break
            if (time >= windowEnd) break
            boundary += 1
        }

        // A sample at or beyond the window boundary proves that this
        // continuous segment actually covers the full aggregation period.
        if (boundary >= segment.size) break

        val boundaryTime = segment[boundary].sample.timestampMs ?: break
        if (boundaryTime < windowEnd) break

        val window = segment.subList(start, boundary)
        if (window.size >= 2) {
            aggregateAnalysisWindow(
                window = window,
                angleKind = angleKind,
                colorRequired = colorRequired
            )?.let(destination::add)
        }

        start = boundary
    }
}

private fun aggregateAnalysisWindow(
    window: List<EligibleAnalysisPoint>,
    angleKind: AnalysisAngleKind,
    colorRequired: Boolean
): AnalysisPoint? {
    if (window.isEmpty()) return null

    var sumSin = 0.0
    var sumCos = 0.0
    var radiusSum = 0.0
    var colorSum = 0.0
    var colorCount = 0

    window.forEach { point ->
        val radians = point.angleDeg * PI / 180.0
        sumSin += sin(radians)
        sumCos += cos(radians)
        radiusSum += point.radius
        point.colorValue?.let {
            colorSum += it
            colorCount += 1
        }
    }

    if (abs(sumSin) < 1e-12 && abs(sumCos) < 1e-12) return null
    val meanAngle = normalizeAnalysisAngle(
        atan2(sumSin, sumCos) * 180.0 / PI,
        angleKind
    )
    if (!meanAngle.isFinite()) return null

    val color =
        if (!colorRequired || colorCount == window.size) {
            if (colorRequired) colorSum / colorCount else null
        } else {
            null
        }

    return AnalysisPoint(
        angleDeg = meanAngle,
        radius = radiusSum / window.size,
        colorValue = color
    )
}

internal fun metricObservedRange(
    metric: AnalysisMetric,
    samples: List<PreparedAnalysisSample>
): ClosedFloatingPointRange<Double>? {
    val values = samples.mapNotNull { metricValue(metric, it) }
    if (values.isEmpty()) return null
    val min = values.minOrNull() ?: return null
    val max = values.maxOrNull() ?: return null
    return min..max
}

internal fun niceAnalysisRadiusMax(observedMax: Double): Double {
    if (!observedMax.isFinite() || observedMax <= 0.0) return 1.0
    val padded = observedMax * 1.1
    val magnitude = when {
        padded >= 100.0 -> 10.0
        padded >= 50.0 -> 5.0
        padded >= 20.0 -> 2.0
        padded >= 10.0 -> 1.0
        padded >= 5.0 -> 0.5
        else -> 0.25
    }
    return max(magnitude, kotlin.math.ceil(padded / magnitude) * magnitude)
}
