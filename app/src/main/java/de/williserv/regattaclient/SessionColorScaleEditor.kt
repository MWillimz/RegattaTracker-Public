package de.williserv.regattaclient

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionColorScaleEditorSheet(
    metricDisplayName: String,
    unit: String?,
    observedRange: ClosedFloatingPointRange<Double>,
    selectedRange: ClosedFloatingPointRange<Float>?,
    hasNegativeValues: Boolean,
    useAbsoluteValue: Boolean,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onAbsoluteValueChange: (Boolean) -> Unit,
    onResetRange: () -> Unit,
    onDismiss: () -> Unit
) {
    val observedStart = observedRange.start.toFloat()
    val observedEnd = observedRange.endInclusive.toFloat()
    val observedFloatRange = observedStart..observedEnd
    val effectiveSelected =
        clampSessionColorRange(selectedRange, observedRange) ?: observedFloatRange
    val unitSuffix = unit.orEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.session_color_scale_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = metricDisplayName,
                fontWeight = FontWeight.Medium
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(
                        Brush.horizontalGradient(colors = sessionColorScale())
                    )
            )

            Text(
                text = stringResource(
                    R.string.session_color_scale_observed,
                    formatSessionColorValue(observedRange.start),
                    formatSessionColorValue(observedRange.endInclusive),
                    unitSuffix
                ),
                fontSize = 12.sp
            )

            if (observedStart < observedEnd) {
                RangeSlider(
                    value = effectiveSelected,
                    onValueChange = onRangeChange,
                    valueRange = observedFloatRange,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(
                text = stringResource(
                    R.string.session_color_scale_range,
                    formatSessionColorValue(effectiveSelected.start.toDouble()),
                    formatSessionColorValue(
                        effectiveSelected.endInclusive.toDouble()
                    ),
                    unitSuffix
                ),
                fontSize = 12.sp
            )

            if (hasNegativeValues) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.session_color_scale_absolute))
                    Switch(
                        checked = useAbsoluteValue,
                        onCheckedChange = onAbsoluteValueChange
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onResetRange,
                    enabled = selectedRange != null
                ) {
                    Text(stringResource(R.string.session_color_scale_reset))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
