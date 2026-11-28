package com.autopilot.visionbenchmark

import kotlinx.serialization.Serializable

@Serializable
sealed interface BenchmarkLifecycleState {
  @Serializable data object Inactive : BenchmarkLifecycleState

  @Serializable data object Initializing : BenchmarkLifecycleState

  @Serializable data object Ready : BenchmarkLifecycleState

  @Serializable data object Running : BenchmarkLifecycleState

  @Serializable data object Paused : BenchmarkLifecycleState

  @Serializable data object Stopping : BenchmarkLifecycleState

  @Serializable data class Completed(val sessionId: String? = null) : BenchmarkLifecycleState

  @Serializable data class Failed(val reason: String) : BenchmarkLifecycleState
}

@Serializable
enum class ResolutionPreset(val label: String, val width: Int, val height: Int) {
  ORIGINAL("Original", 0, 0),
  RES_1080P("1920×1080", 1920, 1080),
  RES_720P("1280×720", 1280, 720),
  RES_480P("854×480", 854, 480),
  RES_VGA("640×480", 640, 480),
  RES_360P("640×360", 640, 360),
  RES_240P("426×240", 426, 240),
  RES_QVGA("320×240", 320, 240),
  CUSTOM("Custom", 640, 480),
}

@Serializable
enum class AspectMode(val nativeCode: Int, val label: String) {
  COVER_CROP(0, "Cover / Crop"),
  LETTERBOX(1, "Letterbox"),
  STRETCH(2, "Stretch"),
}

@Serializable
enum class CodecType(val label: String, val mimeType: String?) {
  NONE("Raw / None", null),
  H264_AVC("H.264 (AVC)", "video/avc"),
  H265_HEVC("H.265 (HEVC)", "video/hevc"),
}

@Serializable
enum class AutoScenarioMode(val label: String) {
  MANUAL("Manual"),
  PROGRESSIVE_DEGRADATION("Progressive Degradation"),
  RANDOMIZED_BOUNDED("Randomized Bounded"),
  TIME_BASED_SCHEDULE("Time-Based Schedule"),
  MULTI_DEFECT_STRESS("Multi-Defect Stress"),
}

@Serializable
data class TransmissionConfig(
  val packetLossPct: Float = 0f,
  val jitterMs: Int = 0,
  val freezeProbabilityPct: Float = 0f,
  val freezeDurationMs: Int = 350,
  val blackoutBurstPct: Float = 0f,
  val blackoutDurationMs: Int = 450,
  val duplicateFramePct: Float = 0f,
  val qualityOscillationEnabled: Boolean = false,
  val keyframeDropEnabled: Boolean = false,
)

@Serializable
data class ManualDegradationConfig(
  val resolutionPreset: ResolutionPreset = ResolutionPreset.RES_VGA,
  val customWidth: Int = 640,
  val customHeight: Int = 480,
  val aspectMode: AspectMode = AspectMode.COVER_CROP,
  val cropZoom: Float = 1.0f,
  val targetFps: Int = 25,
  val codecType: CodecType = CodecType.NONE,
  val codecLoopbackEnabled: Boolean = false,
  val bitrateKbps: Int = 1500,
  val iFrameIntervalSec: Int = 1,
  val compressionBlockStrength: Float = 0f,
  val gaussianBlur: Float = 0f,
  val motionBlurPx: Float = 0f,
  val motionBlurAngleDeg: Float = 0f,
  val noiseStrength: Float = 0f,
  val pixelationBlock: Int = 1,
  val sharpenAmount: Float = 0f,
  val exposureEv: Float = 0f,
  val contrast: Float = 1.0f,
  val brightness: Float = 0f,
  val saturation: Float = 1.0f,
  val colorDistortion: Float = 0f,
  val dynamicRangeBits: Int = 8,
  val chromaSubsample: Int = 1,
  val transmission: TransmissionConfig = TransmissionConfig(),
) {
  fun resolveTargetDimensions(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
    val rawW =
      when (resolutionPreset) {
        ResolutionPreset.ORIGINAL -> sourceWidth.coerceAtLeast(64)
        ResolutionPreset.CUSTOM -> customWidth.coerceIn(128, 3840)
        else -> resolutionPreset.width
      }
    val rawH =
      when (resolutionPreset) {
        ResolutionPreset.ORIGINAL -> sourceHeight.coerceAtLeast(64)
        ResolutionPreset.CUSTOM -> customHeight.coerceIn(96, 2160)
        else -> resolutionPreset.height
      }
    // Keep even dimensions so MediaCodec H.264/H.265 encoders & recorders stay aligned.
    return ((rawW / 2) * 2).coerceAtLeast(64) to ((rawH / 2) * 2).coerceAtLeast(64)
  }
}

@Serializable
data class AutoDegradationConfig(
  val mode: AutoScenarioMode = AutoScenarioMode.MANUAL,
  val seed: Long = 20261128L,
  val cycleDurationSec: Int = 30,
  val stepIntervalMs: Int = 500,
  val stressIntensity: Float = 0.75f,
)

