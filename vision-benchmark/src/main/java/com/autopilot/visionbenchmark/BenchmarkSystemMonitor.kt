package com.autopilot.visionbenchmark

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import kotlin.math.abs

/**
 * Reads authentic Android process CPU %, memory usage, and thermal status,
 * and computes rolling FPS, jitter, and latency percentiles (P50 / P95 / P99).
 */
class BenchmarkMetricsCollector(private val context: Context) {

  private val sourceFrameTimes = ArrayDeque<Long>(128)
  private val degradedFrameTimes = ArrayDeque<Long>(128)
  private val inferenceFrameTimes = ArrayDeque<Long>(128)
  private val latencyWindowMs = ArrayDeque<Float>(256)
  private val history = ArrayDeque<MetricsSample>(120)

  private var lastWallTimeMs: Long = 0L
  private var lastCpuTimeMs: Long = 0L
  private var lastDegradedArrivalMs: Long = 0L
  private val intervalDeltasMs = ArrayDeque<Float>(64)

  private var sourceFramesCount = 0L
  private var degradedFramesCount = 0L
  private var droppedFramesCount = 0L
  private var duplicatedFramesCount = 0L
  private var frozenFramesCount = 0L
  private var blackoutFramesCount = 0L
  private var inferenceFramesCount = 0L

  private var lastTransformMs = 0f
  private var lastCodecMs = 0f
  private var lastPreprocessMs = 0f
  private var lastInferenceMs = 0f
  private var lastPostprocessMs = 0f
  private var lastTrackFlowMs = 0f
  private var lastDetectionCount = 0
  private var lastTrackCount = 0
  private var lastAvgConfidence = 0f
  private var lastQueueDepth = 0
  private var lastCodecStatus = "Bypassed"
  private var lastStageLabel = "Idle"
  private var lastHistoryAppendMs = 0L
  private var cachedCpuPct = 0f

  @Synchronized
  fun reset() {
    sourceFrameTimes.clear()
    degradedFrameTimes.clear()
    inferenceFrameTimes.clear()
    latencyWindowMs.clear()
    history.clear()
    intervalDeltasMs.clear()
    lastWallTimeMs = SystemClock.elapsedRealtime()
    lastCpuTimeMs = Process.getElapsedCpuTime()
    lastDegradedArrivalMs = 0L
    sourceFramesCount = 0L
    degradedFramesCount = 0L
    droppedFramesCount = 0L
    duplicatedFramesCount = 0L
    frozenFramesCount = 0L
    blackoutFramesCount = 0L
    inferenceFramesCount = 0L
    lastTransformMs = 0f
    lastCodecMs = 0f
    lastPreprocessMs = 0f
    lastInferenceMs = 0f
    lastPostprocessMs = 0f
    lastTrackFlowMs = 0f
    lastDetectionCount = 0
    lastTrackCount = 0
    lastAvgConfidence = 0f
    lastQueueDepth = 0
    lastCodecStatus = "Bypassed"
    lastStageLabel = "Idle"
    lastHistoryAppendMs = 0L
    cachedCpuPct = 0f
  }

  @Synchronized
  fun recordSourceFrame(nowMs: Long = SystemClock.elapsedRealtime()) {
    sourceFramesCount++
    pushTimestamp(sourceFrameTimes, nowMs)
  }

