package de.williserv.regattaclient

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
    onBack: () -> Unit
) {
    val samples = detail?.samples.orEmpty()
    var selectedIndex by remember(detail?.session?.id, samples.size) {
        mutableIntStateOf(initialReplayIndex(samples.size))
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

            ReplayCurrentSampleCard(
                sample = selectedSample,
                fallbackEvent = detail.session.eventIdentifier
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
                        .width(76.dp)
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
    fallbackEvent: String?
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
    val mapPaddingPx = with(density) { 20.dp.toPx() }
    val boatRadiusPx = with(density) { 16.dp.toPx() }
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
            val geoPoints = replayProjectionPoints(
                samples = validSamples,
                coursePoints = coursePoints
            )

            val projection = ReplayMapProjection.create(
                points = geoPoints,
                widthPx = size.width,
                heightPx = size.height,
                paddingPx = mapPaddingPx
            ) ?: return@Canvas

            fun point(sample: SessionTrackingSample): Offset? =
                projection.project(sample.lat, sample.lon)

            fun coursePoint(point: CourseOverlayGeoPoint): Offset? =
                projection.project(point.lat, point.lon)

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
                    strokeWidth = if (index <= selectedIndex) 4f else 2.5f
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
                            strokeWidth = 5f
                        )
                    }
                }
            }

            coursePoints
                .filter { it.kind == CourseOverlayKind.MARK }
                .forEach { mark ->
                    coursePoint(mark)?.let { center ->
                        drawCircle(
                            color = courseColor.copy(alpha = if (mark.inactive) 0.35f else 0.9f),
                            radius = 7f,
                            center = center,
                            style = Stroke(width = 3f)
                        )
                    }
                }

            if (selected.hasUsableGpsPosition()) {
                point(selected)?.let { center ->
                    val bearing = cogDegreesForDisplay(selected.cog.toDouble())?.toFloat() ?: 0f
                    drawReplayBoat(
                        center = center,
                        radius = boatRadiusPx,
                        bearingDegrees = bearing,
                        color = markerColor
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
    val markerColor = MaterialTheme.colorScheme.primary
    val railBackground = MaterialTheme.colorScheme.surfaceVariant
    val timelineBoatRadiusPx = with(density) { 14.dp.toPx() }
    val timelineHandleMarginPx = timelineBoatRadiusPx * 1.9f

    fun selectAt(y: Float) {
        if (heightPx <= 0) return
        val usableHeight = (
            heightPx.toFloat() - 2f * timelineHandleMarginPx
        ).coerceAtLeast(1f)
        val fraction = (
            (y - timelineHandleMarginPx) / usableHeight
        ).coerceIn(0f, 1f)
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
            val railTop = timelineHandleMarginPx
            val railBottom = (size.height - timelineHandleMarginPx)
                .coerceAtLeast(railTop + 1f)
            val railHeight = railBottom - railTop

            fun railY(fraction: Float): Float =
                railTop + fraction.coerceIn(0f, 1f) * railHeight

            if (samples.size == 1) {
                drawLine(
                    color = replaySpeedColor(samples[0].sog.toDouble(), maxSog),
                    start = Offset(x, railTop),
                    end = Offset(x, railBottom),
                    strokeWidth = 10f
                )
            } else {
                val stride = max(1, samples.size / 600)
                var index = 0
                while (index < samples.lastIndex) {
                    val next = min(samples.lastIndex, index + stride)
                    val y1 = railY(fractions[index])
                    val y2 = railY(fractions[next])
                    drawLine(
                        color = replaySpeedColor(samples[index].sog.toDouble(), maxSog),
                        start = Offset(x, y1),
                        end = Offset(x, max(y1 + 1f, y2)),
                        strokeWidth = 10f
                    )
                    index = next
                }
            }

            val selectedFraction = fractions
                .getOrElse(selectedIndex) { 0f }
                .coerceIn(0f, 1f)
            val selectedBearing = samples
                .getOrNull(selectedIndex)
                ?.cog
                ?.toDouble()
                ?.let(::cogDegreesForDisplay)
                ?.toFloat()
                ?: 0f
            drawReplayBoat(
                center = Offset(x, railY(selectedFraction)),
                radius = timelineBoatRadiusPx,
                bearingDegrees = selectedBearing,
                color = markerColor
            )
        }
    }
}

internal fun replayProjectionPoints(
    samples: List<SessionTrackingSample>,
    coursePoints: List<CourseOverlayGeoPoint>
): List<OwnShipGeoPoint> {
    val track = samples
        .filter { it.hasUsableGpsPosition() }
        .map { OwnShipGeoPoint(it.lat, it.lon) }

    if (track.isEmpty()) {
        return coursePoints.map { OwnShipGeoPoint(it.lat, it.lon) }
    }

    val centerLat = (track.minOf { it.lat } + track.maxOf { it.lat }) / 2.0
    val centerLon = (track.minOf { it.lon } + track.maxOf { it.lon }) / 2.0
    val metersPerLonDegree =
        METERS_PER_LAT_DEGREE *
            cos(Math.toRadians(centerLat)).coerceAtLeast(0.05)

    val trackSpanX = (
        (track.maxOf { it.lon } - track.minOf { it.lon }) *
            metersPerLonDegree
    ).coerceAtLeast(0.0)
    val trackSpanY = (
        (track.maxOf { it.lat } - track.minOf { it.lat }) *
            METERS_PER_LAT_DEGREE
    ).coerceAtLeast(0.0)
    val trackSpanM = max(trackSpanX, trackSpanY)

    /*
     * Fit primarily to the sailed track. Nearby course geometry is useful
     * context, but a distant mark/start/finish must not shrink a short track
     * to a few pixels. A stationary/short session still gets 300 m of context;
     * longer tracks admit proportionally more nearby course geometry.
     */
    val courseContextRadiusM = max(
        REPLAY_MIN_CONTEXT_RADIUS_M,
        trackSpanM * REPLAY_COURSE_CONTEXT_MULTIPLIER
    )

    val nearbyCourse = coursePoints.filter { point ->
        val dx = (point.lon - centerLon) * metersPerLonDegree
        val dy = (point.lat - centerLat) * METERS_PER_LAT_DEGREE
        dx * dx + dy * dy <= courseContextRadiusM * courseContextRadiusM
    }

    return buildList {
        addAll(track)
        nearbyCourse.forEach { add(OwnShipGeoPoint(it.lat, it.lon)) }
    }
}

internal fun initialReplayIndex(sampleCount: Int): Int =
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
            paddingPx: Float
        ): ReplayMapProjection? {
            if (points.isEmpty() || widthPx <= 0f || heightPx <= 0f) return null

            val centerLat = (points.minOf { it.lat } + points.maxOf { it.lat }) / 2.0
            val centerLon = (points.minOf { it.lon } + points.maxOf { it.lon }) / 2.0
            val metersPerLonDegree =
                METERS_PER_LAT_DEGREE * cos(Math.toRadians(centerLat)).coerceAtLeast(0.05)

            val xValues = points.map { (it.lon - centerLon) * metersPerLonDegree }
            val yValues = points.map { (it.lat - centerLat) * METERS_PER_LAT_DEGREE }
            val spanX = (xValues.maxOrNull()!! - xValues.minOrNull()!!)
                .coerceAtLeast(REPLAY_MIN_VIEW_SPAN_M)
            val spanY = (yValues.maxOrNull()!! - yValues.minOrNull()!!)
                .coerceAtLeast(REPLAY_MIN_VIEW_SPAN_M)

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
    color: Color
) {
    drawCircle(
        color = Color.White.copy(alpha = 0.92f),
        radius = radius * 1.45f,
        center = center
    )
    drawCircle(
        color = color.copy(alpha = 0.18f),
        radius = radius * 1.25f,
        center = center
    )

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
            color = Color.White,
            style = Stroke(width = max(3f, radius * 0.22f))
        )
        drawPath(
            path = path,
            color = color
        )
    }
}

private const val METERS_PER_LAT_DEGREE = 111_320.0
private const val REPLAY_MIN_VIEW_SPAN_M = 120.0
private const val REPLAY_MIN_CONTEXT_RADIUS_M = 300.0
private const val REPLAY_COURSE_CONTEXT_MULTIPLIER = 2.0
