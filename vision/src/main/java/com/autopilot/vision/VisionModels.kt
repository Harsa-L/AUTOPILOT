package com.autopilot.vision

import android.graphics.Bitmap
import android.graphics.RectF

sealed interface DetectorAvailability {
  data class Ready(val acceleratorLabel: String, val modelLabel: String) : DetectorAvailability

  data class ModelNotProvisioned(val guidance: String) : DetectorAvailability

  data class Unavailable(val reason: String) : DetectorAvailability
}

data class VisionDetection(
  val classId: Int,
  val label: String,
  val confidence: Float,
  val boxNormalized: RectF,
)

data class OpticalFlowVector(
  val normX: Float,
  val normY: Float,
  val dxNorm: Float,
  val dyNorm: Float,
  val confidence: Float,
)

data class TrackedTarget(
  val trackId: Int,
  val classId: Int,
  val label: String,
  val confidence: Float,
  val boxNormalized: RectF,
  val velocityXNorm: Float,
  val velocityYNorm: Float,
  val estimatedDistanceMeters: Float?,
  val uncertaintyMeters: Float?,
  val isDistanceCalibrated: Boolean,
)

data class CameraCalibrationConfig(
  val focalLengthPx: Float = 520f,
  val isCalibrated: Boolean = false,
)

enum class VisionModelSource {
  BUNDLED_ASSET,
  IMPORTED_FILE,
}

enum class VisionTensorType {
  FLOAT32,
  INT8,
  UINT8,
}

enum class YoloPostprocessMode {
  AUTO,
  YOLO26_NMS_FREE,
  YOLO_ANCHOR_FREE_NMS,
}

data class VisionModelSpec(
  val id: String,
  val displayName: String,
  val source: VisionModelSource,
  val location: String,
  val fileSizeBytes: Long,
  val inputWidth: Int = 640,
  val inputHeight: Int = 640,
  val tensorType: VisionTensorType = VisionTensorType.FLOAT32,
  val postprocessMode: YoloPostprocessMode = YoloPostprocessMode.AUTO,
  val numClasses: Int = 80,
)

data class VisionStageTimingsMs(
  val preprocessMs: Float = 0f,
  val inferenceMs: Float = 0f,
  val postprocessMs: Float = 0f,
  val trackingFlowMs: Float = 0f,
  val totalMs: Float = 0f,
)

data class VisionFrameResult(
  val timestampMs: Long,
  val detectorAvailability: DetectorAvailability,
  val inferenceTimeMs: Float,
  val globalFlowDxNorm: Float,
  val globalFlowDyNorm: Float,
  val flowVectors: List<OpticalFlowVector>,
  val trackedTargets: List<TrackedTarget>,
  val isSyntheticInput: Boolean,
  val rotationalYawOmega: Float = 0f,
  val validFlowCount: Int = 0,
  val stageTimingsMs: VisionStageTimingsMs = VisionStageTimingsMs(),
  val modelId: String = "yolo26n",
  val frameTimestampUs: Long = 0L,
)

/**
 * Framework-independent inference interface.
 * Concrete engines (LiteRT YOLO26n/26s/11n, etc.) implement this contract
 * without leaking runtime types into the vision domain or UI.
 */
interface ObjectDetector : AutoCloseable {
  val availability: DetectorAvailability

  fun initialize(): DetectorAvailability

  fun detect(bitmap: Bitmap, confidenceThreshold: Float = 0.35f): List<VisionDetection>
}
