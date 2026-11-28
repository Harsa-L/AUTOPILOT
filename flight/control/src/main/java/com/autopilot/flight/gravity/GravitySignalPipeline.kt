package com.autopilot.flight.gravity

import android.view.Surface
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

enum class GravityPreset(
  val label: String,
  val sensitivity: Float,
  val deadzoneDeg: Float,
  val smoothingAlpha: Float,
  val slewRatePerSec: Float,
) {
  PRECISION("Precision", sensitivity = 0.72f, deadzoneDeg = 5.0f, smoothingAlpha = 0.25f, slewRatePerSec = 2.2f),
  NORMAL("Normal", sensitivity = 1.0f, deadzoneDeg = 4.0f, smoothingAlpha = 0.38f, slewRatePerSec = 3.5f),
  SPORT("Sport", sensitivity = 1.25f, deadzoneDeg = 2.8f, smoothingAlpha = 0.55f, slewRatePerSec = 5.5f),
}

enum class GravityResponseCurve(val label: String) {
  LINEAR("Linear"),
  PROGRESSIVE_EXPO("Progressive"),
  PRECISION_CUBIC("Cubic"),
}

data class GravityProfile(
  val enabled: Boolean = false,
  val sensitivityPitch: Float = 1.0f,
  val sensitivityRoll: Float = 1.0f,
  val deadzonePitchDeg: Float = 4.0f,
  val deadzoneRollDeg: Float = 4.0f,
  val maxTiltPitchDeg: Float = 25.0f,
  val maxTiltRollDeg: Float = 25.0f,
  val responseCurve: GravityResponseCurve = GravityResponseCurve.PROGRESSIVE_EXPO,
  val smoothingAlpha: Float = 0.38f,
  val slewRatePerSec: Float = 3.5f,
  val preset: GravityPreset = GravityPreset.NORMAL,
  val staleSensorTimeoutMs: Long = 350L,
  val lockReferenceDuringSequence: Boolean = true,
  val progressiveResumeMs: Long = 450L,
)

data class GravityCalibrationSnapshot(
  val calibrated: Boolean = false,
  val refPitchDeg: Float = 0f,
  val refRollDeg: Float = 0f,
  val displayRotation: Int = Surface.ROTATION_0,
  val stable: Boolean = false,
  val blockedReason: String? = null,
)

data class GravityProcessedOutput(
  val roll: Float = 0f,
  val pitch: Float = 0f,
  val valid: Boolean = false,
  val stale: Boolean = false,
  val deltaRollDeg: Float = 0f,
  val deltaPitchDeg: Float = 0f,
)

/**
 * Remaps device IMU pitch/roll degrees into cockpit-relative pitch/roll degrees across
 * portrait, landscape, and reverse-landscape screen orientations so rotation never inverts controls.
 */
object GravityOrientationMapper {
  fun mapDeviceTiltToCockpit(
    devicePitchDeg: Float,
    deviceRollDeg: Float,
    displayRotation: Int,
  ): Pair<Float, Float> {
    if (!devicePitchDeg.isFinite() || !deviceRollDeg.isFinite()) return 0f to 0f
    return when (displayRotation) {
      Surface.ROTATION_90 -> {
        // Landscape (top rotated left): device +pitch maps to cockpit +roll; device -roll maps to cockpit +pitch
        val cockpitPitch = -deviceRollDeg
        val cockpitRoll = devicePitchDeg
        cockpitPitch to cockpitRoll
      }
      Surface.ROTATION_270 -> {
        // Reverse landscape: device +roll maps to cockpit +pitch; device -pitch maps to cockpit +roll
        val cockpitPitch = deviceRollDeg
        val cockpitRoll = -devicePitchDeg
        cockpitPitch to cockpitRoll
      }
      Surface.ROTATION_180 -> {
        -devicePitchDeg to -deviceRollDeg
      }
      else -> {
        // Surface.ROTATION_0 (portrait)
        devicePitchDeg to deviceRollDeg
      }
    }
  }
}

/**
 * Verifies device stability before accepting manual or initial neutral calibration,
 * preventing accidental re-zeroing mid-maneuver or while a sequence locks the reference.
 */
class GravityCalibrationController {
  private val perOrientationRefs = mutableMapOf<Int, Pair<Float, Float>>()
  private val recentSamples = ArrayDeque<Pair<Float, Float>>(8)
  private var snapshot = GravityCalibrationSnapshot()

  fun currentSnapshot(): GravityCalibrationSnapshot = snapshot

