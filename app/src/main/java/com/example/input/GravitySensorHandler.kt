package com.example.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import com.autopilot.flight.gravity.GravityCalibrationSnapshot
import com.autopilot.flight.gravity.GravityOrientationMapper
import com.autopilot.flight.gravity.GravityProfile
import com.autopilot.flight.gravity.GravitySignalProcessor
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GravityRuntimeStatus(
  val active: Boolean = false,
  val sensorAvailable: Boolean = false,
  val sensorLabel: String = "Unavailable",
  val rollNorm: Float = 0f,
  val pitchNorm: Float = 0f,
  val stale: Boolean = false,
  val calibration: GravityCalibrationSnapshot = GravityCalibrationSnapshot(),
)

/**
 * Full IMU tilt-to-steer sensor driver following priority:
 * 1. TYPE_GAME_ROTATION_VECTOR
 * 2. TYPE_ROTATION_VECTOR
 * 3. TYPE_GRAVITY
 * 4. Low-pass filtered TYPE_ACCELEROMETER (raw unfiltered accel is never emitted)
 *
 * Maps device axes into the active display rotation and processes all samples through
 * [GravitySignalProcessor] (stability calibration, smoothing, deadzone, curve, slew limit, stale guard).
 */
class GravitySensorHandler(
  private val context: Context,
  private val onGravityAxes: (roll: Float, pitch: Float, valid: Boolean) -> Unit,
) : SensorEventListener {

  private val sensorManager =
    context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
  private val primarySensor: Sensor? =
    sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
      ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
      ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
      ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

  val processor = GravitySignalProcessor()

  @Volatile
  var profile: GravityProfile = GravityProfile()

  var maxTiltDeg: Float
    get() = profile.maxTiltPitchDeg
    set(value) {
      profile = profile.copy(maxTiltPitchDeg = value, maxTiltRollDeg = value)
    }

  var deadzoneDeg: Float
    get() = profile.deadzonePitchDeg
    set(value) {
      profile = profile.copy(deadzonePitchDeg = value, deadzoneRollDeg = value)
    }

  @Volatile
  private var isActive: Boolean = false

  @Volatile
  private var isPausedForTool: Boolean = false

  @Volatile
  private var lastDisplayRotation: Int = readDisplayRotation()

  private val rotMatrix = FloatArray(9)
  private val orientAngles = FloatArray(3)
  private val filteredAccel = FloatArray(3)
  private var hasAccelInit = false

  private val _status =
    MutableStateFlow(
      GravityRuntimeStatus(
        sensorAvailable = primarySensor != null,
        sensorLabel = sensorLabelOf(primarySensor),
      )
    )
  val status: StateFlow<GravityRuntimeStatus> = _status.asStateFlow()

  val isSensorAvailable: Boolean
    get() = primarySensor != null

  fun setActive(enabled: Boolean, progressiveResumeMs: Long = profile.progressiveResumeMs) {
    if (isActive == enabled) {
      if (enabled && !isPausedForTool) {
        processor.notifyModeResumed(progressiveResumeMs)
      }
      return
    }
    isActive = enabled
    isPausedForTool = false
    hasAccelInit = false
    lastDisplayRotation = readDisplayRotation()
    if (enabled) {
      processor.notifyModeResumed(progressiveResumeMs)
      val s = primarySensor
      if (s != null) {
        runCatching {
          sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        }
      }
      _status.value =
        GravityRuntimeStatus(
          active = true,
          sensorAvailable = s != null,
          sensorLabel = sensorLabelOf(s),
          calibration = processor.calibration.currentSnapshot(),
        )
    } else {
      runCatching { sensorManager?.unregisterListener(this) }
      processor.resetAll()
      _status.value =
        GravityRuntimeStatus(
          active = false,
          sensorAvailable = primarySensor != null,
          sensorLabel = sensorLabelOf(primarySensor),
          calibration = GravityCalibrationSnapshot(displayRotation = lastDisplayRotation),
        )
      onGravityAxes(0f, 0f, true)
    }
  }

  fun setPausedForTool(paused: Boolean) {
    if (!isActive || isPausedForTool == paused) return
    isPausedForTool = paused
    if (paused) {
      onGravityAxes(0f, 0f, true)
      _status.value = _status.value.copy(rollNorm = 0f, pitchNorm = 0f)
    } else {
      processor.notifyModeResumed(profile.progressiveResumeMs)
    }
  }

  fun recenter(referenceLocked: Boolean): GravityCalibrationSnapshot {
    val snap = processor.recenter(readDisplayRotation(), referenceLocked)
    _status.value = _status.value.copy(calibration = snap)
    return snap
  }

  fun resetCalibration() {
    processor.calibration.resetCalibration()
    _status.value = _status.value.copy(calibration = processor.calibration.currentSnapshot())
  }

  fun checkFreshness(): Boolean {
    if (!isActive || isPausedForTool) return true
    val staleResult = processor.checkFreshness(profile) ?: return true
    _status.value =
      _status.value.copy(
        rollNorm = 0f,
        pitchNorm = 0f,
        stale = staleResult.stale,
      )
    onGravityAxes(0f, 0f, false)
    return false
  }

  override fun onSensorChanged(event: SensorEvent) {
    if (!isActive || isPausedForTool) return
    val rot = readDisplayRotation()
    if (rot != lastDisplayRotation) {
      lastDisplayRotation = rot
      processor.onOrientationChanged(rot, profile.progressiveResumeMs)
    }

    val (devPitchDeg, devRollDeg) =
      when (event.sensor.type) {
        Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
          SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
          SensorManager.getOrientation(rotMatrix, orientAngles)
          Math.toDegrees(orientAngles[1].toDouble()).toFloat() to
            Math.toDegrees(orientAngles[2].toDouble()).toFloat()
        }
        Sensor.TYPE_GRAVITY -> {
          anglesFromGravityVector(
            event.values.getOrElse(0) { 0f },
            event.values.getOrElse(1) { 0f },
            event.values.getOrElse(2) { 9.81f },
          )
        }
        else -> {
          val axRaw = event.values.getOrElse(0) { 0f }
          val ayRaw = event.values.getOrElse(1) { 0f }
          val azRaw = event.values.getOrElse(2) { 9.81f }
          if (!hasAccelInit) {
            filteredAccel[0] = axRaw
            filteredAccel[1] = ayRaw
            filteredAccel[2] = azRaw
            hasAccelInit = true
          } else {
            val lpf = 0.22f
            filteredAccel[0] += lpf * (axRaw - filteredAccel[0])
            filteredAccel[1] += lpf * (ayRaw - filteredAccel[1])
            filteredAccel[2] += lpf * (azRaw - filteredAccel[2])
          }
          anglesFromGravityVector(filteredAccel[0], filteredAccel[1], filteredAccel[2])
        }
      }

    val out = processor.processSample(devPitchDeg, devRollDeg, rot, profile)
    _status.value =
      GravityRuntimeStatus(
        active = true,
        sensorAvailable = primarySensor != null,
        sensorLabel = sensorLabelOf(primarySensor),
        rollNorm = if (out.valid) out.roll else 0f,
        pitchNorm = if (out.valid) out.pitch else 0f,
        stale = out.stale,
        calibration = processor.calibration.currentSnapshot(),
      )
    if (out.valid) {
      onGravityAxes(out.roll, out.pitch, true)
    } else {
      onGravityAxes(0f, 0f, false)
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

  @Suppress("DEPRECATION")
  private fun readDisplayRotation(): Int {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          context.display?.rotation
        } else null
      }
      .getOrNull()
      ?: runCatching {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        wm?.defaultDisplay?.rotation
      }
        .getOrNull()
      ?: Surface.ROTATION_0
  }

  companion object {
    private fun anglesFromGravityVector(ax: Float, ay: Float, az: Float): Pair<Float, Float> {
      val pitch = Math.toDegrees(atan2(ay.toDouble(), sqrt((ax * ax + az * az).toDouble()))).toFloat()
      val roll = Math.toDegrees(atan2(-ax.toDouble(), sqrt((ay * ay + az * az).toDouble()))).toFloat()
      return pitch to roll
    }

    private fun sensorLabelOf(sensor: Sensor?): String =
      when (sensor?.type) {
        Sensor.TYPE_GAME_ROTATION_VECTOR -> "Game Rotation"
        Sensor.TYPE_ROTATION_VECTOR -> "Rotation Vector"
        Sensor.TYPE_GRAVITY -> "Gravity Vector"
        Sensor.TYPE_ACCELEROMETER -> "Filtered Accel"
        else -> "Unavailable"
      }

    fun mapTiltDeltas(
      deltaRollDeg: Float,
      deltaPitchDeg: Float,
      deadzoneDeg: Float = 4f,
      maxTiltDeg: Float = 25f,
    ): Pair<Float, Float> {
      val (cockpitPitch, cockpitRoll) =
        GravityOrientationMapper.mapDeviceTiltToCockpit(deltaPitchDeg, deltaRollDeg, Surface.ROTATION_0)
      val rollOut =
        GravitySignalProcessor.shapeAxis(
          deltaDeg = cockpitRoll,
          deadzoneDeg = deadzoneDeg,
          maxTiltDeg = maxTiltDeg,
          sensitivity = 1.0f,
          curve = com.autopilot.flight.gravity.GravityResponseCurve.LINEAR,
        )
      val pitchOut =
        GravitySignalProcessor.shapeAxis(
          deltaDeg = cockpitPitch,
          deadzoneDeg = deadzoneDeg,
          maxTiltDeg = maxTiltDeg,
          sensitivity = 1.0f,
          curve = com.autopilot.flight.gravity.GravityResponseCurve.LINEAR,
        )
      return rollOut to pitchOut
    }
  }
}
