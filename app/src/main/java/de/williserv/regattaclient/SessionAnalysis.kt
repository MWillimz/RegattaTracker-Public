package de.williserv.regattaclient

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

internal const val MPS_TO_KNOTS = 1.9438444924406

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
    val cogDeg: Double,
    val sogMps: Double,
    val measurements: Map<String, Double>
)

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
    val defaultRadiusId: String
)

internal fun prepareAnalysisSamples(
    samples: List<SessionTrackingSample>
): List<PreparedAnalysisSample> = samples.map { sample ->
    PreparedAnalysisSample(
        cogDeg = sample.cog.toDouble(),
        sogMps = sample.sog.toDouble(),
        measurements = sessionNumericMeasurementValues(sample)
    )
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
            label = "Trim",
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
            val normalizedUnit = measurement.unit?.lowercase()
            val angular = when (normalizedUnit) {
                "deg", "°" -> AnalysisAngleKind.CIRCULAR
                "rad" -> AnalysisAngleKind.CIRCULAR
                else -> null
            }
            metrics += AnalysisMetric(
                id = "measurement:${measurement.key}",
                label = measurement.label,
                unit = if (normalizedUnit == "rad") "deg" else measurement.unit,
                source = AnalysisMetricSource.MEASUREMENT,
                measurementKey = measurement.key,
                angleKind = angular,
                displayScale = if (normalizedUnit == "rad") {
                    180.0 / PI
                } else {
                    1.0
                }
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
        defaultRadiusId = defaultRadius
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
