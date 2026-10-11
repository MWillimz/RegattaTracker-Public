package de.williserv.regattaclient

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RegattaLinkKnownDevicePickerUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val first = RegattaLinkConfiguredDevice("first", "AA:00:00:00:00:01", "First RLink")
    private val second = RegattaLinkConfiguredDevice("second", "AA:00:00:00:00:02", "Second RLink")

    @Test
    fun knownRowsAreOpaqueAtRestAndTapSelectsWithoutForgetting() {
        var selected: String? = null
        val forgotten = mutableListOf<String>()
        var surface = Color.Unspecified
        composeRule.setContent {
            MaterialTheme {
                surface = MaterialTheme.colorScheme.surface
                Column {
                    listOf(first, second).forEach { device ->
                        DismissibleKnownRegattaLinkRow(
                            device = device,
                            isSelected = false,
                            isConnected = false,
                            canSelect = true,
                            onSelect = { selected = device.stableId },
                            onForget = {
                                forgotten.add(device.stableId)
                                true
                            }
                        )
                    }
                }
            }
        }

        // A pixel at the edge of each idle row must cover the red swipe background.
        listOf(first, second).forEach { device ->
            val pixels = composeRule.onNodeWithTag("known-rlink-${device.stableId}")
                .captureToImage().toPixelMap()
            assertEquals(surface.toArgb(), pixels[1, pixels.height / 2].toArgb())
        }
        composeRule.onNodeWithText("Second RLink").performClick()
        composeRule.runOnIdle {
            assertEquals(second.stableId, selected)
            assertEquals(emptyList<String>(), forgotten)
        }
    }

    @Test
    fun leftSwipeForgetsOnlyTheSwipedDevice() {
        val forgotten = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                Column {
                    listOf(first, second).forEach { device ->
                        DismissibleKnownRegattaLinkRow(
                            device = device,
                            isSelected = false,
                            isConnected = false,
                            canSelect = true,
                            onSelect = {},
                            onForget = {
                                forgotten.add(device.stableId)
                                true
                            }
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("known-rlink-${second.stableId}")
            .performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(listOf(second.stableId), forgotten)
        }
    }

    @Test
    fun cancelledSwipeDoesNotForgetTheDevice() {
        var forgetCount = 0
        composeRule.setContent {
            MaterialTheme {
                DismissibleKnownRegattaLinkRow(
                    device = first,
                    isSelected = false,
                    isConnected = false,
                    canSelect = true,
                    onSelect = {},
                    onForget = {
                        forgetCount++
                        true
                    }
                )
            }
        }

        composeRule.onNodeWithTag("known-rlink-${first.stableId}")
            .performTouchInput {
                swipe(
                    start = Offset(centerX + 12.dp.toPx(), centerY),
                    end = Offset(centerX - 12.dp.toPx(), centerY),
                    durationMillis = 250
                )
            }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(0, forgetCount) }
    }

    @Test
    fun connectedRowCannotBeSelectedOrSwipedAway() {
        var selected = false
        composeRule.setContent {
            MaterialTheme {
                KnownRegattaLinkPickerRow(
                    device = first,
                    isSelected = true,
                    isConnected = true,
                    canSelect = true,
                    onSelect = { selected = true }
                )
            }
        }

        composeRule.onNodeWithText("First RLink").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(false, selected) }
    }
}
