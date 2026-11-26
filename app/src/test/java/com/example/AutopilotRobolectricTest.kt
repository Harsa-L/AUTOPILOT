package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutopilotRobolectricTest {

  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun `flight cockpit is main screen and navigates cleanly to vision and settings subpages`() {
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("aircraft_selector_pill").assertIsDisplayed()
    composeRule.onNodeWithTag("connect_toggle_button").assertIsDisplayed()
    composeRule.onNodeWithTag("target_select_toggle").assertIsDisplayed()
    composeRule.onNodeWithTag("left_stick_pad").assertIsDisplayed()
    composeRule.onNodeWithTag("right_stick_pad").assertIsDisplayed()

    // Navigate to dedicated Vision page outside Settings
    composeRule.onNodeWithTag("open_vision_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Computer Vision").assertIsDisplayed()
    composeRule.onNodeWithTag("vision_target_select_switch").assertIsDisplayed()
    composeRule.onNodeWithTag("vision_back_button").performClick()
    composeRule.waitForIdle()

    // Navigate to Settings and Appearance sub-page
    composeRule.onNodeWithTag("open_settings_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Settings").assertIsDisplayed()
    composeRule.onNodeWithTag("settings_row_appearance").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Appearance & Theme").assertIsDisplayed()
    composeRule.onNodeWithTag("settings_sub_back").performClick()
    composeRule.waitForIdle()

    // Navigate to Aircraft & Link sub-page and back
    composeRule.onNodeWithTag("settings_row_aircraft").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Aircraft & Link").assertIsDisplayed()
    composeRule.onNodeWithTag("settings_sub_back").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("settings_back_button").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("left_stick_pad").assertIsDisplayed()
  }
}
