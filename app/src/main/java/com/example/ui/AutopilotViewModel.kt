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
import com.autopilot.flight.gravity.GravityPreset
import com.autopilot.flight.gravity.GravityProfile
import com.autopilot.flight.gravity.GravityResponseCurve
import com.autopilot.flight.input.DeviceSafetyEvaluation
import com.autopilot.flight.input.DeviceSafetyPolicy
import com.autopilot.flight.input.FlightAxis
import com.autopilot.flight.input.FlightCommandLimiter
import com.autopilot.flight.input.FlightInputMixer
import com.autopilot.flight.input.InputSourceId
import com.autopilot.flight.input.StickInputProfile
import com.autopilot.flight.interaction.CockpitInteractionMode
import com.autopilot.flight.interaction.InteractionModeController
import com.autopilot.flight.interaction.InteractionModeState
import com.autopilot.flight.interaction.PointerRouteDecision
import com.autopilot.flight.interaction.TouchInteractionRouter
import com.autopilot.flight.interaction.TouchOwner
import com.autopilot.flight.touch.JoystickMode
import com.autopilot.flight.touch.TouchControlEngine
import com.autopilot.flight.touch.TouchPointPx
import com.autopilot.flight.touch.TouchZoneRuntime
import com.autopilot.video.MvFilterPreset
import com.autopilot.video.VideoCaptureState
import com.autopilot.video.VideoFrameTransform
import com.autopilot.video.VideoPipelineController
import com.autopilot.video.VideoStreamState
import com.autopilot.vision.DetectorAvailability
import com.autopilot.vision.OpticalFlowHoverController
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
import com.example.input.GravityRuntimeStatus
import com.example.input.GravitySensorHandler
import com.example.input.KeyboardAction
import com.example.input.KeyboardAxes
import com.example.input.SpinnerMode
import com.example.service.FlightLinkForegroundService
import com.example.ui.hud.HudElementId
import com.example.ui.hud.HudLayoutConfig
import com.example.ui.hud.HudLayoutEngine
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

