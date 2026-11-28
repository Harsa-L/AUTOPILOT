package com.autopilot.visionbenchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionBenchmarkDomainTest {

  @Test
  fun `built-in profiles contain all 8 required presets and reference drone disclaimer`() {
    val ids = BuiltInDegradationProfiles.all.map { it.id }
    assertTrue(ids.contains("reference_drone"))
    assertTrue(ids.contains("low_quality"))
    assertTrue(ids.contains("very_low_quality"))
    assertTrue(ids.contains("severe_compression"))
    assertTrue(ids.contains("unstable_transmission"))
    assertTrue(ids.contains("poor_lighting"))
    assertTrue(ids.contains("motion_blur"))
    assertTrue(ids.contains("custom"))
    assertEquals(8, BuiltInDegradationProfiles.all.size)

    val ref = BuiltInDegradationProfiles.all.first { it.id == "reference_drone" }
    assertTrue(ref.notes.contains("not measured from a physical drone", ignoreCase = true))
  }

  @Test
  fun `auto degradation scheduler is strictly deterministic per seed`() {
    val base = ManualDegradationConfig()
    val autoA =
      AutoDegradationConfig(
        mode = AutoScenarioMode.RANDOMIZED_BOUNDED,
        seed = 987654L,
        stepIntervalMs = 500,
      )
    val autoB = autoA.copy(seed = 123456L)

    val stepA1 = AutoDegradationScheduler.evaluate(base, autoA, elapsedMs = 2500L, frameIndex = 50L)
    val stepA2 = AutoDegradationScheduler.evaluate(base, autoA, elapsedMs = 2500L, frameIndex = 50L)
    val stepB = AutoDegradationScheduler.evaluate(base, autoB, elapsedMs = 2500L, frameIndex = 50L)

    assertEquals(stepA1.effectiveConfig, stepA2.effectiveConfig)
    assertEquals(stepA1.frameSeed, stepA2.frameSeed)
    assertNotEquals(stepA1.effectiveConfig, stepB.effectiveConfig)
  }

  @Test
  fun `progressive and time-based auto modes advance degradation stages`() {
    val base = ManualDegradationConfig()
    val progressive =
      AutoDegradationConfig(mode = AutoScenarioMode.PROGRESSIVE_DEGRADATION, cycleDurationSec = 20)
    val early = AutoDegradationScheduler.evaluate(base, progressive, elapsedMs = 1000L, frameIndex = 10L)
    val late = AutoDegradationScheduler.evaluate(base, progressive, elapsedMs = 18000L, frameIndex = 360L)
    assertTrue(late.effectiveConfig.gaussianBlur > early.effectiveConfig.gaussianBlur)
    assertTrue(late.effectiveConfig.noiseStrength > early.effectiveConfig.noiseStrength)

    val scheduled = AutoDegradationConfig(mode = AutoScenarioMode.TIME_BASED_SCHEDULE, cycleDurationSec = 40)
    val stage0 = AutoDegradationScheduler.evaluate(base, scheduled, elapsedMs = 2000L, frameIndex = 1L)
    val stage2 = AutoDegradationScheduler.evaluate(base, scheduled, elapsedMs = 22000L, frameIndex = 2L)
    assertNotEquals(stage0.stageLabel, stage2.stageLabel)
  }

  @Test
  fun `transmission simulator enforces pacing drops blackouts and freezes`() {
    val sim = TransmissionLinkStateMachine()

    // Pacing skips frames faster than targetFps (10 fps -> 100ms interval)
    val cfgPace = ManualDegradationConfig(targetFps = 10)
    val (firstOutcome, _) = sim.nextStep(nowMs = 1000L, config = cfgPace, seed = 1L)
    assertEquals(TransmissionOutcome.DELIVERED, firstOutcome)
    val (tooSoonOutcome, _) = sim.nextStep(nowMs = 1020L, config = cfgPace, seed = 2L)
    assertEquals(TransmissionOutcome.PACED_SKIPPED, tooSoonOutcome)

    // 100% packet loss drops frame
    val cfgDrop = ManualDegradationConfig(targetFps = 30, transmission = TransmissionConfig(packetLossPct = 100f))
    val (droppedOutcome, _) = sim.nextStep(nowMs = 1200L, config = cfgDrop, seed = 3L)
    assertEquals(TransmissionOutcome.DROPPED, droppedOutcome)

    // 100% freeze holds previous valid delivered bitmap
    val cfgFreeze = ManualDegradationConfig(targetFps = 30, transmission = TransmissionConfig(freezeProbabilityPct = 100f))
    val (frozenOutcome, _) = sim.nextStep(nowMs = 1400L, config = cfgFreeze, seed = 4L)
    assertEquals(TransmissionOutcome.FROZEN_HOLD, frozenOutcome)

    // 100% blackout triggers blackout burst
    val cfgBlackout = ManualDegradationConfig(targetFps = 30, transmission = TransmissionConfig(blackoutBurstPct = 100f))
    val (blackoutOutcome, _) = sim.nextStep(nowMs = 2000L, config = cfgBlackout, seed = 5L)
    assertEquals(TransmissionOutcome.BLACKOUT, blackoutOutcome)
  }

  @Test
  fun `percentile stats and even target dimensions resolve accurately`() {
    val values = (1..100).map { it.toFloat() }
    val stats = PercentileStats.fromValues(values)
    assertEquals(1f, stats.min, 0.01f)
    assertEquals(100f, stats.max, 0.01f)
    assertEquals(50.5f, stats.avg, 0.01f)
    assertEquals(50f, stats.p50, 1.1f)
    assertEquals(95f, stats.p95, 1.1f)
    assertEquals(99f, stats.p99, 1.1f)

    val oddCustom =
      ManualDegradationConfig(
        resolutionPreset = ResolutionPreset.CUSTOM,
        customWidth = 427,
        customHeight = 241,
      )
    val (w, h) = oddCustom.resolveTargetDimensions(1280, 720)
    assertEquals(426, w)
    assertEquals(240, h)
  }
}
