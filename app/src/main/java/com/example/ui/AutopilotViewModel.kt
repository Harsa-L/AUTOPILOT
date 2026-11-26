package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autopilot.core.common.AutopilotLog
import com.autopilot.core.common.DefaultAutopilotDispatchers
import com.autopilot.core.common.DiagnosticLogEntry
import com.autopilot.core.common.SubsystemTag
import com.autopilot.designsystem.ContextualEffects
import com.autopilot.designsystem.ThemeAppearanceMode
import com.autopilot.designsystem.ThemeManager
import com.autopilot.designsystem.ThemeRuntimeState
import com.autopilot.designsystem.ThemeSchemaValidator
import com.autopilot.designsystem.ThemeValidationResult
import com.autopilot.drone.protocolapi.ConnectionConfig
import com.autopilot.drone.protocolapi.DroneCapabilities
import com.autopilot.drone.protocolapi.DroneTelemetry
import com.autopilot.drone.protocolapi.ProtocolCompatibilityRegistry
import com.autopilot.drone.protocolapi.ProtocolDescriptor
import com.autopilot.drone.protocolapi.ProtocolRegistryEntry
import com.autopilot.drone.protocols.NativeDroneProtocolAdapter
import com.autopilot.drone.protocols.ProtocolPacketEncoder
import com.autopilot.drone.protocols.WifiConnectivityController
import com.autopilot.drone.protocols.WifiLinkSnapshot
import com.autopilot.flight.control.ControlSensitivityConfig
import com.autopilot.flight.control.FlightState
import com.autopilot.flight.control.FlightStateController
import com.autopilot.video.VideoPipelineController
import com.autopilot.video.VideoStreamState
import com.autopilot.vision.DetectorAvailability
import com.autopilot.vision.VisionFrameResult
import com.autopilot.vision.VisionPipelineCoordinator
import com.example.data.AutopilotDatabase
import com.example.data.AutopilotRepository
import com.example.data.AutopilotUserPrefs
import com.example.data.CustomThemeEntity
import com.example.data.DiagnosticTraceEntity
import com.example.data.FlightSessionEntity
import com.example.data.UserPreferencesStore
import com.example.service.FlightLinkForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
  val videoState: StateFlow<VideoStreamState> = videoPipeline.streamState
  val visionResult: StateFlow<VisionFrameResult?> = visionCoordinator.latestResult
  val detectorAvailability: StateFlow<DetectorAvailability> = visionCoordinator.availability
  val liveLogs: StateFlow<List<DiagnosticLogEntry>> = AutopilotLog.entries

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

  val customThemes: StateFlow<List<CustomThemeEntity>> =
    repository.customThemes.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = emptyList(),
    )

  val productionProtocols: List<ProtocolRegistryEntry> =
    ProtocolCompatibilityRegistry.productionEntries()

  val isolatedHarnessEntry: ProtocolRegistryEntry =
    ProtocolCompatibilityRegistry.isolatedTestHarness

  private val _selectedEntry =
    MutableStateFlow(ProtocolCompatibilityRegistry.productionEntries().first())
  val selectedEntry: StateFlow<ProtocolRegistryEntry> = _selectedEntry.asStateFlow()

  private val _customHostIp = MutableStateFlow(_selectedEntry.value.descriptor.defaultHostIp)
  val customHostIp: StateFlow<String> = _customHostIp.asStateFlow()

  private val _visionOverlayEnabled = MutableStateFlow(false)
  val visionOverlayEnabled: StateFlow<Boolean> = _visionOverlayEnabled.asStateFlow()

  private val _themeValidationFeedback = MutableStateFlow<ThemeValidationResult?>(null)
  val themeValidationFeedback: StateFlow<ThemeValidationResult?> =
    _themeValidationFeedback.asStateFlow()

  private var activeSessionRowId: Long? = null
  private var leftStickX = 0f
  private var leftStickY = -0.6f
  private var rightStickX = 0f
  private var rightStickY = 0f

  init {
    wifiController.refreshCurrentWifiState()
    visionCoordinator.initializeAsync()

    viewModelScope.launch {
      val prefs = prefsStore.preferences.first()
      selectProtocol(prefs.selectedProtocolId, persist = false)
      flightController.updateSensitivity(
        ControlSensitivityConfig(
          deadzone = prefs.stickDeadzone,
          expo = prefs.stickExpo,
          maxThrottleCap = prefs.maxThrottleCap,
        )
      )
      _visionOverlayEnabled.value = prefs.visionOverlayEnabled
      visionCoordinator.setEnabled(prefs.visionOverlayEnabled)
      visionCoordinator.updateCalibration(prefs.calibrationFocalLengthPx, prefs.calibrationVerified)
      themeManager.selectTheme(prefs.activeThemeId)
      themeManager.setAppearanceMode(prefs.appearanceMode)
      themeManager.setContextualEffects(
        ContextualEffects(thermalDegradeBlur = prefs.thermalDegradeBlur)
      )
    }

    viewModelScope.launch {
      repository.customThemes.collect { savedThemes ->
        savedThemes.forEach { entity ->
          val res = ThemeSchemaValidator.parseAndValidate(entity.jsonPayload)
          val doc = res.document
          if (res.isValid && doc != null) {
            themeManager.registerCustomTheme(doc, selectImmediately = false)
          }
        }
      }
    }
  }

  fun selectProtocol(protocolId: String, persist: Boolean = true) {
    val entry =
      ProtocolCompatibilityRegistry.findById(protocolId, includeSyntheticHarness = true) ?: return
    _selectedEntry.value = entry
    _customHostIp.value = entry.descriptor.defaultHostIp
    if (persist && !entry.descriptor.isSyntheticHarness) {
      viewModelScope.launch { prefsStore.updateSelectedProtocol(protocolId) }
    }
  }

  fun updateHostIp(ip: String) {
    _customHostIp.value = ip
  }

  fun refreshWifi() {
    wifiController.refreshCurrentWifiState()
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
            message = "Bound Wi-Fi AP network handle=$handle ($hint*)",
          )
        }
      }
    }
  }

  fun connectSelectedProtocol(useIsolatedSyntheticHarness: Boolean = false) {
    val entry =
      if (useIsolatedSyntheticHarness) isolatedHarnessEntry else _selectedEntry.value
    _selectedEntry.value = entry
    val desc = entry.descriptor
    val host = if (useIsolatedSyntheticHarness) "127.0.0.1" else _customHostIp.value.trim()
    val wifiHandle = if (useIsolatedSyntheticHarness) null else wifiLink.value.boundNetworkHandle

    val adapter = NativeDroneProtocolAdapter(entry, dispatchers)
    val config =
      ConnectionConfig(
        hostIp = host.ifEmpty { desc.defaultHostIp },
        controlPort = desc.controlPort,
        videoPort = desc.videoPort,
        heartbeatPort = desc.heartbeatPort,
        rtspPath = desc.rtspPath,
        ssid = wifiLink.value.ssid,
        networkHandle = wifiHandle,
        allowSyntheticHarness = useIsolatedSyntheticHarness,
      )

    viewModelScope.launch {
      activeSessionRowId =
        repository.startFlightSession(
          protocolId = desc.id,
          protocolName = desc.displayName,
          hostIp = config.hostIp,
          isSyntheticHarness = useIsolatedSyntheticHarness,
        )
      repository.recordTrace(
        protocolId = desc.id,
        subsystem = "PROTOCOL",
        severity = if (useIsolatedSyntheticHarness) "WARN" else "INFO",
        message =
          if (useIsolatedSyntheticHarness) {
            "Started ISOLATED SYNTHETIC TEST HARNESS (zero radio output)"
          } else {
            "Connecting ${desc.id} -> ${config.hostIp}:${config.controlPort}"
          },
      )
      FlightLinkForegroundService.start(getApplication(), desc.displayName)
      flightController.connectProtocol(adapter, config)
      videoPipeline.attachToProtocol(adapter) { bitmap, isSynthetic ->
        if (_visionOverlayEnabled.value) {
          visionCoordinator.submitFrame(bitmap, isSynthetic)
        }
      }
      themeManager.setContextualEffects(
        themeManager.state.value.contextualEffects.copy(flightModeActive = true)
      )
    }
  }

  fun disconnect() {
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

  fun disarmAircraft() {
    flightController.disarm()
  }

  fun commandTakeoff() {
    viewModelScope.launch { flightController.takeoff() }
  }

  fun commandControlledLand() {
    viewModelScope.launch { flightController.controlledLand() }
  }

  fun commandMotorStop() {
    viewModelScope.launch { flightController.motorStop() }
  }

  fun commandEmergencyStop() {
    viewModelScope.launch {
      flightController.emergencyStop()
      repository.recordTrace(
        protocolId = _selectedEntry.value.descriptor.id,
        subsystem = "FLIGHT_SAFETY",
        severity = "ERROR",
        message = "Operator activated EMERGENCY_STOP",
        packetHex = telemetry.value?.lastCommandHex,
      )
    }
  }

  fun commandCalibrateGyro() {
    viewModelScope.launch { flightController.calibrateGyro() }
  }

  fun setSpeedTier(tier: Int) {
    viewModelScope.launch { flightController.setSpeedTier(tier) }
  }

  fun tiltCamera(delta: Int) {
    viewModelScope.launch { flightController.tiltCamera(delta) }
  }

  fun updateSensitivity(deadzone: Float, expo: Float, maxThrottleCap: Float) {
    val cfg =
      ControlSensitivityConfig(
        deadzone = deadzone,
        expo = expo,
        maxThrottleCap = maxThrottleCap,
      )
    flightController.updateSensitivity(cfg)
    viewModelScope.launch { prefsStore.updateFlightControls(deadzone, expo, maxThrottleCap) }
  }

  fun toggleVisionOverlay(enabled: Boolean) {
    _visionOverlayEnabled.value = enabled
    visionCoordinator.setEnabled(enabled)
    val prefs = userPrefs.value
    viewModelScope.launch {
      prefsStore.updateVisionSettings(
        overlayEnabled = enabled,
        preferGpu = prefs.preferGpuLiteRt,
        focalLengthPx = prefs.calibrationFocalLengthPx,
        calibrated = prefs.calibrationVerified,
      )
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
          message =
            if (ok) "Imported custom YOLO26n .tflite model into noBackupFilesDir"
            else "Failed to import YOLO26n .tflite model",
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

  fun validateAndSaveCustomTheme(rawJson: String) {
    val result = ThemeSchemaValidator.parseAndValidate(rawJson)
    _themeValidationFeedback.value = result
    val doc = result.document
    if (result.isValid && doc != null) {
      themeManager.registerCustomTheme(doc, selectImmediately = true)
      val mode =
        when (doc.appearance) {
          "light" -> ThemeAppearanceMode.LIGHT
          "dark" -> ThemeAppearanceMode.DARK
          else -> ThemeAppearanceMode.SYSTEM
        }
      viewModelScope.launch {
        repository.saveCustomTheme(
          CustomThemeEntity(
            id = doc.id,
            name = doc.name,
            appearance = doc.appearance,
            jsonPayload = ThemeSchemaValidator.encode(doc),
            updatedAtMs = System.currentTimeMillis(),
          )
        )
        prefsStore.updateTheme(doc.id, mode)
      }
    }
  }

  fun previewPacketHex(
    descriptor: ProtocolDescriptor,
    roll: Float,
    pitch: Float,
    throttle: Float,
    yaw: Float,
  ): String {
    val bytes =
      ProtocolPacketEncoder.encodeControlPacket(
        family = descriptor.family,
        roll = roll,
        pitch = pitch,
        throttle = throttle,
        yaw = yaw,
      )
    return com.autopilot.core.common.ByteFraming.toHexString(bytes)
  }

  fun activeCapabilities(): DroneCapabilities = _selectedEntry.value.capabilities

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
    videoPipeline.detach()
    visionCoordinator.close()
    wifiController.releaseBinding()
    FlightLinkForegroundService.stop(getApplication())
  }
}
