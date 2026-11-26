package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autopilot.core.common.AutopilotLog
import com.autopilot.core.common.DefaultAutopilotDispatchers
import com.autopilot.core.common.SubsystemTag
import com.autopilot.designsystem.ContextualEffects
import com.autopilot.designsystem.ThemeAppearanceMode
import com.autopilot.designsystem.ThemeManager
import com.autopilot.designsystem.ThemeRuntimeState
import com.autopilot.drone.protocolapi.ConnectionConfig
import com.autopilot.drone.protocolapi.DroneCapabilities
import com.autopilot.drone.protocolapi.DroneTelemetry
import com.autopilot.drone.protocolapi.ProtocolCompatibilityRegistry
import com.autopilot.drone.protocolapi.ProtocolRegistryEntry
import com.autopilot.drone.protocolapi.VideoTransportType
import com.autopilot.drone.protocols.NativeDroneProtocolAdapter
import com.autopilot.drone.protocols.WifiConnectivityController
import com.autopilot.drone.protocols.WifiLinkSnapshot
import com.autopilot.flight.control.ControlSensitivityConfig
import com.autopilot.flight.control.FlightState
import com.autopilot.flight.control.FlightStateController
import com.autopilot.video.VideoCaptureState
import com.autopilot.video.VideoPipelineController
import com.autopilot.video.VideoStreamState
import com.autopilot.vision.DetectorAvailability
import com.autopilot.vision.TrackedTarget
import com.autopilot.vision.VisionFrameResult
import com.autopilot.vision.VisionPipelineCoordinator
import com.example.data.AutopilotDatabase
import com.example.data.AutopilotRepository
import com.example.data.AutopilotUserPrefs
import com.example.data.DiagnosticTraceEntity
import com.example.data.FlightSessionEntity
import com.example.data.UserPreferencesStore
import com.example.input.GamepadAction
import com.example.input.GamepadAxes
import com.example.input.GamepadStatus
import com.example.service.FlightLinkForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.hypot

@Serializable
data class TelemetryExportBundle(
  val exportedAtMs: Long,
  val appName: String = "AUTOPILOT",
  val sessions: List<FlightSessionEntity>,
  val traces: List<DiagnosticTraceEntity>,
)

class AutopilotViewModel(application: Application) : AndroidViewModel(application) {

  private val dispatchers = DefaultAutopilotDispatchers()
  private val exportJson = Json { prettyPrint = true }
  private val repository = AutopilotRepository(AutopilotDatabase.getInstance(application))
  private val prefsStore = UserPreferencesStore(application)

  val themeManager = ThemeManager()
  val wifiController = WifiConnectivityController(application)
  val flightController = FlightStateController(viewModelScope)
  val videoPipeline = VideoPipelineController(viewModelScope, dispatchers)
  val visionCoordinator = VisionPipelineCoordinator(application, dispatchers, preferGpu = true)

  val themeState: StateFlow<ThemeRuntimeState> = themeManager.state
  val wifiLink: StateFlow<WifiLinkSnapshot> = wifiController.linkState
  val flightState: StateFlow<FlightState> = flightController.flightState
  val telemetry: StateFlow<DroneTelemetry?> = flightController.telemetry
  val safetyNotice: StateFlow<String?> = flightController.safetyNotice
  val sensitivity: StateFlow<ControlSensitivityConfig> = flightController.sensitivity
  val speedTier: StateFlow<Int> = flightController.speedTier
  val fastFly: StateFlow<Boolean> = flightController.fastFly
  val headlessMode: StateFlow<Boolean> = flightController.headlessMode
  val cameraTiltStep: StateFlow<Int> = flightController.cameraTiltStep
  val videoState: StateFlow<VideoStreamState> = videoPipeline.streamState
  val captureState: StateFlow<VideoCaptureState> = videoPipeline.captureState
  val visionResult: StateFlow<VisionFrameResult?> = visionCoordinator.latestResult
  val detectorAvailability: StateFlow<DetectorAvailability> = visionCoordinator.availability

  val userPrefs: StateFlow<AutopilotUserPrefs> =
    prefsStore.preferences.stateIn(
      scope = viewModelScope,
      started = SharingStarted.Eagerly,
      initialValue = AutopilotUserPrefs(),
    )