  fun observeSample(cockpitPitchDeg: Float, cockpitRollDeg: Float, displayRotation: Int) {
    if (!cockpitPitchDeg.isFinite() || !cockpitRollDeg.isFinite()) return
    if (recentSamples.size >= 8) recentSamples.removeFirst()
    recentSamples.addLast(cockpitPitchDeg to cockpitRollDeg)

    if (displayRotation != snapshot.displayRotation) {
      val cached = perOrientationRefs[displayRotation]
      snapshot =
        if (cached != null) {
          snapshot.copy(
            calibrated = true,
            refPitchDeg = cached.first,
            refRollDeg = cached.second,
            displayRotation = displayRotation,
          )
        } else {
          snapshot.copy(
            calibrated = false,
            displayRotation = displayRotation,
          )
        }
    }

    val stable = isStableNow()
    snapshot = snapshot.copy(stable = stable)
    if (!snapshot.calibrated && stable) {
      perOrientationRefs[displayRotation] = cockpitPitchDeg to cockpitRollDeg
      snapshot =
        snapshot.copy(
          calibrated = true,
          refPitchDeg = cockpitPitchDeg,
          refRollDeg = cockpitRollDeg,
          blockedReason = null,
        )
    }
  }

  fun requestRecenter(
    cockpitPitchDeg: Float,
    cockpitRollDeg: Float,
    displayRotation: Int,
    activeCommandMagnitude: Float,
    referenceLocked: Boolean,
  ): GravityCalibrationSnapshot {
    if (referenceLocked) {
      snapshot = snapshot.copy(blockedReason = "Reference locked during active sequence")
      return snapshot
    }
    if (activeCommandMagnitude > 0.45f) {
      snapshot = snapshot.copy(blockedReason = "Center sticks/tilt slightly before recentering")
      return snapshot
    }
    if (!isStableNow()) {
      snapshot = snapshot.copy(blockedReason = "Hold phone steady for calibration")
      return snapshot
    }
    perOrientationRefs[displayRotation] = cockpitPitchDeg to cockpitRollDeg
    snapshot =
      GravityCalibrationSnapshot(
        calibrated = true,
        refPitchDeg = cockpitPitchDeg,
        refRollDeg = cockpitRollDeg,
        displayRotation = displayRotation,
        stable = true,
        blockedReason = null,
      )
    return snapshot
  }

  fun resetCalibration() {
    perOrientationRefs.clear()
    recentSamples.clear()
    snapshot = GravityCalibrationSnapshot(displayRotation = snapshot.displayRotation)
  }

  private fun isStableNow(): Boolean {
    if (recentSamples.size < 2) return true
    var minP = Float.POSITIVE_INFINITY
    var maxP = Float.NEGATIVE_INFINITY
    var minR = Float.POSITIVE_INFINITY
    var maxR = Float.NEGATIVE_INFINITY
    for ((p, r) in recentSamples) {
      if (p < minP) minP = p
      if (p > maxP) maxP = p
      if (r < minR) minR = r
      if (r > maxR) maxR = r
    }
    return (maxP - minP) <= 4.5f && (maxR - minR) <= 4.5f
  }
}

/**
 * Full Gravity DSP stage: orientation map, jump check, smoothing, reference subtraction,
 * per-axis deadzone, curve shaping, slew-rate limit, progressive resume, and stale timeout guard.
 */
