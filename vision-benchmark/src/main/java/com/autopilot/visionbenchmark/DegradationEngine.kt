package com.autopilot.visionbenchmark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.media.Image
import com.autopilot.nativecore.AutopilotNativeBridge
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Deterministic scheduler computing effective degradation parameters for
 * Progressive, Randomized Bounded, Time-Based Schedule, and Multi-Defect Stress runs.
 */
object AutoDegradationScheduler {

  data class ScheduledStep(
    val effectiveConfig: ManualDegradationConfig,
    val stageLabel: String,
    val frameSeed: Long,
  )

  fun evaluate(
    base: ManualDegradationConfig,
    auto: AutoDegradationConfig,
    elapsedMs: Long,
    frameIndex: Long,
  ): ScheduledStep {
    val cycleMs = (auto.cycleDurationSec.coerceIn(5, 300) * 1000L)
    val intervalMs = auto.stepIntervalMs.coerceIn(100, 5000).toLong()
    val stepIndex = (elapsedMs / intervalMs).coerceAtLeast(0L)
    val cyclePos = (elapsedMs % cycleMs).toFloat() / cycleMs.toFloat()
    val frameSeed = auto.seed xor (frameIndex * 0x9E3779B9L)
    val stepSeed = auto.seed xor (stepIndex * 0x517CC1B727220A95L)

    return when (auto.mode) {
      AutoScenarioMode.MANUAL ->
        ScheduledStep(base, "Manual", frameSeed)

      AutoScenarioMode.PROGRESSIVE_DEGRADATION -> {
        val t = cyclePos.coerceIn(0f, 1f) * auto.stressIntensity.coerceIn(0.2f, 1.0f)
        val eff =
          base.copy(
            gaussianBlur = (base.gaussianBlur + t * 3.0f).coerceIn(0f, 5f),
            motionBlurPx = (base.motionBlurPx + t * 14f).coerceIn(0f, 24f),
            noiseStrength = (base.noiseStrength + t * 0.25f).coerceIn(0f, 0.5f),
            compressionBlockStrength = (base.compressionBlockStrength + t * 0.85f).coerceIn(0f, 1f),
            pixelationBlock = (base.pixelationBlock + (t * 4f).toInt()).coerceIn(1, 16),
            exposureEv = (base.exposureEv - t * 1.4f).coerceIn(-3f, 3f),
            transmission =
              base.transmission.copy(
                packetLossPct = (base.transmission.packetLossPct + t * 18f).coerceIn(0f, 60f),
                jitterMs = (base.transmission.jitterMs + (t * 110f).toInt()).coerceIn(0, 400),
              ),
          )
        ScheduledStep(eff, "Progressive ${(t * 100).toInt()}%", frameSeed)
      }

      AutoScenarioMode.RANDOMIZED_BOUNDED -> {
        val rng = Random(stepSeed)
        val s = auto.stressIntensity.coerceIn(0.2f, 1.0f)
        val eff =
          base.copy(
            gaussianBlur = (rng.nextFloat() * 2.5f * s).coerceIn(0f, 5f),
            motionBlurPx = (rng.nextFloat() * 12f * s).coerceIn(0f, 24f),
            motionBlurAngleDeg = rng.nextFloat() * 180f,
            noiseStrength = (rng.nextFloat() * 0.22f * s).coerceIn(0f, 0.5f),
            compressionBlockStrength = (rng.nextFloat() * 0.80f * s).coerceIn(0f, 1f),
            exposureEv = ((rng.nextFloat() - 0.5f) * 2.6f * s).coerceIn(-3f, 3f),
            contrast = (1.0f + (rng.nextFloat() - 0.5f) * 0.45f * s).coerceIn(0.4f, 1.8f),
            transmission =
              base.transmission.copy(
                packetLossPct = (rng.nextFloat() * 14f * s).coerceIn(0f, 50f),
                jitterMs = (rng.nextFloat() * 90f * s).toInt(),
              ),
          )
        ScheduledStep(eff, "Randomized #${stepIndex + 1}", frameSeed)
      }

      AutoScenarioMode.TIME_BASED_SCHEDULE -> {
        val phase = ((cyclePos * 4f).toInt()).coerceIn(0, 3)
        when (phase) {
          0 ->
            ScheduledStep(
              base.copy(gaussianBlur = 0.3f, noiseStrength = 0.02f),
              "Stage 1/4: Baseline",
              frameSeed,
            )
          1 ->
            ScheduledStep(
              base.copy(
                bitrateKbps = 260,
                compressionBlockStrength = 0.72f,
                chromaSubsample = 4,
              ),
              "Stage 2/4: Bitrate Collapse",
              frameSeed,
            )
          2 ->
            ScheduledStep(
              base.copy(
                motionBlurPx = 12f,
                motionBlurAngleDeg = 15f,
                exposureEv = -1.5f,
                noiseStrength = 0.16f,
              ),
              "Stage 3/4: Low-Light Motion",
              frameSeed,
            )
          else ->
            ScheduledStep(
              base.copy(
                pixelationBlock = 3,
                compressionBlockStrength = 0.55f,
                transmission =
                  base.transmission.copy(
                    packetLossPct = 15f,
                    jitterMs = 100,
                    freezeProbabilityPct = 4f,
                    blackoutBurstPct = 2f,
                  ),
              ),
              "Stage 4/4: RF Instability",
              frameSeed,
            )
        }
      }

      AutoScenarioMode.MULTI_DEFECT_STRESS -> {
        val s = auto.stressIntensity.coerceIn(0.3f, 1.0f)
        val wave = 0.5f + 0.5f * sin(cyclePos * 2.0 * PI).toFloat()
        val waveCos = 0.5f + 0.5f * cos(cyclePos * 2.0 * PI).toFloat()
        val eff =
          base.copy(
            gaussianBlur = (1.1f + wave * 1.8f) * s,
            motionBlurPx = (6f + waveCos * 10f) * s,
            motionBlurAngleDeg = (cyclePos * 360f) % 180f,
            noiseStrength = (0.08f + wave * 0.16f) * s,
            compressionBlockStrength = (0.45f + waveCos * 0.45f) * s,
            pixelationBlock = if (wave > 0.65f) 3 else 2,
            exposureEv = (-1.3f * wave) * s,
            chromaSubsample = 4,
            transmission =
              base.transmission.copy(
                packetLossPct = 10f * s,
                jitterMs = (80f * s).toInt(),
                freezeProbabilityPct = 3f * s,
                duplicateFramePct = 4f * s,
                qualityOscillationEnabled = true,
              ),
          )
        ScheduledStep(eff, "Multi-Defect Stress (${(s * 100).toInt()}%)", frameSeed)
      }
    }
  }
}

