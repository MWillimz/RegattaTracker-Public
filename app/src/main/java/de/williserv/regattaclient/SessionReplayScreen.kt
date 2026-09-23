package de.williserv.regattaclient

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

@Composable
fun SessionReplayScreen(
    detail: SessionDetailData?,
    modifier: Modifier = Modifier,
    extraFieldIds: Set<String> = emptySet(),
    onBack: () -> Unit
) {
    val samples = detail?.samples.orEmpty()
    var selectedIndex by remember(detail?.session?.id, samples.size) {
        mutableIntStateOf(replayInitialSampleIndex(samples.size))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.session_replay_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        if (detail != null) {
            Text(
                text = detail.session.displayName,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (detail == null || samples.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.session_replay_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val safeIndex = selectedIndex.coerceIn(0, samples.lastIndex)
            val selectedSample = samples[safeIndex]
            val extraFields = detail.replayFields
                .filter { it.id in extraFieldIds }

            ReplayCurrentSampleCard(
                sample = selectedSample,
                fallbackEvent = detail.session.eventIdentifier,
                extraFields = extraFields
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReplayTrackCanvas(
                    samples = samples,
                    selectedIndex = safeIndex,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                ReplayTimeline(
                    samples = samples,
                    selectedIndex = safeIndex,
                    onSelectedIndex = { selectedIndex = it },
                    modifier = Modifier
                        .width(52.dp)
                        .fillMaxHeight()
                )
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
private fun ReplayCurrentSampleCard(
    sample: SessionTrackingSample,
    fallbackEvent: String?,
    extraFields: List<ReplayExtraField>
) {
    val epochMillis = sample.sampleEpochMillis()
    val timeText = epochMillis?.let {
        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it))
    } ?: sample.timestamp

    val sogText = sogKnotsForDisplay(sample.sog.toDouble())?.let {
        stringResource(R.string.session_speed_kn, it)
    } ?: stringResource(R.string.session_unknown_value)

    val cogText = cogDegreesForDisplay(sample.cog.toDouble())?.let {
        stringResource(R.string.session_cog_degrees, it)
    } ?: stringResource(R.string.session_unknown_value)

    val event = sample.resolvedEventName
        ?.takeIf { it.isNotBlank() }
        ?: fallbackEvent?.takeIf { it.isNotBlank() }

    val extraValues = remember(sample.localId, extraFields) {
        replayExtraFieldValues(sample, extraFields)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ReplayValue(
                    label = stringResource(R.string.session_replay_time_label),
                    value = timeText,
                    modifier = Modifier.weight(1f)
                )
                ReplayValue(
                    label = stringResource(R.string.session_replay_sog_label),
                    value = sogText,
                    modifier = Modifier.weight(0.8f)
                )
                ReplayValue(
                    label = stringResource(R.string.session_replay_cog_label),
                    value = cogText,
                    modifier = Modifier.weight(0.8f)
                )
            }

            if (extraFields.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    extraFields.forEach { field ->
                        val value = extraValues[field.id]
                            ?: stringResource(R.string.session_unknown_value)
                        ReplayValue(
                            label = replayExtraFieldReplayLabel(field),
                            value = value,
                            modifier = Modifier.width(150.dp)
                        )
                    }
                }
            }

            event?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.session_event_value, it),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun replayExtraFieldReplayLabel(field: ReplayExtraField): String {
    val source = when (field.source) {
        ReplayExtraFieldSource.INTERNAL_IMU ->
            stringResource(R.string.session_replay_field_source_internal_imu)
        ReplayExtraFieldSource.MEASUREMENT ->
            field.measurementGroup
                ?.takeIf { it.isNotBlank() }
                ?.let { group ->
                    if (group.equals("regattalink", ignoreCase = true)) {
                        "RegattaLink"
                    } else {
                        group
                    }
                }
                ?: stringResource(R.string.session_replay_field_source_measurements)
    }
    return "$source · ${field.label}"
}

@Composable
private fun ReplayValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        Text(
            text = value,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun ReplayTrackCanvas(
    samples: List<SessionTrackingSample>,
    selectedIndex: Int,
    modifier: Modifier = Modifier
) {
    val selected = samples[selectedIndex]
    val coursePoints = remember(selected.raceContextId, selected.courseJson) {
        parseCourseOverlayPoints(
            courseJson = selected.courseJson.orEmpty(),
            courseShortened = false
        )
    }
    val density = LocalDensity.current
    val densityValue = density.density
    val minPaddingPx = with(density) { 12.dp.toPx() }
    val trackColor = MaterialTheme.colorScheme.primary
    val futureColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)
    val courseColor = MaterialTheme.colorScheme.secondary
    val startColor = MaterialTheme.colorScheme.tertiary
    val finishColor = MaterialTheme.colorScheme.error
    val markerColor = MaterialTheme.colorScheme.primary

    val validSamples = remember(samples) {
        samples.filter { it.hasUsableGpsPosition() }
    }

    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            )
    ) {
        if (validSamples.isEmpty()) {
            Text(
                text = stringResource(R.string.session_replay_no_position),
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
        ) {
            val geoPoints = buildList {
                validSamples.forEach { add(OwnShipGeoPoint(it.lat, it.lon)) }
                coursePoints.forEach { add(OwnShipGeoPoint(it.lat, it.lon)) }
            }

            val projection = ReplayMapProjection.create(
                points = geoPoints,
                widthPx = size.width,
                heightPx = size.height,
                paddingFraction = 0.09f,
                minPaddingPx = minPaddingPx
            ) ?: return@Canvas

            fun point(sample: SessionTrackingSample): Offset? =
                projection.project(sample.lat, sample.lon)

            fun coursePoint(point: CourseOverlayGeoPoint): Offset? =
                projection.project(point.lat, point.lon)

            val sizing = replayCanvasSizing(
                canvasScalePx = min(size.width, size.height),
                density = densityValue
            )

            for (index in 1 until samples.size) {
                val previous = samples[index - 1]
                val current = samples[index]
                if (
                    !previous.hasUsableGpsPosition() ||
                    !current.hasUsableGpsPosition() ||
                    !areSessionSamplesContiguous(previous, current)
                ) {
                    continue
                }
                val from = point(previous) ?: continue
                val to = point(current) ?: continue
                drawLine(
                    color = if (index <= selectedIndex) trackColor else futureColor,
                    start = from,
                    end = to,
                    strokeWidth = if (index <= selectedIndex) {
                        sizing.sailedTrackWidthPx
                    } else {
                        sizing.futureTrackWidthPx
                    }
                )
            }

            listOf(CourseOverlayKind.START, CourseOverlayKind.FINISH).forEach { kind ->
                val line = coursePoints.filter { it.kind == kind }.take(2)
                if (line.size == 2) {
                    val from = coursePoint(line[0])
                    val to = coursePoint(line[1])
                    if (from != null && to != null) {
                        drawLine(
                            color = if (kind == CourseOverlayKind.START) startColor else finishColor,
                            start = from,
                            end = to,
                            strokeWidth = sizing.startFinishWidthPx
                        )
                    }
                }
            }

            val activeMarks = coursePoints.filter { it.kind == CourseOverlayKind.MARK && !it.inactive }
            val startLine = coursePoints.filter { it.kind == CourseOverlayKind.START }.take(2)
            val finishLine = coursePoints.filter { it.kind == CourseOverlayKind.FINISH }.take(2)
            val referenceRoute = buildList {
                fun midpoint(line: List<CourseOverlayGeoPoint>): Offset? {
                    if (line.size != 2) return null
                    val a = coursePoint(line[0]) ?: return null
                    val b = coursePoint(line[1]) ?: return null
                    return Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                }
                midpoint(startLine)?.let(::add)
                activeMarks.mapNotNull(::coursePoint).forEach(::add)
                midpoint(finishLine)?.let(::add)
            }
            referenceRoute.zipWithNext().forEach { (from, to) ->
                drawLine(
                    color = courseColor.copy(alpha = 0.40f),
                    start = from,
                    end = to,
                    strokeWidth = sizing.referenceRouteWidthPx
                )
            }

            coursePoints
                .filter { it.kind == CourseOverlayKind.MARK }
                .forEachIndexed { index, mark ->
                    coursePoint(mark)?.let { center ->
                        val radius = sizing.markRadiusPx
                        val stroke = sizing.markStrokeWidthPx
                        val color = courseColor.copy(alpha = if (mark.inactive) 0.28f else 0.95f)
                        val buoy = Path().apply {
                            moveTo(center.x, center.y - radius)
                            lineTo(center.x + radius * 0.7f, center.y + radius)
                            lineTo(center.x - radius * 0.7f, center.y + radius)
                            close()
                        }
                        drawPath(buoy, color.copy(alpha = color.alpha * 0.18f))
                        drawPath(buoy, color, style = Stroke(width = stroke))
                        drawLine(
                            color = color,
                            start = Offset(center.x - radius * 0.9f, center.y + radius),
                            end = Offset(center.x + radius * 0.9f, center.y + radius),
                            strokeWidth = stroke
                        )
                        if (!mark.inactive) {
                            val badgeCenter = Offset(center.x, center.y - radius * 1.65f)
                            drawCircle(color = color, radius = radius * 0.62f, center = badgeCenter)
                            val labelPaint = android.graphics.Paint().apply {
                                isAntiAlias = true
                                textAlign = android.graphics.Paint.Align.CENTER
                                textSize = radius
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                                this.color = android.graphics.Color.WHITE
                            }
                            drawContext.canvas.nativeCanvas.drawText(
                                (index + 1).toString(),
                                badgeCenter.x,
                                badgeCenter.y - (labelPaint.ascent() + labelPaint.descent()) / 2f,
                                labelPaint
                            )
                        }
                    }
                }

            if (selected.hasUsableGpsPosition()) {
                point(selected)?.let { center ->
                    val bearing = cogDegreesForDisplay(selected.cog.toDouble())?.toFloat() ?: 0f
                    drawReplayBoat(
                        center = center,
                        radius = sizing.boatRadiusPx,
                        bearingDegrees = bearing,
                        color = markerColor,
                        outlineWidth = sizing.boatOutlineWidthPx
                    )
                }
            }
        }
    }
}

@Composable
private fun ReplayTimeline(
    samples: List<SessionTrackingSample>,
    selectedIndex: Int,
    onSelectedIndex: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val fractions = remember(samples) { replaySampleFractions(samples) }
    val maxSog = remember(samples) {
        samples.asSequence()
            .map { it.sog.toDouble() }
            .filter { it.isFinite() && it >= 0.0 }
            .maxOrNull()
            ?: 0.0
    }
    var heightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val timelineInsetPx = with(density) { 8.dp.toPx() }
    val timelineStrokeWidthPx = with(density) { 10.dp.toPx() }
    val timelineMinSegmentPx = with(density) { 1.dp.toPx() }
    val timelineBoatRadiusPx = with(density) { 9.dp.toPx() }
    val timelineBoatOutlineWidthPx = with(density) { 4.dp.toPx() }
    val markerColor = MaterialTheme.colorScheme.primary
    val railBackground = MaterialTheme.colorScheme.surfaceVariant

    fun selectAt(y: Float) {
        if (heightPx <= 0) return
        val fraction = (y / heightPx.toFloat()).coerceIn(0f, 1f)
        val index = replaySampleIndexForFraction(fractions, fraction)
        if (index >= 0) onSelectedIndex(index)
    }

    Box(
        modifier = modifier
            .background(railBackground, MaterialTheme.shapes.medium)
            .onSizeChanged { heightPx = it.height }
            .pointerInput(samples.size, heightPx) {
                detectDragGestures(
                    onDragStart = { selectAt(it.y) },
                    onDrag = { change, _ ->
                        change.consume()
                        selectAt(change.position.y)
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val x = size.width / 2f
            if (samples.size == 1) {
                drawLine(
                    color = replaySpeedColor(samples[0].sog.toDouble(), maxSog),
                    start = Offset(x, timelineInsetPx),
                    end = Offset(x, size.height - timelineInsetPx),
                    strokeWidth = timelineStrokeWidthPx
                )
            } else {
                val stride = max(1, samples.size / 600)
                var index = 0
                while (index < samples.lastIndex) {
                    val next = min(samples.lastIndex, index + stride)
                    val y1 = fractions[index] * size.height
                    val y2 = fractions[next] * size.height
                    drawLine(
                        color = replaySpeedColor(samples[index].sog.toDouble(), maxSog),
                        start = Offset(x, y1),
                        end = Offset(x, max(y1 + timelineMinSegmentPx, y2)),
                        strokeWidth = timelineStrokeWidthPx
                    )
                    index = next
                }
            }

            val selectedFraction = fractions
                .getOrElse(selectedIndex) { 0f }
                .coerceIn(0f, 1f)
            drawReplayBoat(
                center = Offset(x, selectedFraction * size.height),
                radius = timelineBoatRadiusPx,
                bearingDegrees = 180f,
                color = markerColor,
                outlineWidth = timelineBoatOutlineWidthPx
            )
        }
    }
}

internal fun replayInitialSampleIndex(sampleCount: Int): Int =
    (sampleCount - 1).coerceAtLeast(0)

internal fun replaySampleFractions(samples: List<SessionTrackingSample>): List<Float> {
    if (samples.isEmpty()) return emptyList()
    if (samples.size == 1) return listOf(0f)

    val validTimes = samples.mapNotNull { it.sampleEpochMillis() }
    val firstTime = validTimes.firstOrNull()
    val lastTime = validTimes.lastOrNull()
    val timeSpan = if (firstTime != null && lastTime != null) lastTime - firstTime else 0L

    var previous = 0f
    return samples.mapIndexed { index, sample ->
        val fallback = index.toFloat() / samples.lastIndex.toFloat()
        val raw = if (timeSpan > 0L) {
            sample.sampleEpochMillis()?.let {
                ((it - firstTime!!) / timeSpan.toDouble()).toFloat()
            } ?: fallback
        } else {
            fallback
        }
        raw.coerceIn(0f, 1f)
            .coerceAtLeast(previous)
            .also { previous = it }
    }
}

internal fun replaySampleIndexForFraction(
    fractions: List<Float>,
    fraction: Float
): Int {
    if (fractions.isEmpty()) return -1
    val target = fraction.coerceIn(0f, 1f)
    var low = 0
    var high = fractions.lastIndex

    while (low < high) {
        val mid = (low + high) / 2
        if (fractions[mid] < target) {
            low = mid + 1
        } else {
            high = mid
        }
    }

    if (low == 0) return 0
    val previous = low - 1
    return if (
        abs(fractions[low] - target) < abs(fractions[previous] - target)
    ) {
        low
    } else {
        previous
    }
}

internal data class ReplayCanvasSizing(
    val sailedTrackWidthPx: Float,
    val futureTrackWidthPx: Float,
    val boatRadiusPx: Float,
    val boatOutlineWidthPx: Float,
    val startFinishWidthPx: Float,
    val referenceRouteWidthPx: Float,
    val markRadiusPx: Float,
    val markStrokeWidthPx: Float
)

internal fun replayCanvasSizing(
    canvasScalePx: Float,
    density: Float
): ReplayCanvasSizing {
    require(canvasScalePx >= 0f) { "canvasScalePx must be non-negative" }
    require(density > 0f) { "density must be positive" }

    fun scaled(fraction: Float, minDp: Float, maxDp: Float): Float =
        (canvasScalePx * fraction).coerceIn(minDp * density, maxDp * density)

    return ReplayCanvasSizing(
        sailedTrackWidthPx = scaled(0.006f, 2.5f, 6f),
        futureTrackWidthPx = scaled(0.004f, 1.5f, 4f),
        boatRadiusPx = scaled(0.016f, 7f, 16f),
        boatOutlineWidthPx = scaled(0.005f, 2f, 5f),
        startFinishWidthPx = scaled(0.007f, 3f, 8f),
        referenceRouteWidthPx = scaled(0.004f, 2f, 5f),
        markRadiusPx = scaled(0.018f, 7f, 18f),
        markStrokeWidthPx = scaled(0.004f, 2f, 5f)
    )
}

internal fun replaySpeedFraction(speedMps: Double, maxSpeedMps: Double): Float {
    if (!speedMps.isFinite() || speedMps < 0.0 || !maxSpeedMps.isFinite() || maxSpeedMps <= 0.0) {
        return 0f
    }
    return (speedMps / maxSpeedMps).toFloat().coerceIn(0f, 1f)
}

private fun replaySpeedColor(speedMps: Double, maxSpeedMps: Double): Color {
    val fraction = replaySpeedFraction(speedMps, maxSpeedMps)
    val red = Color(0xFFD32F2F)
    val yellow = Color(0xFFFFC107)
    val green = Color(0xFF2E7D32)
    return if (fraction <= 0.5f) {
        mixReplayColor(red, yellow, fraction * 2f)
    } else {
        mixReplayColor(yellow, green, (fraction - 0.5f) * 2f)
    }
}

private fun mixReplayColor(first: Color, second: Color, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    return Color(
        red = first.red + (second.red - first.red) * t,
        green = first.green + (second.green - first.green) * t,
        blue = first.blue + (second.blue - first.blue) * t,
        alpha = 1f
    )
}

private data class ReplayMapProjection(
    val centerLat: Double,
    val centerLon: Double,
    val metersPerLonDegree: Double,
    val scale: Double,
    val centerX: Double,
    val centerY: Double
) {
    fun project(lat: Double, lon: Double): Offset? {
        if (!lat.isFinite() || !lon.isFinite()) return null
        val xMeters = (lon - centerLon) * metersPerLonDegree
        val yMeters = (lat - centerLat) * METERS_PER_LAT_DEGREE
        return Offset(
            x = (centerX + xMeters * scale).toFloat(),
            y = (centerY - yMeters * scale).toFloat()
        )
    }

    companion object {
        fun create(
            points: List<OwnShipGeoPoint>,
            widthPx: Float,
            heightPx: Float,
            paddingFraction: Float,
            minPaddingPx: Float
        ): ReplayMapProjection? {
            if (points.isEmpty() || widthPx <= 0f || heightPx <= 0f) return null

            val centerLat = (points.minOf { it.lat } + points.maxOf { it.lat }) / 2.0
            val centerLon = (points.minOf { it.lon } + points.maxOf { it.lon }) / 2.0
            val metersPerLonDegree =
                METERS_PER_LAT_DEGREE * cos(Math.toRadians(centerLat)).coerceAtLeast(0.05)

            val xValues = points.map { (it.lon - centerLon) * metersPerLonDegree }
            val yValues = points.map { (it.lat - centerLat) * METERS_PER_LAT_DEGREE }
            val spanX = (xValues.maxOrNull()!! - xValues.minOrNull()!!).coerceAtLeast(20.0)
            val spanY = (yValues.maxOrNull()!! - yValues.minOrNull()!!).coerceAtLeast(20.0)

            val paddingPx = max(minPaddingPx, min(widthPx, heightPx) * paddingFraction.coerceIn(0f, 0.25f))
            val availableWidth = (widthPx - 2f * paddingPx).coerceAtLeast(1f)
            val availableHeight = (heightPx - 2f * paddingPx).coerceAtLeast(1f)
            val scale = min(
                availableWidth.toDouble() / spanX,
                availableHeight.toDouble() / spanY
            )

            return ReplayMapProjection(
                centerLat = centerLat,
                centerLon = centerLon,
                metersPerLonDegree = metersPerLonDegree,
                scale = scale,
                centerX = widthPx / 2.0,
                centerY = heightPx / 2.0
            )
        }
    }
}

private fun DrawScope.drawReplayBoat(
    center: Offset,
    radius: Float,
    bearingDegrees: Float,
    color: Color,
    outlineWidth: Float
) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius * 1.7f)
        lineTo(center.x + radius, center.y + radius)
        lineTo(center.x, center.y + radius * 0.5f)
        lineTo(center.x - radius, center.y + radius)
        close()
    }

    rotate(
        degrees = bearingDegrees,
        pivot = center
    ) {
        drawPath(
            path = path,
            color = Color.White.copy(alpha = 0.95f),
            style = Stroke(width = outlineWidth)
        )
        drawPath(
            path = path,
            color = color
        )
    }
}

private const val METERS_PER_LAT_DEGREE = 111_320.0
