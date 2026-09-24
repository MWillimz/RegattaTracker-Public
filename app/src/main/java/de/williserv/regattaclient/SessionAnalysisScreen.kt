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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
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
                prepared,
                angleMetric,
                radiusMetric,
                colorMetric,
                activeFilters
            ) {
                buildSessionAnalysisDataset(
                    samples = prepared,
                    angleMetric = angleMetric,
                    radiusMetric = radiusMetric,
                    colorMetric = colorMetric,
                    filters = activeFilters,
                    metricsById = metricsById
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

                if (capabilities.filterMetrics.isNotEmpty()) {
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
                            AnalysisFilters(
                                metrics = capabilities.filterMetrics,
                                preparedSamples = prepared,
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
                    selected?.let(::analysisMetricDisplayName)
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
    val lowColor = MaterialTheme.colorScheme.tertiary
    val highColor = MaterialTheme.colorScheme.primary

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
                    "270°",
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
                        interpolateAnalysisColor(lowColor, highColor, t)
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
        }

        Text(
            text = "${angleMetric.label} · ${radiusMetric.label}",
            modifier = Modifier.align(Alignment.CenterHorizontally),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AnalysisColorLegend(
    metric: AnalysisMetric,
    minValue: Double,
    maxValue: Double
) {
    val lowColor = MaterialTheme.colorScheme.tertiary
    val highColor = MaterialTheme.colorScheme.primary

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                R.string.session_analysis_color_legend,
                metric.label
            ),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(lowColor, highColor)
                    )
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

private fun analysisMetricDisplayName(metric: AnalysisMetric): String =
    metric.unit?.takeIf { it.isNotBlank() }
        ?.let { "${metric.label} ($it)" }
        ?: metric.label

private fun formatAnalysisNumber(value: Double): String =
    String.format(Locale.getDefault(), "%.1f", value)

private fun interpolateAnalysisColor(
    start: Color,
    end: Color,
    fraction: Float
): Color {
    val t = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * t,
        green = start.green + (end.green - start.green) * t,
        blue = start.blue + (end.blue - start.blue) * t,
        alpha = start.alpha + (end.alpha - start.alpha) * t
    )
}
