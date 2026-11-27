package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.geometry.Offset
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
import com.autopilot.video.MvFilterPreset
import com.autopilot.video.VideoCaptureState
import com.autopilot.video.VideoFrameTransform
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
import com.example.input.AutomationState
import com.example.input.FlightAutomationCoordinator
import com.example.input.FlightMacroPreset
import com.example.input.GamepadAction
import com.example.input.GamepadAxes
import com.example.input.GamepadStatus
import com.example.input.GravitySensorHandler
import com.example.input.KeyboardAction
import com.example.input.KeyboardAxes
import com.example.input.SpinnerMode
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

  val automationCoordinator =
    FlightAutomationCoordinator(viewModelScope) { roll, pitch, throttle, yaw ->
      val effectiveThrottle = if (throttle != 0f) throttle else leftStickY
      flightController.sendStickInput(
        roll = roll,
        pitch = pitch,
        throttle = effectiveThrottle,
        yaw = yaw,
      )
    }

  val gravityHandler =
    GravitySensorHandler(application) { roll, pitch ->
      if (automationCoordinator.state.value.gravityActive &&
        !manualStickActive &&
        !keyboardAxesActive &&
        !gamepadAxesActive
      ) {
        rightStickX = roll
        rightStickY = pitch
        pushSticks()
      }
    }

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
  val automationState: StateFlow<AutomationState> = automationCoordinator.state
  val builtInMacros: List<FlightMacroPreset>
    get() = automationCoordinator.builtInMacros

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

  private val _detectedProtocolMatches = MutableStateFlow<List<ProtocolRegistryEntry>>(emptyList())
  val detectedProtocolMatches: StateFlow<List<ProtocolRegistryEntry>> =
    _detectedProtocolMatches.asStateFlow()

  private val _activeCameraIndex = MutableStateFlow(1)
  val activeCameraIndex: StateFlow<Int> = _activeCameraIndex.asStateFlow()

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
  private var leftStickY = 0f
  private var rightStickX = 0f
  private var rightStickY = 0f
  private var manualStickActive = false
  private var gamepadAxesActive = false
  private var keyboardAxesActive = false
  private var keyboardThrottleInput = 0f
  private var keyboardHoldJob: Job? = null
  private var lastVisionTiltAtMs = 0L
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
      syncLivePrefTargets(prefs)
    }

    viewModelScope.launch {
      prefsStore.preferences.collect { prefs ->
        syncLivePrefTargets(prefs)
      }
    }

    startVisionTrackingLoop()
  }

  private fun syncLivePrefTargets(
    prefs: AutopilotUserPrefs,
    entry: ProtocolRegistryEntry = _selectedEntry.value,
  ) {
    val effectiveRot =
      if (prefs.videoRotationAuto) entry.descriptor.defaultAutoRotationDeg
      else prefs.videoRotationDeg
    videoPipeline.updateFrameTransform(
      VideoFrameTransform(
        rotationDeg = effectiveRot,
        mirrorHorizontal = prefs.videoMirrorHorizontal,
        mvFilter = prefs.mvFilterPreset,
      )
    )
    gravityHandler.maxTiltDeg = prefs.gravityMaxAngleDeg
    automationCoordinator.spinnerRate = prefs.spinnerSpeedRatio
  }

  fun selectProtocol(protocolId: String, persist: Boolean = true) {
    val entry =
      ProtocolCompatibilityRegistry.findById(protocolId, includeSyntheticHarness = false) ?: return
    val changed = entry.descriptor.id != _selectedEntry.value.descriptor.id
    _selectedEntry.value = entry
    _customHostIp.value = entry.descriptor.defaultHostIp
    _videoTransportOverride.value = null
    _activeCameraIndex.value = 1
    syncLivePrefTargets(userPrefs.value, entry)
    if (persist) {
      viewModelScope.launch {
        prefsStore.updateSelectedProtocol(protocolId)
        prefsStore.updateStreamOverrides("AUTO", entry.descriptor.defaultHostIp)
        if (changed) {
          prefsStore.updateLinkEndpointPrefs(
            rtspAutoFallback = userPrefs.value.rtspAutoFallback,
            customRtspPath = "",
            customVideoPort = 0,
          )
        }
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
    val snap = wifiController.refreshCurrentWifiState()
    _detectedProtocolMatches.value =
      snap.ssid?.let { ProtocolCompatibilityRegistry.findMatchesBySsid(it) }.orEmpty()
  }

  fun detectedProtocolFromWifi(): ProtocolRegistryEntry? {
    val ssid = wifiLink.value.ssid ?: return null
    return ProtocolCompatibilityRegistry.findMatchesBySsid(ssid).firstOrNull()
  }

  fun bindWifiApForSelectedProtocol() {
    val desc = _selectedEntry.value.descriptor
    val hints = desc.ssidHints.ifEmpty { listOf("WIFI") }
    val currentSsid =
      wifiLink.value.ssid?.takeIf { ssid ->
        hints.any { h -> ssid.startsWith(h, ignoreCase = true) }
      }
    flightController.markDiscovering()
    wifiController.requestDroneApBinding(hints, preferredSsid = currentSsid) { handle ->
      if (handle != null) {
        viewModelScope.launch {
          repository.recordTrace(
            protocolId = desc.id,
            subsystem = "CONNECTIVITY",
            severity = "INFO",
            message = "Connected to aircraft Wi-Fi network (${currentSsid ?: hints.first()})",
          )
        }
      }
    }
  }

  fun connectSelectedProtocol() {
    val entry = _selectedEntry.value
    val desc = entry.descriptor
    val prefs = userPrefs.value
    val freshWifi = wifiController.refreshCurrentWifiState()
    _detectedProtocolMatches.value =
      freshWifi.ssid?.let { ProtocolCompatibilityRegistry.findMatchesBySsid(it) }.orEmpty()
    val host = _customHostIp.value.trim().ifEmpty { desc.defaultHostIp }
    val wifiHandle = freshWifi.boundNetworkHandle ?: wifiLink.value.boundNetworkHandle
    val selectedVideo = _videoTransportOverride.value ?: desc.videoTransport

    val resolvedPort = desc.resolvedVideoPort(selectedVideo, prefs.customVideoPort)
    val resolvedRtsp =
      if (prefs.customRtspPath.isNotBlank()) {
        prefs.customRtspPath.trim()
      } else {
        when (selectedVideo) {
          VideoTransportType.RTSP_H265 ->
            if (desc.id == "x69_lg") "/live/ch00_1" else desc.rtspPath
          VideoTransportType.RTSP_H264 ->
            if (desc.id == "x69_lg") "/live/ch00_1" else desc.rtspPath
          else -> desc.rtspPath
        }
      }

    val adapter = NativeDroneProtocolAdapter(entry, dispatchers)
    val config =
      ConnectionConfig(
        hostIp = host,
        controlPort = desc.controlPort,
        videoPort = resolvedPort,
        heartbeatPort = desc.heartbeatPort,
        rtspPath = resolvedRtsp,
        ssid = freshWifi.ssid ?: wifiLink.value.ssid,
        networkHandle = wifiHandle,
        allowSyntheticHarness = false,
        videoTransportOverride = selectedVideo,
        rtspAllowUdpFallback = prefs.rtspAutoFallback,
        preferRtspUdp = false,
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
    stopAllAutomation()
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
      _activeCameraIndex.value = 1
      keyboardHoldJob?.cancel()
      keyboardHoldJob = null
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
    if (active) {
      preemptAutomationOnManualInput()
      if (_activeTargetTask.value != null && _activeTargetTask.value?.action != TargetActionType.TRACK_TARGET) {
        stopTargetAction()
      }
    }
  }

  fun onLeftStickChanged(yaw: Float, throttle: Float) {
    leftStickX = yaw
    leftStickY = throttle
    pushSticks()
  }

  fun onRightStickChanged(roll: Float, pitch: Float) {
    if (roll != 0f || pitch != 0f) {
      preemptAutomationOnManualInput()
    }
    rightStickX = roll
    rightStickY = pitch
    pushSticks()
  }

  fun onGamepadAxesChanged(axes: GamepadAxes) {
    gamepadAxesActive = axes.activeStickMotion
    if (axes.activeStickMotion) {
      preemptAutomationOnManualInput()
      if (_activeTargetTask.value != null && _activeTargetTask.value?.action != TargetActionType.TRACK_TARGET) {
        stopTargetAction()
      }
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

  fun onKeyboardAxesChanged(axes: KeyboardAxes) {
    keyboardAxesActive = axes.active
    if (axes.active) {
      preemptAutomationOnManualInput()
      if (_activeTargetTask.value != null && _activeTargetTask.value?.action != TargetActionType.TRACK_TARGET) {
        stopTargetAction()
      }
    }
    leftStickX = axes.yaw
    keyboardThrottleInput = axes.throttle
    if (axes.throttle != 0f) {
      leftStickY = (leftStickY + axes.throttle * 0.08f).coerceIn(-1f, 1f)
      if (keyboardHoldJob == null || keyboardHoldJob?.isActive != true) {
        keyboardHoldJob =
          viewModelScope.launch {
            while (keyboardThrottleInput != 0f) {
              delay(50L)
              leftStickY = (leftStickY + keyboardThrottleInput * 0.05f).coerceIn(-1f, 1f)
              pushSticks()
            }
          }
      }
    } else {
      keyboardHoldJob?.cancel()
      keyboardHoldJob = null
    }
    rightStickX = axes.roll
    rightStickY = axes.pitch
    pushSticks()
  }

  fun onKeyboardAction(action: KeyboardAction) {
    when (action) {
      KeyboardAction.ARM_OR_TOGGLE_TAKEOFF_LAND -> {
        when (flightState.value) {
          FlightState.Ready, FlightState.Connected -> armAircraft()
          FlightState.Armed -> commandTakeoff()
          FlightState.Flying -> commandControlledLand()
          else -> {}
        }
      }
      KeyboardAction.EMERGENCY_STOP -> commandEmergencyStop()
      KeyboardAction.MOTOR_STOP -> commandMotorStop()
      KeyboardAction.FLIP_360 -> commandFlip360()
      KeyboardAction.TOGGLE_HEADLESS -> setHeadlessMode(!headlessMode.value)
      KeyboardAction.CALIBRATE_GYRO -> commandCalibrateGyro()
      KeyboardAction.SPEED_LOW -> setSpeedTier(0)
      KeyboardAction.SPEED_MED -> setSpeedTier(1)
      KeyboardAction.SPEED_FAST -> setSpeedTier(2)
      KeyboardAction.SNAPSHOT -> captureSnapshot()
      KeyboardAction.TOGGLE_RECORD -> toggleVideoRecording()
    }
  }

  fun toggleGravityControl(enabled: Boolean) {
    if (enabled) {
      val state = flightState.value
      val canEnable =
        state == FlightState.Ready ||
          state == FlightState.Connected ||
          state == FlightState.Armed ||
          state == FlightState.TakingOff ||
          state == FlightState.Flying
      if (!canEnable || !gravityHandler.isSensorAvailable) {
        return
      }
      stopTargetAction()
      automationCoordinator.setGravityActive(true)
      gravityHandler.setActive(true)
    } else {
      gravityHandler.setActive(false)
      automationCoordinator.setGravityActive(false)
      rightStickX = 0f
      rightStickY = 0f
      pushSticks()
    }
  }

  fun toggleSpinner(mode: SpinnerMode) {
    val state = flightState.value
    if (state != FlightState.Armed && state != FlightState.Flying && mode != SpinnerMode.NONE) {
      return
    }
    if (mode != SpinnerMode.NONE) {
      gravityHandler.setActive(false)
      stopTargetAction()
    }
    automationCoordinator.toggleSpinner(mode)
  }

  fun setTrajectoryDrawMode(enabled: Boolean) {
    if (enabled) {
      gravityHandler.setActive(false)
      stopTargetAction()
    }
    automationCoordinator.setTrajectoryDrawMode(enabled)
  }

  fun updatePendingTrajectory(points: List<Offset>) {
    automationCoordinator.setPendingTrajectoryPoints(points)
  }

  fun executeTrajectory() {
    val state = flightState.value
    if (state != FlightState.Armed && state != FlightState.Flying) {
      return
    }
    gravityHandler.setActive(false)
    stopTargetAction()
    automationCoordinator.executePendingTrajectory()
  }

  fun clearTrajectory() {
    automationCoordinator.clearTrajectory()
  }

  fun startMacro(preset: FlightMacroPreset) {
    val state = flightState.value
    if (state != FlightState.Armed && state != FlightState.Flying) {
      return
    }
    gravityHandler.setActive(false)
    stopTargetAction()
    automationCoordinator.startMacro(preset)
  }

  fun stopAllAutomation() {
    gravityHandler.setActive(false)
    automationCoordinator.stopAll(keepTrajectoryPreview = false)
    rightStickX = 0f
    rightStickY = 0f
    pushSticks()
  }

  private fun preemptAutomationOnManualInput() {
    val cur = automationCoordinator.state.value
    if (cur.spinnerMode != SpinnerMode.NONE || cur.activeSequenceTitle != null) {
      automationCoordinator.stopSpinner()
      automationCoordinator.stopSequence()
    }
    if (cur.gravityActive && (manualStickActive || gamepadAxesActive || keyboardAxesActive)) {
      gravityHandler.setActive(false)
      automationCoordinator.setGravityActive(false)
    }
  }

  fun updateGamepadSettings(enabled: Boolean, throttleHold: Boolean) {
    viewModelScope.launch {
      prefsStore.updateGamepadPrefs(enabled = enabled, throttleHold = throttleHold)
    }
  }

  fun updateLinkEndpointSettings(
    rtspAutoFallback: Boolean,
    customRtspPath: String,
    customVideoPort: Int,
  ) {
    viewModelScope.launch {
      prefsStore.updateLinkEndpointPrefs(
        rtspAutoFallback = rtspAutoFallback,
        customRtspPath = customRtspPath,
        customVideoPort = customVideoPort,
      )
    }
  }

  fun updateVideoTransformSettings(
    rotationDeg: Int,
    mirrorHorizontal: Boolean,
    mvFilterPreset: MvFilterPreset,
    firmwareSdTriggerEnabled: Boolean,
    videoRotationAuto: Boolean = false,
  ) {
    val normRot = ((rotationDeg % 360) + 360) % 360
    val effectiveRot =
      if (videoRotationAuto) _selectedEntry.value.descriptor.defaultAutoRotationDeg else normRot
    videoPipeline.updateFrameTransform(
      VideoFrameTransform(
        rotationDeg = effectiveRot,
        mirrorHorizontal = mirrorHorizontal,
        mvFilter = mvFilterPreset,
      )
    )
    viewModelScope.launch {
      prefsStore.updateVideoTransformPrefs(
        rotationDeg = normRot,
        mirrorHorizontal = mirrorHorizontal,
        mvFilterPreset = mvFilterPreset,
        firmwareSdTriggerEnabled = firmwareSdTriggerEnabled,
        videoRotationAuto = videoRotationAuto,
      )
    }
  }

  fun updateAssistAndInputSettings(
    keyboardEnabled: Boolean,
    softDescentAssistEnabled: Boolean,
    gravityMaxAngleDeg: Float,
    spinnerSpeedRatio: Float,
  ) {
    gravityHandler.maxTiltDeg = gravityMaxAngleDeg
    automationCoordinator.spinnerRate = spinnerSpeedRatio
    viewModelScope.launch {
      prefsStore.updateAssistAndInputPrefs(
        keyboardEnabled = keyboardEnabled,
        softDescentAssistEnabled = softDescentAssistEnabled,
        gravityMaxAngleDeg = gravityMaxAngleDeg,
        spinnerSpeedRatio = spinnerSpeedRatio,
      )
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
    stopAllAutomation()
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
    stopAllAutomation()
    stopTargetAction()
    val softAssist = userPrefs.value.softDescentAssistEnabled
    viewModelScope.launch { flightController.controlledLand(softDescentAssist = softAssist) }
  }

  fun commandMotorStop() {
    stopAllAutomation()
    stopTargetAction()
    viewModelScope.launch { flightController.motorStop() }
  }

  fun commandEmergencyStop() {
    stopAllAutomation()
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

  fun switchCamera() {
    val next = if (_activeCameraIndex.value == 1) 2 else 1
    _activeCameraIndex.value = next
    viewModelScope.launch { flightController.switchCamera(next) }
  }

  fun captureSnapshot() {
    videoPipeline.captureSnapshot(getApplication())
    if (userPrefs.value.firmwareSdTriggerEnabled) {
      viewModelScope.launch { flightController.triggerFirmwarePhoto() }
    }
  }

  fun toggleVideoRecording() {
    val willRecord = !captureState.value.isRecording
    videoPipeline.toggleRecording(getApplication())
    if (userPrefs.value.firmwareSdTriggerEnabled) {
      viewModelScope.launch { flightController.setFirmwareRecord(willRecord) }
    }
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
          if (manualStickActive || automationState.value.hasActiveAutomation) continue
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
                if (delta != 0 && now - lastVisionTiltAtMs >= 350L) {
                  lastVisionTiltAtMs = now
                  flightController.tiltCamera(delta)
                }
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
    gravityHandler.setActive(false)
    automationCoordinator.stopAll()
    keyboardHoldJob?.cancel()
    trackingLoopJob?.cancel()
    videoPipeline.detach()
    visionCoordinator.close()
    wifiController.releaseBinding()
    FlightLinkForegroundService.stop(getApplication())
  }
}