@Serializable
data class DegradationProfile(
  val id: String,
  val name: String,
  val description: String,
  val isBuiltIn: Boolean,
  val notes: String = "",
  val config: ManualDegradationConfig,
  val autoConfig: AutoDegradationConfig = AutoDegradationConfig(),
)

object BuiltInDegradationProfiles {
  val all: List<DegradationProfile> =
    listOf(
      DegradationProfile(
        id = "reference_drone",
        name = "Reference Drone",
        description = "Uncalibrated baseline simulating a 480p Wi-Fi toy-drone camera link.",
        isBuiltIn = true,
        notes =
          "Simulated baseline approximation (~640x480 @ 20 FPS, ~900 kbps H.264, mild blur/noise/2% loss); not measured from a physical drone.",
        config =
          ManualDegradationConfig(
            resolutionPreset = ResolutionPreset.RES_VGA,
            targetFps = 20,
            codecType = CodecType.H264_AVC,
            codecLoopbackEnabled = true,
            bitrateKbps = 900,
            compressionBlockStrength = 0.18f,
            gaussianBlur = 0.7f,
            motionBlurPx = 2.0f,
            noiseStrength = 0.04f,
            contrast = 1.05f,
            saturation = 0.92f,
            chromaSubsample = 2,
            transmission =
              TransmissionConfig(
                packetLossPct = 2f,
                jitterMs = 18,
                freezeProbabilityPct = 0.5f,
                freezeDurationMs = 220,
              ),
          ),
      ),
      DegradationProfile(
        id = "low_quality",
        name = "Low Quality",
        description = "640x360 scaled feed with noticeable blur, noise, and compressed bitrate.",
        isBuiltIn = true,
        config =
          ManualDegradationConfig(
            resolutionPreset = ResolutionPreset.RES_360P,
            targetFps = 18,
            codecType = CodecType.H264_AVC,
            codecLoopbackEnabled = false,
            bitrateKbps = 550,
            compressionBlockStrength = 0.35f,
            gaussianBlur = 1.4f,
            motionBlurPx = 3.0f,
            noiseStrength = 0.08f,
            contrast = 0.94f,
            saturation = 0.85f,
            chromaSubsample = 2,
            transmission = TransmissionConfig(packetLossPct = 4f, jitterMs = 30),
          ),
      ),
      DegradationProfile(
        id = "very_low_quality",
        name = "Very Low Quality",
        description = "426x240 heavily degraded feed with pixelation, strong blur, and 4-bit color.",
        isBuiltIn = true,
        config =
          ManualDegradationConfig(
            resolutionPreset = ResolutionPreset.RES_240P,
            targetFps = 12,
            bitrateKbps = 240,
            compressionBlockStrength = 0.65f,
            gaussianBlur = 2.2f,
            motionBlurPx = 5.0f,
            noiseStrength = 0.16f,
            pixelationBlock = 3,
            dynamicRangeBits = 4,
            chromaSubsample = 4,
            transmission =
              TransmissionConfig(
                packetLossPct = 7f,
                jitterMs = 55,
                freezeProbabilityPct = 2f,
                freezeDurationMs = 380,
              ),
          ),
      ),
      DegradationProfile(
        id = "severe_compression",
        name = "Severe Compression",
        description = "Extreme H.264/HEVC quantization blocks, keyframe drops, and macroblocking.",
        isBuiltIn = true,
        config =
          ManualDegradationConfig(
            resolutionPreset = ResolutionPreset.RES_VGA,
            targetFps = 20,
            codecType = CodecType.H264_AVC,
            codecLoopbackEnabled = true,
            bitrateKbps = 180,
            iFrameIntervalSec = 3,
            compressionBlockStrength = 0.88f,
            chromaSubsample = 4,
            transmission = TransmissionConfig(keyframeDropEnabled = true, jitterMs = 20),
          ),
      ),
      DegradationProfile(
        id = "unstable_transmission",
        name = "Unstable Transmission",
        description = "High RF jitter, packet loss bursts, freeze-frame holds, and short blackouts.",
        isBuiltIn = true,
        config =
          ManualDegradationConfig(
            resolutionPreset = ResolutionPreset.RES_VGA,
            targetFps = 20,
            compressionBlockStrength = 0.30f,
            transmission =
              TransmissionConfig(
                packetLossPct = 14f,
                jitterMs = 95,
                freezeProbabilityPct = 5f,
                freezeDurationMs = 550,
                blackoutBurstPct = 3f,
                blackoutDurationMs = 450,
                duplicateFramePct = 6f,
                qualityOscillationEnabled = true,
              ),
          ),
      ),
      DegradationProfile(
        id = "poor_lighting",
        name = "Poor Lighting",
        description = "Underexposed sensor simulation (-1.8 EV), high grain, and color drift.",
        isBuiltIn = true,
        config =
          ManualDegradationConfig(
            resolutionPreset = ResolutionPreset.RES_VGA,
            targetFps = 20,
            exposureEv = -1.8f,
            brightness = -0.08f,
            contrast = 0.78f,
            saturation = 0.65f,
            colorDistortion = 0.35f,
            noiseStrength = 0.22f,
            dynamicRangeBits = 5,
          ),
      ),
      DegradationProfile(
        id = "motion_blur",
        name = "Motion Blur",
        description = "Directional camera shake and yaw blur during rapid quadcopter maneuvers.",
        isBuiltIn = true,
        config =
          ManualDegradationConfig(
            resolutionPreset = ResolutionPreset.RES_VGA,
            targetFps = 18,
            motionBlurPx = 14f,
            motionBlurAngleDeg = 18f,
            gaussianBlur = 0.6f,
            sharpenAmount = 0f,
            noiseStrength = 0.05f,
          ),
      ),
      DegradationProfile(
        id = "custom",
        name = "Custom",
        description = "Clean 640x480 baseline ready for custom manual or automatic tuning.",
        isBuiltIn = true,
        config = ManualDegradationConfig(resolutionPreset = ResolutionPreset.RES_VGA, targetFps = 25),
      ),
    )

