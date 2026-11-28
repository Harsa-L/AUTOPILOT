package com.autopilot.vision

import kotlin.math.abs

data class OpticalFlowHoverOutput(
  val roll: Float = 0f,
  val pitch: Float = 0f,
  val yaw: Float = 0f,
  val active: Boolean = false,
  val holdoffRemainingMs: Long = 0L,
)

/**
 * Experimental optical-flow hover stabilization PD controller ported from pyDroneWire
 * `OpticalFlowStabilizer`. Computes conservative drift-damping corrections (`<= +/-20%` lateral,
 * `<= +/-15%` yaw) from C++20 Lucas-Kanade median/rotational flow summaries, with automatic
 * holdoff whenever the pilot moves manual sticks or feature count drops below threshold.
 */
class OpticalFlowHoverController(
  private val flowDeadzone: Float = 0.008f,
  private val emaAlpha: Float = 0.45f,
  private val kpLateral: Float = 1.6f,
  private val kdLateral: Float = 0.25f,
  private val kpYaw: Float = 0.8f,
  private val maxLateralAuthority: Float = 0.20f,
  private val maxYawAuthority: Float = 0.15f,
  private val minValidVectors: Int = 3,
  private val manualHoldoffMs: Long = 600L,
) {
  private var smoothDx = 0f
  private var smoothDy = 0f
  private var smoothRot = 0f
  private var prevDx = 0f
  private var prevDy = 0f
  private var lastManualInputMs = 0L

  fun notifyManualInput(nowMs: Long) {
    lastManualInputMs = nowMs
  }

  fun reset() {
    smoothDx = 0f
    smoothDy = 0f
    smoothRot = 0f
    prevDx = 0f
    prevDy = 0f
  }

  fun update(
    enabled: Boolean,
    result: VisionFrameResult?,
    nowMs: Long,
  ): OpticalFlowHoverOutput {
    if (!enabled || result == null) {
      reset()
      return OpticalFlowHoverOutput()
    }

    val holdoffElapsed = (nowMs - lastManualInputMs).coerceAtLeast(0L)
    if (lastManualInputMs > 0L && holdoffElapsed < manualHoldoffMs) {
      return OpticalFlowHoverOutput(
        active = false,
        holdoffRemainingMs = manualHoldoffMs - holdoffElapsed,
      )
    }

    if (result.validFlowCount < minValidVectors) {
      return OpticalFlowHoverOutput(active = false)
    }

    smoothDx = emaAlpha * result.globalFlowDxNorm + (1f - emaAlpha) * smoothDx
    smoothDy = emaAlpha * result.globalFlowDyNorm + (1f - emaAlpha) * smoothDy
    smoothRot = emaAlpha * result.rotationalYawOmega + (1f - emaAlpha) * smoothRot

    val dDx = smoothDx - prevDx
    val dDy = smoothDy - prevDy
    prevDx = smoothDx
    prevDy = smoothDy

    // Image moving right (+dx) indicates aircraft drifting left -> command +roll to counter.
    val rawRoll =
      if (abs(smoothDx) > flowDeadzone) {
        (smoothDx * kpLateral + dDx * kdLateral)
      } else {
        0f
      }
    // Image moving down (+dy) indicates aircraft drifting backward -> command +pitch to counter.
    val rawPitch =
      if (abs(smoothDy) > flowDeadzone) {
        (smoothDy * kpLateral + dDy * kdLateral)
      } else {
        0f
      }
    val rawYaw =
      if (abs(smoothRot) > flowDeadzone * 2f) {
        (-smoothRot * kpYaw)
      } else {
        0f
      }

    val rollOut = rawRoll.coerceIn(-maxLateralAuthority, maxLateralAuthority)
    val pitchOut = rawPitch.coerceIn(-maxLateralAuthority, maxLateralAuthority)
    val yawOut = rawYaw.coerceIn(-maxYawAuthority, maxYawAuthority)

    return OpticalFlowHoverOutput(
      roll = rollOut,
      pitch = pitchOut,
      yaw = yawOut,
      active = true,
      holdoffRemainingMs = 0L,
    )
  }
}
