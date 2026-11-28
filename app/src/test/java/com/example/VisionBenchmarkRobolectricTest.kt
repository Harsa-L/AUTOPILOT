package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VisionBenchmarkRobolectricTest {

  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun `navigates from vision screen to isolated vision benchmark studio and cycles tabs cleanly`() {
    composeRule.waitForIdle()

    // Navigate from Flight Cockpit -> Computer Vision -> Vision Benchmark Studio
    composeRule.onNodeWithTag("open_vision_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("open_vision_benchmark_button"))
    composeRule.onNodeWithTag("open_vision_benchmark_button").assertIsDisplayed().performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Vision Benchmark Studio").assertIsDisplayed()
    composeRule.onNodeWithTag("benchmark_tab_0").assertIsDisplayed()

    // Switch to Degrade tab and verify built-in Reference Drone preset
    composeRule.onNodeWithTag("benchmark_tab_1").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("profile_chip_reference_drone").assertIsDisplayed().performClick()
    composeRule.waitForIdle()

    // Switch through CV & Models, Monitor, and Recordings tabs
    composeRule.onNodeWithTag("benchmark_tab_2").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("benchmark_import_model_button").assertIsDisplayed()

    composeRule.onNodeWithTag("benchmark_tab_3").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Real-Time FPS Streams").assertIsDisplayed()

    composeRule.onNodeWithTag("benchmark_tab_4").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("rec_degraded_switch").assertIsDisplayed()

    // Return cleanly back to Vision and Flight Cockpit without disrupting cockpit HUD
    composeRule.onNodeWithTag("benchmark_back_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Computer Vision").assertIsDisplayed()
    composeRule.onNodeWithTag("vision_back_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("left_stick_pad").assertIsDisplayed()
  }
}
