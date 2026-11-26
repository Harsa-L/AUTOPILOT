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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
  fun `trajectory builder produces directional steps and coordinator stops cleanly`() {
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
    coord.toggleSpinner(SpinnerMode.ROTATION)
    assertEquals(SpinnerMode.ROTATION, coord.state.value.spinnerMode)
    coord.stopAll()
    assertEquals(SpinnerMode.NONE, coord.state.value.spinnerMode)
    scope.cancel()
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
}