  val flightSessions: StateFlow<List<FlightSessionEntity>> =
    repository.flightSessions.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = emptyList(),
    )

  val diagnosticTraces: StateFlow<List<DiagnosticTraceEntity>> =
    repository.diagnosticTraces.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = emptyList(),
    )

  val productionProtocols: List<ProtocolRegistryEntry> =
    ProtocolCompatibilityRegistry.productionEntries()

  private val _selectedEntry =
    MutableStateFlow(ProtocolCompatibilityRegistry.productionEntries().first())
  val selectedEntry: StateFlow<ProtocolRegistryEntry> = _selectedEntry.asStateFlow()

  private val _customHostIp = MutableStateFlow(_selectedEntry.value.descriptor.defaultHostIp)
  val customHostIp: StateFlow<String> = _customHostIp.asStateFlow()

  private val _videoTransportOverride = MutableStateFlow<VideoTransportType?>(null)
  val videoTransportOverride: StateFlow<VideoTransportType?> = _videoTransportOverride.asStateFlow()

  private val _gamepadStatus = MutableStateFlow(GamepadStatus())
  val gamepadStatus: StateFlow<GamepadStatus> = _gamepadStatus.asStateFlow()

  private val _visionOverlayEnabled = MutableStateFlow(false)
  val visionOverlayEnabled: StateFlow<Boolean> = _visionOverlayEnabled.asStateFlow()

  private val _targetSelectionEnabled = MutableStateFlow(false)
  val targetSelectionEnabled: StateFlow<Boolean> = _targetSelectionEnabled.asStateFlow()

  private val _selectedTargetId = MutableStateFlow<Int?>(null)
  val selectedTargetId: StateFlow<Int?> = _selectedTargetId.asStateFlow()

  private val _targetActionConfig = MutableStateFlow(TargetActionConfig())
  val targetActionConfig: StateFlow<TargetActionConfig> = _targetActionConfig.asStateFlow()

  private val _activeTargetTask = MutableStateFlow<ActiveTargetTask?>(null)
  val activeTargetTask: StateFlow<ActiveTargetTask?> = _activeTargetTask.asStateFlow()

  private var activeSessionRowId: Long? = null
  private var leftStickX = 0f
  private var leftStickY = -0.6f
  private var rightStickX = 0f
  private var rightStickY = 0f
  private var manualStickActive = false
  private var trackingLoopJob: Job? = null

  init {
    wifiController.refreshCurrentWifiState()
    visionCoordinator.initializeAsync()

    viewModelScope.launch {
      val prefs = prefsStore.preferences.first()
      selectProtocol(prefs.selectedProtocolId, persist = false)
      val parsedTransport =
        runCatching {
            if (prefs.selectedVideoTransport == "AUTO") null
            else VideoTransportType.valueOf(prefs.selectedVideoTransport)
          }
          .getOrNull()
      if (parsedTransport != null &&
        _selectedEntry.value.capabilities.supportedVideoModes.contains(parsedTransport)
      ) {
        _videoTransportOverride.value = parsedTransport
      }
      if (prefs.customHostIp.isNotBlank()) {
        _customHostIp.value = prefs.customHostIp
      }
      flightController.updateSensitivity(
        ControlSensitivityConfig(
          deadzone = prefs.stickDeadzone,
          expo = prefs.stickExpo,
          maxThrottleCap = prefs.maxThrottleCap,
          trimRoll = prefs.stickTrimRoll,
          trimPitch = prefs.stickTrimPitch,
        )
      )
      _visionOverlayEnabled.value = prefs.visionOverlayEnabled
      visionCoordinator.setEnabled(prefs.visionOverlayEnabled)
      visionCoordinator.updateCalibration(prefs.calibrationFocalLengthPx, prefs.calibrationVerified)
      visionCoordinator.updateConfidenceThreshold(prefs.visionConfidenceThreshold)
      _targetActionConfig.value =
        _targetActionConfig.value.copy(
          minTrackingDistanceMeters = prefs.visionDefaultMinDistanceM,
          maxTrackingAuthority = prefs.visionTrackingAuthority,
        )
      themeManager.selectTheme(prefs.activeThemeId)
      themeManager.setAppearanceMode(prefs.appearanceMode)
      themeManager.setContextualEffects(
        ContextualEffects(thermalDegradeBlur = prefs.thermalDegradeBlur)
      )
    }

    startVisionTrackingLoop()
  }

  fun selectProtocol(protocolId: String, persist: Boolean = true) {
    val entry =
      ProtocolCompatibilityRegistry.findById(protocolId, includeSyntheticHarness = false) ?: return
    _selectedEntry.value = entry
    _customHostIp.value = entry.descriptor.defaultHostIp
    _videoTransportOverride.value = null
    if (persist) {
      viewModelScope.launch {
        prefsStore.updateSelectedProtocol(protocolId)
        prefsStore.updateStreamOverrides("AUTO", entry.descriptor.defaultHostIp)
      }
    }
  }

  fun updateHostIp(ip: String) {
    _customHostIp.value = ip
    viewModelScope.launch {
      prefsStore.updateStreamOverrides(_videoTransportOverride.value?.name ?: "AUTO", ip)
    }
  }

  fun selectVideoTransportOverride(transport: VideoTransportType?) {
    val entry = _selectedEntry.value
    val validated =
      if (transport != null && entry.capabilities.supportedVideoModes.contains(transport)) transport
      else null
    _videoTransportOverride.value = validated
    // Adjust default X69 host IP hint if RTSP CH00_1 is chosen on 172.16.11.1 if user kept default
    if (entry.descriptor.id == "x69_lg" &&
      validated == VideoTransportType.RTSP_H265 &&
      _customHostIp.value == entry.descriptor.defaultHostIp &&
      entry.descriptor.alternateHostIps.isNotEmpty()
    ) {
      _customHostIp.value = entry.descriptor.alternateHostIps.first()
    }
    viewModelScope.launch {
      prefsStore.updateStreamOverrides(validated?.name ?: "AUTO", _customHostIp.value)
    }
  }

  fun refreshWifi() {
    wifiController.refreshCurrentWifiState()
  }

  fun detectedProtocolFromWifi(): ProtocolRegistryEntry? {
    val ssid = wifiLink.value.ssid ?: return null
    return productionProtocols.firstOrNull { entry ->
      entry.descriptor.ssidHints.any { hint -> ssid.startsWith(hint, ignoreCase = true) }
    }
  }

  fun bindWifiApForSelectedProtocol() {
    val hint = _selectedEntry.value.descriptor.ssidHints.firstOrNull() ?: "WIFI"
    flightController.markDiscovering()
    wifiController.requestDroneApBinding(hint) { handle ->
      if (handle != null) {
        viewModelScope.launch {
          repository.recordTrace(
            protocolId = _selectedEntry.value.descriptor.id,
            subsystem = "CONNECTIVITY",
            severity = "INFO",
            message = "Connected to aircraft Wi-Fi network ($hint*)",
          )
        }
      }
    }
  }

  fun connectSelectedProtocol() {
    val entry = _selectedEntry.value
    val desc = entry.descriptor
    val host = _customHostIp.value.trim()
    val wifiHandle = wifiLink.value.boundNetworkHandle
    val selectedVideo = _videoTransportOverride.value ?: desc.videoTransport

    val resolvedRtsp =
      when (selectedVideo) {
        VideoTransportType.RTSP_H265 ->
          if (desc.id == "x69_lg") "/live/ch00_1" else desc.rtspPath
        VideoTransportType.RTSP_H264 ->
          if (desc.id == "x69_lg") "rtsp://${host.ifEmpty { desc.defaultHostIp }}:7070/webcam"
          else desc.rtspPath
        else -> desc.rtspPath
      }

    val adapter = NativeDroneProtocolAdapter(entry, dispatchers)
    val config =
      ConnectionConfig(
        hostIp = host.ifEmpty { desc.defaultHostIp },
        controlPort = desc.controlPort,
        videoPort = desc.videoPort,
        heartbeatPort = desc.heartbeatPort,
        rtspPath = resolvedRtsp,
        ssid = wifiLink.value.ssid,
        networkHandle = wifiHandle,
        allowSyntheticHarness = false,
        videoTransportOverride = selectedVideo,
      )

    viewModelScope.launch {
      activeSessionRowId =
        repository.startFlightSession(
          protocolId = desc.id,
          protocolName = desc.displayName,
          hostIp = config.hostIp,
          isSyntheticHarness = false,
        )
      repository.recordTrace(
        protocolId = desc.id,
        subsystem = "PROTOCOL",
        severity = "INFO",
        message = "Connecting ${desc.displayName} (${config.hostIp}:${desc.controlPort}, video=$selectedVideo)",
      )
      FlightLinkForegroundService.start(getApplication(), desc.displayName)
      flightController.connectProtocol(adapter, config)
      videoPipeline.attachToProtocol(adapter, transportOverride = selectedVideo) { bitmap, isSynthetic ->
        if (_visionOverlayEnabled.value || _targetSelectionEnabled.value) {
          visionCoordinator.submitFrame(bitmap, isSynthetic)
        }
      }
      themeManager.setContextualEffects(
        themeManager.state.value.contextualEffects.copy(flightModeActive = true)
      )
    }
  }

  fun disconnect() {
    stopTargetAction()
    clearSelectedTarget()
    viewModelScope.launch {
      val currentTelemetry = telemetry.value
      val currentVideo = (videoState.value as? VideoStreamState.ActiveFrame)?.metrics
      activeSessionRowId?.let { id ->
        repository.finishFlightSession(
          sessionId = id,
          sent = currentTelemetry?.packetsSent ?: 0L,
          received = currentTelemetry?.packetsReceived ?: 0L,
          dropped = currentTelemetry?.droppedPackets ?: 0L,
          frames = currentVideo?.framesDecoded ?: 0L,
          reason = flightState.value.label,
        )
      }
      activeSessionRowId = null
      videoPipeline.detach()
      flightController.disconnect()
      wifiController.releaseBinding()
      FlightLinkForegroundService.stop(getApplication())
      themeManager.setContextualEffects(
        themeManager.state.value.contextualEffects.copy(flightModeActive = false)
      )
    }
  }

  fun onManualStickTouchChanged(active: Boolean) {
    manualStickActive = active
    if (active && _activeTargetTask.value != null && _activeTargetTask.value?.action != TargetActionType.TRACK_TARGET) {
      stopTargetAction()
    }
  }

  fun onLeftStickChanged(yaw: Float, throttle: Float) {
    leftStickX = yaw
    leftStickY = throttle
    pushSticks()
  }

  fun onRightStickChanged(roll: Float, pitch: Float) {
    rightStickX = roll
    rightStickY = pitch
    pushSticks()
  }

  fun onGamepadAxesChanged(axes: GamepadAxes) {
    if (axes.activeStickMotion && _activeTargetTask.value != null &&
      _activeTargetTask.value?.action != TargetActionType.TRACK_TARGET
    ) {
      stopTargetAction()
    }
    leftStickX = axes.yaw
    leftStickY = axes.throttle
    rightStickX = axes.roll
    rightStickY = axes.pitch
    pushSticks()
  }

  fun onGamepadStatusChanged(status: GamepadStatus) {
    _gamepadStatus.value = status
  }

  fun onGamepadAction(action: GamepadAction) {
    when (action) {
      GamepadAction.ARM_OR_TAKEOFF -> {
        when (flightState.value) {
          FlightState.Ready, FlightState.Connected -> armAircraft()
          FlightState.Armed -> commandTakeoff()
          else -> {}
        }
      }
      GamepadAction.CONTROLLED_LAND -> commandControlledLand()
      GamepadAction.FLIP_360 -> commandFlip360()
      GamepadAction.TOGGLE_HEADLESS -> setHeadlessMode(!headlessMode.value)
      GamepadAction.SNAPSHOT -> captureSnapshot()
      GamepadAction.TOGGLE_RECORD -> toggleVideoRecording()
      GamepadAction.TOGGLE_FAST_FLY -> setFastFly(!fastFly.value)
      GamepadAction.MOTOR_STOP -> commandMotorStop()
      GamepadAction.CALIBRATE_GYRO -> commandCalibrateGyro()
      GamepadAction.EMERGENCY_STOP_CHORD -> commandEmergencyStop()
      GamepadAction.TILT_UP -> tiltCamera(1)
      GamepadAction.TILT_DOWN -> tiltCamera(-1)
      GamepadAction.CYCLE_SPEED -> setSpeedTier((speedTier.value + 1) % 3)
    }
  }

  fun updateGamepadSettings(enabled: Boolean, throttleHold: Boolean) {
    viewModelScope.launch {
      prefsStore.updateGamepadPrefs(enabled = enabled, throttleHold = throttleHold)
    }
  }

  private fun pushSticks() {
    viewModelScope.launch {
      flightController.sendStickInput(
        roll = rightStickX,
        pitch = rightStickY,
        throttle = leftStickY,
        yaw = leftStickX,
      )
    }
  }

  fun armAircraft() {
    flightController.arm()
  }

  fun commandUnlockMotors() {
    viewModelScope.launch { flightController.unlockMotors() }
  }

  fun disarmAircraft() {
    stopTargetAction()
    flightController.disarm()
  }

  fun commandTakeoff() {
    viewModelScope.launch { flightController.takeoff() }
  }

  fun commandFlip360(direction: Int = 0) {
    viewModelScope.launch { flightController.flip360(direction) }
  }

  fun commandControlledLand() {
    stopTargetAction()
    viewModelScope.launch { flightController.controlledLand() }
  }

  fun commandMotorStop() {
    stopTargetAction()
    viewModelScope.launch { flightController.motorStop() }
  }

  fun commandEmergencyStop() {
    stopTargetAction()
    videoPipeline.stopRecording()
    viewModelScope.launch {
      flightController.emergencyStop()
      repository.recordTrace(
        protocolId = _selectedEntry.value.descriptor.id,
        subsystem = "FLIGHT_SAFETY",
        severity = "ERROR",
        message = "Operator activated emergency stop",
      )
    }
  }

  fun commandCalibrateGyro() {
    viewModelScope.launch { flightController.calibrateGyro() }
  }

  fun setSpeedTier(tier: Int) {
    viewModelScope.launch { flightController.setSpeedTier(tier) }
  }

  fun setFastFly(enabled: Boolean) {
    viewModelScope.launch { flightController.setFastFly(enabled) }
  }

  fun setHeadlessMode(enabled: Boolean) {
    viewModelScope.launch { flightController.setHeadlessMode(enabled) }
  }

  fun tiltCamera(delta: Int) {
    viewModelScope.launch { flightController.tiltCamera(delta) }
  }

  fun captureSnapshot() {
    videoPipeline.captureSnapshot(getApplication())
  }

  fun toggleVideoRecording() {
    videoPipeline.toggleRecording(getApplication())
  }

  fun clearCaptureNotice() {
    videoPipeline.clearNotice()
  }

  fun updateSensitivity(
    deadzone: Float,
    expo: Float,
    maxThrottleCap: Float,
    trimRoll: Float = sensitivity.value.trimRoll,
    trimPitch: Float = sensitivity.value.trimPitch,
  ) {
    val cfg =
      ControlSensitivityConfig(
        deadzone = deadzone,
        expo = expo,
        maxThrottleCap = maxThrottleCap,
        trimRoll = trimRoll.coerceIn(-0.25f, 0.25f),
        trimPitch = trimPitch.coerceIn(-0.25f, 0.25f),
      )
    flightController.updateSensitivity(cfg)
    viewModelScope.launch {
      prefsStore.updateFlightControls(deadzone, expo, maxThrottleCap, cfg.trimRoll, cfg.trimPitch)
    }
  }

  fun updateTrim(trimRoll: Float, trimPitch: Float) {
    val current = sensitivity.value
    updateSensitivity(
      deadzone = current.deadzone,
      expo = current.expo,
      maxThrottleCap = current.maxThrottleCap,
      trimRoll = trimRoll,
      trimPitch = trimPitch,
    )
  }

  fun toggleVisionOverlay(enabled: Boolean) {
    _visionOverlayEnabled.value = enabled
    if (!enabled) {
      _targetSelectionEnabled.value = false
      clearSelectedTarget()
      stopTargetAction()
    }
    visionCoordinator.setEnabled(enabled || _targetSelectionEnabled.value)
    val prefs = userPrefs.value
    viewModelScope.launch {
      prefsStore.updateVisionSettings(
        overlayEnabled = enabled,
        preferGpu = prefs.preferGpuLiteRt,
        focalLengthPx = prefs.calibrationFocalLengthPx,
        calibrated = prefs.calibrationVerified,
        confidenceThreshold = prefs.visionConfidenceThreshold,
        defaultMinDistanceM = prefs.visionDefaultMinDistanceM,
        trackingAuthority = prefs.visionTrackingAuthority,
      )
    }
  }

  fun toggleTargetSelection(enabled: Boolean) {
    _targetSelectionEnabled.value = enabled
    if (enabled && !_visionOverlayEnabled.value) {
      _visionOverlayEnabled.value = true
    }
    if (!enabled) {
      clearSelectedTarget()
      stopTargetAction()
    }
    visionCoordinator.setEnabled(_visionOverlayEnabled.value || enabled)
  }

  fun selectTargetAtNormalizedPoint(normX: Float, normY: Float): TrackedTarget? {
    val targets = visionResult.value?.trackedTargets.orEmpty()
    if (targets.isEmpty()) {
      _selectedTargetId.value = null
      return null
    }
    val directHit =
      targets.firstOrNull { target ->
        val box = target.boxNormalized
        normX in (box.left - 0.04f)..(box.right + 0.04f) &&
          normY in (box.top - 0.04f)..(box.bottom + 0.04f)
      }
    val chosen =
      directHit
        ?: targets
          .minByOrNull { target ->
            hypot(target.boxNormalized.centerX() - normX, target.boxNormalized.centerY() - normY)
          }
          ?.takeIf { target ->
            hypot(target.boxNormalized.centerX() - normX, target.boxNormalized.centerY() - normY) < 0.18f
          }

    _selectedTargetId.value = chosen?.trackId
    return chosen
  }

  fun clearSelectedTarget() {
    _selectedTargetId.value = null
  }

  fun updateTargetActionConfig(config: TargetActionConfig) {
    _targetActionConfig.value = config
    _activeTargetTask.value = _activeTargetTask.value?.copy(config = config)
  }

  fun startTargetAction(
    target: TrackedTarget,
    action: TargetActionType,
    config: TargetActionConfig = _targetActionConfig.value,
  ) {
    val availability =
      TargetActionEvaluator.evaluateActions(
          descriptor = _selectedEntry.value.descriptor,
          capabilities = _selectedEntry.value.capabilities,
          flightState = flightState.value,
        )
        .firstOrNull { it.action == action }
    if (availability == null || !availability.readyNow) {
      return
    }
    _selectedTargetId.value = target.trackId
    _targetActionConfig.value = config
    val now = System.currentTimeMillis()
    _activeTargetTask.value =
      ActiveTargetTask(
        targetId = target.trackId,
        targetLabel = target.label,
        action = action,
        config = config,
        startedAtMs = now,
        lastSeenAtMs = now,
      )
    AutopilotLog.info(SubsystemTag.VISION, "Engaged ${action.title} on #${target.trackId} ${target.label}")
  }

  fun stopTargetAction() {
    if (_activeTargetTask.value != null) {
      _activeTargetTask.value = null
      viewModelScope.launch {
        flightController.sendStickInput(roll = 0f, pitch = 0f, throttle = leftStickY, yaw = 0f)
      }
    }
  }

  private fun startVisionTrackingLoop() {
    trackingLoopJob?.cancel()
    trackingLoopJob =
      viewModelScope.launch {
        while (true) {
          delay(50L)
          val task = _activeTargetTask.value ?: continue
          if (manualStickActive) continue
          val targets = visionResult.value?.trackedTargets.orEmpty()
          val matched = targets.firstOrNull { it.trackId == task.targetId }
          val now = System.currentTimeMillis()
          if (matched != null) {
            _activeTargetTask.value = task.copy(lastSeenAtMs = now)
            if (task.action != TargetActionType.TRACK_TARGET && flightState.value == FlightState.Flying) {
              val cmd =
                TargetActionEvaluator.computeServoCommand(
                  task = task,
                  target = matched,
                  capabilities = _selectedEntry.value.capabilities,
                )
              flightController.sendStickInput(
                roll = cmd.roll,
                pitch = cmd.pitch,
                throttle = leftStickY,
                yaw = cmd.yaw,
              )
              cmd.cameraTiltDelta?.let { delta ->
                if (delta != 0) flightController.tiltCamera(delta)
              }
            }
          } else {
            val lostElapsedSec = (now - task.lastSeenAtMs) / 1000f
            if (lostElapsedSec > task.config.lostTargetTimeoutSec) {
              stopTargetAction()
            } else if (task.action != TargetActionType.TRACK_TARGET && flightState.value == FlightState.Flying) {
              flightController.sendStickInput(roll = 0f, pitch = 0f, throttle = leftStickY, yaw = 0f)
            }
          }
        }
      }
  }

  fun updateVisionCalibration(focalLengthPx: Float, verified: Boolean) {
    visionCoordinator.updateCalibration(focalLengthPx, verified)
    val prefs = userPrefs.value
    viewModelScope.launch {
      prefsStore.updateVisionSettings(
        overlayEnabled = _visionOverlayEnabled.value,
        preferGpu = prefs.preferGpuLiteRt,
        focalLengthPx = focalLengthPx,
        calibrated = verified,
        confidenceThreshold = prefs.visionConfidenceThreshold,
        defaultMinDistanceM = prefs.visionDefaultMinDistanceM,
        trackingAuthority = prefs.visionTrackingAuthority,
      )
    }
  }

  fun updateVisionConfidence(threshold: Float) {
    visionCoordinator.updateConfidenceThreshold(threshold)
    val prefs = userPrefs.value
    viewModelScope.launch {
      prefsStore.updateVisionSettings(
        overlayEnabled = _visionOverlayEnabled.value,
        preferGpu = prefs.preferGpuLiteRt,
        focalLengthPx = prefs.calibrationFocalLengthPx,
        calibrated = prefs.calibrationVerified,
        confidenceThreshold = threshold,
        defaultMinDistanceM = prefs.visionDefaultMinDistanceM,
        trackingAuthority = prefs.visionTrackingAuthority,
      )
    }
  }

  fun updateVisionTrackingDefaults(minDistM: Float, authority: Float) {
    _targetActionConfig.value =
      _targetActionConfig.value.copy(
        minTrackingDistanceMeters = minDistM,
        maxTrackingAuthority = authority,
      )
    val prefs = userPrefs.value
    viewModelScope.launch {
      prefsStore.updateVisionSettings(
        overlayEnabled = _visionOverlayEnabled.value,
        preferGpu = prefs.preferGpuLiteRt,
        focalLengthPx = prefs.calibrationFocalLengthPx,
        calibrated = prefs.calibrationVerified,
        confidenceThreshold = prefs.visionConfidenceThreshold,
        defaultMinDistanceM = minDistM,
        trackingAuthority = authority,
      )
    }
  }

  fun importYoloModelUri(uri: Uri, onComplete: (Boolean) -> Unit) {
    visionCoordinator.importYoloModel(uri) { ok ->
      viewModelScope.launch(dispatchers.main) {
        repository.recordTrace(
          protocolId = _selectedEntry.value.descriptor.id,
          subsystem = "VISION",
          severity = if (ok) "INFO" else "ERROR",
          message = if (ok) "Imported object detection model" else "Failed to import detection model",
        )
        onComplete(ok)
      }
    }
  }

  fun selectTheme(themeId: String) {
    themeManager.selectTheme(themeId)
    val state = themeManager.state.value
    viewModelScope.launch { prefsStore.updateTheme(state.activeThemeId, state.appearanceMode) }
  }

  fun setAppearanceMode(mode: ThemeAppearanceMode) {
    themeManager.setAppearanceMode(mode)
    val state = themeManager.state.value
    viewModelScope.launch { prefsStore.updateTheme(state.activeThemeId, mode) }
  }

  fun setThermalDegradeBlur(degrade: Boolean) {
    themeManager.setContextualEffects(
      themeManager.state.value.contextualEffects.copy(thermalDegradeBlur = degrade)
    )
    viewModelScope.launch { prefsStore.updateThermalBlurDegradation(degrade) }
  }

  fun activeCapabilities(): DroneCapabilities = _selectedEntry.value.capabilities

  fun availableTargetActions(): List<TargetActionAvailability> =
    TargetActionEvaluator.evaluateActions(
      descriptor = _selectedEntry.value.descriptor,
      capabilities = _selectedEntry.value.capabilities,
      flightState = flightState.value,
    )

  fun exportTelemetryToUri(uri: Uri, onComplete: (Boolean) -> Unit) {
    viewModelScope.launch(dispatchers.io) {
      val ok =
        runCatching {
            val bundle =
              TelemetryExportBundle(
                exportedAtMs = System.currentTimeMillis(),
                sessions = flightSessions.value,
                traces = diagnosticTraces.value,
              )
            val json = exportJson.encodeToString(TelemetryExportBundle.serializer(), bundle)
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
              out.write(json.toByteArray(Charsets.UTF_8))
            }
          }
          .isSuccess
      viewModelScope.launch(dispatchers.main) { onComplete(ok) }
    }
  }

  fun clearHistory() {
    viewModelScope.launch { repository.clearFlightHistory() }
  }

  override fun onCleared() {
    super.onCleared()
    trackingLoopJob?.cancel()
    videoPipeline.detach()
    visionCoordinator.close()
    wifiController.releaseBinding()
    FlightLinkForegroundService.stop(getApplication())
  }
}