data class DegradedFrameOutput(
  val cleanBitmap: Bitmap?,
  val degradedBitmap: Bitmap,
  val width: Int,
  val height: Int,
  val transformAndDegradeMs: Float,
)

/**
 * Coordinates real-time YUV420 / RGBA pixel degradation through the C++20 NativeVideoDegrader
 * with reusable direct ByteBuffers and a clean JVM fallback if native lib isn't loaded in unit tests.
 */
class DegradationEngine : AutoCloseable {

  private var nativeHandle: Long = 0L
  private var cleanBuffer: ByteBuffer? = null
  private var degradedBuffer: ByteBuffer? = null
  private var srcRgbaBuffer: ByteBuffer? = null
  private var cleanBitmap: Bitmap? = null
  private var degradedBitmap: Bitmap? = null
  private val intParams = IntArray(7)
  private val floatParams = FloatArray(12)

  fun ensureInitialized() {
    if (nativeHandle == 0L && AutopilotNativeBridge.isNativeLoaded) {
      nativeHandle = AutopilotNativeBridge.nativeCreateDegraderEngine()
    }
  }

  @Synchronized
  fun processCameraImage(
    image: Image,
    rotationDeg: Int,
    mirrorH: Boolean,
    config: ManualDegradationConfig,
    frameSeed: Long,
    needCleanOutput: Boolean,
  ): DegradedFrameOutput? {
    if (image.planes.size < 3) return null
    ensureInitialized()

    val srcW = image.width
    val srcH = image.height
    val (orientedW, orientedH) =
      if (rotationDeg == 90 || rotationDeg == 270) srcH to srcW else srcW to srcH
    val (targetW, targetH) = config.resolveTargetDimensions(orientedW, orientedH)

    val outClean =
      if (needCleanOutput) {
        ensureCleanResources(orientedW, orientedH)
        cleanBuffer
      } else {
        null
      }
    ensureDegradedResources(targetW, targetH)
    val outDeg = degradedBuffer ?: return null

    packParams(targetW, targetH, config, frameSeed)

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    val startNs = System.nanoTime()
    val nativeNs =
      if (nativeHandle != 0L && AutopilotNativeBridge.isNativeLoaded) {
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        yBuf.rewind()
        uBuf.rewind()
        vBuf.rewind()
        AutopilotNativeBridge.nativeDegradeYuv420(
          handle = nativeHandle,
          yBuf = yBuf,
          yStride = yPlane.rowStride,
          uBuf = uBuf,
          uStride = uPlane.rowStride,
          uPixStride = uPlane.pixelStride,
          vBuf = vBuf,
          vStride = vPlane.rowStride,
          vPixStride = vPlane.pixelStride,
          srcW = srcW,
          srcH = srcH,
          rotationDeg = rotationDeg,
          mirrorH = mirrorH,
          outCleanBuf = outClean,
          cleanW = orientedW,
          cleanH = orientedH,
          outDegradedBuf = outDeg,
          intParams = intParams,
          floatParams = floatParams,
        )
      } else {
        0L
      }

    val elapsedMs =
      if (nativeNs > 0L) nativeNs / 1_000_000f else (System.nanoTime() - startNs) / 1_000_000f

    val cleanOutBmp =
      if (needCleanOutput && outClean != null) {
        outClean.rewind()
        cleanBitmap?.also { it.copyPixelsFromBuffer(outClean) }
      } else {
        null
      }

    outDeg.rewind()
    val degBmp = degradedBitmap ?: return null
    degBmp.copyPixelsFromBuffer(outDeg)

    return DegradedFrameOutput(
      cleanBitmap = cleanOutBmp,
      degradedBitmap = degBmp,
      width = targetW,
      height = targetH,
      transformAndDegradeMs = elapsedMs,
    )
  }

