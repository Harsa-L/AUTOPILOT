package com.autopilot.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpticalFlowHoverControllerTest {

  private fun frame(
    dx: Float = 0f,
    dy: Float = 0f,
    rot: Float = 0f,
    validCount: Int = 8,
  ): VisionFrameResult =
    VisionFrameResult(
      timestampMs = 1000L,
      detectorAvailability = DetectorAvailability.Ready("CPU", "Test"),
      inferenceTimeMs = 1f,
      globalFlowDxNorm = dx,
      globalFlowDyNorm = dy,
      flowVectors = emptyList(),
      trackedTargets = emptyList(),
      isSyntheticInput = false,
      rotationalYawOmega = rot,
      validFlowCount = validCount,
    )

  @Test
  fun `hover controller outputs bounded PD corrections, skips low feature count, and honors 600ms manual holdoff`() {
    val controller = OpticalFlowHoverController()

    // Disabled -> inactive
    assertFalse(controller.update(enabled = false, result = frame(dx = 0.05f), nowMs = 1000L).active)

    // Low valid vector count (< 3) -> inactive
    assertFalse(controller.update(enabled = true, result = frame(dx = 0.05f, validCount = 2), nowMs = 1100L).active)

    // Positive optical drift (+dx right in image, +dy down in image, +rot CW) -> +roll, +pitch, -yaw within clamps
    val large = controller.update(enabled = true, result = frame(dx = 0.5f, dy = 0.5f, rot = 0.5f, validCount = 10), nowMs = 1200L)
    assertTrue(large.active)
    assertTrue(large.roll > 0f && large.roll <= 0.20f)
    assertTrue(large.pitch > 0f && large.pitch <= 0.20f)
    assertTrue(large.yaw < 0f && large.yaw >= -0.15f)

    // Manual input triggers 600ms holdoff window
    controller.notifyManualInput(nowMs = 1500L)
    val held = controller.update(enabled = true, result = frame(dx = 0.1f, dy = 0.1f), nowMs = 1800L)
    assertFalse(held.active)
    assertEquals(300L, held.holdoffRemainingMs)

    // After 600ms elapsed, stabilization resumes cleanly
    val resumed = controller.update(enabled = true, result = frame(dx = 0.1f, dy = 0.1f), nowMs = 2150L)
    assertTrue(resumed.active)
    assertEquals(0L, resumed.holdoffRemainingMs)
  }
}
