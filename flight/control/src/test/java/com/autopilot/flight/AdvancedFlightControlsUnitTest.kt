package com.autopilot.flight

import android.view.Surface
import com.autopilot.drone.protocolapi.ProtocolCompatibilityRegistry
import com.autopilot.flight.gravity.GravityCalibrationController
import com.autopilot.flight.gravity.GravityOrientationMapper
import com.autopilot.flight.gravity.GravityProfile
import com.autopilot.flight.gravity.GravityResponseCurve
import com.autopilot.flight.gravity.GravitySignalProcessor
import com.autopilot.flight.input.DeviceSafetyPolicy
import com.autopilot.flight.input.FlightAxis
import com.autopilot.flight.input.FlightCommandLimiter
import com.autopilot.flight.input.FlightInputMixer
import com.autopilot.flight.input.InputSourceId
import com.autopilot.flight.input.NeutralSemantic
import com.autopilot.flight.input.StickInputProfile
import com.autopilot.flight.interaction.CockpitInteractionMode
import com.autopilot.flight.interaction.InteractionModeController
import com.autopilot.flight.interaction.PointerRouteDecision
import com.autopilot.flight.interaction.TouchInteractionRouter
import com.autopilot.flight.interaction.TouchOwner
import com.autopilot.flight.touch.JoystickMode
import com.autopilot.flight.touch.TouchControlEngine
import com.autopilot.flight.touch.TouchPointPx
import com.autopilot.flight.trajectory.NormPoint
import com.autopilot.flight.trajectory.TrajectoryPathProcessor
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedFlightControlsUnitTest {

  @Test
  fun `FlightInputMixer enforces hybrid axis ownership, stale expiry, progressive ramp, and hardware preemption`() {
    var clockMs = 1000L
    val mixer = FlightInputMixer(nowMs = { clockMs })
    mixer.ownershipManager.applyProfile(StickInputProfile.ATC_HYBRID_GRAVITY, gravityEnabled = true)

    mixer.submit(
      InputSourceId.ATC_LEFT,
      mapOf(FlightAxis.THROTTLE to 0.5f, FlightAxis.YAW to -0.3f),
      active = true,
    )
    mixer.submit(
      InputSourceId.GRAVITY,
      mapOf(FlightAxis.ROLL to 0.4f, FlightAxis.PITCH to -0.6f),
      active = true,
      staleTimeoutMs = 350L,
    )

    val initial = mixer.mix()
    assertEquals(0.5f, initial.throttle, 1e-4f)
    assertEquals(-0.3f, initial.yaw, 1e-4f)
    assertEquals(0.4f, initial.roll, 1e-4f)
    assertEquals(-0.6f, initial.pitch, 1e-4f)
    assertEquals(InputSourceId.GRAVITY, initial.activeOwners[FlightAxis.ROLL])
    assertEquals(InputSourceId.ATC_LEFT, initial.activeOwners[FlightAxis.THROTTLE])

    // Hardware gamepad override preempts both ATC and Gravity cleanly
    mixer.submit(
      InputSourceId.GAMEPAD,
      mapOf(
        FlightAxis.ROLL to -0.8f,
        FlightAxis.PITCH to 0.8f,
        FlightAxis.THROTTLE to 0.1f,
        FlightAxis.YAW to 0.2f,
      ),
      active = true,
    )
    val preempted = mixer.mix(activeHardwareOverride = InputSourceId.GAMEPAD)
    assertEquals(-0.8f, preempted.roll, 1e-4f)
    assertEquals(0.1f, preempted.throttle, 1e-4f)
    assertEquals(InputSourceId.GAMEPAD, preempted.activeOwners[FlightAxis.ROLL])

    // Stale gravity sample expires cleanly while ATC_LEFT continues unaffected
    clockMs += 400L
    val afterStale = mixer.mix()
    assertTrue(afterStale.staleClearedSources.contains(InputSourceId.GRAVITY))
    assertEquals(0f, afterStale.roll, 1e-4f)
    assertEquals(0f, afterStale.pitch, 1e-4f)
    assertEquals(0.5f, afterStale.throttle, 1e-4f)

    // Progressive resume ramps gravity output smoothly from 0..1 over resume duration
    mixer.suspendSource(InputSourceId.GRAVITY, neutralizeOwnedAxes = true)
    mixer.resumeSource(InputSourceId.GRAVITY, progressiveRampMs = 500L)
    mixer.submit(InputSourceId.GRAVITY, mapOf(FlightAxis.ROLL to 1.0f, FlightAxis.PITCH to 1.0f), active = true)
    clockMs += 250L
    val midRamp = mixer.mix()
    assertEquals(0.5f, midRamp.roll, 0.05f)
    clockMs += 300L
    val fullRamp = mixer.mix()
    assertEquals(1.0f, fullRamp.roll, 1e-4f)
  }

  @Test
  fun `DeviceSafetyPolicy refuses trajectory execution on real toy protocols and exposes neutral semantics`() {
    val s2x = ProtocolCompatibilityRegistry.findById("s2x")!!
    val eval = DeviceSafetyPolicy.evaluate(s2x.descriptor, s2x.capabilities)
    assertFalse(eval.supportsTrajectoryExecution)
    assertNotNull(eval.trajectoryBlockReason)
    assertEquals(NeutralSemantic.PROTOCOL_NEUTRAL_NO_ALT_HOLD, eval.neutralSemantic)

    val harness = ProtocolCompatibilityRegistry.isolatedTestHarness
    val harnessEval = DeviceSafetyPolicy.evaluate(harness.descriptor, harness.capabilities)
    assertTrue(harnessEval.supportsTrajectoryExecution)
    assertNull(harnessEval.trajectoryBlockReason)
  }

  @Test
  fun `InteractionModeController and TouchInteractionRouter enforce pointer ownership and prevent mode bleed`() {
    val controller = InteractionModeController()
    val router = TouchInteractionRouter(controller)

    // Concurrent left + right ATC fingers work with independent pointer IDs
    val dLeft = router.onPointerDown(pointerId = 1, requestedAtcZone = TouchOwner.ATC_LEFT)
    val dRight = router.onPointerDown(pointerId = 2, requestedAtcZone = TouchOwner.ATC_RIGHT)
    assertEquals(PointerRouteDecision.Capture(TouchOwner.ATC_LEFT), dLeft)
    assertEquals(PointerRouteDecision.Capture(TouchOwner.ATC_RIGHT), dRight)

    // Second finger on an occupied zone is ignored cleanly
    val busyLeft = router.onPointerDown(pointerId = 3, requestedAtcZone = TouchOwner.ATC_LEFT)
    assertEquals(PointerRouteDecision.IgnoreBusyOwner, busyLeft)

    // Switching to TRAJECTORY evicts held fingers & requires fresh pointer DOWN before drawing
    controller.enterTrajectoryMode(gravityCurrentlyActive = true)
    assertEquals(CockpitInteractionMode.TRAJECTORY, controller.state.mode)
    assertEquals(TouchOwner.NONE, router.onPointerMove(pointerId = 1))

    // Held finger 1 cannot hijack trajectory draw until lifted cleanly
    assertEquals(
      PointerRouteDecision.IgnoreExistingHeld,
      router.onPointerDown(pointerId = 1, requestedAtcZone = TouchOwner.TRAJECTORY_DRAW),
    )
    router.onPointerUpOrCancel(pointerId = 1)
    assertEquals(
      PointerRouteDecision.Capture(TouchOwner.TRAJECTORY_DRAW),
      router.onPointerDown(pointerId = 1),
    )

    // HUD Edit suspends flight inputs & stores gravity flag for safe progressive resume
    controller.enterHudEditingMode(gravityCurrentlyActive = false)
    assertTrue(controller.state.suspendedInputSources.contains(InputSourceId.GRAVITY))
    val resumed = controller.returnToPiloting(restoreGravity = true)
    assertEquals(CockpitInteractionMode.PILOTING, resumed.mode)
    assertTrue(resumed.requiresFreshPointerDown.contains(TouchOwner.ATC_LEFT))
  }

  @Test
  fun `GravityOrientationMapper, calibration stability gate, and DSP processor handle orientation and spikes safely`() {
    val (p90, r90) = GravityOrientationMapper.mapDeviceTiltToCockpit(12f, -8f, Surface.ROTATION_90)
    assertEquals(8f, p90, 1e-4f)
    assertEquals(12f, r90, 1e-4f)

    val (p270, r270) = GravityOrientationMapper.mapDeviceTiltToCockpit(12f, -8f, Surface.ROTATION_270)
    assertEquals(-8f, p270, 1e-4f)
    assertEquals(-12f, r270, 1e-4f)

    val cal = GravityCalibrationController()
    cal.observeSample(5f, 2f, Surface.ROTATION_0)
    assertTrue(cal.currentSnapshot().calibrated)

    // Rejects recentering when sequence locks reference or tilt command magnitude is large
    val lockedSnap = cal.requestRecenter(5f, 2f, Surface.ROTATION_0, activeCommandMagnitude = 0.1f, referenceLocked = true)
    assertNotNull(lockedSnap.blockedReason)
    val highTiltSnap = cal.requestRecenter(5f, 2f, Surface.ROTATION_0, activeCommandMagnitude = 0.8f, referenceLocked = false)
    assertNotNull(highTiltSnap.blockedReason)

    var nowMs = 100L
    val proc = GravitySignalProcessor(nowMs = { nowMs })
    val profile =
      GravityProfile(
        enabled = true,
        deadzonePitchDeg = 4f,
        deadzoneRollDeg = 4f,
        maxTiltPitchDeg = 24f,
        maxTiltRollDeg = 24f,
        responseCurve = GravityResponseCurve.PRECISION_CUBIC,
        smoothingAlpha = 1.0f,
        slewRatePerSec = 10f,
        staleSensorTimeoutMs = 300L,
      )
    proc.processSample(0f, 0f, Surface.ROTATION_0, profile)
    nowMs += 20L
    val dead = proc.processSample(2f, -2f, Surface.ROTATION_0, profile)
    assertEquals(0f, dead.roll, 1e-4f)
    assertEquals(0f, dead.pitch, 1e-4f)

    // Sudden 80-deg jump inside 20ms is rejected as abnormal IMU spike
    nowMs += 20L
    val spike = proc.processSample(85f, 0f, Surface.ROTATION_0, profile)
    assertFalse(spike.valid)

    // Stale watchdog invalidates old output after timeout
    nowMs += 400L
    val stale = proc.checkFreshness(profile)
    assertNotNull(stale)
    assertTrue(stale!!.stale)
    assertFalse(stale.valid)
  }

  @Test
  fun `TouchControlEngine sets neutral anchor on DOWN, computes relative MOVE, and isolates UP per zone`() {
    val engine =
      TouchControlEngine(
        leftConfig = TouchControlEngine.defaultLeftZone().copy(joystickMode = JoystickMode.FLOATING),
        rightConfig = TouchControlEngine.defaultRightZone().copy(joystickMode = JoystickMode.FLOATING),
      )
    val leftDown = engine.onDown("left", pointerId = 10, TouchPointPx(200f, 500f), 1080f, 1920f)!!
    assertEquals(0f, leftDown.axes[FlightAxis.YAW] ?: -1f, 1e-4f)
    assertEquals(0f, leftDown.axes[FlightAxis.THROTTLE] ?: -1f, 1e-4f)

    val rightDown = engine.onDown("right", pointerId = 11, TouchPointPx(850f, 500f), 1080f, 1920f)!!
    assertEquals(0f, rightDown.axes[FlightAxis.ROLL] ?: -1f, 1e-4f)

    // Moving right finger outside its original half stays captured on right zone by pointerId 11
    val rightMove = engine.onMove(pointerId = 11, TouchPointPx(100f, 380f))!!
    assertEquals("right", rightMove.zoneId)
    assertTrue((rightMove.axes[FlightAxis.ROLL] ?: 0f) < -0.5f)
    assertTrue((rightMove.axes[FlightAxis.PITCH] ?: 0f) > 0.04f)

    // Moving left finger upward commands positive throttle independently
    val leftMove = engine.onMove(pointerId = 10, TouchPointPx(200f, 360f))!!
    assertEquals("left", leftMove.zoneId)
    assertTrue((leftMove.axes[FlightAxis.THROTTLE] ?: 0f) > 0.8f)

    // Releasing right finger leaves left finger untouched and still holding its axis
    val rightUp = engine.onUpOrCancel(pointerId = 11)!!
    assertEquals(0f, rightUp.axes[FlightAxis.ROLL] ?: -1f, 1e-4f)
    assertEquals(0f, rightUp.axes[FlightAxis.PITCH] ?: -1f, 1e-4f)
    assertNotNull(engine.leftRuntime.activePointerId)
    assertNull(engine.rightRuntime.activePointerId)
  }

  @Test
  fun `TrajectoryPathProcessor smooths and simplifies freehand path into honest directional waypoints`() {
    val raw =
      listOf(
        NormPoint(0.20f, 0.80f),
        NormPoint(0.205f, 0.795f), // noisy micro jitter below spacing
        NormPoint(0.21f, 0.60f),
        NormPoint(0.22f, 0.40f),
        NormPoint(0.55f, 0.40f),
      )
    val cleaned = TrajectoryPathProcessor.process(raw)
    assertTrue(cleaned.isValidForPreview)
    assertTrue(cleaned.cleanedPoints.size <= raw.size)
    assertTrue(cleaned.waypoints.isNotEmpty())
    assertTrue(abs(cleaned.waypoints.first().headingDy) > 0.5f)
  }

  @Test
  fun `FlightCommandLimiter enforces per-step slew limit and resets cleanly`() {
    val limiter = FlightCommandLimiter(maxDeltaPerStep = 0.25f)
    assertEquals(0.25f, limiter.limit(FlightAxis.ROLL, 1.0f), 1e-4f)
    assertEquals(0.50f, limiter.limit(FlightAxis.ROLL, 1.0f), 1e-4f)
    // Bypass immediately reaches target
    assertEquals(-0.8f, limiter.limit(FlightAxis.ROLL, -0.8f, bypassSlew = true), 1e-4f)
    limiter.reset(FlightAxis.ROLL)
    assertEquals(0.25f, limiter.limit(FlightAxis.ROLL, 0.9f), 1e-4f)
  }

  @Test
  fun `JoystickMode HYBRID shows rest anchor when idle and anchors dynamically on DOWN`() {
    val engine =
      TouchControlEngine(
        leftConfig = TouchControlEngine.defaultLeftZone().copy(joystickMode = JoystickMode.HYBRID),
        rightConfig = TouchControlEngine.defaultRightZone().copy(joystickMode = JoystickMode.FLOATING),
      )
    assertTrue(engine.leftRuntime.showRestAnchor)
    assertFalse(engine.leftRuntime.isVisible)
    assertFalse(engine.rightRuntime.showRestAnchor)

    // DOWN in HYBRID anchors dynamically at touch coordinates rather than fixedCenterNormalized
    engine.onDown("left", pointerId = 7, TouchPointPx(260f, 420f), 1080f, 1920f)
    assertTrue(engine.leftRuntime.isVisible)
    assertEquals(260f, engine.leftRuntime.anchorPx!!.x, 1e-3f)
    assertEquals(420f, engine.leftRuntime.anchorPx!!.y, 1e-3f)

    engine.onUpOrCancel(pointerId = 7)
    assertFalse(engine.leftRuntime.isVisible)
    assertTrue(engine.leftRuntime.showRestAnchor)
  }
}