  @Synchronized
  fun processBitmap(
    source: Bitmap,
    config: ManualDegradationConfig,
    frameSeed: Long,
  ): DegradedFrameOutput {
    ensureInitialized()
    val (targetW, targetH) = config.resolveTargetDimensions(source.width, source.height)
    val startNs = System.nanoTime()

    if (nativeHandle != 0L && AutopilotNativeBridge.isNativeLoaded) {
      val srcBytes = source.width * source.height * 4
      if ((srcRgbaBuffer?.capacity() ?: 0) < srcBytes) {
        srcRgbaBuffer = ByteBuffer.allocateDirect(srcBytes)
      }
      val srcBuf = srcRgbaBuffer!!
      srcBuf.clear()
      val argbSrc =
        if (source.config == Bitmap.Config.ARGB_8888) source
        else source.copy(Bitmap.Config.ARGB_8888, false)
      argbSrc.copyPixelsToBuffer(srcBuf)
      if (argbSrc !== source) argbSrc.recycle()
      srcBuf.rewind()

      ensureDegradedResources(targetW, targetH)
      val outDeg = degradedBuffer!!
      packParams(targetW, targetH, config, frameSeed)
      val nativeNs =
        AutopilotNativeBridge.nativeDegradeRgba(
          handle = nativeHandle,
          srcRgbaBuf = srcBuf,
          srcW = source.width,
          srcH = source.height,
          outDegradedBuf = outDeg,
          intParams = intParams,
          floatParams = floatParams,
        )
      outDeg.rewind()
      val degBmp = degradedBitmap!!
      degBmp.copyPixelsFromBuffer(outDeg)
      val elapsedMs =
        if (nativeNs > 0L) nativeNs / 1_000_000f else (System.nanoTime() - startNs) / 1_000_000f
      return DegradedFrameOutput(
        cleanBitmap = source,
        degradedBitmap = degBmp,
        width = targetW,
        height = targetH,
        transformAndDegradeMs = elapsedMs,
      )
    }

    // Host JVM unit-test fallback (Android Canvas / Bitmap scaling + color matrix).
    val degBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(degBmp)
    val paint =
      Paint(Paint.FILTER_BITMAP_FLAG).apply {
        val cm = ColorMatrix().apply { setSaturation(config.saturation.coerceIn(0f, 2f)) }
        colorFilter = ColorMatrixColorFilter(cm)
      }
    canvas.drawBitmap(source, null, Rect(0, 0, targetW, targetH), paint)
    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000f
    return DegradedFrameOutput(
      cleanBitmap = source,
      degradedBitmap = degBmp,
      width = targetW,
      height = targetH,
      transformAndDegradeMs = elapsedMs,
    )
  }

