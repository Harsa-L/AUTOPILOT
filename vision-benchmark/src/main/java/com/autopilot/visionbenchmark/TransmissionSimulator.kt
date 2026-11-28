package com.autopilot.visionbenchmark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

enum class TransmissionOutcome {
  DELIVERED,
  DROPPED,
  PACED_SKIPPED,
  FROZEN_HOLD,
  DUPLICATED,
  BLACKOUT,
}

data class TransmissionFrameDecision(
  val outcome: TransmissionOutcome,
  val outputBitmap: Bitmap?,
  val simulatedJitterDelayMs: Long,
  val duplicatedExtraBitmap: Bitmap? = null,
)

class TransmissionLinkStateMachine {
  private var lastAcceptedSourceTimeMs: Long = Long.MIN_VALUE
  private var freezeUntilMs: Long = 0L
  private var blackoutUntilMs: Long = 0L
  var hasCachedFrame: Boolean = false

  fun reset() {
    lastAcceptedSourceTimeMs = Long.MIN_VALUE
    freezeUntilMs = 0L
    blackoutUntilMs = 0L
    hasCachedFrame = false
  }

  fun nextStep(
    nowMs: Long,
    config: ManualDegradationConfig,
    seed: Long,
  ): Pair<TransmissionOutcome, Long> {
    val targetFps = config.targetFps.coerceIn(1, 60)
    val minIntervalMs = (1000.0 / targetFps).toLong().coerceAtLeast(16L)
    if (lastAcceptedSourceTimeMs != Long.MIN_VALUE && (nowMs - lastAcceptedSourceTimeMs) < minIntervalMs) {
      return TransmissionOutcome.PACED_SKIPPED to 0L
    }
    lastAcceptedSourceTimeMs = nowMs

    val tx = config.transmission
    val rng = Random(seed)
    val jitterDelay = if (tx.jitterMs > 0) rng.nextLong(0L, tx.jitterMs.toLong() + 1L) else 0L

    if (nowMs < blackoutUntilMs) {
      return TransmissionOutcome.BLACKOUT to jitterDelay
    }
    if (nowMs < freezeUntilMs && hasCachedFrame) {
      return TransmissionOutcome.FROZEN_HOLD to jitterDelay
    }
    if (tx.blackoutBurstPct > 0f && rng.nextFloat() * 100f < tx.blackoutBurstPct) {
      blackoutUntilMs = nowMs + tx.blackoutDurationMs.coerceIn(80, 3000)
      return TransmissionOutcome.BLACKOUT to jitterDelay
    }
    if (tx.freezeProbabilityPct > 0f && hasCachedFrame && rng.nextFloat() * 100f < tx.freezeProbabilityPct) {
      freezeUntilMs = nowMs + tx.freezeDurationMs.coerceIn(80, 3000)
      return TransmissionOutcome.FROZEN_HOLD to jitterDelay
    }
    if (tx.packetLossPct > 0f && rng.nextFloat() * 100f < tx.packetLossPct) {
      return TransmissionOutcome.DROPPED to jitterDelay
    }
    hasCachedFrame = true
    val shouldDuplicate = tx.duplicateFramePct > 0f && rng.nextFloat() * 100f < tx.duplicateFramePct
    return (if (shouldDuplicate) TransmissionOutcome.DUPLICATED else TransmissionOutcome.DELIVERED) to jitterDelay
  }
}

/**
 * Simulates unstable drone RF transmission characteristics: FPS pacing, packet loss,
 * frame freeze holds, transmission blackouts, frame duplication, jitter variance,
 * and quality oscillation.
 */
class TransmissionSimulator {

  private val stateMachine = TransmissionLinkStateMachine()
  private var lastDeliveredBitmap: Bitmap? = null
  private var blackoutBitmap: Bitmap? = null

  fun reset() {
    stateMachine.reset()
    lastDeliveredBitmap?.recycle()
    lastDeliveredBitmap = null
    blackoutBitmap?.recycle()
    blackoutBitmap = null
  }

  fun oscillationCompressionBoost(enabled: Boolean, elapsedMs: Long): Float {
    if (!enabled) return 0f
    val wave = 0.5f + 0.5f * sin((elapsedMs / 1200.0) * 2.0 * PI).toFloat()
    return wave * 0.45f
  }

  fun evaluate(
    sourceBitmap: Bitmap,
    nowMs: Long,
    config: ManualDegradationConfig,
    seed: Long,
  ): TransmissionFrameDecision {
    val (outcome, jitterDelay) = stateMachine.nextStep(nowMs, config, seed)
    return when (outcome) {
      TransmissionOutcome.PACED_SKIPPED ->
        TransmissionFrameDecision(TransmissionOutcome.PACED_SKIPPED, null, 0L)
      TransmissionOutcome.DROPPED ->
        TransmissionFrameDecision(TransmissionOutcome.DROPPED, null, jitterDelay)
      TransmissionOutcome.BLACKOUT ->
        TransmissionFrameDecision(
          TransmissionOutcome.BLACKOUT,
          ensureBlackoutBitmap(sourceBitmap.width, sourceBitmap.height),
          jitterDelay,
        )
      TransmissionOutcome.FROZEN_HOLD ->
        TransmissionFrameDecision(
          TransmissionOutcome.FROZEN_HOLD,
          lastDeliveredBitmap ?: sourceBitmap,
          jitterDelay,
        )
      TransmissionOutcome.DUPLICATED -> {
        val copy = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
        lastDeliveredBitmap?.recycle()
        lastDeliveredBitmap = copy
        TransmissionFrameDecision(
          TransmissionOutcome.DUPLICATED,
          copy ?: sourceBitmap,
          jitterDelay,
          duplicatedExtraBitmap = copy,
        )
      }
      TransmissionOutcome.DELIVERED -> {
        val copy = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
        lastDeliveredBitmap?.recycle()
        lastDeliveredBitmap = copy
        TransmissionFrameDecision(
          TransmissionOutcome.DELIVERED,
          copy ?: sourceBitmap,
          jitterDelay,
        )
      }
    }
  }

  private fun ensureBlackoutBitmap(w: Int, h: Int): Bitmap {
    val existing = blackoutBitmap
    if (existing != null && !existing.isRecycled && existing.width == w && existing.height == h) {
      return existing
    }
    existing?.recycle()
    val created = Bitmap.createBitmap(w.coerceAtLeast(16), h.coerceAtLeast(16), Bitmap.Config.ARGB_8888)
    Canvas(created).drawColor(Color.rgb(10, 12, 16))
    blackoutBitmap = created
    return created
  }
}
