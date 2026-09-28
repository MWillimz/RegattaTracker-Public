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
private const val IMU_HEEL_KEY = "regattalink.summary.heel_filtered_deg"
private const val IMU_TRIM_KEY = "regattalink.summary.trim_filtered_deg"

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
    DERIVED_VMG
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
    val measurements: Map<String, Double>
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
): List<PreparedAnalysisSample> = samples.map { sample ->
    PreparedAnalysisSample(
        timestampMs = analysisSampleTimestampMs(sample),
        cogDeg = sample.cog.toDouble(),
        sogMps = sample.sog.toDouble(),
        measurements = sessionNumericMeasurementValues(sample)
    )
}

private fun analysisSampleTimestampMs(sample: SessionTrackingSample): Long? {
    return runCatching {
        val local = LocalDateTime.parse(sample.timestamp)
        val offsetSeconds = (sample.utcOffsetMinutes ?: 0) * 60
        local.toInstant(ZoneOffset.ofTotalSeconds(offsetSeconds)).toEpochMilli()
    }.getOrNull()
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
            sample.measurements[IMU_HEEL_KEY]?.isFinite() == true
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
        val heel = sample.measurements[IMU_HEEL_KEY]
        if (time == null || heel == null || !heel.isFinite()) {
            excluded[index] = true
            previousIndex = null
            return@forEachIndexed
        }

        val previous = previousIndex
        if (previous != null) {
            val previousSample = samples[previous]
            val previousTime = previousSample.timestampMs
            val previousHeel = previousSample.measurements[IMU_HEEL_KEY]
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

                    val trim = sample.measurements[IMU_TRIM_KEY]
                    val previousTrim =
                        previousSample.measurements[IMU_TRIM_KEY]
                    val trimRate =
                        if (
                            trim != null &&
                            previousTrim != null &&
                            trim.isFinite() &&
                            previousTrim.isFinite()
                        ) {
                            abs(trim - previousTrim) / dtSeconds
                        } else {
                            0.0
                        }

                    if (
                        max(heelRate, trimRate) >
                        filter.maxAttitudeRateDps
                    ) {
                        /*
                         * The transition spans both 1 Hz summary samples.
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
            key = "regattalink.summary.heel_filtered_deg",
            label = "Heel",
            unit = "deg",
            recommended = setOf(AnalysisMetricUse.COLOR)
        ),
        knownMetric(
            key = "regattalink.summary.trim_filtered_deg",
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
        metric.source != AnalysisMetricSource.DERIVED_VMG ||
            preparedSamples.any { metricValue(metric, it) != null }
    }

    val filterMetrics = colorMetrics

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

internal fun buildSessionAnalysisDataset(
    samples: List<PreparedAnalysisSample>,
    angleMetric: AnalysisMetric,
    radiusMetric: AnalysisMetric,
    colorMetric: AnalysisMetric?,
    filters: List<AnalysisRangeFilter>,
    metricsById: Map<String, AnalysisMetric>
): SessionAnalysisDataset {
    val points = buildList {
        samples.forEach { sample ->
            val passes = filters.all { filter ->
                val metric = metricsById[filter.metricId] ?: return@all false
                val value = metricValue(metric, sample) ?: return@all false
                value in filter.min..filter.max
            }
            if (!passes) return@forEach

            val rawAngle = metricValue(angleMetric, sample) ?: return@forEach
            val kind = angleMetric.angleKind ?: return@forEach
            val angle = normalizeAnalysisAngle(rawAngle, kind)
            if (!angle.isFinite()) return@forEach

            val radius = metricValue(radiusMetric, sample) ?: return@forEach
            if (!radius.isFinite() || radius < 0.0) return@forEach

            val color = colorMetric?.let { metricValue(it, sample) }
            add(
                AnalysisPoint(
                    angleDeg = angle,
                    radius = radius,
                    colorValue = color?.takeIf { it.isFinite() }
                )
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
