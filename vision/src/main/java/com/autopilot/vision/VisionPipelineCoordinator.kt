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
import kotlinx.coroutines.withContext

/**
 * Isolated frame-input adapter boundary for feeding frames into AUTOPILOT's
 * production CV pipeline (LiteRT + C++20 NativeVisionEngine) without touching
 * flight control or sharing mutable session state.
 */
interface VisionFrameInputAdapter : AutoCloseable {
  val latestResult: StateFlow<VisionFrameResult?>
  val availability: StateFlow<DetectorAvailability>
  val calibration: StateFlow<CameraCalibrationConfig>

  fun initializeAsync()
  fun setEnabled(active: Boolean)
  fun updateCalibration(focalLengthPx: Float, isCalibrated: Boolean)
  fun updateConfidenceThreshold(threshold: Float)
  fun selectModel(spec: VisionModelSpec?, preferGpu: Boolean = true)
  fun submitFrame(bitmap: Bitmap, isSynthetic: Boolean)
  suspend fun processFrameSynchronously(
    bitmap: Bitmap,
    isSynthetic: Boolean,
    frameTimestampUs: Long = 0L,
  ): VisionFrameResult
}

/**
 * Coordinates the bounded-queue vision pipeline on a dedicated single-thread dispatcher.
 * Combines framework-independent ObjectDetector (LiteRT YOLO26n/26s/11n) with the C++20
 * NativeVisionEngine (Lucas-Kanade optical flow, multi-object tracking, monocular depth).
 *
 * Each instance owns an isolated native handle, detector, dispatcher, and state flows so
 * production flight CV and Vision Benchmark Studio sessions never share mutable state.
 */
