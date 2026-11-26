package com.example

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Assert.assertTrue
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

    // Navigate to Flight Controls & Gamepad sub-page
    composeRule.onNodeWithTag("settings_row_controls").performClick()
    composeRule.waitForIdle()
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("gamepad_enable_switch"))
    composeRule.onNodeWithTag("gamepad_enable_switch").assertIsDisplayed()
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("keyboard_enable_switch"))
    composeRule.onNodeWithTag("keyboard_enable_switch").assertIsDisplayed()
    composeRule.onNodeWithTag("soft_descent_switch").assertIsDisplayed()
    composeRule.onNodeWithTag("settings_sub_back").performClick()
    composeRule.waitForIdle()

    // Navigate to Aircraft & Link sub-page, pick X69 LG, and verify RTSP_H265, fallback, and video transform controls
    composeRule.onNodeWithTag("settings_row_aircraft").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Aircraft & Link").assertIsDisplayed()
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("protocol_card_x69_lg"))
    composeRule.onNodeWithTag("protocol_card_x69_lg").performClick()
    composeRule.waitForIdle()
    composeRule.onNode(hasScrollAction()).performScrollToIndex(0)
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("video_mode_rtsp_h265").performScrollTo().assertIsDisplayed()
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("rtsp_fallback_switch"))
    composeRule.onNodeWithTag("rtsp_fallback_switch").assertIsDisplayed()
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("video_rot_90"))
    composeRule.onNodeWithTag("video_rot_90").assertIsDisplayed()
    composeRule.onNodeWithTag("settings_mv_warm_fpv").assertIsDisplayed()
    composeRule.onNodeWithTag("settings_sub_back").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("settings_back_button").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("left_stick_pad").assertIsDisplayed()
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h740dp-port")
  fun `galaxy s9 portrait hud leaves at least 70 percent video clear with zero scrolling`() {
    composeRule.waitForIdle()
    assertHudConstraints(minStickDp = 104f)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w740dp-h360dp-land")
  fun `galaxy s9 landscape hud leaves at least 70 percent video clear with zero scrolling`() {
    composeRule.waitForIdle()
    assertHudConstraints(minStickDp = 88f)
  }

  private fun assertHudConstraints(minStickDp: Float) {
    // Zero scrollable elements allowed on Flight HUD
    val scrollableNodes = composeRule.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
    assertTrue("Expected zero scrollable nodes on Flight HUD, found ${scrollableNodes.size}", scrollableNodes.isEmpty())

    // All primary and capability controls visible simultaneously
    listOf(
      "aircraft_selector_pill",
      "connect_toggle_button",
      "snapshot_button",
      "record_video_button",
      "target_select_toggle",
      "open_vision_button",
      "open_settings_button",
      "speed_tier_cycle",
      "headless_mode_chip",
      "flip_360_chip",
      "calibrate_gyro_chip",
      "trim_button",
      "flight_modes_chip",
      "arm_button",
      "controlled_land_button",
      "motor_stop_button",
      "emergency_stop_button",
      "left_stick_pad",
      "right_stick_pad",
    ).forEach { tag ->
      composeRule.onNodeWithTag(tag).assertIsDisplayed()
    }

    composeRule.onNodeWithTag("arm_button").assertWidthIsAtLeast(64.dp).assertHeightIsAtLeast(44.dp)
    composeRule.onNodeWithTag("left_stick_pad").assertWidthIsAtLeast(minStickDp.dp).assertHeightIsAtLeast(minStickDp.dp)
    composeRule.onNodeWithTag("right_stick_pad").assertWidthIsAtLeast(minStickDp.dp).assertHeightIsAtLeast(minStickDp.dp)

    val rootBounds = composeRule.onRoot().getBoundsInRoot()
    val totalArea = rootBounds.width.value * rootBounds.height.value
    val hudTags =
      listOf(
        "hud_top_left",
        "hud_top_right",
        "hud_modifiers_row",
        "hud_actions_row",
        "left_stick_pad",
        "right_stick_pad",
      )
    val hudArea =
      hudTags.sumOf { tag ->
        val b = composeRule.onNodeWithTag(tag).getBoundsInRoot()
        (b.width.value * b.height.value).toDouble()
      }
    val hudFraction = hudArea / totalArea
    assertTrue(
      "HUD overlay fraction $hudFraction exceeded 0.30 (cleared video must be >= 70%)",
      hudFraction <= 0.30,
    )
  }
}
