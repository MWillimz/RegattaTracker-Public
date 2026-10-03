package de.williserv.regattaclient

import androidx.compose.ui.graphics.Color

private val SESSION_COLOR_SCALE = listOf(
    Color(0xFF440154),
    Color(0xFF3B528B),
    Color(0xFF21918C),
    Color(0xFF5EC962),
    Color(0xFFFDE725)
)

internal fun sessionColorScale(): List<Color> = SESSION_COLOR_SCALE

internal fun sessionColorFraction(
    value: Double?,
    minValue: Double?,
    maxValue: Double?
): Float? {
    if (
        value == null ||
        minValue == null ||
        maxValue == null ||
        !value.isFinite() ||
        !minValue.isFinite() ||
        !maxValue.isFinite() ||
        maxValue < minValue
    ) {
        return null
    }

    val span = maxValue - minValue
    return if (span > 0.0) {
        ((value - minValue) / span).coerceIn(0.0, 1.0).toFloat()
    } else {
        0.5f
    }
}

internal fun sampleSessionColor(
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
