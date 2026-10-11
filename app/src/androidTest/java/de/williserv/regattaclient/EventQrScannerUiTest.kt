package de.williserv.regattaclient

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class EventQrScannerUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun withoutEventHomeActionNamesTheQrScanner() {
        composeRule.setContent {
            MaterialTheme {
                ActionRow(
                    setupConfirmed = true,
                    eventAccessLoaded = false,
                    inRace = false,
                    raceFinished = false,
                    onSetup = {},
                    onRace = {}
                )
            }
        }

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.scan_event_qr)
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.event)
        ).assertDoesNotExist()
    }

    @Test
    fun withEventHomeActionKeepsTheEventLabel() {
        composeRule.setContent {
            MaterialTheme {
                ActionRow(
                    setupConfirmed = true,
                    eventAccessLoaded = true,
                    inRace = false,
                    raceFinished = false,
                    onSetup = {},
                    onRace = {}
                )
            }
        }

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.event)
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.scan_event_qr)
        ).assertDoesNotExist()
    }

    @Test
    fun scannerInstructionIsDisplayed() {
        composeRule.setContent {
            MaterialTheme {
                EventQrScannerHint()
            }
        }

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.scan_event_qr_code)
        ).assertIsDisplayed()
    }
}