  @Synchronized
  fun recordTransmissionOutcome(
    outcome: TransmissionOutcome,
    transformMs: Float,
    codecMs: Float,
    codecStatus: String,
    stageLabel: String,
    nowMs: Long = SystemClock.elapsedRealtime(),
  ) {
    lastTransformMs = transformMs
    lastCodecMs = codecMs
    lastCodecStatus = codecStatus
    lastStageLabel = stageLabel
    when (outcome) {
      TransmissionOutcome.DELIVERED -> {
        degradedFramesCount++
        pushTimestamp(degradedFrameTimes, nowMs)
        recordJitterInterval(nowMs)
      }
      TransmissionOutcome.DUPLICATED -> {
        degradedFramesCount++
        duplicatedFramesCount++
        pushTimestamp(degradedFrameTimes, nowMs)
        recordJitterInterval(nowMs)
      }
      TransmissionOutcome.FROZEN_HOLD -> {
        degradedFramesCount++
        frozenFramesCount++
        pushTimestamp(degradedFrameTimes, nowMs)
        recordJitterInterval(nowMs)
      }
      TransmissionOutcome.BLACKOUT -> {
        degradedFramesCount++
        blackoutFramesCount++
        pushTimestamp(degradedFrameTimes, nowMs)
        recordJitterInterval(nowMs)
      }
      TransmissionOutcome.DROPPED -> droppedFramesCount++
      TransmissionOutcome.PACED_SKIPPED -> Unit
    }
  }

  @Synchronized
  fun recordInferenceResult(
    preprocessMs: Float,
    inferenceMs: Float,
    postprocessMs: Float,
    trackFlowMs: Float,
    totalStageMs: Float,
    detectionCount: Int,
    trackCount: Int,
    avgConfidence: Float,
    queueDepth: Int,
    nowMs: Long = SystemClock.elapsedRealtime(),
  ) {
    inferenceFramesCount++
    lastPreprocessMs = preprocessMs
    lastInferenceMs = inferenceMs
    lastPostprocessMs = postprocessMs
    lastTrackFlowMs = trackFlowMs
    lastDetectionCount = detectionCount
    lastTrackCount = trackCount
    lastAvgConfidence = avgConfidence
    lastQueueDepth = queueDepth

    pushTimestamp(inferenceFrameTimes, nowMs)
    if (latencyWindowMs.size >= 240) latencyWindowMs.removeFirst()
    latencyWindowMs.addLast(totalStageMs)

    if (nowMs - lastHistoryAppendMs >= 250L) {
      lastHistoryAppendMs = nowMs
      val sFps = computeFps(sourceFrameTimes, nowMs)
      val dFps = computeFps(degradedFrameTimes, nowMs)
      val iFps = computeFps(inferenceFrameTimes, nowMs)
      val e2e = lastTransformMs + lastCodecMs + totalStageMs
      if (history.size >= 120) history.removeFirst()
      history.addLast(
        MetricsSample(
          timestampMs = System.currentTimeMillis(),
          sourceFps = sFps,
          degradedFps = dFps,
          inferenceFps = iFps,
          inferenceLatencyMs = inferenceMs,
          e2eLatencyMs = e2e,
        )
      )
    }
  }

  @Synchronized
  fun snapshot(nowMs: Long = SystemClock.elapsedRealtime()): BenchmarkMetricsSnapshot {
    sampleCpuPercent(nowMs)
    val stats = PercentileStats.fromValues(latencyWindowMs)
    val jitterMs = if (intervalDeltasMs.isEmpty()) 0f else intervalDeltasMs.average().toFloat()

    return BenchmarkMetricsSnapshot(
      sourceFps = computeFps(sourceFrameTimes, nowMs),
      degradedFps = computeFps(degradedFrameTimes, nowMs),
      inferenceFps = computeFps(inferenceFrameTimes, nowMs),
      transformAndDegradeMs = lastTransformMs,
      codecLoopbackMs = lastCodecMs,
      preprocessMs = lastPreprocessMs,
      inferenceMs = lastInferenceMs,
      postprocessMs = lastPostprocessMs,
      trackingFlowMs = lastTrackFlowMs,
      avgLatencyMs = stats.avg,
      minLatencyMs = stats.min,
      maxLatencyMs = stats.max,
      p50LatencyMs = stats.p50,
      p95LatencyMs = stats.p95,
      p99LatencyMs = stats.p99,
      frameJitterMs = jitterMs,
      queueDepth = lastQueueDepth,
      sourceFramesCount = sourceFramesCount,
      degradedFramesCount = degradedFramesCount,
      droppedFramesCount = droppedFramesCount,
      duplicatedFramesCount = duplicatedFramesCount,
      frozenFramesCount = frozenFramesCount,
      blackoutFramesCount = blackoutFramesCount,
      inferenceFramesCount = inferenceFramesCount,
      detectionCount = lastDetectionCount,
      trackCount = lastTrackCount,
      avgConfidence = lastAvgConfidence,
      cpuUsagePercent = cachedCpuPct,
      memoryUsageMb = sampleMemoryMb(),
      thermalStatusLabel = readThermalLabel(),
      codecStatusLabel = lastCodecStatus,
      activeStageLabel = lastStageLabel,
      historySamples = history.toList(),
    )
  }

