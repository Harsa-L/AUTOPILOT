package com.autopilot.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import com.autopilot.core.common.AutopilotDispatchers
import com.autopilot.core.common.CloseableDispatcher
import com.autopilot.nativecore.AutopilotNativeBridge
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinates the bounded-queue vision pipeline on a dedicated single-thread dispatcher.
 * Combines framework-independent ObjectDetector (YOLO26n on LiteRT) with the C++20
 * NativeVisionEngine (Lucas-Kanade optical flow, multi-object tracking, monocular depth).
 */
class VisionPipelineCoordinator(
  context: Context,
  private val dispatchers: AutopilotDispatchers,
  private val preferGpu: Boolean = true,
) : AutoCloseable {

  private val detector = LiteRtYolo26Detector(context, preferGpu)
  private val visionDispatcher: CloseableDispatcher =
    dispatchers.newSingleThreadVisionDispatcher("autopilot-litert-vision")
  private val visionScope = CoroutineScope(visionDispatcher.dispatcher)

  private val _latestResult = MutableStateFlow<VisionFrameResult?>(null)
  val latestResult: StateFlow<VisionFrameResult?> = _latestResult.asStateFlow()

  private val _availability = MutableStateFlow(detector.availability)
  val availability: StateFlow<DetectorAvailability> = _availability.asStateFlow()

  private val _calibration = MutableStateFlow(CameraCalibrationConfig())
  val calibration: StateFlow<CameraCalibrationConfig> = _calibration.asStateFlow()

  private val enabled = AtomicBoolean(false)
  private val frameQueue = Channel<Pair<Bitmap, Boolean>>(Channel.CONFLATED)
  private var workerJob: Job? = null
  private var nativeVisionHandle: Long = 0L
  @Volatile private var confidenceThreshold: Float = 0.40f

  // Pre-allocated direct RGB24 buffer for 160x120 optical flow grid (no per-frame heap churn).
  private val flowWidth = 160
  private val flowHeight = 120
  private val rgbDirectBuffer = ByteBuffer.allocateDirect(flowWidth * flowHeight * 3)
  private val flowPixels = IntArray(flowWidth * flowHeight)

  fun initializeAsync() {
    visionScope.launch {
      if (nativeVisionHandle == 0L && AutopilotNativeBridge.isNativeLoaded) {
        nativeVisionHandle = AutopilotNativeBridge.nativeCreateVisionEngine()
      }
      _availability.value = detector.initialize()
    }
  }

  fun importYoloModel(uri: Uri, onResult: (Boolean) -> Unit) {
    visionScope.launch {
      val ok = detector.importModelFromUri(uri)
      if (ok) {
        _availability.value = detector.initialize()
      }
      onResult(ok)
    }
  }

  fun updateCalibration(focalLengthPx: Float, isCalibrated: Boolean) {
    _calibration.value = CameraCalibrationConfig(focalLengthPx, isCalibrated)
  }

  fun updateConfidenceThreshold(threshold: Float) {
    confidenceThreshold = threshold.coerceIn(0.15f, 0.90f)
  }

  fun setEnabled(active: Boolean) {
    enabled.set(active)
    if (active && workerJob == null) {
      startWorker()
    } else if (!active) {
      _latestResult.value = null
    }
  }

  fun submitFrame(bitmap: Bitmap, isSynthetic: Boolean) {
    if (!enabled.get()) return
    // Copy bitmap safely so video decoder reuse does not race with background inference.
    val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
    val result = frameQueue.trySend(copy to isSynthetic)
    if (result.isFailure) {
      copy.recycle()
    }
  }

  private fun startWorker() {
    workerJob =
      visionScope.launch {
        if (nativeVisionHandle == 0L && AutopilotNativeBridge.isNativeLoaded) {
          nativeVisionHandle = AutopilotNativeBridge.nativeCreateVisionEngine()
        }
        if (detector.availability !is DetectorAvailability.Ready) {
          _availability.value = detector.initialize()
        }

        for ((frame, isSynthetic) in frameQueue) {
          if (!enabled.get()) {
            frame.recycle()
            continue
          }
          val startNs = System.nanoTime()
          val detections =
            if (detector.availability is DetectorAvailability.Ready) {
              detector.detect(frame, confidenceThreshold)
            } else {
              emptyList()
            }

          packRgb24ForFlow(frame)
          frame.recycle()

          val packedDets = FloatArray(detections.size * 6)
          detections.forEachIndexed { idx, d ->
            val base = idx * 6
            packedDets[base] = d.classId.toFloat()
            packedDets[base + 1] = d.confidence
            packedDets[base + 2] = d.boxNormalized.left
            packedDets[base + 3] = d.boxNormalized.top
            packedDets[base + 4] = d.boxNormalized.right
            packedDets[base + 5] = d.boxNormalized.bottom
          }

          val cal = _calibration.value
          val nativeOut =
            if (nativeVisionHandle != 0L && AutopilotNativeBridge.isNativeLoaded) {
              AutopilotNativeBridge.nativeProcessVisionFrame(
                handle = nativeVisionHandle,
                rgb24DirectBuffer = rgbDirectBuffer,
                width = flowWidth,
                height = flowHeight,
                detectionsPacked = packedDets,
                focalLengthPx = cal.focalLengthPx,
                isCalibrated = cal.isCalibrated,
              )
            } else {
              FloatArray(0)
            }

          val elapsedMs = (System.nanoTime() - startNs) / 1_000_000f
          _latestResult.value = unpackVisionResult(nativeOut, elapsedMs, isSynthetic, cal.isCalibrated)
        }
      }
  }

  private fun packRgb24ForFlow(src: Bitmap) {
    val scaled = Bitmap.createScaledBitmap(src, flowWidth, flowHeight, false)
    scaled.getPixels(flowPixels, 0, flowWidth, 0, 0, flowWidth, flowHeight)
    if (scaled !== src) scaled.recycle()
    rgbDirectBuffer.clear()
    for (p in flowPixels) {
      rgbDirectBuffer.put(((p shr 16) and 0xFF).toByte())
      rgbDirectBuffer.put(((p shr 8) and 0xFF).toByte())
      rgbDirectBuffer.put((p and 0xFF).toByte())
    }
    rgbDirectBuffer.flip()
  }

  private fun unpackVisionResult(
    packed: FloatArray,
    inferenceMs: Float,
    isSynthetic: Boolean,
    isCalibrated: Boolean,
  ): VisionFrameResult {
    if (packed.size < 4) {
      return VisionFrameResult(
        timestampMs = System.currentTimeMillis(),
        detectorAvailability = _availability.value,
        inferenceTimeMs = inferenceMs,
        globalFlowDxNorm = 0f,
        globalFlowDyNorm = 0f,
        flowVectors = emptyList(),
        trackedTargets = emptyList(),
        isSyntheticInput = isSynthetic,
      )
    }

    val globalDx = packed[0]
    val globalDy = packed[1]
    val flowCount = packed[2].toInt().coerceAtLeast(0)
    val trackCount = packed[3].toInt().coerceAtLeast(0)

    var idx = 4
    val flows = ArrayList<OpticalFlowVector>(flowCount)
    for (i in 0 until flowCount) {
      if (idx + 4 >= packed.size) break
      flows.add(
        OpticalFlowVector(
          normX = packed[idx],
          normY = packed[idx + 1],
          dxNorm = packed[idx + 2],
          dyNorm = packed[idx + 3],
          confidence = packed[idx + 4],
        )
      )
      idx += 5
    }

    val tracks = ArrayList<TrackedTarget>(trackCount)
    for (i in 0 until trackCount) {
      if (idx + 10 >= packed.size) break
      val classId = packed[idx + 1].toInt()
      val distM = packed[idx + 9].takeIf { it > 0f }
      val uncM = packed[idx + 10].takeIf { it > 0f }
      tracks.add(
        TrackedTarget(
          trackId = packed[idx].toInt(),
          classId = classId,
          label = LiteRtYolo26Detector.labelFor(classId),
          confidence = packed[idx + 2],
          boxNormalized = RectF(packed[idx + 3], packed[idx + 4], packed[idx + 5], packed[idx + 6]),
          velocityXNorm = packed[idx + 7],
          velocityYNorm = packed[idx + 8],
          estimatedDistanceMeters = distM,
          uncertaintyMeters = uncM,
          isDistanceCalibrated = isCalibrated,
        )
      )
      idx += 11
    }

    return VisionFrameResult(
      timestampMs = System.currentTimeMillis(),
      detectorAvailability = _availability.value,
      inferenceTimeMs = inferenceMs,
      globalFlowDxNorm = globalDx,
      globalFlowDyNorm = globalDy,
      flowVectors = flows,
      trackedTargets = tracks,
      isSyntheticInput = isSynthetic,
    )
  }

  override fun close() {
    enabled.set(false)
    workerJob?.cancel()
    frameQueue.close()
    visionScope.launch {
      detector.close()
      if (nativeVisionHandle != 0L && AutopilotNativeBridge.isNativeLoaded) {
        AutopilotNativeBridge.nativeDestroyVisionEngine(nativeVisionHandle)
        nativeVisionHandle = 0L
      }
      visionDispatcher.close()
    }
  }
}
