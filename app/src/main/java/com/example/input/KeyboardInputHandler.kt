package com.example.input

import android.view.KeyEvent

data class KeyboardAxes(
  val yaw: Float = 0f,
  val throttle: Float = 0f,
  val roll: Float = 0f,
  val pitch: Float = 0f,
  val active: Boolean = false,
)

enum class KeyboardAction {
  ARM_OR_TOGGLE_TAKEOFF_LAND,
  EMERGENCY_STOP,
  MOTOR_STOP,
  FLIP_360,
  TOGGLE_HEADLESS,
  CALIBRATE_GYRO,
  SPEED_LOW,
  SPEED_MED,
  SPEED_FAST,
  SNAPSHOT,
  TOGGLE_RECORD,
}

/**
 * Maps physical Bluetooth/USB keyboard keys (WASD + Arrows + Hotkeys) matching
 * TurboDrone desktop controls without interfering with TextField typing or system BACK.
 */
class KeyboardInputHandler(
  private val onAxesUpdate: (KeyboardAxes) -> Unit,
  private val onAction: (KeyboardAction) -> Unit,
) {
  var enabled: Boolean = true
  var suspendForTextInput: Boolean = false

  private val pressedKeys = mutableSetOf<Int>()

  fun clearHeldKeys() {
    if (pressedKeys.isNotEmpty()) {
      pressedKeys.clear()
      onAxesUpdate(KeyboardAxes())
    }
  }

  fun handleKeyEvent(event: KeyEvent): Boolean {
    if (!enabled || suspendForTextInput) return false
    val keyCode = event.keyCode
    if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME) return false

    // Do not treat game-controller keys as keyboard typing
    val fromKeyboard =
      (event.source and android.view.InputDevice.SOURCE_KEYBOARD) ==
        android.view.InputDevice.SOURCE_KEYBOARD
    if (!fromKeyboard) return false

    return when (event.action) {
      KeyEvent.ACTION_DOWN -> handleKeyDown(keyCode, event.repeatCount)
      KeyEvent.ACTION_UP -> handleKeyUp(keyCode)
      else -> false
    }
  }

  private fun handleKeyDown(keyCode: Int, repeatCount: Int): Boolean {
    if (isMovementKey(keyCode)) {
      val added = pressedKeys.add(keyCode)
      if (added) emitAxes()
      return true
    }
    if (repeatCount > 0) return false
    val action =
      when (keyCode) {
        KeyEvent.KEYCODE_SPACE -> KeyboardAction.ARM_OR_TOGGLE_TAKEOFF_LAND
        KeyEvent.KEYCODE_E,
        KeyEvent.KEYCODE_ESCAPE -> KeyboardAction.EMERGENCY_STOP
        KeyEvent.KEYCODE_M -> KeyboardAction.MOTOR_STOP
        KeyEvent.KEYCODE_F -> KeyboardAction.FLIP_360
        KeyEvent.KEYCODE_H -> KeyboardAction.TOGGLE_HEADLESS
        KeyEvent.KEYCODE_C -> KeyboardAction.CALIBRATE_GYRO
        KeyEvent.KEYCODE_1 -> KeyboardAction.SPEED_LOW
        KeyEvent.KEYCODE_2 -> KeyboardAction.SPEED_MED
        KeyEvent.KEYCODE_3 -> KeyboardAction.SPEED_FAST
        KeyEvent.KEYCODE_P -> KeyboardAction.SNAPSHOT
        KeyEvent.KEYCODE_R -> KeyboardAction.TOGGLE_RECORD
        else -> null
      } ?: return false
    onAction(action)
    return true
  }

  private fun handleKeyUp(keyCode: Int): Boolean {
    if (isMovementKey(keyCode)) {
      if (pressedKeys.remove(keyCode)) emitAxes()
      return true
    }
    return false
  }

  private fun emitAxes() {
    var yaw = 0f
    var throttle = 0f
    var roll = 0f
    var pitch = 0f
    if (KeyEvent.KEYCODE_W in pressedKeys) throttle += 0.65f
    if (KeyEvent.KEYCODE_S in pressedKeys) throttle -= 0.65f
    if (KeyEvent.KEYCODE_D in pressedKeys) yaw += 0.70f
    if (KeyEvent.KEYCODE_A in pressedKeys) yaw -= 0.70f
    if (KeyEvent.KEYCODE_DPAD_UP in pressedKeys) pitch += 0.70f
    if (KeyEvent.KEYCODE_DPAD_DOWN in pressedKeys) pitch -= 0.70f
    if (KeyEvent.KEYCODE_DPAD_RIGHT in pressedKeys) roll += 0.70f
    if (KeyEvent.KEYCODE_DPAD_LEFT in pressedKeys) roll -= 0.70f

    val active = pressedKeys.isNotEmpty()
    onAxesUpdate(
      KeyboardAxes(
        yaw = yaw.coerceIn(-1f, 1f),
        throttle = throttle.coerceIn(-1f, 1f),
        roll = roll.coerceIn(-1f, 1f),
        pitch = pitch.coerceIn(-1f, 1f),
        active = active,
      )
    )
  }

  private fun isMovementKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_W ||
      keyCode == KeyEvent.KEYCODE_S ||
      keyCode == KeyEvent.KEYCODE_A ||
      keyCode == KeyEvent.KEYCODE_D ||
      keyCode == KeyEvent.KEYCODE_DPAD_UP ||
      keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
      keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
      keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
}
