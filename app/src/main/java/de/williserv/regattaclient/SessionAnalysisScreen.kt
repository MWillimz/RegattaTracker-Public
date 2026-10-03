package de.williserv.regattaclient

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.min

@Composable
fun SessionAnalysisScreen(
    detail: SessionDetailData?,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.session_analysis_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (detail == null || detail.samples.isEmpty()) {
            Text(
                text = stringResource(R.string.session_analysis_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val prepared = remember(detail.session.id, detail.samples) {
                prepareAnalysisSamples(detail.samples)
            }
            val capabilities = remember(detail.session.id, detail.samples) {
                discoverSessionAnalysisCapabilities(
                    sourceSamples = detail.samples,
                    preparedSamples = prepared
                )
            }
            val metricsById = remember(capabilities.metrics) {
                capabilities.metrics.associateBy { it.id }
            }

            var angleId by rememberSaveable(detail.session.id) {
                mutableStateOf(capabilities.defaultAngleId)
            }
            var radiusId by rememberSaveable(detail.session.id) {
                mutableStateOf(capabilities.defaultRadiusId)
            }
            var colorId by rememberSaveable(detail.session.id) {
                mutableStateOf<String?>(null)
            }

            LaunchedEffect(capabilities, angleId, radiusId, colorId) {
                if (capabilities.angleMetrics.none { it.id == angleId }) {
                    angleId = capabilities.defaultAngleId
                }
                if (capabilities.radiusMetrics.none { it.id == radiusId }) {
                    radiusId = capabilities.defaultRadiusId
                }
                if (colorId != null && capabilities.colorMetrics.none { it.id == colorId }) {
                    colorId = null
                }
            }

            val activeFilterRanges =
                remember(detail.session.id) {
                    mutableStateMapOf<String, ClosedFloatingPointRange<Float>>()
                }
            var filtersExpanded by rememberSaveable(detail.session.id) {
                mutableStateOf(false)
            }
            var allFiltersExpanded by rememberSaveable(detail.session.id) {
                mutableStateOf(false)
            }
            var gpsManeuverFilterEnabled by rememberSaveable(detail.session.id) {
                mutableStateOf(false)
            }
            var gpsManeuverThresholdDeg by rememberSaveable(detail.session.id) {
                mutableStateOf(DEFAULT_GPS_MANEUVER_THRESHOLD_DEG.toFloat())
            }
            var gpsManeuverRecoverySeconds by rememberSaveable(detail.session.id) {
                mutableStateOf(DEFAULT_ANALYSIS_RECOVERY_SECONDS.toFloat())
            }
            var imuStabilityFilterEnabled by rememberSaveable(detail.session.id) {
                mutableStateOf(false)
            }
            var imuMaxAttitudeRateDps by rememberSaveable(detail.session.id) {
                mutableStateOf(DEFAULT_IMU_STEADY_ATTITUDE_RATE_DPS.toFloat())
            }
            var imuRecoverySeconds by rememberSaveable(detail.session.id) {
                mutableStateOf(DEFAULT_ANALYSIS_RECOVERY_SECONDS.toFloat())
            }

            LaunchedEffect(
                capabilities.gpsManeuverFilterAvailable,
                capabilities.imuStabilityFilterAvailable
            ) {
                if (!capabilities.gpsManeuverFilterAvailable) {
                    gpsManeuverFilterEnabled = false
                }
                if (!capabilities.imuStabilityFilterAvailable) {
                    imuStabilityFilterEnabled = false
                }
            }

            val sampleFilters = buildList<AnalysisSampleFilter> {
                if (
                    gpsManeuverFilterEnabled &&
                    capabilities.gpsManeuverFilterAvailable
                ) {
                    add(
                        GpsManeuverAnalysisFilter(
                            changeThresholdDeg =
                                gpsManeuverThresholdDeg.toDouble(),
                            recoverySeconds =
                                gpsManeuverRecoverySeconds.toDouble()
                        )
                    )
                }
                if (
                    imuStabilityFilterEnabled &&
                    capabilities.imuStabilityFilterAvailable
                ) {
                    add(
                        ImuStabilityAnalysisFilter(
                            maxAttitudeRateDps = imuMaxAttitudeRateDps.toDouble(),
                            recoverySeconds = imuRecoverySeconds.toDouble()
                        )
                    )
                }
            }
            val analysisSamples = remember(prepared, sampleFilters) {
                applyAnalysisSampleFilters(prepared, sampleFilters)
            }

            LaunchedEffect(analysisSamples, metricsById) {
                activeFilterRanges.keys.toList().forEach { metricId ->
                    val metric = metricsById[metricId]
                    val observed = metric?.let {
                        metricObservedRange(it, analysisSamples)
                    }
                    if (observed == null) {
                        activeFilterRanges.remove(metricId)
                    } else {
                        val current = activeFilterRanges[metricId]
                            ?: return@forEach
                        val observedStart = observed.start.toFloat()
                        val observedEnd = observed.endInclusive.toFloat()
                        val start = current.start.coerceIn(
                            observedStart,
                            observedEnd
                        )
                        val end = current.endInclusive.coerceIn(
                            observedStart,
                            observedEnd
                        )
                        activeFilterRanges[metricId] =
                            minOf(start, end)..maxOf(start, end)
                    }
                }
            }

            val activeFilters = activeFilterRanges.mapNotNull { (metricId, range) ->
                if (metricsById[metricId] == null) {
                    null
                } else {
                    AnalysisRangeFilter(
                        metricId = metricId,
                        min = range.start.toDouble(),
                        max = range.endInclusive.toDouble()
                    )
                }
            }

            val angleMetric =
                capabilities.angleMetrics.firstOrNull { it.id == angleId }
                    ?: capabilities.angleMetrics.first()
            val radiusMetric =
                capabilities.radiusMetrics.firstOrNull { it.id == radiusId }
                    ?: capabilities.radiusMetrics.first()
            val colorMetric =
                colorId?.let { selected ->
                    capabilities.colorMetrics.firstOrNull { it.id == selected }
                }

            val dataset = remember(
                analysisSamples,
                angleMetric,
                radiusMetric,
                colorMetric,
                activeFilters
            ) {
                buildSessionAnalysisDataset(
                    samples = analysisSamples,
                    angleMetric = angleMetric,
                    radiusMetric = radiusMetric,
                    colorMetric = colorMetric,
                    filters = activeFilters,
                    metricsById = metricsById,
                    aggregationWindowMs = ANALYSIS_AGGREGATION_WINDOW_MS
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    SessionPolarPlot(
                        dataset = dataset,
                        angleMetric = angleMetric,
                        radiusMetric = radiusMetric,
                        colorMetric = colorMetric,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    )
                }

                item {
                    AnalysisMetricSelector(
                        label = stringResource(R.string.session_analysis_angle),
                        selected = angleMetric,
                        metrics = capabilities.angleMetrics,
                        use = AnalysisMetricUse.ANGLE,
                        onSelected = { angleId = it.id }
                    )
                }

                item {
                    AnalysisMetricSelector(
                        label = stringResource(R.string.session_analysis_radius),
                        selected = radiusMetric,
                        metrics = capabilities.radiusMetrics,
                        use = AnalysisMetricUse.RADIUS,
                        onSelected = { radiusId = it.id }
                    )
                }

                item {
                    AnalysisMetricSelector(
                        label = stringResource(R.string.session_analysis_color),
                        selected = colorMetric,
                        metrics = capabilities.colorMetrics,
                        use = AnalysisMetricUse.COLOR,
                        allowNone = true,
                        onClear = { colorId = null },
                        onSelected = { colorId = it.id }
                    )
                }

                if (colorMetric != null &&
                    dataset.colorMin != null &&
                    dataset.colorMax != null
                ) {
                    item {
                        AnalysisColorLegend(
                            metric = colorMetric,
                            minValue = dataset.colorMin,
                            maxValue = dataset.colorMax
                        )
                    }
                }

                if (
                    capabilities.filterMetrics.isNotEmpty() ||
                    capabilities.gpsManeuverFilterAvailable ||
                    capabilities.imuStabilityFilterAvailable
                ) {
                    item {
                        TextButton(
                            onClick = { filtersExpanded = !filtersExpanded }
                        ) {
                            Text(
                                if (filtersExpanded) {
                                    stringResource(R.string.session_analysis_hide_filters)
                                } else {
                                    stringResource(R.string.session_analysis_show_filters)
                                }
                            )
                        }
                    }

                    if (filtersExpanded) {
                        item {
                            AnalysisStateFilters(
                                gpsAvailable =
                                    capabilities.gpsManeuverFilterAvailable,
                                gpsEnabled = gpsManeuverFilterEnabled,
                                onGpsEnabledChange = {
                                    gpsManeuverFilterEnabled = it
                                },
                                gpsThresholdDeg = gpsManeuverThresholdDeg,
                                onGpsThresholdChange = {
                                    gpsManeuverThresholdDeg = it
                                },
                                gpsRecoverySeconds =
                                    gpsManeuverRecoverySeconds,
                                onGpsRecoveryChange = {
                                    gpsManeuverRecoverySeconds = it
                                },
                                imuAvailable =
                                    capabilities.imuStabilityFilterAvailable,
                                imuEnabled = imuStabilityFilterEnabled,
                                onImuEnabledChange = {
                                    imuStabilityFilterEnabled = it
                                },
                                imuMaxAttitudeRateDps = imuMaxAttitudeRateDps,
                                onImuMaxAttitudeRateChange = {
                                    imuMaxAttitudeRateDps = it
                                },
                                imuRecoverySeconds = imuRecoverySeconds,
                                onImuRecoveryChange = {
                                    imuRecoverySeconds = it
                                }
                            )
                        }
                        item {
                            AnalysisFilters(
                                metrics = capabilities.filterMetrics,
                                preparedSamples = analysisSamples,
                                activeRanges = activeFilterRanges,
                                allExpanded = allFiltersExpanded,
                                onAllExpandedChange = { allFiltersExpanded = it }
                            )
                        }
                    }
                }

                item {
                    Text(
                        text = stringResource(
                            R.string.session_analysis_points,
                            dataset.points.size
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.session_back))
        }
    }
}


@Composable
private fun AnalysisMetricSelector(
    label: String,
    selected: AnalysisMetric?,
    metrics: List<AnalysisMetric>,
    use: AnalysisMetricUse,
    allowNone: Boolean = false,
    onClear: () -> Unit = {},
    onSelected: (AnalysisMetric) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var allExpanded by remember { mutableStateOf(false) }

    val recommended = metrics.filter { it.isRecommendedFor(use) }
    val additional = metrics.filterNot { it.isRecommendedFor(use) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontWeight = FontWeight.SemiBold
        )
        Box {
            OutlinedButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    selected?.let { analysisMetricDisplayName(it) }
                        ?: stringResource(R.string.session_analysis_none)
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                if (allowNone) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.session_analysis_none))
                        },
                        onClick = {
                            onClear()
                            menuExpanded = false
                        }
                    )
                }

                if (recommended.isNotEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.session_analysis_recommended))
                        },
                        onClick = {},
                        enabled = false
                    )
                    recommended.forEach { metric ->
                        DropdownMenuItem(
                            text = { Text(analysisMetricDisplayName(metric)) },
                            onClick = {
                                onSelected(metric)
                                menuExpanded = false
                            }
                        )
                    }
                }

                if (additional.isNotEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (allExpanded) {
                                    stringResource(R.string.session_analysis_hide_all_sensors)
                                } else {
                                    stringResource(
                                        R.string.session_analysis_all_sensors,
                                        additional.size
                                    )
                                }
                            )
                        },
                        onClick = { allExpanded = !allExpanded }
                    )

                    if (allExpanded) {
                        additional.forEach { metric ->
                            DropdownMenuItem(
                                text = { Text(analysisMetricDisplayName(metric)) },
                                onClick = {
                                    onSelected(metric)
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisStateFilters(
    gpsAvailable: Boolean,
    gpsEnabled: Boolean,
    onGpsEnabledChange: (Boolean) -> Unit,
    gpsThresholdDeg: Float,
    onGpsThresholdChange: (Float) -> Unit,
    gpsRecoverySeconds: Float,
    onGpsRecoveryChange: (Float) -> Unit,
    imuAvailable: Boolean,
    imuEnabled: Boolean,
    onImuEnabledChange: (Boolean) -> Unit,
    imuMaxAttitudeRateDps: Float,
    onImuMaxAttitudeRateChange: (Float) -> Unit,
    imuRecoverySeconds: Float,
    onImuRecoveryChange: (Float) -> Unit
) {
    if (!gpsAvailable && !imuAvailable) return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (gpsAvailable) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = gpsEnabled,
                        onCheckedChange = onGpsEnabledChange
                    )
                    Text(stringResource(R.string.session_analysis_gps_maneuver_filter))
                }
                if (gpsEnabled) {
                    Text(
                        text = stringResource(
                            R.string.session_analysis_gps_maneuver_threshold,
                            gpsThresholdDeg.toInt()
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    Slider(
                        value = gpsThresholdDeg,
                        onValueChange = onGpsThresholdChange,
                        valueRange = 5f..45f,
                        steps = 39,
                        modifier = Modifier.fillMaxWidth()
                    )
                    AnalysisRecoverySlider(
                        value = gpsRecoverySeconds,
                        onValueChange = onGpsRecoveryChange
                    )
                }
            }
        }

        if (imuAvailable) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = imuEnabled,
                        onCheckedChange = onImuEnabledChange
                    )
                    Text(stringResource(R.string.session_analysis_imu_stability_filter))
                }
                if (imuEnabled) {
                    Text(
                        text = stringResource(
                            R.string.session_analysis_imu_attitude_limit,
                            formatAnalysisNumber(imuMaxAttitudeRateDps.toDouble())
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    Slider(
                        value = imuMaxAttitudeRateDps,
                        onValueChange = onImuMaxAttitudeRateChange,
                        valueRange = 0.5f..10f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    AnalysisRecoverySlider(
                        value = imuRecoverySeconds,
                        onValueChange = onImuRecoveryChange
                    )
                }
            }
        }
    }
}