class GravitySignalProcessor(
  private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
  val calibration = GravityCalibrationController()

  private var filteredPitchDeg: Float = 0f
  private var filteredRollDeg: Float = 0f
  private var hasFilteredSample = false
  private var lastCockpitPitch: Float = 0f
  private var lastCockpitRoll: Float = 0f
  private var lastSampleTimestampMs: Long = 0L
  private var lastOutRoll: Float = 0f
  private var lastOutPitch: Float = 0f
  private var lastStepTimestampMs: Long = 0L
  private var resumeRampStartMs: Long = 0L
  private var resumeRampDurationMs: Long = 0L

  fun notifyModeResumed(progressiveResumeMs: Long) {
    resumeRampStartMs = nowMs()
    resumeRampDurationMs = progressiveResumeMs.coerceAtLeast(0L)
    lastOutRoll = 0f
    lastOutPitch = 0f
  }

  fun onOrientationChanged(newRotation: Int, progressivePauseMs: Long = 450L) {
    hasFilteredSample = false
    lastOutRoll = 0f
    lastOutPitch = 0f
    resumeRampStartMs = nowMs()
    resumeRampDurationMs = progressivePauseMs
    calibration.observeSample(lastCockpitPitch, lastCockpitRoll, newRotation)
  }

  fun recenter(displayRotation: Int, referenceLocked: Boolean): GravityCalibrationSnapshot =
    calibration.requestRecenter(
      cockpitPitchDeg = lastCockpitPitch,
      cockpitRollDeg = lastCockpitRoll,
      displayRotation = displayRotation,
      activeCommandMagnitude = maxOf(abs(lastOutRoll), abs(lastOutPitch)),
      referenceLocked = referenceLocked,
    )

  fun resetAll() {
    hasFilteredSample = false
    lastSampleTimestampMs = 0L
    lastOutRoll = 0f
    lastOutPitch = 0f
    calibration.resetCalibration()
  }

  fun processSample(
    devicePitchDeg: Float,
    deviceRollDeg: Float,
    displayRotation: Int,
    profile: GravityProfile,
  ): GravityProcessedOutput {
    if (!devicePitchDeg.isFinite() || !deviceRollDeg.isFinite()) {
      return GravityProcessedOutput(valid = false)
    }
    val now = nowMs()
    val (cockpitPitch, cockpitRoll) =
      GravityOrientationMapper.mapDeviceTiltToCockpit(devicePitchDeg, deviceRollDeg, displayRotation)

    // Abnormal instantaneous IMU spike rejection (> 65 deg within 50ms)
    if (hasFilteredSample && (now - lastSampleTimestampMs) in 1L..50L) {
      if (abs(cockpitPitch - lastCockpitPitch) > 65f || abs(cockpitRoll - lastCockpitRoll) > 65f) {
        return GravityProcessedOutput(roll = lastOutRoll, pitch = lastOutPitch, valid = false)
      }
    }

    lastCockpitPitch = cockpitPitch
    lastCockpitRoll = cockpitRoll
    lastSampleTimestampMs = now

    calibration.observeSample(cockpitPitch, cockpitRoll, displayRotation)
    val snap = calibration.currentSnapshot()
    if (!snap.calibrated) {
      return GravityProcessedOutput(roll = 0f, pitch = 0f, valid = true)
    }

    val alpha = profile.smoothingAlpha.coerceIn(0.08f, 1.0f)
    if (!hasFilteredSample) {
      filteredPitchDeg = cockpitPitch
      filteredRollDeg = cockpitRoll
      hasFilteredSample = true
    } else {
      filteredPitchDeg += alpha * (cockpitPitch - filteredPitchDeg)
      filteredRollDeg += alpha * (cockpitRoll - filteredRollDeg)
    }

    val deltaPitch = -(filteredPitchDeg - snap.refPitchDeg)
    val deltaRoll = filteredRollDeg - snap.refRollDeg

    val targetRoll =
      shapeAxis(
        deltaDeg = deltaRoll,
        deadzoneDeg = profile.deadzoneRollDeg,
        maxTiltDeg = profile.maxTiltRollDeg,
        sensitivity = profile.sensitivityRoll,
        curve = profile.responseCurve,
      )
    val targetPitch =
      shapeAxis(
        deltaDeg = deltaPitch,
        deadzoneDeg = profile.deadzonePitchDeg,
        maxTiltDeg = profile.maxTiltPitchDeg,
        sensitivity = profile.sensitivityPitch,
        curve = profile.responseCurve,
      )

    val dtSec =
      if (lastStepTimestampMs == 0L) 0.02f
      else ((now - lastStepTimestampMs).coerceIn(5L, 120L) / 1000f)
    lastStepTimestampMs = now

    val maxDelta = (profile.slewRatePerSec.coerceAtLeast(0.5f) * dtSec)
    var nextRoll = stepSlew(lastOutRoll, targetRoll, maxDelta)
    var nextPitch = stepSlew(lastOutPitch, targetPitch, maxDelta)

    if (resumeRampDurationMs > 0L) {
      val rampElapsed = (now - resumeRampStartMs).coerceAtLeast(0L)
      if (rampElapsed >= resumeRampDurationMs) {
        resumeRampDurationMs = 0L
      } else {
        val gain = (rampElapsed.toFloat() / resumeRampDurationMs.toFloat()).coerceIn(0f, 1f)
        nextRoll *= gain
        nextPitch *= gain
      }
    }

    lastOutRoll = nextRoll
    lastOutPitch = nextPitch

    return GravityProcessedOutput(
      roll = nextRoll,
      pitch = nextPitch,
      valid = true,
      stale = false,
      deltaRollDeg = deltaRoll,
      deltaPitchDeg = deltaPitch,
    )
  }

  fun checkFreshness(profile: GravityProfile): GravityProcessedOutput? {
    if (lastSampleTimestampMs == 0L) return null
    val age = nowMs() - lastSampleTimestampMs
    return if (age > profile.staleSensorTimeoutMs) {
      lastOutRoll = 0f
      lastOutPitch = 0f
      GravityProcessedOutput(roll = 0f, pitch = 0f, valid = false, stale = true)
    } else {
      null
    }
  }

  companion object {
    fun shapeAxis(
      deltaDeg: Float,
      deadzoneDeg: Float,
      maxTiltDeg: Float,
      sensitivity: Float,
      curve: GravityResponseCurve,
    ): Float {
      val dz = deadzoneDeg.coerceIn(0f, 15f)
      val maxTilt = maxTiltDeg.coerceAtLeast(dz + 5f)
      val mag = abs(deltaDeg)
      if (mag <= dz) return 0f
      val linear = ((mag - dz) / (maxTilt - dz)).coerceIn(0f, 1f)
      val curved =
        when (curve) {
          GravityResponseCurve.LINEAR -> linear
          GravityResponseCurve.PROGRESSIVE_EXPO -> 0.55f * linear + 0.45f * linear.pow(3)
          GravityResponseCurve.PRECISION_CUBIC -> linear.pow(3)
        }
      return (sign(deltaDeg) * curved * sensitivity.coerceIn(0.25f, 1.5f)).coerceIn(-1f, 1f)
    }

    private fun stepSlew(current: Float, target: Float, maxDelta: Float): Float {
      val diff = target - current
      return if (abs(diff) <= maxDelta) target else current + sign(diff) * maxDelta
    }
  }
}