  private fun recordJitterInterval(nowMs: Long) {
    if (lastDegradedArrivalMs > 0L && nowMs > lastDegradedArrivalMs) {
      val dt = (nowMs - lastDegradedArrivalMs).toFloat()
      if (intervalDeltasMs.size >= 40) intervalDeltasMs.removeFirst()
      // Compute deviation from mean recent frame spacing
      val mean = if (intervalDeltasMs.isEmpty()) dt else intervalDeltasMs.average().toFloat()
      intervalDeltasMs.addLast(abs(dt - mean))
    }
    lastDegradedArrivalMs = nowMs
  }

  private fun pushTimestamp(q: ArrayDeque<Long>, nowMs: Long) {
    q.addLast(nowMs)
    while (q.isNotEmpty() && nowMs - q.first() > 2000L) {
      q.removeFirst()
    }
  }

  private fun computeFps(q: ArrayDeque<Long>, nowMs: Long): Float {
    while (q.isNotEmpty() && nowMs - q.first() > 2000L) {
      q.removeFirst()
    }
    if (q.size < 2) return 0f
    val spanMs = (q.last() - q.first()).coerceAtLeast(1L)
    return ((q.size - 1) * 1000f) / spanMs.toFloat()
  }

  private fun sampleCpuPercent(nowMs: Long) {
    val wallDelta = nowMs - lastWallTimeMs
    if (wallDelta < 400L) return
    val cpuNow = Process.getElapsedCpuTime()
    val cpuDelta = (cpuNow - lastCpuTimeMs).coerceAtLeast(0L)
    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    cachedCpuPct = ((cpuDelta.toFloat() / (wallDelta * cores)) * 100f).coerceIn(0f, 100f)
    lastWallTimeMs = nowMs
    lastCpuTimeMs = cpuNow
  }

  private fun sampleMemoryMb(): Float {
    val rt = Runtime.getRuntime()
    val jvmUsed = rt.totalMemory() - rt.freeMemory()
    val nativeUsed = Debug.getNativeHeapAllocatedSize()
    return (jvmUsed + nativeUsed) / (1024f * 1024f)
  }

  private fun readThermalLabel(): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "API <29 N/A"
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return "Unknown"
    return when (pm.currentThermalStatus) {
      PowerManager.THERMAL_STATUS_NONE -> "Nominal"
      PowerManager.THERMAL_STATUS_LIGHT -> "Light"
      PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
      PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
      PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
      PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
      PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
      else -> "Unknown"
    }
  }
}

data class PercentileStats(
  val avg: Float,
  val min: Float,
  val max: Float,
  val p50: Float,
  val p95: Float,
  val p99: Float,
) {
  companion object {
    fun fromValues(values: Collection<Float>): PercentileStats {
      if (values.isEmpty()) return PercentileStats(0f, 0f, 0f, 0f, 0f, 0f)
      val sorted = values.sorted()
      val n = sorted.size
      fun pct(p: Float): Float {
        val idx = ((p / 100f) * (n - 1)).toInt().coerceIn(0, n - 1)
        return sorted[idx]
      }
      return PercentileStats(
        avg = sorted.average().toFloat(),
        min = sorted.first(),
        max = sorted.last(),
        p50 = pct(50f),
        p95 = pct(95f),
        p99 = pct(99f),
      )
    }
  }
}
