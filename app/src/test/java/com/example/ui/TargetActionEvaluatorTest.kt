package com.example.ui

import android.graphics.RectF
import com.autopilot.drone.protocolapi.ProtocolCompatibilityRegistry
import com.autopilot.flight.control.FlightState
import com.autopilot.vision.TrackedTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TargetActionEvaluatorTest {

  @Test
  fun `hover hold is marked unsupported on aircraft without altitude hold or gps`() {
    val s2x = ProtocolCompatibilityRegistry.findById("s2x")!!
    val actions =
      TargetActionEvaluator.evaluateActions(
        descriptor = s2x.descriptor,
        capabilities = s2x.capabilities,
        flightState = FlightState.Flying,
      )
    val hover = actions.first { it.action == TargetActionType.HOVER_HOLD }
    assertFalse(hover.supportedByDrone)
    assertFalse(hover.readyNow)

    val follow = actions.first { it.action == TargetActionType.FOLLOW_TARGET }
    assertTrue(follow.supportedByDrone)
    assertTrue(follow.readyNow)
  }

  @Test
  fun `servo command respects max tracking authority and camera tilt capability`() {
    val s2x = ProtocolCompatibilityRegistry.findById("s2x")!!
    val x69 = ProtocolCompatibilityRegistry.findById("x69_lg")!!
    val target =
      TrackedTarget(
        trackId = 7,
        classId = 0,
        label = "person",
        confidence = 0.9f,
        boxNormalized = RectF(0.75f, 0.10f, 0.95f, 0.40f),
        velocityXNorm = 0f,
        velocityYNorm = 0f,
        estimatedDistanceMeters = 5.0f,
        uncertaintyMeters = 0.5f,
        isDistanceCalibrated = true,
      )
    val cfg = TargetActionConfig(minTrackingDistanceMeters = 2.0f, maxTrackingAuthority = 0.30f)
    val task =
      ActiveTargetTask(
        targetId = 7,
        targetLabel = "person",
        action = TargetActionType.FOLLOW_TARGET,
        config = cfg,
        startedAtMs = 1L,
        lastSeenAtMs = 1L,
      )

    val s2xCmd = TargetActionEvaluator.computeServoCommand(task, target, s2x.capabilities)
    assertTrue(s2xCmd.yaw in 0f..0.30f)
    assertTrue(s2xCmd.pitch in 0f..0.30f)
    assertNull(s2xCmd.cameraTiltDelta)

    val x69Cmd = TargetActionEvaluator.computeServoCommand(task, target, x69.capabilities)
    assertNotNull(x69Cmd.cameraTiltDelta)
    assertEquals(1, x69Cmd.cameraTiltDelta)
  }
}
