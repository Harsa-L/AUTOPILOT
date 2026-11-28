package com.autopilot.visionbenchmark

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.autopilot.core.common.AutopilotDispatchers
import com.autopilot.core.common.AutopilotLog
import com.autopilot.core.common.DefaultAutopilotDispatchers
import com.autopilot.core.common.SubsystemTag
import com.autopilot.vision.DetectorAvailability
import com.autopilot.vision.VisionFrameResult
import com.autopilot.vision.VisionModelRegistry
import com.autopilot.vision.VisionModelSpec
import com.autopilot.vision.VisionPipelineCoordinator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Completely isolated Vision Benchmark Studio orchestrator.
 * Reuses AUTOPILOT's production VisionPipelineCoordinator (LiteRT + C++20 NativeVisionEngine)
 * via its VisionFrameInputAdapter boundary in a dedicated session ("benchmark"), with ZERO
 * references or dependencies on flight control, trajectory planners, or drone links.
 */
class VisionBenchmarkController(
  private val context: Context,
  private val dispatchers: AutopilotDispatchers = DefaultAutopilotDispatchers(),
) : AutoCloseable {

  private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
  private val repository = BenchmarkRepository(context)
  private val metricsCollector = BenchmarkMetricsCollector(context)
  private val degradationEngine = DegradationEngine()
  private val codecSimulator = CodecCompressionSimulator()
  private val transmissionSimulator = TransmissionSimulator()
  private val recordingCoordinator = BenchmarkRecordingCoordinator()

  private var visionCoordinator: VisionPipelineCoordinator? = null
  private var videoSource: Camera2VideoSource? = null
  private var metricsTickerJob: Job? = null
  private var comparisonJob: Job? = null

  private val _lifecycleState =
    MutableStateFlow<BenchmarkLifecycleState>(BenchmarkLifecycleState.Inactive)
  val lifecycleState: StateFlow<BenchmarkLifecycleState> = _lifecycleState.asStateFlow()

  private val _cameras = MutableStateFlow<List<CameraDeviceInfo>>(emptyList())
  val cameras: StateFlow<List<CameraDeviceInfo>> = _cameras.asStateFlow()

  private val _cameraConfig = MutableStateFlow(CameraCaptureConfig())
  val cameraConfig: StateFlow<CameraCaptureConfig> = _cameraConfig.asStateFlow()

  private val _availableModels = MutableStateFlow<List<VisionModelSpec>>(emptyList())
  val availableModels: StateFlow<List<VisionModelSpec>> = _availableModels.asStateFlow()

  private val _selectedModel = MutableStateFlow<VisionModelSpec?>(null)
  val selectedModel: StateFlow<VisionModelSpec?> = _selectedModel.asStateFlow()

  private val _preferGpu = MutableStateFlow(true)
  val preferGpu: StateFlow<Boolean> = _preferGpu.asStateFlow()

  private val _confidenceThreshold = MutableStateFlow(0.40f)
  val confidenceThreshold: StateFlow<Float> = _confidenceThreshold.asStateFlow()

  private val _cvEnabled = MutableStateFlow(true)
  val cvEnabled: StateFlow<Boolean> = _cvEnabled.asStateFlow()

  private val _detectorAvailability =
    MutableStateFlow<DetectorAvailability>(
      DetectorAvailability.ModelNotProvisioned("Discovering LiteRT models...")
    )
  val detectorAvailability: StateFlow<DetectorAvailability> = _detectorAvailability.asStateFlow()

  private val _profiles = MutableStateFlow<List<DegradationProfile>>(emptyList())
  val profiles: StateFlow<List<DegradationProfile>> = _profiles.asStateFlow()

  private val _activeProfile = MutableStateFlow(BuiltInDegradationProfiles.defaultProfile())
  val activeProfile: StateFlow<DegradationProfile> = _activeProfile.asStateFlow()

  private val _manualConfig = MutableStateFlow(BuiltInDegradationProfiles.defaultProfile().config)
  val manualConfig: StateFlow<ManualDegradationConfig> = _manualConfig.asStateFlow()

  private val _autoConfig = MutableStateFlow(AutoDegradationConfig())
  val autoConfig: StateFlow<AutoDegradationConfig> = _autoConfig.asStateFlow()

  private val _recordingSelection = MutableStateFlow(RecordingSelection(recordDegraded = true))
  val recordingSelection: StateFlow<RecordingSelection> = _recordingSelection.asStateFlow()

  private val _originalPreview = MutableStateFlow<Bitmap?>(null)
  val originalPreview: StateFlow<Bitmap?> = _originalPreview.asStateFlow()

  private val _degradedPreview = MutableStateFlow<Bitmap?>(null)
  val degradedPreview: StateFlow<Bitmap?> = _degradedPreview.asStateFlow()

  private val _latestVisionResult = MutableStateFlow<VisionFrameResult?>(null)
  val latestVisionResult: StateFlow<VisionFrameResult?> = _latestVisionResult.asStateFlow()

  private val _metrics = MutableStateFlow(BenchmarkMetricsSnapshot())
  val metrics: StateFlow<BenchmarkMetricsSnapshot> = _metrics.asStateFlow()

  private val _savedSessions = MutableStateFlow<List<BenchmarkSessionMetadata>>(emptyList())
  val savedSessions: StateFlow<List<BenchmarkSessionMetadata>> = _savedSessions.asStateFlow()

  private val _lastCompletedSession = MutableStateFlow<BenchmarkSessionMetadata?>(null)
  val lastCompletedSession: StateFlow<BenchmarkSessionMetadata?> =
    _lastCompletedSession.asStateFlow()

  private val _comparisonResults = MutableStateFlow<List<ModelComparisonEntry>>(emptyList())
  val comparisonResults: StateFlow<List<ModelComparisonEntry>> = _comparisonResults.asStateFlow()

  private val _isComparingModels = MutableStateFlow(false)
  val isComparingModels: StateFlow<Boolean> = _isComparingModels.asStateFlow()

  private val _comparisonProgressText = MutableStateFlow<String?>(null)
  val comparisonProgressText: StateFlow<String?> = _comparisonProgressText.asStateFlow()

  val supportedCodecs: Set<CodecType>
    get() = codecSimulator.supportedCodecs

  private val isProcessingFrame = AtomicBoolean(false)
  private var sessionStartMs: Long = 0L
  private var frameCounter: Long = 0L
  private var currentSessionId: String? = null
  private var currentSessionDir: File? = null
  private var recordingInitialized = false
  private var visionObserverJob: Job? = null

  fun initializeStudio() {
    if (_lifecycleState.value == BenchmarkLifecycleState.Running) return
    _lifecycleState.value = BenchmarkLifecycleState.Initializing

    val cams = Camera2VideoSource.enumerateCameras(context)
    _cameras.value = cams
    if (cams.isNotEmpty()) {
      val currentId = _cameraConfig.value.cameraId
      val matched = cams.firstOrNull { it.cameraId == currentId } ?: cams.first()
      val (w, h) = parseResolution(matched.supportedResolutions.firstOrNull())
      _cameraConfig.value =
        CameraCaptureConfig(
          cameraId = matched.cameraId,
          captureWidth = w,
          captureHeight = h,
          targetMinFps = 15,
          targetMaxFps = 30,
        )
    }

    _profiles.value = repository.loadAllProfiles()
    _savedSessions.value = repository.listSessions()
    refreshModels()

    val coord = ensureVisionCoordinator()
    coord.initializeAsync()

    _lifecycleState.value = BenchmarkLifecycleState.Ready
  }

  fun refreshModels() {
    val models = VisionModelRegistry.discoverModels(context)
    _availableModels.value = models
    val curr = _selectedModel.value
    if (curr == null || models.none { it.id == curr.id }) {
      _selectedModel.value = models.firstOrNull()
    }
  }

  fun importModelUri(uri: Uri, onComplete: (Boolean) -> Unit) {
    scope.launch(dispatchers.io) {
      val spec = VisionModelRegistry.importModelFromUri(context, uri)
      if (spec != null) {
        val models = VisionModelRegistry.discoverModels(context)
        _availableModels.value = models
        selectModel(spec)
        withContext(dispatchers.main) { onComplete(true) }
      } else {
        withContext(dispatchers.main) { onComplete(false) }
      }
    }
  }

  fun selectModel(spec: VisionModelSpec?) {
    _selectedModel.value = spec
    visionCoordinator?.selectModel(spec, _preferGpu.value)
  }

  fun setPreferGpu(preferGpu: Boolean) {
    _preferGpu.value = preferGpu
    visionCoordinator?.selectModel(_selectedModel.value, preferGpu)
  }

  fun setConfidenceThreshold(threshold: Float) {
    val clamped = threshold.coerceIn(0.15f, 0.90f)
    _confidenceThreshold.value = clamped
    visionCoordinator?.updateConfidenceThreshold(clamped)
  }

  fun setCvEnabled(enabled: Boolean) {
    _cvEnabled.value = enabled
    visionCoordinator?.setEnabled(enabled && _lifecycleState.value == BenchmarkLifecycleState.Running)
    if (!enabled) _latestVisionResult.value = null
  }

  fun selectCamera(cameraId: String) {
    val cam = _cameras.value.firstOrNull { it.cameraId == cameraId } ?: return
    val (w, h) = parseResolution(cam.supportedResolutions.firstOrNull())
    val wasRunning = _lifecycleState.value == BenchmarkLifecycleState.Running
    if (wasRunning) stopBenchmark()
    _cameraConfig.value =
      _cameraConfig.value.copy(cameraId = cam.cameraId, captureWidth = w, captureHeight = h)
    if (wasRunning) startBenchmark()
  }

  fun selectCameraResolution(resString: String) {
    val (w, h) = parseResolution(resString)
    val wasRunning = _lifecycleState.value == BenchmarkLifecycleState.Running
    if (wasRunning) stopBenchmark()
    _cameraConfig.value = _cameraConfig.value.copy(captureWidth = w, captureHeight = h)
    if (wasRunning) startBenchmark()
  }

  fun selectProfile(profile: DegradationProfile) {
    _activeProfile.value = profile
    _manualConfig.value = profile.config
    _autoConfig.value = profile.autoConfig
  }

  fun updateManualConfig(transform: (ManualDegradationConfig) -> ManualDegradationConfig) {
    _manualConfig.value = transform(_manualConfig.value)
  }

  fun updateAutoConfig(transform: (AutoDegradationConfig) -> AutoDegradationConfig) {
    _autoConfig.value = transform(_autoConfig.value)
  }

  fun updateRecordingSelection(transform: (RecordingSelection) -> RecordingSelection) {
    _recordingSelection.value = transform(_recordingSelection.value)
  }

  fun saveCurrentAsCustomProfile(name: String, description: String) {
    val saved =
      repository.saveCustomProfile(
        name = name,
        description = description,
        config = _manualConfig.value,
        autoConfig = _autoConfig.value,
      )
    _profiles.value = repository.loadAllProfiles()
    _activeProfile.value = saved
  }

  fun duplicateProfile(source: DegradationProfile, newName: String) {
    val dup = repository.duplicateProfile(source, newName)
    _profiles.value = repository.loadAllProfiles()
    _activeProfile.value = dup
    _manualConfig.value = dup.config
    _autoConfig.value = dup.autoConfig
  }

  fun deleteCustomProfile(profileId: String) {
    if (repository.deleteCustomProfile(profileId)) {
      _profiles.value = repository.loadAllProfiles()
      if (_activeProfile.value.id == profileId) {
        selectProfile(BuiltInDegradationProfiles.defaultProfile())
      }
    }
  }

  fun startBenchmark() {
    if (_lifecycleState.value == BenchmarkLifecycleState.Running) return
    if (_cameras.value.isEmpty()) {
      _cameras.value = Camera2VideoSource.enumerateCameras(context)
    }
    if (_cameras.value.isEmpty()) {
      _lifecycleState.value = BenchmarkLifecycleState.Failed("No Camera2 device available")
      return
    }

    metricsCollector.reset()
    transmissionSimulator.reset()
    degradationEngine.ensureInitialized()

    val sessionId =
      SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
        "_" +
        UUID.randomUUID().toString().take(4)
    currentSessionId = sessionId
    currentSessionDir = repository.createSessionDir(sessionId)
    recordingInitialized = false
    frameCounter = 0L
    sessionStartMs = SystemClock.elapsedRealtime()

    val coord = ensureVisionCoordinator()
    coord.resetTrackerStateAsync()
    coord.setEnabled(_cvEnabled.value)

    _lifecycleState.value = BenchmarkLifecycleState.Running
    startMetricsTicker()

    val camSource =
      Camera2VideoSource(
        context = context,
        captureConfig = _cameraConfig.value,
        onFrame = { img, tsNs, rotDeg, isFront ->
          handleCameraFrame(img, tsNs, rotDeg, isFront)
        },
        onError = { err ->
          AutopilotLog.error(SubsystemTag.VISION, "Benchmark camera error: $err")
          scope.launch(dispatchers.main) {
            stopInternal(failedReason = err)
          }
        },
      )
    videoSource = camSource
    if (!camSource.start()) {
      stopInternal(failedReason = "Unable to start Camera2 stream")
    }
  }

  fun pauseBenchmark() {
    if (_lifecycleState.value == BenchmarkLifecycleState.Running) {
      _lifecycleState.value = BenchmarkLifecycleState.Paused
    }
  }

  fun resumeBenchmark() {
    if (_lifecycleState.value == BenchmarkLifecycleState.Paused) {
      _lifecycleState.value = BenchmarkLifecycleState.Running
    }
  }

  fun stopBenchmark() {
    stopInternal(failedReason = null)
  }

  private fun stopInternal(failedReason: String?) {
    val current = _lifecycleState.value
    if (
      current == BenchmarkLifecycleState.Inactive ||
        current == BenchmarkLifecycleState.Stopping ||
        current == BenchmarkLifecycleState.Ready
    ) {
      if (failedReason != null) {
        _lifecycleState.value = BenchmarkLifecycleState.Failed(failedReason)
      }
      return
    }

    _lifecycleState.value = BenchmarkLifecycleState.Stopping
    metricsTickerJob?.cancel()
    metricsTickerJob = null

    videoSource?.stop()
    videoSource?.close()
    videoSource = null

    visionCoordinator?.setEnabled(false)
    codecSimulator.releaseCodecs()
    transmissionSimulator.reset()

    val recordedFiles =
      if (recordingInitialized) recordingCoordinator.stopAndFinalize() else emptyList()
    recordingInitialized = false

    val sessId = currentSessionId
    val finalSnapshot = metricsCollector.snapshot()
    _metrics.value = finalSnapshot

    if (sessId != null && (finalSnapshot.sourceFramesCount > 0L || recordedFiles.isNotEmpty())) {
      val camLabel =
        _cameras.value.firstOrNull { it.cameraId == _cameraConfig.value.cameraId }?.displayLabel
          ?: _cameraConfig.value.cameraId
      val model = _selectedModel.value
      val metadata =
        BenchmarkSessionMetadata(
          sessionId = sessId,
          createdAtIso =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()),
          createdAtMs = System.currentTimeMillis(),
          deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
          cameraLabel = camLabel,
          modelId = model?.id ?: "none",
          modelName = model?.displayName ?: "No Model",
          profileId = _activeProfile.value.id,
          profileName = _activeProfile.value.name,
          autoScenarioMode = _autoConfig.value.mode,
          autoSeed = _autoConfig.value.seed,
          config = _manualConfig.value,
          metricsSummary = finalSnapshot,
          recordedFiles = recordedFiles,
        )
      repository.saveSessionMetadata(metadata)
      _lastCompletedSession.value = metadata
      _savedSessions.value = repository.listSessions()
    }

    _lifecycleState.value =
      if (failedReason != null) {
        BenchmarkLifecycleState.Failed(failedReason)
      } else {
        BenchmarkLifecycleState.Completed(sessId)
      }
  }

  fun deleteSession(sessionId: String) {
    repository.deleteSession(sessionId)
    _savedSessions.value = repository.listSessions()
    if (_lastCompletedSession.value?.sessionId == sessionId) {
      _lastCompletedSession.value = null
    }
  }

  fun exportFile(sourceFile: File, destUri: Uri, onComplete: (Result<Long>) -> Unit) {
    scope.launch(dispatchers.io) {
      val res = repository.exportFileToUri(sourceFile, destUri)
      withContext(dispatchers.main) { onComplete(res) }
    }
  }

  fun exportComparisonJson(destUri: Uri, onComplete: (Result<Long>) -> Unit) {
    scope.launch(dispatchers.io) {
      val res =
        runCatching {
          val payload = repository.encodeComparisonReportJson(_comparisonResults.value)
          context.contentResolver.openOutputStream(destUri)?.use { out ->
            out.write(payload.toByteArray(Charsets.UTF_8))
          } ?: error("Unable to open output stream")
          payload.length.toLong()
        }
      withContext(dispatchers.main) { onComplete(res) }
    }
  }

  /**
   * Controlled Model Comparison: replays the exact recorded degraded MP4 video sequentially
   * across every selected YOLO model through AUTOPILOT's shared VisionPipelineCoordinator
   * and measures authentic per-model latency, FPS, and detection counts.
   */
  fun runControlledModelComparison(
    degradedVideoFile: File,
    models: List<VisionModelSpec>,
    maxFrames: Int = 120,
  ) {
    if (_isComparingModels.value || models.isEmpty() || !degradedVideoFile.exists()) return
    comparisonJob?.cancel()
    _isComparingModels.value = true
    _comparisonResults.value = emptyList()

    comparisonJob =
      scope.launch(dispatchers.io) {
        try {
          val coord = ensureVisionCoordinator()
          coord.setEnabled(false)
          val results = mutableListOf<ModelComparisonEntry>()

          for ((idx, model) in models.withIndex()) {
            _comparisonProgressText.value =
              "Evaluating ${model.displayName} (${idx + 1}/${models.size})..."
            coord.selectModel(model, _preferGpu.value)
            coord.updateConfidenceThreshold(_confidenceThreshold.value)
            // Wait briefly for worker model reload to finish deterministically.
            delay(250L)

            val latencies = mutableListOf<Float>()
            var detTotal = 0
            var confSum = 0f
            var confCount = 0
            var skipped = 0
            val runStartNs = System.nanoTime()

            val decoded =
              RecordedMp4FrameReader.readAllFrames(degradedVideoFile, maxFrames = maxFrames) {
                _,
                bmp,
                ptsUs ->
                val res =
                  kotlinx.coroutines.runBlocking {
                    coord.processFrameSynchronously(
                      bitmap = bmp,
                      isSynthetic = false,
                      frameTimestampUs = ptsUs,
                    )
                  }
                if (res.detectorAvailability is DetectorAvailability.Ready) {
                  latencies.add(res.stageTimingsMs.totalMs)
                  detTotal += res.trackedTargets.size
                  res.trackedTargets.forEach { t ->
                    confSum += t.confidence
                    confCount++
                  }
                } else {
                  skipped++
                }
              }

            val totalElapsedSec = ((System.nanoTime() - runStartNs) / 1_000_000_000f).coerceAtLeast(0.001f)
            val stats = PercentileStats.fromValues(latencies)
            results.add(
              ModelComparisonEntry(
                modelId = model.id,
                modelName = model.displayName,
                framesProcessed = decoded,
                totalDetections = detTotal,
                avgDetectionsPerFrame = if (decoded > 0) detTotal.toFloat() / decoded else 0f,
                avgConfidence = if (confCount > 0) confSum / confCount else 0f,
                avgInferenceMs = stats.avg,
                p50InferenceMs = stats.p50,
                p95InferenceMs = stats.p95,
                p99InferenceMs = stats.p99,
                achievedFps = latencies.size.toFloat() / totalElapsedSec,
                skippedFrames = skipped,
                statusLabel =
                  when (val av = coord.availability.value) {
                    is DetectorAvailability.Ready -> "OK (${av.acceleratorLabel})"
                    is DetectorAvailability.ModelNotProvisioned -> "Missing weights"
                    is DetectorAvailability.Unavailable -> av.reason
                  },
              )
            )
            _comparisonResults.value = results.toList()
          }
          _comparisonProgressText.value = "Comparison complete (${results.size} models)"
        } finally {
          _isComparingModels.value = false
        }
      }
  }

  private fun handleCameraFrame(
    image: android.media.Image,
    timestampNs: Long,
    rotationDeg: Int,
    isFrontFacing: Boolean,
  ) {
    if (_lifecycleState.value != BenchmarkLifecycleState.Running) return
    val nowMs = SystemClock.elapsedRealtime()
    metricsCollector.recordSourceFrame(nowMs)

    if (!isProcessingFrame.compareAndSet(false, true)) {
      return
    }
    try {
      val idx = frameCounter++
      val elapsedMs = (nowMs - sessionStartMs).coerceAtLeast(0L)
      val scheduled =
        AutoDegradationScheduler.evaluate(_manualConfig.value, _autoConfig.value, elapsedMs, idx)

      val oscBoost =
        transmissionSimulator.oscillationCompressionBoost(
          scheduled.effectiveConfig.transmission.qualityOscillationEnabled,
          elapsedMs,
        )
      val effectiveConfig =
        scheduled.effectiveConfig.copy(
          compressionBlockStrength =
            (scheduled.effectiveConfig.compressionBlockStrength + oscBoost).coerceIn(0f, 1f)
        )

      val recSel = _recordingSelection.value
      val degOutput =
        degradationEngine.processCameraImage(
          image = image,
          rotationDeg = rotationDeg,
          mirrorH = isFrontFacing,
          config = effectiveConfig,
          frameSeed = scheduled.frameSeed,
          needCleanOutput = true,
        ) ?: return

      val codecResult =
        codecSimulator.applyIfEnabled(
          input = degOutput.degradedBitmap,
          config = effectiveConfig,
          seed = scheduled.frameSeed,
        )

      val txDecision =
        transmissionSimulator.evaluate(
          sourceBitmap = codecResult.bitmap,
          nowMs = nowMs,
          config = effectiveConfig,
          seed = scheduled.frameSeed,
        )

      metricsCollector.recordTransmissionOutcome(
        outcome = txDecision.outcome,
        transformMs = degOutput.transformAndDegradeMs,
        codecMs = codecResult.elapsedMs,
        codecStatus = codecResult.statusLabel,
        stageLabel = scheduled.stageLabel,
        nowMs = nowMs,
      )

      val cleanBmp = degOutput.cleanBitmap
      if (cleanBmp != null) {
        _originalPreview.value = cleanBmp.copy(Bitmap.Config.ARGB_8888, false)
      }

      if (!recordingInitialized && recSel.anyEnabled && currentSessionDir != null && cleanBmp != null) {
        recordingInitialized = true
        recordingCoordinator.startSession(
          sessionDir = currentSessionDir!!,
          selection = recSel,
          originalW = cleanBmp.width,
          originalH = cleanBmp.height,
          degradedW = degOutput.width,
          degradedH = degOutput.height,
          targetFps = effectiveConfig.targetFps,
        )
      }

      val ptsUs = (timestampNs / 1000L).coerceAtLeast(idx * 33_333L)
      if (recordingInitialized && recSel.recordOriginal && cleanBmp != null) {
        recordingCoordinator.enqueueOriginal(cleanBmp, ptsUs)
      }

      val finalBitmap = txDecision.outputBitmap ?: return
      _degradedPreview.value = finalBitmap.copy(Bitmap.Config.ARGB_8888, false)

      if (recordingInitialized && recSel.recordDegraded) {
        recordingCoordinator.enqueueDegraded(finalBitmap, ptsUs)
      }

      val coord = visionCoordinator
      if (_cvEnabled.value && coord != null) {
        coord.submitFrameWithTimestamp(finalBitmap, isSynthetic = false, frameTimestampUs = ptsUs)
        val duplicate = txDecision.duplicatedExtraBitmap
        if (duplicate != null) {
          coord.submitFrameWithTimestamp(duplicate, isSynthetic = false, frameTimestampUs = ptsUs + 1000L)
        }
      } else if (recordingInitialized && recSel.recordAnnotated) {
        recordingCoordinator.enqueueAnnotated(finalBitmap, null, scheduled.stageLabel, ptsUs)
      }
    } finally {
      isProcessingFrame.set(false)
    }
  }

  private fun ensureVisionCoordinator(): VisionPipelineCoordinator {
    visionCoordinator?.let { return it }
    val created =
      VisionPipelineCoordinator(
        context = context,
        dispatchers = dispatchers,
        preferGpu = _preferGpu.value,
        initialModelSpec = _selectedModel.value,
        sessionName = "benchmark",
      )
    created.updateConfidenceThreshold(_confidenceThreshold.value)
    visionCoordinator = created

    visionObserverJob?.cancel()
    visionObserverJob =
      scope.launch {
        launch {
          created.availability.collect { av -> _detectorAvailability.value = av }
        }
        launch {
          created.latestResult.collect { res ->
            if (res == null) return@collect
            _latestVisionResult.value = res
            val detCount = res.trackedTargets.size
            val avgConf =
              if (detCount > 0) res.trackedTargets.map { it.confidence }.average().toFloat() else 0f
            metricsCollector.recordInferenceResult(
              preprocessMs = res.stageTimingsMs.preprocessMs,
              inferenceMs = res.stageTimingsMs.inferenceMs,
              postprocessMs = res.stageTimingsMs.postprocessMs,
              trackFlowMs = res.stageTimingsMs.trackingFlowMs,
              totalStageMs = res.stageTimingsMs.totalMs,
              detectionCount = detCount,
              trackCount = detCount,
              avgConfidence = avgConf,
              queueDepth = if (isProcessingFrame.get()) 1 else 0,
            )
            if (recordingInitialized && _recordingSelection.value.recordAnnotated) {
              val preview = _degradedPreview.value
              if (preview != null && !preview.isRecycled) {
                recordingCoordinator.enqueueAnnotated(
                  degradedBitmap = preview,
                  visionResult = res,
                  stageLabel = _metrics.value.activeStageLabel,
                  ptsUs = res.frameTimestampUs,
                )
              }
            }
          }
        }
      }
    return created
  }

  private fun startMetricsTicker() {
    metricsTickerJob?.cancel()
    metricsTickerJob =
      scope.launch {
        while (isActive) {
          _metrics.value = metricsCollector.snapshot()
          delay(250L)
        }
      }
  }

  private fun parseResolution(text: String?): Pair<Int, Int> {
    if (text.isNullOrBlank()) return 1280 to 720
    val parts = text.split("x", "×")
    val w = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 1280
    val h = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 720
    return w.coerceIn(320, 1920) to h.coerceIn(240, 1080)
  }

  override fun close() {
    stopInternal(failedReason = null)
    comparisonJob?.cancel()
    visionObserverJob?.cancel()
    visionCoordinator?.close()
    visionCoordinator = null
    degradationEngine.close()
    codecSimulator.close()
    scope.cancel()
    _lifecycleState.value = BenchmarkLifecycleState.Inactive
  }
}
