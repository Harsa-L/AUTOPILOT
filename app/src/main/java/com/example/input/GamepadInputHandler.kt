package com.example.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

enum class GamepadAction {
  ARM_OR_TAKEOFF,
  CONTROLLED_LAND,
  FLIP_360,
  TOGGLE_HEADLESS,
  SNAPSHOT,
  TOGGLE_RECORD,
  TOGGLE_FAST_FLY,
  MOTOR_STOP,
  CALIBRATE_GYRO,
  EMERGENCY_STOP_CHORD,
  TILT_UP,
  TILT_DOWN,
  CYCLE_SPEED,
}

data class GamepadStatus(
  val connected: Boolean = false,
  val controllerName: String? = null,
  val controllerCount: Int = 0,
  val lastEventSummary: String? = null,
)

data class GamepadAxes(
  val yaw: Float = 0f,
  val throttle: Float = 0f,
  val roll: Float = 0f,
  val pitch: Float = 0f,
  val activeStickMotion: Boolean = false,
)

/**
 * Hardware Bluetooth & USB game controller mapper supporting Xbox, PlayStation,
 * and Switch-style InputDevices using MotionEvent analog axes and KeyEvent actions.
 */
class GamepadInputHandler(
  private val context: Context,
  private val onAxesUpdate: (GamepadAxes) -> Unit,
  private val onAction: (GamepadAction) -> Unit,
  private val onStatusChanged: (GamepadStatus) -> Unit,
) {

  var enabled: Boolean = true
  var throttleHoldMode: Boolean = false

  private var l2Down = false
  private var r2Down = false
  private var prevHatX = 0
  private var prevHatY = 0
  private var heldThrottle = 0f

  private val inputManager =
    context.getSystemService(Context.INPUT_SERVICE) as? InputManager

  private val deviceListener =
    object : InputManager.InputDeviceListener {
      override fun onInputDeviceAdded(deviceId: Int) = refreshConnectedControllers()

      override fun onInputDeviceRemoved(deviceId: Int) = refreshConnectedControllers()

      override fun onInputDeviceChanged(deviceId: Int) = refreshConnectedControllers()
    }

  fun startListening() {
    runCatching { inputManager?.registerInputDeviceListener(deviceListener, null) }
    refreshConnectedControllers()
  }

  fun stopListening() {
    runCatching { inputManager?.unregisterInputDeviceListener(deviceListener) }
  }

  fun refreshConnectedControllers() {
    val controllers =
      InputDevice.getDeviceIds()
        .toList()
        .mapNotNull { InputDevice.getDevice(it) }
        .filter { isGameController(it) }
    val primary = controllers.firstOrNull()
    onStatusChanged(
      GamepadStatus(
        connected = controllers.isNotEmpty(),
        controllerName = primary?.name,
        controllerCount = controllers.size,
      )
    )
  }

  fun handleMotionEvent(event: MotionEvent): Boolean {
    if (!enabled) return false
    if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK &&
      (event.source and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
    ) {
      return false
    }
    if (event.action != MotionEvent.ACTION_MOVE) return false

    val dev = event.device
    val yaw = readCenteredAxis(event, dev, MotionEvent.AXIS_X)
    val rawThr = -readCenteredAxis(event, dev, MotionEvent.AXIS_Y)
    val roll = readCenteredAxis(event, dev, MotionEvent.AXIS_Z)
    val pitch = -readCenteredAxis(event, dev, MotionEvent.AXIS_RZ)

    val throttle =
      if (throttleHoldMode) {
        if (abs(rawThr) > 0.08f) {
          heldThrottle = (heldThrottle + rawThr * 0.06f).coerceIn(-1f, 1f)
        }
        heldThrottle
      } else {
        rawThr
      }

    val activeMotion =
      abs(yaw) > 0.12f || abs(rawThr) > 0.12f || abs(roll) > 0.12f || abs(pitch) > 0.12f

    onAxesUpdate(
      GamepadAxes(
        yaw = yaw,
        throttle = throttle,
        roll = roll,
        pitch = pitch,
        activeStickMotion = activeMotion,
      )
    )

    // Hat D-pad handling
    val hatX = axisDirection(event.getAxisValue(MotionEvent.AXIS_HAT_X))
    val hatY = axisDirection(event.getAxisValue(MotionEvent.AXIS_HAT_Y))
    if (hatY != prevHatY) {
      if (hatY < 0) onAction(GamepadAction.TILT_UP)
      else if (hatY > 0) onAction(GamepadAction.TILT_DOWN)
      prevHatY = hatY
    }
    if (hatX != prevHatX && hatX != 0) {
      onAction(GamepadAction.CYCLE_SPEED)
    }
    prevHatX = hatX

    // Analog shoulder triggers (disambiguating LTRIGGER/BRAKE & RTRIGGER/GAS)
    val lVal = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
    val rVal = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
    val nextL2 = lVal > 0.65f
    val nextR2 = rVal > 0.65f
    if ((nextL2 && !l2Down) || (nextR2 && !r2Down)) {
      if (nextL2 && nextR2) {
        onAction(GamepadAction.EMERGENCY_STOP_CHORD)
      } else if (nextL2 && !l2Down) {
        onAction(GamepadAction.TOGGLE_FAST_FLY)
      } else if (nextR2 && !r2Down) {
        onAction(GamepadAction.MOTOR_STOP)
      }
    }
    l2Down = nextL2
    r2Down = nextR2
    return true
  }

  fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    if (!enabled) return false
    if ((event.source and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD &&
      (event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK &&
      (event.source and InputDevice.SOURCE_DPAD) != InputDevice.SOURCE_DPAD
    ) {
      return false
    }
    if (keyCode == KeyEvent.KEYCODE_BACK) return false
    if (event.repeatCount > 0) return true

    return when (keyCode) {
      KeyEvent.KEYCODE_BUTTON_A -> {
        onAction(GamepadAction.ARM_OR_TAKEOFF)
        true
      }
      KeyEvent.KEYCODE_BUTTON_B -> {
        onAction(GamepadAction.CONTROLLED_LAND)
        true
      }
      KeyEvent.KEYCODE_BUTTON_X -> {
        onAction(GamepadAction.FLIP_360)
        true
      }
      KeyEvent.KEYCODE_BUTTON_Y -> {
        onAction(GamepadAction.TOGGLE_HEADLESS)
        true
      }
      KeyEvent.KEYCODE_BUTTON_L1 -> {
        onAction(GamepadAction.SNAPSHOT)
        true
      }
      KeyEvent.KEYCODE_BUTTON_R1 -> {
        onAction(GamepadAction.TOGGLE_RECORD)
        true
      }
      KeyEvent.KEYCODE_BUTTON_L2 -> {
        l2Down = true
        if (r2Down) onAction(GamepadAction.EMERGENCY_STOP_CHORD)
        else onAction(GamepadAction.TOGGLE_FAST_FLY)
        true
      }
      KeyEvent.KEYCODE_BUTTON_R2 -> {
        r2Down = true
        if (l2Down) onAction(GamepadAction.EMERGENCY_STOP_CHORD)
        else onAction(GamepadAction.MOTOR_STOP)
        true
      }
      KeyEvent.KEYCODE_BUTTON_START -> {
        onAction(GamepadAction.CALIBRATE_GYRO)
        true
      }
      KeyEvent.KEYCODE_DPAD_UP -> {
        onAction(GamepadAction.TILT_UP)
        true
      }
      KeyEvent.KEYCODE_DPAD_DOWN -> {
        onAction(GamepadAction.TILT_DOWN)
        true
      }
      KeyEvent.KEYCODE_DPAD_RIGHT,
      KeyEvent.KEYCODE_DPAD_LEFT -> {
        onAction(GamepadAction.CYCLE_SPEED)
        true
      }
      else -> false
    }
  }

  fun handleKeyUp(keyCode: Int, event: KeyEvent): Boolean {
    if (!enabled || keyCode == KeyEvent.KEYCODE_BACK) return false
    if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) l2Down = false
    if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) r2Down = false
    return isGameController(event.device) && keyCode != KeyEvent.KEYCODE_BACK
  }

  companion object {
    fun isGameController(device: InputDevice?): Boolean {
      if (device == null || device.isVirtual) return false
      val sources = device.sources
      val hasGamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
      val hasJoystick = (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
      return hasGamepad || hasJoystick
    }

    fun readCenteredAxis(event: MotionEvent, device: InputDevice?, axis: Int): Float {
      val value = event.getAxisValue(axis)
      val flat = device?.getMotionRange(axis, event.source)?.flat?.coerceAtLeast(0.05f) ?: 0.06f
      return if (abs(value) > flat) value.coerceIn(-1f, 1f) else 0f
    }

    private fun axisDirection(v: Float): Int =
      when {
        v > 0.5f -> 1
        v < -0.5f -> -1
        else -> 0
      }
  }
}