class VisionPipelineCoordinator(
  context: Context,
  private val dispatchers: AutopilotDispatchers,
  preferGpu: Boolean = true,
  initialModelSpec: VisionModelSpec? = null,
  private val sessionName: String = "production",
) : VisionFrameInputAdapter {

  private val detector = LiteRtYolo26Detector(context, preferGpu, initialModelSpec)
  private val visionDispatcher: CloseableDispatcher =
    dispatchers.newSingleThreadVisionDispatcher("autopilot-litert-$sessionName")
  private val visionScope = CoroutineScope(visionDispatcher.dispatcher)

  private val _latestResult = MutableStateFlow<VisionFrameResult?>(null)
  override val latestResult: StateFlow<VisionFrameResult?> = _latestResult.asStateFlow()

  private val _availability = MutableStateFlow(detector.availability)
  override val availability: StateFlow<DetectorAvailability> = _availability.asStateFlow()

  private val _calibration = MutableStateFlow(CameraCalibrationConfig())
  override val calibration: StateFlow<CameraCalibrationConfig> = _calibration.asStateFlow()

  val activeModelSpec: VisionModelSpec?
    get() = detector.activeModelSpec

  private val enabled = AtomicBoolean(false)
  private val frameQueue = Channel<Triple<Bitmap, Boolean, Long>>(Channel.CONFLATED)
  private var workerJob: Job? = null
  private var nativeVisionHandle: Long = 0L
  @Volatile private var confidenceThreshold: Float = 0.40f

  // Pre-allocated direct RGB24 buffer for 160x120 optical flow grid (no per-frame heap churn).
  private val flowWidth = 160
  private val flowHeight = 120
  private val rgbDirectBuffer = ByteBuffer.allocateDirect(flowWidth * flowHeight * 3)
  private val flowPixels = IntArray(flowWidth * flowHeight)

  override fun initializeAsync() {
    visionScope.launch {
      ensureInitializedOnWorker()
    }
  }

  override fun selectModel(spec: VisionModelSpec?, preferGpu: Boolean) {
    visionScope.launch {
      detector.setPreferGpu(preferGpu)
      detector.setModelSpec(spec)
      if (nativeVisionHandle != 0L && AutopilotNativeBridge.isNativeLoaded) {
        AutopilotNativeBridge.nativeResetVisionEngine(nativeVisionHandle)
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

  override fun updateCalibration(focalLengthPx: Float, isCalibrated: Boolean) {
    _calibration.value = CameraCalibrationConfig(focalLengthPx, isCalibrated)
  }

  override fun updateConfidenceThreshold(threshold: Float) {
    confidenceThreshold = threshold.coerceIn(0.15f, 0.90f)
  }

  override fun setEnabled(active: Boolean) {
    enabled.set(active)
    if (active && workerJob == null) {
      startWorker()
    } else if (!active) {
      _latestResult.value = null
    }
  }

  override fun submitFrame(bitmap: Bitmap, isSynthetic: Boolean) {
    submitFrameWithTimestamp(bitmap, isSynthetic, frameTimestampUs = 0L)
  }

  fun submitFrameWithTimestamp(bitmap: Bitmap, isSynthetic: Boolean, frameTimestampUs: Long) {
    if (!enabled.get()) return
    // Copy bitmap safely so upstream buffer reuse does not race with background inference.
    val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
    val result = frameQueue.trySend(Triple(copy, isSynthetic, frameTimestampUs))
    if (result.isFailure) {
      copy.recycle()
    }
  }

  override suspend fun processFrameSynchronously(
    bitmap: Bitmap,
    isSynthetic: Boolean,
    frameTimestampUs: Long,
  ): VisionFrameResult =
    withContext(visionDispatcher.dispatcher) {
      ensureInitializedOnWorker()
      executeSingleFrame(bitmap, isSynthetic, frameTimestampUs, recycleFrame = false)
    }

  fun resetTrackerStateAsync() {
    visionScope.launch {
      if (nativeVisionHandle != 0L && AutopilotNativeBridge.isNativeLoaded) {
        AutopilotNativeBridge.nativeResetVisionEngine(nativeVisionHandle)
      }
      _latestResult.value = null
    }
  }

  private fun ensureInitializedOnWorker() {
    if (nativeVisionHandle == 0L && AutopilotNativeBridge.isNativeLoaded) {
      nativeVisionHandle = AutopilotNativeBridge.nativeCreateVisionEngine()
    }
    if (detector.availability !is DetectorAvailability.Ready) {
      _availability.value = detector.initialize()
    }
  }

  private fun startWorker() {
    workerJob =
      visionScope.launch {
        ensureInitializedOnWorker()
        for ((frame, isSynthetic, timestampUs) in frameQueue) {
          if (!enabled.get()) {
            frame.recycle()
            continue
          }
          _latestResult.value =
            executeSingleFrame(frame, isSynthetic, timestampUs, recycleFrame = true)
        }
      }
  }

  private fun executeSingleFrame(
    frame: Bitmap,
    isSynthetic: Boolean,
    frameTimestampUs: Long,
    recycleFrame: Boolean,
  ): VisionFrameResult {
    val startNs = System.nanoTime()
    val (detections, detTimings) =
      if (detector.availability is DetectorAvailability.Ready) {
        detector.detectWithTimings(frame, confidenceThreshold)
      } else {
        emptyList<VisionDetection>() to VisionStageTimingsMs()
      }

    val trackStartNs = System.nanoTime()
    packRgb24ForFlow(frame)
    if (recycleFrame) {
      frame.recycle()
    }

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

    val endNs = System.nanoTime()
    val elapsedMs = (endNs - startNs) / 1_000_000f
    val trackMs = (endNs - trackStartNs) / 1_000_000f
    val fullTimings = detTimings.copy(trackingFlowMs = trackMs, totalMs = elapsedMs)
    return unpackVisionResult(
      packed = nativeOut,
      inferenceMs = elapsedMs,
      isSynthetic = isSynthetic,
      isCalibrated = cal.isCalibrated,
      stageTimings = fullTimings,
      modelId = detector.activeModelSpec?.id ?: "yolo26n",
      frameTimestampUs = frameTimestampUs,
    )
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
    stageTimings: VisionStageTimingsMs,
    modelId: String,
    frameTimestampUs: Long,
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
        stageTimingsMs = stageTimings,
        modelId = modelId,
        frameTimestampUs = frameTimestampUs,
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

    val rotOmega = if (idx < packed.size) packed[idx++] else 0f
    val validFlowCount =
      if (idx < packed.size) packed[idx].toInt().coerceAtLeast(0)
      else flows.count { it.confidence > 0.1f }

    return VisionFrameResult(
      timestampMs = System.currentTimeMillis(),
      detectorAvailability = _availability.value,
      inferenceTimeMs = inferenceMs,
      globalFlowDxNorm = globalDx,
      globalFlowDyNorm = globalDy,
      flowVectors = flows,
      trackedTargets = tracks,
      isSyntheticInput = isSynthetic,
      rotationalYawOmega = rotOmega,
      validFlowCount = validFlowCount,
      stageTimingsMs = stageTimings,
      modelId = modelId,
      frameTimestampUs = frameTimestampUs,
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
