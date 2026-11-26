package com.example.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Zero-permission phone IMU tilt-to-steer controller matching KY UFO "Gravity" mode.
 * Captures the current resting tilt as zero-origin on [setActive] = true so pilots
 * can steer comfortably from any ergonomic handheld angle.
 */
class GravitySensorHandler(
  context: Context,
  private val onGravityAxes: (roll: Float, pitch: Float) -> Unit,
) : SensorEventListener {

  private val sensorManager =
    context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
  private val rotationSensor =
    sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
      ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

  @Volatile
  var maxTiltDeg: Float = 25f

  @Volatile
  var deadzoneDeg: Float = 4f

  @Volatile
  private var isActive: Boolean = false
  private var refPitchDeg: Float? = null
  private var refRollDeg: Float? = null

  private val rotMatrix = FloatArray(9)
  private val orientAngles = FloatArray(3)

  val isSensorAvailable: Boolean
    get() = rotationSensor != null

  fun setActive(enabled: Boolean) {
    if (isActive == enabled) return
    isActive = enabled
    refPitchDeg = null
    refRollDeg = null
    if (enabled) {
      val s = rotationSensor
      if (s != null) {
        runCatching {
          sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        }
      }
    } else {
      runCatching { sensorManager?.unregisterListener(this) }
      onGravityAxes(0f, 0f)
    }
  }

  override fun onSensorChanged(event: SensorEvent) {
    if (!isActive) return
    val pitchDeg: Float
    val rollDeg: Float
    if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
      SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
      SensorManager.getOrientation(rotMatrix, orientAngles)
      pitchDeg = Math.toDegrees(orientAngles[1].toDouble()).toFloat()
      rollDeg = Math.toDegrees(orientAngles[2].toDouble()).toFloat()
    } else {
      val ax = event.values.getOrElse(0) { 0f }
      val ay = event.values.getOrElse(1) { 0f }
      val az = event.values.getOrElse(2) { 9.81f }
      pitchDeg = Math.toDegrees(atan2(ay.toDouble(), sqrt((ax * ax + az * az).toDouble()))).toFloat()
      rollDeg = Math.toDegrees(atan2(-ax.toDouble(), sqrt((ay * ay + az * az).toDouble()))).toFloat()
    }

    if (refPitchDeg == null || refRollDeg == null) {
      refPitchDeg = pitchDeg
      refRollDeg = rollDeg
      return
    }

    val deltaPitch = pitchDeg - (refPitchDeg ?: pitchDeg)
    val deltaRoll = rollDeg - (refRollDeg ?: rollDeg)
    val (normRoll, normPitch) =
      mapTiltDeltas(
        deltaRollDeg = deltaRoll,
        deltaPitchDeg = -deltaPitch,
        deadzoneDeg = deadzoneDeg,
        maxTiltDeg = maxTiltDeg,
      )
    onGravityAxes(normRoll, normPitch)
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

  companion object {
    fun mapTiltDeltas(
      deltaRollDeg: Float,
      deltaPitchDeg: Float,
      deadzoneDeg: Float = 4f,
      maxTiltDeg: Float = 25f,
    ): Pair<Float, Float> {
      val maxRange = maxTiltDeg.coerceAtLeast(deadzoneDeg + 5f)
      fun normalizeAxis(deg: Float): Float {
        val mag = abs(deg)
        if (mag <= deadzoneDeg) return 0f
        val effective = ((mag - deadzoneDeg) / (maxRange - deadzoneDeg)).coerceIn(0f, 1f)
        return if (deg >= 0f) effective else -effective
      }
      return normalizeAxis(deltaRollDeg) to normalizeAxis(deltaPitchDeg)
    }
  }
}
