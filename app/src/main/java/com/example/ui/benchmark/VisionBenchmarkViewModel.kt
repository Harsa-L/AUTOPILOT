package com.example.ui.benchmark

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.autopilot.core.common.DefaultAutopilotDispatchers
import com.autopilot.vision.VisionModelSpec
import com.autopilot.visionbenchmark.AutoDegradationConfig
import com.autopilot.visionbenchmark.BenchmarkSessionMetadata
import com.autopilot.visionbenchmark.CodecType
import com.autopilot.visionbenchmark.DegradationProfile
import com.autopilot.visionbenchmark.ManualDegradationConfig
import com.autopilot.visionbenchmark.RecordingSelection
import com.autopilot.visionbenchmark.VisionBenchmarkController
import java.io.File

/**
 * Isolated ViewModel for Vision Benchmark Studio.
 * Owns its own VisionBenchmarkController and has zero references to flight control,
 * trajectory, or drone protocol classes.
 */
class VisionBenchmarkViewModel(application: Application) : AndroidViewModel(application) {

  private val controller =
    VisionBenchmarkController(application.applicationContext, DefaultAutopilotDispatchers())

  val lifecycleState = controller.lifecycleState
  val cameras = controller.cameras
  val cameraConfig = controller.cameraConfig
  val availableModels = controller.availableModels
  val selectedModel = controller.selectedModel
  val preferGpu = controller.preferGpu
  val confidenceThreshold = controller.confidenceThreshold
  val cvEnabled = controller.cvEnabled
  val detectorAvailability = controller.detectorAvailability
  val profiles = controller.profiles
  val activeProfile = controller.activeProfile
  val manualConfig = controller.manualConfig
  val autoConfig = controller.autoConfig
  val recordingSelection = controller.recordingSelection
  val originalPreview = controller.originalPreview
  val degradedPreview = controller.degradedPreview
  val latestVisionResult = controller.latestVisionResult
  val metrics = controller.metrics
  val savedSessions = controller.savedSessions
  val lastCompletedSession = controller.lastCompletedSession
  val comparisonResults = controller.comparisonResults
  val isComparingModels = controller.isComparingModels
  val comparisonProgressText = controller.comparisonProgressText

  val supportedCodecs: Set<CodecType>
    get() = controller.supportedCodecs

  fun initializeStudio() = controller.initializeStudio()

  fun refreshModels() = controller.refreshModels()

  fun importModelUri(uri: Uri, onComplete: (Boolean) -> Unit) =
    controller.importModelUri(uri, onComplete)

  fun selectModel(spec: VisionModelSpec?) = controller.selectModel(spec)

  fun setPreferGpu(preferGpu: Boolean) = controller.setPreferGpu(preferGpu)

  fun setConfidenceThreshold(threshold: Float) = controller.setConfidenceThreshold(threshold)

  fun setCvEnabled(enabled: Boolean) = controller.setCvEnabled(enabled)

  fun selectCamera(cameraId: String) = controller.selectCamera(cameraId)

  fun selectCameraResolution(res: String) = controller.selectCameraResolution(res)

  fun selectProfile(profile: DegradationProfile) = controller.selectProfile(profile)

  fun updateManualConfig(transform: (ManualDegradationConfig) -> ManualDegradationConfig) =
    controller.updateManualConfig(transform)

  fun updateAutoConfig(transform: (AutoDegradationConfig) -> AutoDegradationConfig) =
    controller.updateAutoConfig(transform)

  fun updateRecordingSelection(transform: (RecordingSelection) -> RecordingSelection) =
    controller.updateRecordingSelection(transform)

  fun saveCustomProfile(name: String, description: String) =
    controller.saveCurrentAsCustomProfile(name, description)

  fun duplicateProfile(source: DegradationProfile, newName: String) =
    controller.duplicateProfile(source, newName)

  fun deleteCustomProfile(profileId: String) = controller.deleteCustomProfile(profileId)

  fun startBenchmark() = controller.startBenchmark()

  fun pauseBenchmark() = controller.pauseBenchmark()

  fun resumeBenchmark() = controller.resumeBenchmark()

  fun stopBenchmark() = controller.stopBenchmark()

  fun deleteSession(sessionId: String) = controller.deleteSession(sessionId)

  fun exportFile(sourceFile: File, destUri: Uri, onComplete: (Result<Long>) -> Unit) =
    controller.exportFile(sourceFile, destUri, onComplete)

  fun exportComparisonJson(destUri: Uri, onComplete: (Result<Long>) -> Unit) =
    controller.exportComparisonJson(destUri, onComplete)

  fun runControlledModelComparison(session: BenchmarkSessionMetadata, models: List<VisionModelSpec>) {
    val degraded =
      session.recordedFiles.firstOrNull { it.role.equals("Degraded", ignoreCase = true) }
        ?: session.recordedFiles.firstOrNull()
        ?: return
    controller.runControlledModelComparison(File(degraded.filePath), models)
  }

  override fun onCleared() {
    controller.close()
    super.onCleared()
  }
}
