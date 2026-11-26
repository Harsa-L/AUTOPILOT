package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
  fun `dashboard renders protocol registry and navigates across primary tabs`() {
    composeRule.waitForIdle()
    composeRule.onNodeWithText("AUTOPILOT Flight Console").assertIsDisplayed()
    composeRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("connect_protocol_button"))
    composeRule.onNodeWithTag("connect_protocol_button").performScrollTo().assertIsDisplayed()

    composeRule.onNodeWithTag("nav_diagnostics").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Diagnostics & Packet Inspector").assertIsDisplayed()

    composeRule.onNodeWithTag("nav_vision").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Vision Engine & Flight Tuning").assertIsDisplayed()

    composeRule.onNodeWithTag("nav_theme").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Design Runtime & Theme Studio").assertIsDisplayed()

    composeRule.onNodeWithTag("nav_flight").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("close_cockpit_button").assertIsDisplayed()
  }
}
