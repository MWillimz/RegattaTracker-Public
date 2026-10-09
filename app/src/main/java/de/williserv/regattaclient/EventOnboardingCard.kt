package de.williserv.regattaclient

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.williserv.regattaclient.ui.theme.RegattaGreen
import de.williserv.regattaclient.ui.theme.RegattaOrange
import kotlinx.coroutines.delay

private const val ONBOARDING_COMPLETION_ANIMATION_MS = 1_500

@Composable
internal fun EventOnboardingCard(
    state: EventOnboardingState,
    eventKey: String?,
    previouslyObservedMask: Int?,
    onObserveMask: (String?, Int) -> Unit,
    suppressed: Boolean,
    onSuppress: () -> Unit,
    onOpenStep: (OnboardingStep) -> Unit
) {
    var expanded by rememberSaveable(eventKey) { mutableStateOf(false) }
    var previousMask by remember(eventKey) {
        mutableIntStateOf(previouslyObservedMask ?: state.completedMask)
    }
    var celebratingMask by remember(eventKey) { mutableIntStateOf(0) }
    var visualMask by remember(eventKey) {
        mutableIntStateOf(previouslyObservedMask ?: state.completedMask)
    }
    var keepVisible by remember(eventKey) { mutableStateOf(!state.complete) }

    // Never replay progress on initial composition, event switch or page revisit.
    LaunchedEffect(eventKey, state.completedMask) {
        val newMask = eventOnboardingNewlyCompleted(previousMask, state.completedMask)
        previousMask = state.completedMask
        onObserveMask(eventKey, state.completedMask)
        if (newMask != 0 && !suppressed) {
            celebratingMask = newMask
            expanded = true
            keepVisible = true
            // A completion on another page must first render its previous
            // blue/orange dot before transitioning to green on Home.
            delay(40L)
            visualMask = state.completedMask
            delay(ONBOARDING_COMPLETION_ANIMATION_MS - 40L)
            celebratingMask = 0
            delay(1_800L)
            expanded = false
            if (state.complete) keepVisible = false
        } else {
            visualMask = state.completedMask
            if (state.complete && celebratingMask == 0) {
                keepVisible = false
            }
        }
    }

    if (suppressed) return
    AnimatedVisibility(
        visible = !state.complete || keepVisible,
        enter = fadeIn(tween(250)) + expandVertically(tween(300)),
        exit = fadeOut(tween(300)) + shrinkVertically(tween(300))
    ) {
        val title = stringResource(R.string.onboarding_title)
        val progress = stringResource(
            R.string.onboarding_progress, state.completedCount, 4
        )
        val accessibleSteps = OnboardingStep.entries.mapIndexed { index, step ->
            stringResource(step.titleRes()) + ": " +
                stringResource(state.statuses[index].accessibilityRes())
        }.joinToString("; ")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "$title, $progress. $accessibleSteps"
                },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    state.statuses.forEachIndexed { index, status ->
                        OnboardingDot(
                            onboardingVisualStatus(status, visualMask, index)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(tween(260)) + fadeIn(tween(260)),
                    exit = shrinkVertically(tween(260)) + fadeOut(tween(220))
                ) {
                    Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp)) {
                        OnboardingStep.entries.forEachIndexed { index, step ->
                            val status = state.statuses[index]
                            val newlyCompleted =
                                celebratingMask and (1 shl index) != 0
                            val nextAfterCompletion =
                                celebratingMask != 0 &&
                                    status != OnboardingStatus.DONE &&
                                    state.statuses.take(index).any {
                                        it == OnboardingStatus.DONE
                                    } &&
                                    index == state.statuses.indexOfFirst {
                                        it != OnboardingStatus.DONE
                                    }
                            AnimatedVisibility(
                                visible = !nextAfterCompletion,
                                enter = fadeIn(tween(350)) +
                                    expandVertically(tween(350)),
                                exit = fadeOut(tween(180))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenStep(step) }
                                        .padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OnboardingDot(
                                        onboardingVisualStatus(status, visualMask, index)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(step.titleRes()),
                                            fontSize = 14.sp,
                                            fontWeight = if (
                                                status == OnboardingStatus.CURRENT ||
                                                status == OnboardingStatus.URGENT
                                            ) FontWeight.SemiBold else FontWeight.Normal,
                                            textDecoration = if (
                                                status == OnboardingStatus.DONE &&
                                                !newlyCompleted
                                            ) TextDecoration.LineThrough else null
                                        )
                                        Text(
                                            text = stringResource(step.detailRes()),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 12.sp
                                        )
                                    }
                                    if (status == OnboardingStatus.DONE && !newlyCompleted) {
                                        Text(
                                            text = "✓",
                                            color = RegattaGreen,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }
                        }
                        TextButton(
                            onClick = onSuppress,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(stringResource(R.string.onboarding_dont_show))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingDot(status: OnboardingStatus) {
    val targetColor: Color = when (status) {
        OnboardingStatus.DONE -> RegattaGreen
        OnboardingStatus.CURRENT -> MaterialTheme.colorScheme.primary
        OnboardingStatus.URGENT -> RegattaOrange
        OnboardingStatus.FUTURE -> MaterialTheme.colorScheme.outlineVariant
    }
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(ONBOARDING_COMPLETION_ANIMATION_MS),
        label = "Onboarding status"
    )
    Box(
        modifier = Modifier
            .size(9.dp)
            .background(animatedColor, CircleShape)
    )
}

private fun OnboardingStep.titleRes(): Int = when (this) {
    OnboardingStep.BOAT_SETUP -> R.string.onboarding_boat
    OnboardingStep.REGISTER -> R.string.onboarding_register
    OnboardingStep.ENTER_RACE -> R.string.onboarding_enter
    OnboardingStep.UPLOAD_CHECK -> R.string.onboarding_upload
}

private fun OnboardingStep.detailRes(): Int = when (this) {
    OnboardingStep.BOAT_SETUP -> R.string.onboarding_boat_detail
    OnboardingStep.REGISTER -> R.string.onboarding_register_detail
    OnboardingStep.ENTER_RACE -> R.string.onboarding_enter_detail
    OnboardingStep.UPLOAD_CHECK -> R.string.onboarding_upload_detail
}

private fun OnboardingStatus.accessibilityRes(): Int = when (this) {
    OnboardingStatus.DONE -> R.string.onboarding_status_done
    OnboardingStatus.CURRENT -> R.string.onboarding_status_current
    OnboardingStatus.URGENT -> R.string.onboarding_status_urgent
    OnboardingStatus.FUTURE -> R.string.onboarding_status_future
}

private fun onboardingVisualStatus(
    actual: OnboardingStatus,
    completedVisualMask: Int,
    index: Int
): OnboardingStatus =
    if (actual == OnboardingStatus.DONE &&
        completedVisualMask and (1 shl index) == 0
    ) {
        OnboardingStatus.CURRENT
    } else actual
