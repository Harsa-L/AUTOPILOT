package com.example

import android.graphics.Bitmap
import android.graphics.Color
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import com.autopilot.video.MvFilterPreset
import com.autopilot.video.VideoFrameTransform
import com.autopilot.video.VideoPipelineController
import com.example.input.FlightAutomationCoordinator
import com.example.input.GravitySensorHandler
import com.example.input.KeyboardAction
import com.example.input.KeyboardAxes
import com.example.input.KeyboardInputHandler
import com.example.input.SpinnerMode
import com.example.ui.hud.HudElementId
import com.example.ui.hud.HudLayoutEngine
import com.example.ui.hud.HudViewportConstraints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppInputAndAutomationTest {

  private fun keyboardEvent(keyCode: Int, action: Int = KeyEvent.ACTION_DOWN): KeyEvent =
    KeyEvent(
      /* downTime = */ 0L,
      /* eventTime = */ 10L,
      /* action = */ action,
      /* code = */ keyCode,
      /* repeat = */ 0,
      /* metaState = */ 0,
      /* deviceId = */ 2,
      /* scancode = */ 0,
      /* flags = */ 0,
      /* source = */ InputDevice.SOURCE_KEYBOARD,
    )

  @Test
  fun `keyboard handler maps WASD, arrows, and hotkeys while respecting text-input suspension`() {
    var axes = KeyboardAxes()
    val actions = mutableListOf<KeyboardAction>()
    val handler =
      KeyboardInputHandler(
        onAxesUpdate = { axes = it },
        onAction = { actions += it },
      )

    assertTrue(handler.handleKeyEvent(keyboardEvent(KeyEvent.KEYCODE_W, KeyEvent.ACTION_DOWN)))
    assertTrue(handler.handleKeyEvent(keyboardEvent(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN)))
    assertTrue(axes.active)
    assertTrue(axes.throttle > 0f)
    assertTrue(axes.pitch > 0f)

    assertTrue(handler.handleKeyEvent(keyboardEvent(KeyEvent.KEYCODE_W, KeyEvent.ACTION_UP)))
    assertTrue(handler.handleKeyEvent(keyboardEvent(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_UP)))
    assertFalse(axes.active)
    assertEquals(0f, axes.throttle, 1e-4f)
    assertEquals(0f, axes.pitch, 1e-4f)

    assertTrue(handler.handleKeyEvent(keyboardEvent(KeyEvent.KEYCODE_F)))
    assertTrue(handler.handleKeyEvent(keyboardEvent(KeyEvent.KEYCODE_E)))
    assertTrue(handler.handleKeyEvent(keyboardEvent(KeyEvent.KEYCODE_SPACE)))
    assertEquals(
      listOf(KeyboardAction.FLIP_360, KeyboardAction.EMERGENCY_STOP, KeyboardAction.ARM_OR_TOGGLE_TAKEOFF_LAND),
      actions,
    )

    assertFalse(handler.handleKeyEvent(keyboardEvent(KeyEvent.KEYCODE_BACK)))

    handler.suspendForTextInput = true
    assertFalse(handler.handleKeyEvent(keyboardEvent(KeyEvent.KEYCODE_F)))
  }

  @Test
  fun `gravity tilt mapper applies deadzone and symmetric clamping`() {
    val (rZero, pZero) = GravitySensorHandler.mapTiltDeltas(2f, -3f, deadzoneDeg = 4f, maxTiltDeg = 25f)
    assertEquals(0f, rZero, 1e-4f)
    assertEquals(0f, pZero, 1e-4f)

    val (rMax, pMin) = GravitySensorHandler.mapTiltDeltas(40f, -40f, deadzoneDeg = 4f, maxTiltDeg = 25f)
    assertEquals(1f, rMax, 1e-4f)
    assertEquals(-1f, pMin, 1e-4f)
  }

  @Test
  fun `trajectory builder produces directional steps and coordinator enforces protocol capability gating`() {
    val steps =
      FlightAutomationCoordinator.buildTrajectorySteps(
        listOf(
          Offset(0.2f, 0.8f),
          Offset(0.2f, 0.4f), // upward on screen -> positive pitch
          Offset(0.7f, 0.4f), // rightward on screen -> positive roll
        )
      )
    assertEquals(2, steps.size)
    assertTrue(steps[0].pitch > 0.2f)
    assertTrue(steps[1].roll > 0.2f)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val coord = FlightAutomationCoordinator(scope) { _, _, _, _ -> }
    coord.setPendingTrajectoryPoints(
      listOf(Offset(0.2f, 0.8f), Offset(0.2f, 0.3f), Offset(0.7f, 0.3f))
    )
    coord.executePendingTrajectory(protocolSupportsTrajectory = false)
    assertNull(coord.state.value.activeSequenceTitle)

    coord.toggleSpinner(SpinnerMode.ROTATION)
    assertEquals(SpinnerMode.ROTATION, coord.state.value.spinnerMode)
    coord.stopAll()
    assertEquals(SpinnerMode.NONE, coord.state.value.spinnerMode)
    scope.cancel()
  }

  @Test
  fun `hud layout engine enforces viewport bounds, essential visibility, 30 percent budget, and JSON round-trip`() {
    val portrait = HudLayoutEngine.defaultPortrait()
    val landscape = HudLayoutEngine.defaultLandscape()
    assertTrue(
      HudLayoutEngine.estimatePersistentCoverageFraction(portrait, includeSticks = true) <=
        HudViewportConstraints.MAX_PERSISTENT_HUD_FRACTION
    )
    assertTrue(
      HudLayoutEngine.estimatePersistentCoverageFraction(landscape, includeSticks = true) <=
        HudViewportConstraints.MAX_PERSISTENT_HUD_FRACTION
    )

    // Attempt to hide essential E-Stop / primary actions must be refused by clamp
    val withEssentialAttempt =
      HudLayoutEngine.updateElementStyle(
        portrait,
        HudElementId.PRIMARY_ACTIONS,
        visible = false,
        opacityDelta = -1.0f,
      )
    assertTrue(withEssentialAttempt.element(HudElementId.PRIMARY_ACTIONS).visible)
    assertTrue(withEssentialAttempt.element(HudElementId.PRIMARY_ACTIONS).opacity >= 0.70f)

    // Non-essential modifiers CAN be hidden and moved within safe bounds
    val movedAndHidden =
      HudLayoutEngine.moveElement(
        HudLayoutEngine.updateElementStyle(withEssentialAttempt, HudElementId.CAPABILITY_MODIFIERS, visible = false),
        HudElementId.TOP_LEFT_STATUS,
        dxNorm = -5.0f,
        dyNorm = -5.0f,
      )
    assertFalse(movedAndHidden.element(HudElementId.CAPABILITY_MODIFIERS).visible)
    assertTrue(movedAndHidden.element(HudElementId.TOP_LEFT_STATUS).normalizedX >= 0.08f)

    val serialized = HudLayoutEngine.serialize(movedAndHidden)
    val restored = HudLayoutEngine.deserialize(serialized, landscape = false)
    assertFalse(restored.element(HudElementId.CAPABILITY_MODIFIERS).visible)
    assertTrue(restored.element(HudElementId.PRIMARY_ACTIONS).visible)
  }

  @Test
  fun `video transform rotates dimensions and applies monochrome MV filter`() {
    val src = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
    src.eraseColor(Color.rgb(220, 40, 20))

    val rotated =
      VideoPipelineController.applyVideoTransform(
        src,
        VideoFrameTransform(rotationDeg = 90, mirrorHorizontal = true, mvFilter = MvFilterPreset.MONOCHROME),
      )
    assertEquals(2, rotated.width)
    assertEquals(4, rotated.height)
    val px = rotated.getPixel(1, 2)
    val r = Color.red(px)
    val g = Color.green(px)
    val b = Color.blue(px)
    assertTrue("Expected desaturated monochrome pixel, got ($r,$g,$b)", kotlin.math.abs(r - g) <= 2 && kotlin.math.abs(g - b) <= 2)
  }

  @Test
  fun `hud layout engine lock prevents dragging while unlock restores movement`() {
    val portrait = HudLayoutEngine.defaultPortrait()
    val origX = portrait.element(HudElementId.TOP_LEFT_STATUS).normalizedX
    val locked = HudLayoutEngine.toggleLock(portrait, HudElementId.TOP_LEFT_STATUS)
    assertTrue(locked.element(HudElementId.TOP_LEFT_STATUS).locked)

    val attemptedMove = HudLayoutEngine.moveElement(locked, HudElementId.TOP_LEFT_STATUS, dxNorm = 0.25f, dyNorm = 0.25f)
    assertEquals(origX, attemptedMove.element(HudElementId.TOP_LEFT_STATUS).normalizedX, 1e-4f)

    val unlocked = HudLayoutEngine.toggleLock(attemptedMove, HudElementId.TOP_LEFT_STATUS)
    assertFalse(unlocked.element(HudElementId.TOP_LEFT_STATUS).locked)
    val moved = HudLayoutEngine.moveElement(unlocked, HudElementId.TOP_LEFT_STATUS, dxNorm = 0.15f, dyNorm = 0f)
    assertTrue(moved.element(HudElementId.TOP_LEFT_STATUS).normalizedX > origX)
  }

  @Test
  fun `executePendingTrajectory uses RDP-simplified waypoints when available`() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val coord = FlightAutomationCoordinator(scope) { _, _, _, _ -> }
    // L-shaped path with noisy intermediate collinear points along the vertical leg
    coord.setPendingTrajectoryPoints(
      listOf(
        Offset(0.20f, 0.80f),
        Offset(0.205f, 0.65f),
        Offset(0.202f, 0.50f),
        Offset(0.20f, 0.35f),
        Offset(0.60f, 0.35f),
      )
    )
    val expectedWaypoints = coord.state.value.cleanedTrajectory!!.waypoints.size
    assertTrue(expectedWaypoints >= 2)
    assertTrue(coord.executePendingTrajectory(protocolSupportsTrajectory = true))
    assertEquals("Trajectory Flight", coord.state.value.activeSequenceTitle)
    assertEquals(expectedWaypoints - 1, coord.state.value.totalSteps)
    coord.stopAll()
    scope.cancel()
  }
}