  fun defaultProfile(): DegradationProfile = all.first()
}

@Serializable
data class CameraDeviceInfo(
  val cameraId: String,
  val lensFacingLabel: String,
  val displayLabel: String,
  val sensorOrientationDeg: Int,
  val isFrontFacing: Boolean,
  val supportedResolutions: List<String>,
  val supportedFpsRanges: List<String>,
)

@Serializable
data class CameraCaptureConfig(
  val cameraId: String = "0",
  val captureWidth: Int = 1280,
  val captureHeight: Int = 720,
  val targetMinFps: Int = 15,
  val targetMaxFps: Int = 30,
)

@Serializable
data class MetricsSample(
  val timestampMs: Long,
  val sourceFps: Float,
  val degradedFps: Float,
  val inferenceFps: Float,
  val inferenceLatencyMs: Float,
  val e2eLatencyMs: Float,
)

@Serializable
data class BenchmarkMetricsSnapshot(
  val sourceFps: Float = 0f,
  val degradedFps: Float = 0f,
  val inferenceFps: Float = 0f,
  val transformAndDegradeMs: Float = 0f,
  val codecLoopbackMs: Float = 0f,
  val preprocessMs: Float = 0f,
  val inferenceMs: Float = 0f,
  val postprocessMs: Float = 0f,
  val trackingFlowMs: Float = 0f,
  val avgLatencyMs: Float = 0f,
  val minLatencyMs: Float = 0f,
  val maxLatencyMs: Float = 0f,
  val p50LatencyMs: Float = 0f,
  val p95LatencyMs: Float = 0f,
  val p99LatencyMs: Float = 0f,
  val frameJitterMs: Float = 0f,
  val queueDepth: Int = 0,
  val sourceFramesCount: Long = 0L,
  val degradedFramesCount: Long = 0L,
  val droppedFramesCount: Long = 0L,
  val duplicatedFramesCount: Long = 0L,
  val frozenFramesCount: Long = 0L,
  val blackoutFramesCount: Long = 0L,
  val inferenceFramesCount: Long = 0L,
  val detectionCount: Int = 0,
  val trackCount: Int = 0,
  val avgConfidence: Float = 0f,
  val cpuUsagePercent: Float = 0f,
  val memoryUsageMb: Float = 0f,
  val thermalStatusLabel: String = "Nominal",
  val gpuMetricsNote: String = "GPU % unavailable via standard Android SDK",
  val codecStatusLabel: String = "Bypassed",
  val activeStageLabel: String = "Idle",
  val historySamples: List<MetricsSample> = emptyList(),
)

@Serializable
data class RecordingSelection(
  val recordOriginal: Boolean = false,
  val recordDegraded: Boolean = false,
  val recordAnnotated: Boolean = false,
) {
  val anyEnabled: Boolean
    get() = recordOriginal || recordDegraded || recordAnnotated
}

@Serializable
data class RecordedFileInfo(
  val role: String,
  val filePath: String,
  val fileName: String,
  val durationMs: Long,
  val frameCount: Long,
  val width: Int,
  val height: Int,
  val fileSizeBytes: Long,
  val integrityPassed: Boolean,
  val notes: String = "",
)

@Serializable
data class BenchmarkSessionMetadata(
  val sessionId: String,
  val createdAtIso: String,
  val createdAtMs: Long,
  val deviceModel: String,
  val cameraLabel: String,
  val modelId: String,
  val modelName: String,
  val profileId: String,
  val profileName: String,
  val autoScenarioMode: AutoScenarioMode,
  val autoSeed: Long,
  val config: ManualDegradationConfig,
  val metricsSummary: BenchmarkMetricsSnapshot,
  val recordedFiles: List<RecordedFileInfo>,
)

@Serializable
data class ModelComparisonEntry(
  val modelId: String,
  val modelName: String,
  val framesProcessed: Int,
  val totalDetections: Int,
  val avgDetectionsPerFrame: Float,
  val avgConfidence: Float,
  val avgInferenceMs: Float,
  val p50InferenceMs: Float,
  val p95InferenceMs: Float,
  val p99InferenceMs: Float,
  val achievedFps: Float,
  val skippedFrames: Int,
  val statusLabel: String,
)