  private fun packParams(w: Int, h: Int, config: ManualDegradationConfig, frameSeed: Long) {
    intParams[0] = w
    intParams[1] = h
    intParams[2] = config.aspectMode.nativeCode
    intParams[3] = config.pixelationBlock.coerceIn(1, 16)
    intParams[4] = config.dynamicRangeBits.coerceIn(2, 8)
    intParams[5] = config.chromaSubsample.coerceIn(1, 4)
    intParams[6] = (frameSeed and 0x7FFFFFFFL).toInt()

    floatParams[0] = config.cropZoom.coerceIn(1.0f, 3.0f)
    floatParams[1] = config.gaussianBlur.coerceIn(0f, 5f)
    floatParams[2] = config.motionBlurPx.coerceIn(0f, 24f)
    floatParams[3] = config.motionBlurAngleDeg
    floatParams[4] = config.noiseStrength.coerceIn(0f, 0.5f)
    floatParams[5] = config.sharpenAmount.coerceIn(0f, 2f)
    floatParams[6] = config.contrast.coerceIn(0.3f, 2.2f)
    floatParams[7] = config.brightness.coerceIn(-0.6f, 0.6f)
    floatParams[8] = config.saturation.coerceIn(0f, 2.0f)
    floatParams[9] = config.colorDistortion.coerceIn(0f, 1.0f)
    floatParams[10] = config.exposureEv.coerceIn(-3.0f, 3.0f)
    floatParams[11] = config.compressionBlockStrength.coerceIn(0f, 1.0f)
  }

  private fun ensureCleanResources(w: Int, h: Int) {
    val needed = w * h * 4
    if ((cleanBuffer?.capacity() ?: 0) < needed) {
      cleanBuffer = ByteBuffer.allocateDirect(needed)
    }
    cleanBuffer?.clear()
    if (cleanBitmap == null || cleanBitmap?.width != w || cleanBitmap?.height != h) {
      cleanBitmap?.recycle()
      cleanBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }
  }

  private fun ensureDegradedResources(w: Int, h: Int) {
    val needed = w * h * 4
    if ((degradedBuffer?.capacity() ?: 0) < needed) {
      degradedBuffer = ByteBuffer.allocateDirect(needed)
    }
    degradedBuffer?.clear()
    if (degradedBitmap == null || degradedBitmap?.width != w || degradedBitmap?.height != h) {
      degradedBitmap?.recycle()
      degradedBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }
  }

  @Synchronized
  override fun close() {
    if (nativeHandle != 0L && AutopilotNativeBridge.isNativeLoaded) {
      AutopilotNativeBridge.nativeDestroyDegraderEngine(nativeHandle)
      nativeHandle = 0L
    }
    cleanBitmap?.recycle()
    cleanBitmap = null
    degradedBitmap?.recycle()
    degradedBitmap = null
    cleanBuffer = null
    degradedBuffer = null
    srcRgbaBuffer = null
  }
}