@Composable
private fun AnalysisRecoverySlider(
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Text(
        text = stringResource(
            R.string.session_analysis_recovery_seconds,
            value.toInt()
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = 0f..15f,
        steps = 14,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun AnalysisFilters(
    metrics: List<AnalysisMetric>,
    preparedSamples: List<PreparedAnalysisSample>,
    activeRanges: MutableMap<String, ClosedFloatingPointRange<Float>>,
    allExpanded: Boolean,
    onAllExpandedChange: (Boolean) -> Unit
) {
    val recommended = metrics.filter {
        it.isRecommendedFor(AnalysisMetricUse.FILTER)
    }
    val additional = metrics.filterNot {
        it.isRecommendedFor(AnalysisMetricUse.FILTER)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.session_analysis_filters),
            fontWeight = FontWeight.SemiBold
        )

        recommended.forEach { metric ->
            AnalysisFilterRow(
                metric = metric,
                samples = preparedSamples,
                activeRanges = activeRanges
            )
        }

        if (additional.isNotEmpty()) {
            TextButton(onClick = { onAllExpandedChange(!allExpanded) }) {
                Text(
                    if (allExpanded) {
                        stringResource(R.string.session_analysis_hide_all_sensors)
                    } else {
                        stringResource(
                            R.string.session_analysis_all_sensors,
                            additional.size
                        )
                    }
                )
            }
        }

        if (allExpanded) {
            additional.forEach { metric ->
                AnalysisFilterRow(
                    metric = metric,
                    samples = preparedSamples,
                    activeRanges = activeRanges
                )
            }
        }
    }
}

@Composable
private fun AnalysisFilterRow(
    metric: AnalysisMetric,
    samples: List<PreparedAnalysisSample>,
    activeRanges: MutableMap<String, ClosedFloatingPointRange<Float>>
) {
    val observed = remember(metric, samples) {
        metricObservedRange(metric, samples)
    } ?: return
    val observedFloat = observed.start.toFloat()..observed.endInclusive.toFloat()
    val active = activeRanges[metric.id]
    val enabled = active != null

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = enabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        activeRanges[metric.id] = observedFloat
                    } else {
                        activeRanges.remove(metric.id)
                    }
                }
            )
            Text(analysisMetricDisplayName(metric))
        }

        if (enabled) {
            val range = active ?: observedFloat
            if (observed.start < observed.endInclusive) {
                RangeSlider(
                    value = range,
                    onValueChange = { activeRanges[metric.id] = it },
                    valueRange = observedFloat,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                text = stringResource(
                    R.string.session_analysis_filter_range,
                    formatAnalysisNumber(range.start.toDouble()),
                    formatAnalysisNumber(range.endInclusive.toDouble()),
                    metric.unit.orEmpty()
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun SessionPolarPlot(
    dataset: SessionAnalysisDataset,
    angleMetric: AnalysisMetric,
    radiusMetric: AnalysisMetric,
    colorMetric: AnalysisMetric?,
    modifier: Modifier = Modifier
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val pointColor = MaterialTheme.colorScheme.primary
    val neutralColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val colorScale = analysisColorScale()

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val labelSpace = 28.dp.toPx()
                val plotRadius = (min(size.width, size.height) / 2f - labelSpace)
                    .coerceAtLeast(1f)
                val center = Offset(size.width / 2f, size.height / 2f)

                repeat(4) { index ->
                    drawCircle(
                        color = gridColor,
                        radius = plotRadius * (index + 1) / 4f,
                        center = center,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 1.dp.toPx()
                        )
                    )
                }
                drawLine(
                    gridColor,
                    Offset(center.x, center.y - plotRadius),
                    Offset(center.x, center.y + plotRadius),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    gridColor,
                    Offset(center.x - plotRadius, center.y),
                    Offset(center.x + plotRadius, center.y),
                    strokeWidth = 1.dp.toPx()
                )

                val paint = Paint().apply {
                    color = labelColor.toArgb()
                    textSize = 11.sp.toPx()
                    isAntiAlias = true
                }
                paint.textAlign = Paint.Align.CENTER
                drawContext.canvas.nativeCanvas.drawText(
                    "0°",
                    center.x,
                    center.y - plotRadius - 6.dp.toPx(),
                    paint
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "180°",
                    center.x,
                    center.y + plotRadius + 16.dp.toPx(),
                    paint
                )
                paint.textAlign = Paint.Align.LEFT
                drawContext.canvas.nativeCanvas.drawText(
                    "90°",
                    center.x + plotRadius + 4.dp.toPx(),
                    center.y + 4.dp.toPx(),
                    paint
                )
                paint.textAlign = Paint.Align.RIGHT
                drawContext.canvas.nativeCanvas.drawText(
                    if (angleMetric.angleKind == AnalysisAngleKind.RELATIVE) "-90°" else "270°",
                    center.x - plotRadius - 4.dp.toPx(),
                    center.y + 4.dp.toPx(),
                    paint
                )

                val maxRadius = dataset.radiusMax.coerceAtLeast(0.0001)
                dataset.points.forEach { point ->
                    val projection = projectAnalysisAngleDegrees(
                        point.angleDeg,
                        angleMetric.angleKind ?: AnalysisAngleKind.CIRCULAR
                    )
                    if (!projection.first.isFinite() || !projection.second.isFinite()) {
                        return@forEach
                    }
                    val radial = (point.radius / maxRadius)
                        .coerceIn(0.0, 1.0)
                        .toFloat()
                    val plotted = Offset(
                        x = center.x + projection.first.toFloat() * plotRadius * radial,
                        y = center.y + projection.second.toFloat() * plotRadius * radial
                    )

                    val color = if (
                        colorMetric != null &&
                        point.colorValue != null &&
                        dataset.colorMin != null &&
                        dataset.colorMax != null
                    ) {
                        val span = dataset.colorMax - dataset.colorMin
                        val t = if (span > 0.0) {
                            ((point.colorValue - dataset.colorMin) / span)
                                .coerceIn(0.0, 1.0)
                                .toFloat()
                        } else {
                            0.5f
                        }
                        sampleAnalysisColor(colorScale, t)
                    } else if (colorMetric != null) {
                        neutralColor
                    } else {
                        pointColor
                    }

                    drawCircle(
                        color = color,
                        radius = 2.5.dp.toPx(),
                        center = plotted
                    )
                }

                paint.textAlign = Paint.Align.RIGHT
                drawContext.canvas.nativeCanvas.drawText(
                    "${formatAnalysisNumber(dataset.radiusMax)} ${radiusMetric.unit.orEmpty()}",
                    center.x + plotRadius,
                    center.y - 5.dp.toPx(),
                    paint
                )
            }

            if (
                colorMetric != null &&
                dataset.colorMin != null &&
                dataset.colorMax != null
            ) {
                PlotColorLegend(
                    metric = colorMetric,
                    minValue = dataset.colorMin,
                    maxValue = dataset.colorMax,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                )
            }
        }

        Text(
            text = "${analysisMetricLabel(angleMetric)} · " +
                analysisMetricLabel(radiusMetric),
            modifier = Modifier.align(Alignment.CenterHorizontally),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PlotColorLegend(
    metric: AnalysisMetric,
    minValue: Double,
    maxValue: Double,
    modifier: Modifier = Modifier
) {
    val colorScale = analysisColorScale()

    Column(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = analysisMetricDisplayName(metric),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .width(112.dp)
                .height(6.dp)
                .background(
                    brush = Brush.horizontalGradient(colors = colorScale),
                    shape = RoundedCornerShape(3.dp)
                )
        )
        Row(
            modifier = Modifier.width(112.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatAnalysisNumber(minValue),
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatAnalysisNumber(maxValue),
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AnalysisColorLegend(
    metric: AnalysisMetric,
    minValue: Double,
    maxValue: Double
) {
    val colorScale = analysisColorScale()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                R.string.session_analysis_color_legend,
                analysisMetricLabel(metric)
            ),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(
                    Brush.horizontalGradient(colors = colorScale)
                )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${formatAnalysisNumber(minValue)} ${metric.unit.orEmpty()}",
                fontSize = 11.sp
            )
            Text(
                "${formatAnalysisNumber(maxValue)} ${metric.unit.orEmpty()}",
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun analysisMetricLabel(metric: AnalysisMetric): String =
    when (metric.id) {
        "gps.cog" -> stringResource(R.string.session_metric_cog)
        "gps.sog" -> stringResource(R.string.session_metric_sog)
        "measurement:nmea.heading_magnetic_deg" ->
            stringResource(R.string.session_metric_mag)
        "measurement:nmea.awa_deg" ->
            stringResource(R.string.session_metric_awa)
        "measurement:nmea.twa_deg" ->
            stringResource(R.string.session_metric_twa)
        "measurement:nmea.stw_mps" ->
            stringResource(R.string.session_metric_stw)
        "measurement:nmea.aws_mps" ->
            stringResource(R.string.session_metric_aws)
        "measurement:nmea.tws_mps" ->
            stringResource(R.string.session_metric_tws)
        "measurement:regattalink.motion.heel_deg" ->
            stringResource(R.string.session_metric_heel)
        "measurement:regattalink.motion.pitch_deg" ->
            stringResource(R.string.session_metric_pitch_imu)
        "measurement:nmea.pitch_deg" ->
            stringResource(R.string.session_metric_pitch_boat_data)
        "measurement:nmea.depth_m" ->
            stringResource(R.string.session_metric_depth)
        "measurement:nmea.water_temperature_c" ->
            stringResource(R.string.session_metric_water_temperature)
        "derived.vmg" ->
            stringResource(R.string.session_metric_vmg)
        "derived.acceleration_5s" ->
            stringResource(R.string.session_metric_acceleration_5s)
        else -> metric.label
    }

@Composable
private fun analysisMetricDisplayName(metric: AnalysisMetric): String {
    val label = analysisMetricLabel(metric)
    return metric.unit?.takeIf { it.isNotBlank() }
        ?.let { "$label ($it)" }
        ?: label
}

private fun formatAnalysisNumber(value: Double): String =
    String.format(Locale.getDefault(), "%.1f", value)

private fun analysisColorScale(): List<Color> = listOf(
    Color(0xFF440154),
    Color(0xFF3B528B),
    Color(0xFF21918C),
    Color(0xFF5EC962),
    Color(0xFFFDE725)
)

private fun sampleAnalysisColor(
    scale: List<Color>,
    fraction: Float
): Color {
    if (scale.isEmpty()) return Color.Unspecified
    if (scale.size == 1) return scale.first()

    val t = fraction.coerceIn(0f, 1f)
    val scaled = t * (scale.size - 1)
    val lowerIndex = scaled.toInt().coerceIn(0, scale.lastIndex)
    val upperIndex = (lowerIndex + 1).coerceAtMost(scale.lastIndex)
    val localT = scaled - lowerIndex

    val start = scale[lowerIndex]
    val end = scale[upperIndex]
    return Color(
        red = start.red + (end.red - start.red) * localT,
        green = start.green + (end.green - start.green) * localT,
        blue = start.blue + (end.blue - start.blue) * localT,
        alpha = start.alpha + (end.alpha - start.alpha) * localT
    )
}
