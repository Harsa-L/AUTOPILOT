package com.example

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.example.input.GamepadAction
import com.example.input.GamepadAxes
import com.example.input.GamepadInputHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GamepadInputHandlerTest {

  private fun createJoystickMotionEvent(
    yaw: Float = 0f,
    throttleStick: Float = 0f,
    roll: Float = 0f,
    pitchStick: Float = 0f,
    hatX: Float = 0f,
    hatY: Float = 0f,
    lTrigger: Float = 0f,
    rTrigger: Float = 0f,
  ): MotionEvent {
    val props =
      arrayOf(
        MotionEvent.PointerProperties().apply { id = 0 },
      )
    val coords =
      arrayOf(
        MotionEvent.PointerCoords().apply {
          setAxisValue(MotionEvent.AXIS_X, yaw)
          setAxisValue(MotionEvent.AXIS_Y, throttleStick)
          setAxisValue(MotionEvent.AXIS_Z, roll)
          setAxisValue(MotionEvent.AXIS_RZ, pitchStick)
          setAxisValue(MotionEvent.AXIS_HAT_X, hatX)
          setAxisValue(MotionEvent.AXIS_HAT_Y, hatY)
          setAxisValue(MotionEvent.AXIS_LTRIGGER, lTrigger)
          setAxisValue(MotionEvent.AXIS_RTRIGGER, rTrigger)
        },
      )
    return MotionEvent.obtain(
      /* downTime = */ 0L,
      /* eventTime = */ 10L,
      /* action = */ MotionEvent.ACTION_MOVE,
      /* pointerCount = */ 1,
      /* pointerProperties = */ props,
      /* pointerCoords = */ coords,
      /* metaState = */ 0,
      /* buttonState = */ 0,
      /* xPrecision = */ 1f,
      /* yPrecision = */ 1f,
      /* deviceId = */ 1,
      /* edgeFlags = */ 0,
      /* source = */ InputDevice.SOURCE_JOYSTICK,
      /* flags = */ 0,
    )
  }

  @Test
  fun `joystick axes map Mode 2 sticks, hat D-pad, and dual-trigger emergency chord`() {
    var lastAxes = GamepadAxes()
    val actions = mutableListOf<GamepadAction>()
    val handler =
      GamepadInputHandler(
        context = ApplicationProvider.getApplicationContext(),
        onAxesUpdate = { lastAxes = it },
        onAction = { actions += it },
        onStatusChanged = {},
      )
    handler.enabled = true

    val moveEvent =
      createJoystickMotionEvent(
        yaw = 0.6f,
        throttleStick = -0.8f, // pushing stick up produces negative AXIS_Y -> positive throttle
        roll = -0.4f,
        pitchStick = -0.5f,    // pushing stick up produces negative AXIS_RZ -> positive pitch
        hatY = -1f,
      )
    assertTrue(handler.handleMotionEvent(moveEvent))
    assertEquals(0.6f, lastAxes.yaw, 1e-3f)
    assertEquals(0.8f, lastAxes.throttle, 1e-3f)
    assertEquals(-0.4f, lastAxes.roll, 1e-3f)
    assertEquals(0.5f, lastAxes.pitch, 1e-3f)
    assertTrue(lastAxes.activeStickMotion)
    assertTrue(actions.contains(GamepadAction.TILT_UP))
    moveEvent.recycle()

    // Staggered R2 first, then L2 chord: MUST invoke ONLY EMERGENCY_STOP_CHORD (no MOTOR_STOP leak!)
    actions.clear()
    val r2First = createJoystickMotionEvent(lTrigger = 0f, rTrigger = 0.9f)
    assertTrue(handler.handleMotionEvent(r2First))
    assertTrue(actions.isEmpty())
    r2First.recycle()

    val chordEvent = createJoystickMotionEvent(lTrigger = 0.9f, rTrigger = 0.9f)
    assertTrue(handler.handleMotionEvent(chordEvent))
    assertEquals(listOf(GamepadAction.EMERGENCY_STOP_CHORD), actions)
    chordEvent.recycle()

    val releaseBoth = createJoystickMotionEvent(lTrigger = 0f, rTrigger = 0f)
    assertTrue(handler.handleMotionEvent(releaseBoth))
    assertEquals(listOf(GamepadAction.EMERGENCY_STOP_CHORD), actions)
    releaseBoth.recycle()

    // Single R2 press and release without L2 triggers MOTOR_STOP
    val r2SoloDown = createJoystickMotionEvent(lTrigger = 0f, rTrigger = 0.9f)
    handler.handleMotionEvent(r2SoloDown)
    r2SoloDown.recycle()
    val r2SoloUp = createJoystickMotionEvent(lTrigger = 0f, rTrigger = 0f)
    handler.handleMotionEvent(r2SoloUp)
    r2SoloUp.recycle()
    assertEquals(listOf(GamepadAction.EMERGENCY_STOP_CHORD, GamepadAction.MOTOR_STOP), actions)
  }

  @Test
  fun `gamepad buttons trigger expected flight actions and leave system BACK unhandled`() {
    val actions = mutableListOf<GamepadAction>()
    val handler =
      GamepadInputHandler(
        context = ApplicationProvider.getApplicationContext(),
        onAxesUpdate = {},
        onAction = { actions += it },
        onStatusChanged = {},
      )
    handler.enabled = true

    fun dispatchKey(keyCode: Int): Boolean {
      val event =
        KeyEvent(
          /* downTime = */ 0L,
          /* eventTime = */ 10L,
          /* action = */ KeyEvent.ACTION_DOWN,
          /* code = */ keyCode,
          /* repeat = */ 0,
          /* metaState = */ 0,
          /* deviceId = */ 1,
          /* scancode = */ 0,
          /* flags = */ 0,
          /* source = */ InputDevice.SOURCE_GAMEPAD,
        )
      return handler.handleKeyDown(keyCode, event)
    }

    assertTrue(dispatchKey(KeyEvent.KEYCODE_BUTTON_A))
    assertTrue(dispatchKey(KeyEvent.KEYCODE_BUTTON_B))
    assertTrue(dispatchKey(KeyEvent.KEYCODE_BUTTON_X))
    assertTrue(dispatchKey(KeyEvent.KEYCODE_BUTTON_Y))
    assertTrue(dispatchKey(KeyEvent.KEYCODE_BUTTON_L1))
    assertTrue(dispatchKey(KeyEvent.KEYCODE_BUTTON_R1))
    assertTrue(dispatchKey(KeyEvent.KEYCODE_BUTTON_START))
    assertFalse(dispatchKey(KeyEvent.KEYCODE_BACK))

    assertEquals(
      listOf(
        GamepadAction.ARM_OR_TAKEOFF,
        GamepadAction.CONTROLLED_LAND,
        GamepadAction.FLIP_360,
        GamepadAction.TOGGLE_HEADLESS,
        GamepadAction.SNAPSHOT,
        GamepadAction.TOGGLE_RECORD,
        GamepadAction.CALIBRATE_GYRO,
      ),
      actions,
    )
  }
}