data class HudHistoryFlags(
  val canUndoPortrait: Boolean = false,
  val canRedoPortrait: Boolean = false,
  val canUndoLandscape: Boolean = false,
  val canRedoLandscape: Boolean = false,
) {
  fun canUndo(landscape: Boolean): Boolean = if (landscape) canUndoLandscape else canUndoPortrait
  fun canRedo(landscape: Boolean): Boolean = if (landscape) canRedoLandscape else canRedoPortrait
}

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

  val inputMixer = FlightInputMixer()
  val touchControlEngine = TouchControlEngine()
  private val commandLimiter = FlightCommandLimiter(maxDeltaPerStep = 0.28f)
  private val hoverController = OpticalFlowHoverController()

  lateinit var interactionModeController: InteractionModeController
  lateinit var touchRouter: TouchInteractionRouter

  val automationCoordinator: FlightAutomationCoordinator =
    FlightAutomationCoordinator(viewModelScope) { roll, pitch, throttle, yaw ->
      val activeSrc =
        if (automationCoordinator.state.value.spinnerMode != SpinnerMode.NONE) InputSourceId.SPINNER
        else InputSourceId.TRAJECTORY
      inputMixer.submit(
        activeSrc,
        mapOf(
          FlightAxis.ROLL to roll,
          FlightAxis.PITCH to pitch,
          FlightAxis.THROTTLE to throttle,
          FlightAxis.YAW to yaw,
        ),
        active = true,
      )
      pushMixedSticks()
    }

  val gravityHandler: GravitySensorHandler =
    GravitySensorHandler(application) { roll, pitch, valid ->
      if (!valid) {
        inputMixer.neutralizeSource(InputSourceId.GRAVITY)
        if (automationCoordinator.state.value.gravityActive) {
          pushMixedSticks()
        }
        return@GravitySensorHandler
      }
      if (automationCoordinator.state.value.gravityActive &&
        !interactionModeController.state.suspendedInputSources.contains(InputSourceId.GRAVITY)
      ) {
        inputMixer.submit(
          source = InputSourceId.GRAVITY,
          axes = mapOf(FlightAxis.ROLL to roll, FlightAxis.PITCH to pitch),
          active = true,
          staleTimeoutMs = gravityHandler.profile.staleSensorTimeoutMs,
        )
        pushMixedSticks()
      }
    }

  init {
    interactionModeController =
      InteractionModeController(
        onSuspendSources = { suspended ->
          suspended.forEach { inputMixer.suspendSource(it, neutralizeOwnedAxes = true) }
          inputMixer.suspendSource(InputSourceId.VISION_SERVO, neutralizeOwnedAxes = true)
          commandLimiter.reset()
          hoverController.reset()
          touchControlEngine.resetAll(neutralizeThrottle = true)
          syncTouchRuntimeFlows()
          gravityHandler.setPausedForTool(suspended.contains(InputSourceId.GRAVITY))
          pushMixedSticks()
        },
        onResumePiloting = { restoreGravity, progressiveMs ->
          listOf(
            InputSourceId.ATC_LEFT,
            InputSourceId.ATC_RIGHT,
            InputSourceId.FIXED_LEFT_STICK,
            InputSourceId.FIXED_RIGHT_STICK,
            InputSourceId.GRAVITY,
            InputSourceId.TRAJECTORY,
            InputSourceId.SPINNER,
            InputSourceId.VISION_SERVO,
          ).forEach { src ->
            inputMixer.resumeSource(src, progressiveRampMs = if (src == InputSourceId.GRAVITY) progressiveMs else 0L)
          }
          if (restoreGravity) {
            gravityHandler.setPausedForTool(false)
            automationCoordinator.setGravityActive(true)
          }
          pushMixedSticks()
        },
        onNeutralizeAxes = { axes ->
          if (axes.contains(FlightAxis.ROLL) && axes.contains(FlightAxis.PITCH)) {
            rightStickX = 0f
            rightStickY = 0f
          }
          if (axes.contains(FlightAxis.YAW)) {
            leftStickX = 0f
          }
          if (axes.contains(FlightAxis.THROTTLE)) {
            leftStickY = 0f
          }
          commandLimiter.reset()
          pushMixedSticks()
        },
      )
    touchRouter = TouchInteractionRouter(interactionModeController)
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
  val fixedHeightMode: StateFlow<Boolean> = flightController.fixedHeightMode
  val linkRxVerified: StateFlow<Boolean> = flightController.linkRxVerified
  val cameraTiltStep: StateFlow<Int> = flightController.cameraTiltStep
  val videoState: StateFlow<VideoStreamState> = videoPipeline.streamState
  val captureState: StateFlow<VideoCaptureState> = videoPipeline.captureState
  val visionResult: StateFlow<VisionFrameResult?> = visionCoordinator.latestResult
  val detectorAvailability: StateFlow<DetectorAvailability> = visionCoordinator.availability
  val automationState: StateFlow<AutomationState> = automationCoordinator.state
  val gravityStatus: StateFlow<GravityRuntimeStatus> = gravityHandler.status
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

  private val _interactionModeState = MutableStateFlow(InteractionModeState())
  val interactionModeState: StateFlow<InteractionModeState> = _interactionModeState.asStateFlow()

  private val _atcLeftRuntime = MutableStateFlow(touchControlEngine.leftRuntime)
  val atcLeftRuntime: StateFlow<TouchZoneRuntime> = _atcLeftRuntime.asStateFlow()

  private val _atcRightRuntime = MutableStateFlow(touchControlEngine.rightRuntime)
  val atcRightRuntime: StateFlow<TouchZoneRuntime> = _atcRightRuntime.asStateFlow()

  private val _hudPortraitLayout = MutableStateFlow(HudLayoutEngine.defaultPortrait())
  val hudPortraitLayout: StateFlow<HudLayoutConfig> = _hudPortraitLayout.asStateFlow()

  private val _hudLandscapeLayout = MutableStateFlow(HudLayoutEngine.defaultLandscape())
  val hudLandscapeLayout: StateFlow<HudLayoutConfig> = _hudLandscapeLayout.asStateFlow()

  private val _selectedHudElement = MutableStateFlow(HudElementId.TOP_LEFT_STATUS)
  val selectedHudElement: StateFlow<HudElementId> = _selectedHudElement.asStateFlow()

  private val _hoverAssistEnabled = MutableStateFlow(false)
  val hoverAssistEnabled: StateFlow<Boolean> = _hoverAssistEnabled.asStateFlow()

  private val _diagSummary = MutableStateFlow("")
  val diagSummary: StateFlow<String> = _diagSummary.asStateFlow()

  private val _hudHistoryFlags = MutableStateFlow(HudHistoryFlags())
  val hudHistoryFlags: StateFlow<HudHistoryFlags> = _hudHistoryFlags.asStateFlow()

  private val portraitUndoStack = ArrayDeque<HudLayoutConfig>()
  private val portraitRedoStack = ArrayDeque<HudLayoutConfig>()
  private val landscapeUndoStack = ArrayDeque<HudLayoutConfig>()
  private val landscapeRedoStack = ArrayDeque<HudLayoutConfig>()

  private val _deviceSafetyEvaluation =
    MutableStateFlow(
      DeviceSafetyPolicy.evaluate(
        _selectedEntry.value.descriptor,
        _selectedEntry.value.capabilities,
      )
    )
  val deviceSafetyEvaluation: StateFlow<DeviceSafetyEvaluation> =
    _deviceSafetyEvaluation.asStateFlow()

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
  private var gravityWatchdogJob: Job? = null
  private var lastVisionTiltAtMs = 0L
  private var trackingLoopJob: Job? = null
  private var trajectoryStrokeBuffer = mutableListOf<Offset>()

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
      _hoverAssistEnabled.value = prefs.opticalFlowHoverAssistEnabled
      flightController.allowExperimentalFlightCommands = prefs.allowExperimentalFlightCommands
      visionCoordinator.setEnabled(
        prefs.visionOverlayEnabled || _targetSelectionEnabled.value || prefs.opticalFlowHoverAssistEnabled
      )
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
    startGravityWatchdogLoop()
  }

  private fun startGravityWatchdogLoop() {
    gravityWatchdogJob?.cancel()
    gravityWatchdogJob =
      viewModelScope.launch {
        while (true) {
          delay(120L)
          if (automationCoordinator.state.value.gravityActive) {
            gravityHandler.checkFreshness()
          }
        }
      }
  }

  private fun syncTouchRuntimeFlows() {
    _atcLeftRuntime.value = touchControlEngine.leftRuntime
    _atcRightRuntime.value = touchControlEngine.rightRuntime
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
    gravityHandler.profile =
      GravityProfile(
        enabled = automationCoordinator.state.value.gravityActive,
        sensitivityPitch = prefs.gravitySensitivityPitch,
        sensitivityRoll = prefs.gravitySensitivityRoll,
        deadzonePitchDeg = prefs.gravityDeadzoneDeg,
        deadzoneRollDeg = prefs.gravityDeadzoneDeg,
        maxTiltPitchDeg = prefs.gravityMaxAngleDeg,
        maxTiltRollDeg = prefs.gravityMaxAngleDeg,
        responseCurve = prefs.gravityCurve,
        smoothingAlpha = prefs.gravitySmoothingAlpha,
        slewRatePerSec = prefs.gravitySlewRatePerSec,
        preset = prefs.gravityPreset,
      )
    flightController.allowExperimentalFlightCommands = prefs.allowExperimentalFlightCommands
    _hoverAssistEnabled.value = prefs.opticalFlowHoverAssistEnabled
    visionCoordinator.setEnabled(
      _visionOverlayEnabled.value || _targetSelectionEnabled.value || prefs.opticalFlowHoverAssistEnabled
    )
    automationCoordinator.spinnerRate = prefs.spinnerSpeedRatio
    inputMixer.ownershipManager.applyProfile(
      profile = prefs.stickInputProfile,
      gravityEnabled = automationCoordinator.state.value.gravityActive,
    )
    inputMixer.holdThrottleOnRelease = prefs.gamepadThrottleHold

    val baseLeft = TouchControlEngine.defaultLeftZone(enabled = prefs.stickInputProfile != StickInputProfile.MANUAL_LOCKED)
    val baseRight =
      TouchControlEngine.defaultRightZone(
        enabled =
          prefs.stickInputProfile != StickInputProfile.ATC_HYBRID_GRAVITY &&
            prefs.stickInputProfile != StickInputProfile.MANUAL_LOCKED &&
            !automationCoordinator.state.value.gravityActive,
      )
    val leftInvertX = if (prefs.atcSwapZones) prefs.atcInvertRoll else prefs.atcInvertYaw
    val leftInvertY = if (prefs.atcSwapZones) prefs.atcInvertPitch else prefs.atcInvertThrottle
    val rightInvertX = if (prefs.atcSwapZones) prefs.atcInvertYaw else prefs.atcInvertRoll
    val rightInvertY = if (prefs.atcSwapZones) prefs.atcInvertThrottle else prefs.atcInvertPitch
    val leftCfg =
      (if (prefs.atcSwapZones) baseRight.copy(id = "left") else baseLeft).copy(
        joystickMode = prefs.atcJoystickMode,
        sensitivityX = prefs.atcSensitivity,
        sensitivityY = prefs.atcSensitivity,
        deadzone = prefs.atcDeadzone,
        visualRadiusDp = prefs.atcMaxTravelDp,
        opacity = prefs.atcOpacity,
        invertX = leftInvertX,
        invertY = leftInvertY,
      )
    val rightCfg =
      (if (prefs.atcSwapZones) baseLeft.copy(id = "right") else baseRight).copy(
        joystickMode = prefs.atcJoystickMode,
        sensitivityX = prefs.atcSensitivity,
        sensitivityY = prefs.atcSensitivity,
        deadzone = prefs.atcDeadzone,
        visualRadiusDp = prefs.atcMaxTravelDp,
        opacity = prefs.atcOpacity,
        invertX = rightInvertX,
        invertY = rightInvertY,
      )
    touchControlEngine.updateZoneConfigs(leftCfg, rightCfg)
    syncTouchRuntimeFlows()

    if (prefs.hudLayoutPortraitJson.isNotBlank()) {
      _hudPortraitLayout.value = HudLayoutEngine.deserialize(prefs.hudLayoutPortraitJson, landscape = false)
    }
    if (prefs.hudLayoutLandscapeJson.isNotBlank()) {
      _hudLandscapeLayout.value = HudLayoutEngine.deserialize(prefs.hudLayoutLandscapeJson, landscape = true)
    }
    _deviceSafetyEvaluation.value =
      DeviceSafetyPolicy.evaluate(entry.descriptor, entry.capabilities, prefs.gamepadThrottleHold)
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
        if (_visionOverlayEnabled.value || _targetSelectionEnabled.value || _hoverAssistEnabled.value) {
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
      hoverController.notifyManualInput(System.currentTimeMillis())
      preemptAutomationOnManualInput()
      if (_activeTargetTask.value != null && _activeTargetTask.value?.action != TargetActionType.TRACK_TARGET) {
        stopTargetAction()
      }
    }
  }

  fun onLeftStickChanged(yaw: Float, throttle: Float) {
    leftStickX = yaw
    leftStickY = throttle
    if (yaw != 0f || throttle != 0f) {
      hoverController.notifyManualInput(System.currentTimeMillis())
    }
    inputMixer.submit(
      InputSourceId.FIXED_LEFT_STICK,
      mapOf(FlightAxis.YAW to yaw, FlightAxis.THROTTLE to throttle),
      active = yaw != 0f || throttle != 0f,
    )
    pushMixedSticks()
  }

  fun onRightStickChanged(roll: Float, pitch: Float) {
    if (roll != 0f || pitch != 0f) {
      hoverController.notifyManualInput(System.currentTimeMillis())
      preemptAutomationOnManualInput()
    }
    rightStickX = roll
    rightStickY = pitch
    inputMixer.submit(
      InputSourceId.FIXED_RIGHT_STICK,
      mapOf(FlightAxis.ROLL to roll, FlightAxis.PITCH to pitch),
      active = roll != 0f || pitch != 0f,
    )
    pushMixedSticks()
  }

  fun pickAtcZone(normX: Float, normY: Float): TouchOwner? {
    val zone = touchControlEngine.pickZoneForNormalizedPoint(normX, normY) ?: return null
    return if (zone.id == "left") TouchOwner.ATC_LEFT else TouchOwner.ATC_RIGHT
  }

  fun onAtcPointerDown(
    pointerId: Int,
    zone: TouchOwner,
    posPx: TouchPointPx,
    viewportW: Float,
    viewportH: Float,
  ) {
    val decision = touchRouter.onPointerDown(pointerId, requestedAtcZone = zone)
    _interactionModeState.value = interactionModeController.state
    if (decision is PointerRouteDecision.Capture) {
      hoverController.notifyManualInput(System.currentTimeMillis())
      val zoneId = if (decision.owner == TouchOwner.ATC_LEFT) "left" else "right"
      val cmd = touchControlEngine.onDown(zoneId, pointerId, posPx, viewportW, viewportH)
      if (cmd != null) {
        val src = if (zoneId == "left") InputSourceId.ATC_LEFT else InputSourceId.ATC_RIGHT
        if (zoneId == "right") {
          preemptAutomationOnManualInput()
        }
        inputMixer.submit(src, cmd.axes, active = true)
        syncTouchRuntimeFlows()
        pushMixedSticks()
      }
    }
  }

  fun onAtcPointerMove(pointerId: Int, posPx: TouchPointPx) {
    val owner = touchRouter.onPointerMove(pointerId)
    if (owner != TouchOwner.ATC_LEFT && owner != TouchOwner.ATC_RIGHT) return
    hoverController.notifyManualInput(System.currentTimeMillis())
    val cmd = touchControlEngine.onMove(pointerId, posPx) ?: return
    val src = if (cmd.zoneId == "left") InputSourceId.ATC_LEFT else InputSourceId.ATC_RIGHT
    inputMixer.submit(src, cmd.axes, active = true)
    syncTouchRuntimeFlows()
    pushMixedSticks()
  }

  fun onAtcPointerUpOrCancel(pointerId: Int) {
    val owner = touchRouter.onPointerUpOrCancel(pointerId)
    _interactionModeState.value = interactionModeController.state
    if (owner != TouchOwner.ATC_LEFT && owner != TouchOwner.ATC_RIGHT) return
    val cmd =
      touchControlEngine.onUpOrCancel(
        pointerId = pointerId,
        holdVerticalIfConfigured = userPrefs.value.gamepadThrottleHold,
      ) ?: return
    val src = if (cmd.zoneId == "left") InputSourceId.ATC_LEFT else InputSourceId.ATC_RIGHT
    inputMixer.submit(src, cmd.axes, active = false)
    syncTouchRuntimeFlows()
    pushMixedSticks()
  }

  fun cancelAllAtcPointers() {
    touchControlEngine.resetAll(neutralizeThrottle = true).forEach { cmd ->
      val src = if (cmd.zoneId == "left") InputSourceId.ATC_LEFT else InputSourceId.ATC_RIGHT
      inputMixer.submit(src, cmd.axes, active = false)
    }
    syncTouchRuntimeFlows()
    pushMixedSticks()
  }

  fun onTrajectoryPointerDown(pointerId: Int, normPos: Offset): Boolean {
    val decision = touchRouter.onPointerDown(pointerId)
    _interactionModeState.value = interactionModeController.state
    if (decision is PointerRouteDecision.Capture && decision.owner == TouchOwner.TRAJECTORY_DRAW) {
      trajectoryStrokeBuffer = mutableListOf(normPos)
      automationCoordinator.setPendingTrajectoryPoints(trajectoryStrokeBuffer.toList())
      return true
    }
    return false
  }

  fun onTrajectoryPointerMove(pointerId: Int, normPos: Offset): Boolean {
    if (touchRouter.onPointerMove(pointerId) != TouchOwner.TRAJECTORY_DRAW) return false
    trajectoryStrokeBuffer.add(normPos)
    automationCoordinator.setPendingTrajectoryPoints(trajectoryStrokeBuffer.toList())
    return true
  }

  fun onTrajectoryPointerUpOrCancel(pointerId: Int) {
    touchRouter.onPointerUpOrCancel(pointerId)
    _interactionModeState.value = interactionModeController.state
  }

  fun onTargetPointerDown(pointerId: Int): Boolean {
    val decision =
      touchRouter.onPointerDown(pointerId, targetSelectActive = _targetSelectionEnabled.value)
    _interactionModeState.value = interactionModeController.state
    return decision is PointerRouteDecision.Capture && decision.owner == TouchOwner.VISION_TARGET
  }

  fun onTargetPointerUpOrCancel(pointerId: Int) {
    touchRouter.onPointerUpOrCancel(pointerId)
    _interactionModeState.value = interactionModeController.state
  }

  fun onGamepadAxesChanged(axes: GamepadAxes) {
    gamepadAxesActive = axes.activeStickMotion
    if (axes.activeStickMotion) {
      hoverController.notifyManualInput(System.currentTimeMillis())
      preemptAutomationOnManualInput()
      if (_activeTargetTask.value != null && _activeTargetTask.value?.action != TargetActionType.TRACK_TARGET) {
        stopTargetAction()
      }
    }
    leftStickX = axes.yaw
    leftStickY = axes.throttle
    rightStickX = axes.roll
    rightStickY = axes.pitch
    inputMixer.submit(
      InputSourceId.GAMEPAD,
      mapOf(
        FlightAxis.ROLL to axes.roll,
        FlightAxis.PITCH to axes.pitch,
        FlightAxis.THROTTLE to axes.throttle,
        FlightAxis.YAW to axes.yaw,
      ),
      active = axes.activeStickMotion || axes.throttle != 0f,
    )
    pushMixedSticks()
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
      hoverController.notifyManualInput(System.currentTimeMillis())
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
              inputMixer.submit(
                InputSourceId.KEYBOARD,
                mapOf(
                  FlightAxis.ROLL to rightStickX,
                  FlightAxis.PITCH to rightStickY,
                  FlightAxis.THROTTLE to leftStickY,
                  FlightAxis.YAW to leftStickX,
                ),
                active = true,
              )
              pushMixedSticks()
            }
          }
      }
    } else {
      keyboardHoldJob?.cancel()
      keyboardHoldJob = null
    }
    rightStickX = axes.roll
    rightStickY = axes.pitch
    inputMixer.submit(
      InputSourceId.KEYBOARD,
      mapOf(
        FlightAxis.ROLL to rightStickX,
        FlightAxis.PITCH to rightStickY,
        FlightAxis.THROTTLE to leftStickY,
        FlightAxis.YAW to leftStickX,
      ),
      active = axes.active || leftStickY != 0f,
    )
    pushMixedSticks()
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
      if (interactionModeController.state.mode != CockpitInteractionMode.PILOTING) {
        interactionModeController.returnToPiloting(targetSelectActive = _targetSelectionEnabled.value)
        _interactionModeState.value = interactionModeController.state
      }
      inputMixer.ownershipManager.applyProfile(userPrefs.value.stickInputProfile, gravityEnabled = true)
      inputMixer.resumeSource(InputSourceId.GRAVITY, progressiveRampMs = gravityHandler.profile.progressiveResumeMs)
      automationCoordinator.setGravityActive(true)
      gravityHandler.setActive(true)
      syncLivePrefTargets(userPrefs.value)
    } else {
      gravityHandler.setActive(false)
      automationCoordinator.setGravityActive(false)
      inputMixer.neutralizeSource(InputSourceId.GRAVITY)
      inputMixer.ownershipManager.applyProfile(userPrefs.value.stickInputProfile, gravityEnabled = false)
      rightStickX = 0f
      rightStickY = 0f
      syncLivePrefTargets(userPrefs.value)
      pushMixedSticks()
    }
  }

  fun recenterGravity() {
    val locked =
      gravityHandler.profile.lockReferenceDuringSequence &&
        automationCoordinator.state.value.activeSequenceTitle != null
    gravityHandler.recenter(referenceLocked = locked)
  }

  fun toggleSpinner(mode: SpinnerMode) {
    val state = flightState.value
    if (state != FlightState.Armed && state != FlightState.Flying && mode != SpinnerMode.NONE) {
      return
    }
    if (mode != SpinnerMode.NONE) {
      gravityHandler.setActive(false)
      inputMixer.neutralizeSource(InputSourceId.GRAVITY)
      stopTargetAction()
    }
    automationCoordinator.toggleSpinner(mode)
    if (mode == SpinnerMode.NONE) {
      inputMixer.neutralizeSource(InputSourceId.SPINNER)
      pushMixedSticks()
    }
  }

  fun setTrajectoryDrawMode(enabled: Boolean) {
    if (enabled) {
      val wasGravity = automationCoordinator.state.value.gravityActive
      stopTargetAction()
      interactionModeController.enterTrajectoryMode(gravityCurrentlyActive = wasGravity)
      _interactionModeState.value = interactionModeController.state
      automationCoordinator.setTrajectoryDrawMode(true)
    } else {
      automationCoordinator.setTrajectoryDrawMode(false)
      interactionModeController.returnToPiloting(
        targetSelectActive = _targetSelectionEnabled.value,
        restoreGravity = true,
      )
      _interactionModeState.value = interactionModeController.state
    }
  }

  fun updatePendingTrajectory(points: List<Offset>) {
    automationCoordinator.setPendingTrajectoryPoints(points)
  }

  fun executeTrajectory() {
    val state = flightState.value
    val eval = _deviceSafetyEvaluation.value
    if (!eval.supportsTrajectoryExecution) {
      return
    }
    if (state != FlightState.Armed && state != FlightState.Flying) {
      return
    }
    gravityHandler.setActive(false)
    stopTargetAction()
    automationCoordinator.executePendingTrajectory(protocolSupportsTrajectory = true)
    interactionModeController.returnToPiloting(
      targetSelectActive = _targetSelectionEnabled.value,
      restoreGravity = false,
    )
    _interactionModeState.value = interactionModeController.state
  }

  fun clearTrajectory() {
    automationCoordinator.clearTrajectory()
    if (interactionModeController.state.mode == CockpitInteractionMode.TRAJECTORY) {
      interactionModeController.returnToPiloting(
        targetSelectActive = _targetSelectionEnabled.value,
        restoreGravity = true,
      )
      _interactionModeState.value = interactionModeController.state
    }
  }

  fun enterHudEditingMode() {
    val wasGravity = automationCoordinator.state.value.gravityActive
    automationCoordinator.setTrajectoryDrawMode(false)
    stopTargetAction()
    interactionModeController.enterHudEditingMode(gravityCurrentlyActive = wasGravity)
    _interactionModeState.value = interactionModeController.state
  }

  fun exitHudEditingMode() {
    interactionModeController.returnToPiloting(
      targetSelectActive = _targetSelectionEnabled.value,
      restoreGravity = true,
    )
    _interactionModeState.value = interactionModeController.state
    viewModelScope.launch {
      prefsStore.updateHudLayouts(
        portraitJson = HudLayoutEngine.serialize(_hudPortraitLayout.value),
        landscapeJson = HudLayoutEngine.serialize(_hudLandscapeLayout.value),
      )
    }
  }

  fun selectHudElement(id: HudElementId) {
    _selectedHudElement.value = id
  }

  private fun pushHudUndoSnapshot(landscape: Boolean) {
    val undo = if (landscape) landscapeUndoStack else portraitUndoStack
    val redo = if (landscape) landscapeRedoStack else portraitRedoStack
    val current = if (landscape) _hudLandscapeLayout.value else _hudPortraitLayout.value
    if (undo.size >= 25) undo.removeFirst()
    undo.addLast(current)
    redo.clear()
    refreshHudHistoryFlags()
  }

  private fun refreshHudHistoryFlags() {
    _hudHistoryFlags.value =
      HudHistoryFlags(
        canUndoPortrait = portraitUndoStack.isNotEmpty(),
        canRedoPortrait = portraitRedoStack.isNotEmpty(),
        canUndoLandscape = landscapeUndoStack.isNotEmpty(),
        canRedoLandscape = landscapeRedoStack.isNotEmpty(),
      )
  }

  fun moveHudElement(
    landscape: Boolean,
    id: HudElementId,
    dxNorm: Float,
    dyNorm: Float,
    pushUndo: Boolean = false,
  ) {
    if (pushUndo) pushHudUndoSnapshot(landscape)
    if (landscape) {
      _hudLandscapeLayout.value = HudLayoutEngine.moveElement(_hudLandscapeLayout.value, id, dxNorm, dyNorm)
    } else {
      _hudPortraitLayout.value = HudLayoutEngine.moveElement(_hudPortraitLayout.value, id, dxNorm, dyNorm)
    }
  }

  fun beginHudDragUndo(landscape: Boolean) {
    pushHudUndoSnapshot(landscape)
  }

  fun scaleHudElement(landscape: Boolean, id: HudElementId, delta: Float) {
    pushHudUndoSnapshot(landscape)
    if (landscape) {
      _hudLandscapeLayout.value = HudLayoutEngine.updateElementStyle(_hudLandscapeLayout.value, id, scaleDelta = delta)
    } else {
      _hudPortraitLayout.value = HudLayoutEngine.updateElementStyle(_hudPortraitLayout.value, id, scaleDelta = delta)
    }
  }

  fun opacityHudElement(landscape: Boolean, id: HudElementId, delta: Float) {
    pushHudUndoSnapshot(landscape)
    if (landscape) {
      _hudLandscapeLayout.value = HudLayoutEngine.updateElementStyle(_hudLandscapeLayout.value, id, opacityDelta = delta)
    } else {
      _hudPortraitLayout.value = HudLayoutEngine.updateElementStyle(_hudPortraitLayout.value, id, opacityDelta = delta)
    }
  }

  fun toggleHudElementVisibility(landscape: Boolean, id: HudElementId) {
    pushHudUndoSnapshot(landscape)
    if (landscape) {
      val cur = _hudLandscapeLayout.value.element(id)
      _hudLandscapeLayout.value = HudLayoutEngine.updateElementStyle(_hudLandscapeLayout.value, id, visible = !cur.visible)
    } else {
      val cur = _hudPortraitLayout.value.element(id)
      _hudPortraitLayout.value = HudLayoutEngine.updateElementStyle(_hudPortraitLayout.value, id, visible = !cur.visible)
    }
  }

  fun toggleHudElementLock(landscape: Boolean, id: HudElementId) {
    pushHudUndoSnapshot(landscape)
    if (landscape) {
      _hudLandscapeLayout.value = HudLayoutEngine.toggleLock(_hudLandscapeLayout.value, id)
    } else {
      _hudPortraitLayout.value = HudLayoutEngine.toggleLock(_hudPortraitLayout.value, id)
    }
  }

  fun resetHudLayout(landscape: Boolean) {
    pushHudUndoSnapshot(landscape)
    if (landscape) {
      _hudLandscapeLayout.value = HudLayoutEngine.defaultLandscape()
    } else {
      _hudPortraitLayout.value = HudLayoutEngine.defaultPortrait()
    }
  }

  fun undoHudLayout(landscape: Boolean) {
    val undo = if (landscape) landscapeUndoStack else portraitUndoStack
    val redo = if (landscape) landscapeRedoStack else portraitRedoStack
    if (undo.isEmpty()) return
    val current = if (landscape) _hudLandscapeLayout.value else _hudPortraitLayout.value
    redo.addLast(current)
    val prev = undo.removeLast()
    if (landscape) _hudLandscapeLayout.value = prev else _hudPortraitLayout.value = prev
    refreshHudHistoryFlags()
  }

  fun redoHudLayout(landscape: Boolean) {
    val undo = if (landscape) landscapeUndoStack else portraitUndoStack
    val redo = if (landscape) landscapeRedoStack else portraitRedoStack
    if (redo.isEmpty()) return
    val current = if (landscape) _hudLandscapeLayout.value else _hudPortraitLayout.value
    undo.addLast(current)
    val next = redo.removeLast()
    if (landscape) _hudLandscapeLayout.value = next else _hudPortraitLayout.value = next
    refreshHudHistoryFlags()
  }

  fun onCockpitInterrupted(reason: String) {
    keyboardHoldJob?.cancel()
    keyboardHoldJob = null
    manualStickActive = false
    keyboardAxesActive = false
    gamepadAxesActive = false
    commandLimiter.reset()
    hoverController.reset()
    cancelAllAtcPointers()
    interactionModeController.handleInterrupt(reason)
    _interactionModeState.value = interactionModeController.state
    inputMixer.neutralizeAll()
    pushMixedSticks()
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
    inputMixer.neutralizeSource(InputSourceId.GRAVITY)
    inputMixer.neutralizeSource(InputSourceId.SPINNER)
    inputMixer.neutralizeSource(InputSourceId.TRAJECTORY)
    if (interactionModeController.state.mode == CockpitInteractionMode.TRAJECTORY) {
      interactionModeController.returnToPiloting(targetSelectActive = _targetSelectionEnabled.value)
      _interactionModeState.value = interactionModeController.state
    }
    rightStickX = 0f
    rightStickY = 0f
    pushMixedSticks()
  }

  private fun preemptAutomationOnManualInput() {
    val cur = automationCoordinator.state.value
    if (cur.spinnerMode != SpinnerMode.NONE || cur.activeSequenceTitle != null) {
      automationCoordinator.stopSpinner()
      automationCoordinator.stopSequence()
      inputMixer.neutralizeSource(InputSourceId.SPINNER)
      inputMixer.neutralizeSource(InputSourceId.TRAJECTORY)
    }
    if (cur.gravityActive && (manualStickActive || gamepadAxesActive || keyboardAxesActive)) {
      gravityHandler.setActive(false)
      automationCoordinator.setGravityActive(false)
      inputMixer.neutralizeSource(InputSourceId.GRAVITY)
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

  fun updateAtcAndGravityAdvancedSettings(
    stickInputProfile: StickInputProfile,
    atcSensitivity: Float,
    atcDeadzone: Float,
    atcMaxTravelDp: Float,
    atcOpacity: Float,
    atcInvertThrottle: Boolean,
    atcInvertPitch: Boolean,
    atcSwapZones: Boolean,
    gravityPreset: GravityPreset,
    gravityCurve: GravityResponseCurve,
    gravitySensitivityPitch: Float,
    gravitySensitivityRoll: Float,
    gravityDeadzoneDeg: Float,
    gravitySmoothingAlpha: Float,
    gravitySlewRatePerSec: Float,
    atcJoystickMode: JoystickMode = userPrefs.value.atcJoystickMode,
    atcInvertRoll: Boolean = userPrefs.value.atcInvertRoll,
    atcInvertYaw: Boolean = userPrefs.value.atcInvertYaw,
  ) {
    viewModelScope.launch {
      prefsStore.updateAtcAndGravityAdvancedPrefs(
        stickInputProfile = stickInputProfile,
        atcSensitivity = atcSensitivity,
        atcDeadzone = atcDeadzone,
        atcMaxTravelDp = atcMaxTravelDp,
        atcOpacity = atcOpacity,
        atcInvertThrottle = atcInvertThrottle,
        atcInvertPitch = atcInvertPitch,
        atcSwapZones = atcSwapZones,
        gravityPreset = gravityPreset,
        gravityCurve = gravityCurve,
        gravitySensitivityPitch = gravitySensitivityPitch,
        gravitySensitivityRoll = gravitySensitivityRoll,
        gravityDeadzoneDeg = gravityDeadzoneDeg,
        gravitySmoothingAlpha = gravitySmoothingAlpha,
        gravitySlewRatePerSec = gravitySlewRatePerSec,
        atcJoystickMode = atcJoystickMode,
        atcInvertRoll = atcInvertRoll,
        atcInvertYaw = atcInvertYaw,
      )
    }
  }

  fun updateExperimentalFlightAllowed(allowed: Boolean) {
    flightController.allowExperimentalFlightCommands = allowed
    viewModelScope.launch { prefsStore.updateExperimentalFlightAllowed(allowed) }
  }

  fun toggleHoverAssist(enabled: Boolean) {
    _hoverAssistEnabled.value = enabled
    if (!enabled) {
      hoverController.reset()
      inputMixer.neutralizeSource(InputSourceId.VISION_SERVO)
      pushMixedSticks()
    }
    visionCoordinator.setEnabled(_visionOverlayEnabled.value || _targetSelectionEnabled.value || enabled)
    viewModelScope.launch { prefsStore.updateHoverAssistEnabled(enabled) }
  }

  private fun pushMixedSticks() {
    val hwOverride =
      when {
        gamepadAxesActive -> InputSourceId.GAMEPAD
        keyboardAxesActive -> InputSourceId.KEYBOARD
        else -> null
      }
    val atcTouchActive =
      _atcLeftRuntime.value.activePointerId != null || _atcRightRuntime.value.activePointerId != null
    val visionServoEligible =
      !manualStickActive &&
        !atcTouchActive &&
        !automationCoordinator.state.value.gravityActive &&
        (_activeTargetTask.value != null || _hoverAssistEnabled.value)
    val autoOverride =
      when {
        automationCoordinator.state.value.spinnerMode != SpinnerMode.NONE -> InputSourceId.SPINNER
        automationCoordinator.state.value.activeSequenceTitle != null -> InputSourceId.TRAJECTORY
        visionServoEligible -> InputSourceId.VISION_SERVO
        else -> null
      }
    val mixed =
      inputMixer.mix(
        activeHardwareOverride = hwOverride,
        activeAutomationOverride = autoOverride,
      )
    val limitedRoll = commandLimiter.limit(FlightAxis.ROLL, mixed.roll)
    val limitedPitch = commandLimiter.limit(FlightAxis.PITCH, mixed.pitch)
    val limitedThrottle = commandLimiter.limit(FlightAxis.THROTTLE, mixed.throttle)
    val limitedYaw = commandLimiter.limit(FlightAxis.YAW, mixed.yaw)
    viewModelScope.launch {
      flightController.sendStickInput(
        roll = limitedRoll,
        pitch = limitedPitch,
        throttle = limitedThrottle,
        yaw = limitedYaw,
        preShapedRollPitch = mixed.isRollPitchPreShaped,
        preShapedThrottleYaw = mixed.isThrottleYawPreShaped,
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
    commandLimiter.reset()
    hoverController.reset()
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
    commandLimiter.reset()
    hoverController.reset()
    stopAllAutomation()
    stopTargetAction()
    viewModelScope.launch { flightController.motorStop() }
  }

  fun commandEmergencyStop() {
    commandLimiter.reset()
    hoverController.reset()
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

  fun toggleLight() {
    viewModelScope.launch { flightController.toggleLight() }
  }

  fun setFixedHeightMode(enabled: Boolean) {
    viewModelScope.launch { flightController.setFixedHeightMode(enabled) }
  }

  fun setServoProbe(enabled: Boolean, byteIndex: Int, hexValue: Int) {
    flightController.setServoProbe(enabled, byteIndex, hexValue)
  }

  fun sendCooingdvPassword(digits8: String): Boolean =
    flightController.sendCooingdvPassword(digits8)

  fun readDiagSummary(): String {
    val summary = flightController.readDiagSummary()
    _diagSummary.value = summary
    return summary
  }

  fun refreshDiagSummary() {
    _diagSummary.value = flightController.readDiagSummary()
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
    visionCoordinator.setEnabled(enabled || _targetSelectionEnabled.value || _hoverAssistEnabled.value)
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
    visionCoordinator.setEnabled(_visionOverlayEnabled.value || enabled || _hoverAssistEnabled.value)
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
      inputMixer.neutralizeSource(InputSourceId.VISION_SERVO)
      pushMixedSticks()
    }
  }

  private fun startVisionTrackingLoop() {
    trackingLoopJob?.cancel()
    trackingLoopJob =
      viewModelScope.launch {
        var visionServoActive = false
        while (true) {
          delay(50L)
          val task = _activeTargetTask.value
          val atcTouchActive =
            _atcLeftRuntime.value.activePointerId != null || _atcRightRuntime.value.activePointerId != null
          val blockedByManualOrAuto =
            manualStickActive ||
              atcTouchActive ||
              gamepadAxesActive ||
              keyboardAxesActive ||
              automationState.value.hasActiveAutomation
          if (blockedByManualOrAuto) {
            if (visionServoActive) {
              inputMixer.neutralizeSource(InputSourceId.VISION_SERVO)
              visionServoActive = false
              pushMixedSticks()
            }
            continue
          }

          if (task != null) {
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
                inputMixer.submit(
                  source = InputSourceId.VISION_SERVO,
                  axes =
                    mapOf(
                      FlightAxis.ROLL to cmd.roll,
                      FlightAxis.PITCH to cmd.pitch,
                      FlightAxis.YAW to cmd.yaw,
                    ),
                  active = true,
                  staleTimeoutMs = 350L,
                )
                visionServoActive = true
                pushMixedSticks()
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
                visionServoActive = false
              } else if (visionServoActive) {
                inputMixer.neutralizeSource(InputSourceId.VISION_SERVO)
                visionServoActive = false
                pushMixedSticks()
              }
            }
          } else if (_hoverAssistEnabled.value && flightState.value == FlightState.Flying) {
            val hoverOut =
              hoverController.update(
                enabled = true,
                result = visionResult.value,
                nowMs = System.currentTimeMillis(),
              )
            if (hoverOut.active) {
              inputMixer.submit(
                source = InputSourceId.VISION_SERVO,
                axes =
                  mapOf(
                    FlightAxis.ROLL to hoverOut.roll,
                    FlightAxis.PITCH to hoverOut.pitch,
                    FlightAxis.YAW to hoverOut.yaw,
                  ),
                active = true,
                staleTimeoutMs = 350L,
              )
              visionServoActive = true
              pushMixedSticks()
            } else if (visionServoActive) {
              inputMixer.neutralizeSource(InputSourceId.VISION_SERVO)
              visionServoActive = false
              pushMixedSticks()
            }
          } else if (visionServoActive) {
            inputMixer.neutralizeSource(InputSourceId.VISION_SERVO)
            visionServoActive = false
            pushMixedSticks()
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
    gravityWatchdogJob?.cancel()
    automationCoordinator.stopAll()
    keyboardHoldJob?.cancel()
    trackingLoopJob?.cancel()
    videoPipeline.detach()
    visionCoordinator.close()
    wifiController.releaseBinding()
    FlightLinkForegroundService.stop(getApplication())
  }
}
