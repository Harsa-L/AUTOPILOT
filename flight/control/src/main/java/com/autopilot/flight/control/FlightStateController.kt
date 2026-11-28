package com.autopilot.flight.control

import com.autopilot.core.common.AutopilotLog
import com.autopilot.core.common.SubsystemTag
import com.autopilot.core.common.VerificationStatus
import com.autopilot.drone.protocolapi.ConnectionConfig
import com.autopilot.drone.protocolapi.ConnectionState
import com.autopilot.drone.protocolapi.DroneCapabilities
import com.autopilot.drone.protocolapi.DroneProtocol
import com.autopilot.drone.protocolapi.DroneTelemetry
import com.autopilot.drone.protocolapi.FlightCommand
import com.autopilot.drone.protocolapi.ProtocolDescriptor
import com.autopilot.drone.protocolapi.ProtocolFamily
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Explicit safety-critical flight state machine.
 */
sealed interface FlightState {
  data object Disconnected : FlightState

  data object Discovering : FlightState

  data object Connecting : FlightState

  data object Connected : FlightState

  data object Ready : FlightState

  data object Armed : FlightState

  data object TakingOff : FlightState

  data object Flying : FlightState

  data object Landing : FlightState

  data object Emergency : FlightState

  data class ConnectionLost(val reason: String) : FlightState

  data class Fault(val reason: String) : FlightState

  val label: String
    get() =
      when (this) {
        Disconnected -> "DISCONNECTED"
        Discovering -> "DISCOVERING"
        Connecting -> "CONNECTING"
        Connected -> "CONNECTED"
        Ready -> "READY"
        Armed -> "ARMED"
        TakingOff -> "TAKING OFF"
        Flying -> "FLYING"
        Landing -> "LANDING"
        Emergency -> "EMERGENCY STOP"
        is ConnectionLost -> "LINK LOST"
        is Fault -> "FAULT"
      }
}

data class ControlSensitivityConfig(
  val deadzone: Float = 0.06f,
  val expo: Float = 0.35f,
  val maxThrottleCap: Float = 0.85f,
  val trimRoll: Float = 0f,
  val trimPitch: Float = 0f,
)

object InputNormalizer {
  fun normalizeAxis(raw: Float, deadzone: Float, expo: Float): Float {
    val clamped = raw.coerceIn(-1f, 1f)
    val mag = abs(clamped)
    if (mag < deadzone) return 0f
    val rescaled = ((mag - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
    val curved = (1f - expo) * rescaled + expo * rescaled.pow(3)
    return sign(clamped) * curved
  }

  fun normalizeSticks(
    rawRoll: Float,
    rawPitch: Float,
    rawThrottle: Float,
    rawYaw: Float,
    config: ControlSensitivityConfig,
    preShapedRollPitch: Boolean = false,
    preShapedThrottleYaw: Boolean = false,
  ): FlightCommand.StickAxes {
    val normRoll =
      if (preShapedRollPitch) rawRoll.coerceIn(-1f, 1f)
      else normalizeAxis(rawRoll, config.deadzone, config.expo)
    val normPitch =
      if (preShapedRollPitch) rawPitch.coerceIn(-1f, 1f)
      else normalizeAxis(rawPitch, config.deadzone, config.expo)
    val normThrottle =
      if (preShapedThrottleYaw) rawThrottle.coerceIn(-1f, 1f)
      else normalizeAxis(rawThrottle, config.deadzone, config.expo)
    val normYaw =
      if (preShapedThrottleYaw) rawYaw.coerceIn(-1f, 1f)
      else normalizeAxis(rawYaw, config.deadzone, config.expo)
    return FlightCommand.StickAxes(
      roll = normRoll,
      pitch = normPitch,
      throttle = (normThrottle * config.maxThrottleCap).coerceIn(-1f, 1f),
      yaw = normYaw,
      trimRoll = config.trimRoll.coerceIn(-0.25f, 0.25f),
      trimPitch = config.trimPitch.coerceIn(-0.25f, 0.25f),
    )
  }
}

/**
 * Safety-critical flight state machine and command validator.
 * Strictly separates ControlledLand, MotorStop, and EmergencyStop and refuses to
 * issue unsupported commands or fabricate sensor states.
 */
class FlightStateController(private val scope: CoroutineScope) {

  private val _flightState = MutableStateFlow<FlightState>(FlightState.Disconnected)
  val flightState: StateFlow<FlightState> = _flightState.asStateFlow()

  private val _telemetry = MutableStateFlow<DroneTelemetry?>(null)
  val telemetry: StateFlow<DroneTelemetry?> = _telemetry.asStateFlow()

  private val _safetyNotice = MutableStateFlow<String?>(null)
  val safetyNotice: StateFlow<String?> = _safetyNotice.asStateFlow()

  private val _sensitivity = MutableStateFlow(ControlSensitivityConfig())
  val sensitivity: StateFlow<ControlSensitivityConfig> = _sensitivity.asStateFlow()

  private val _speedTier = MutableStateFlow(1)
  val speedTier: StateFlow<Int> = _speedTier.asStateFlow()

  private val _fastFly = MutableStateFlow(false)
  val fastFly: StateFlow<Boolean> = _fastFly.asStateFlow()

  private val _headlessMode = MutableStateFlow(false)
  val headlessMode: StateFlow<Boolean> = _headlessMode.asStateFlow()

  private val _cameraTiltStep = MutableStateFlow(0)
  val cameraTiltStep: StateFlow<Int> = _cameraTiltStep.asStateFlow()

  private val _fixedHeightMode = MutableStateFlow(false)
  val fixedHeightMode: StateFlow<Boolean> = _fixedHeightMode.asStateFlow()

  private val _linkRxVerified = MutableStateFlow(false)
  val linkRxVerified: StateFlow<Boolean> = _linkRxVerified.asStateFlow()

  @Volatile var allowExperimentalFlightCommands: Boolean = true

  private var activeProtocol: DroneProtocol? = null
  private var stateJob: Job? = null
  private var telemetryJob: Job? = null
  private var watchdogJob: Job? = null
  private var lastRxSeenAtMs: Long = 0L
  private var connectedAtMs: Long = 0L
  private var rxSilenceWarned: Boolean = false

  val currentDescriptor: ProtocolDescriptor?
    get() = activeProtocol?.descriptor

  val currentCapabilities: DroneCapabilities?
    get() = activeProtocol?.capabilities

  fun updateSensitivity(config: ControlSensitivityConfig) {
    _sensitivity.value = config
  }

  fun markDiscovering() {
    if (_flightState.value == FlightState.Disconnected) {
      _flightState.value = FlightState.Discovering
    }
  }

  suspend fun connectProtocol(protocol: DroneProtocol, config: ConnectionConfig) {
    disconnect()
    activeProtocol = protocol
    _safetyNotice.value = null
    _flightState.value = FlightState.Connecting

    stateJob =
      protocol.connectionState
        .onEach { conn ->
          when (conn) {
            ConnectionState.Disconnected -> _flightState.value = FlightState.Disconnected
            ConnectionState.Discovering -> _flightState.value = FlightState.Discovering
            is ConnectionState.Connecting -> _flightState.value = FlightState.Connecting
            is ConnectionState.Connected -> {
              val now = System.currentTimeMillis()
              lastRxSeenAtMs = now
              connectedAtMs = now
              rxSilenceWarned = false
              _linkRxVerified.value = protocol.descriptor.isSyntheticHarness
              _flightState.value = FlightState.Ready
            }
            is ConnectionState.Reconnecting -> {
              _safetyNotice.value = "Reconnecting (attempt ${conn.attempt}): ${conn.reason}"
            }
            is ConnectionState.ConnectionLost -> {
              _flightState.value = FlightState.ConnectionLost(conn.reason)
              _safetyNotice.value =
                "Radio/Wi-Fi link lost: software cannot force recovery on unresponsive firmware."
            }
            is ConnectionState.Error -> {
              _flightState.value = FlightState.Fault(conn.message)
              _safetyNotice.value = conn.message
            }
          }
        }
        .launchIn(scope)

    telemetryJob =
      protocol.telemetry()
        .onEach { t ->
          _telemetry.value = t
          if (t.packetsReceived > 0L) {
            lastRxSeenAtMs = t.timestampMs
            _linkRxVerified.value = true
          }
        }
        .launchIn(scope)

    startWatchdog(protocol)
    protocol.connect(config)
  }

  suspend fun disconnect() {
    watchdogJob?.cancel()
    stateJob?.cancel()
    telemetryJob?.cancel()
    activeProtocol?.let { proto ->
      runCatching {
        proto.setServoProbe(false, 9, 0)
        proto.setControlArmed(false)
        proto.sendCommand(FlightCommand.StickAxes(0f, 0f, 0f, 0f))
        proto.disconnect()
      }
    }
    activeProtocol = null
    _telemetry.value = null
    _headlessMode.value = false
    _fastFly.value = false
    _fixedHeightMode.value = false
    _linkRxVerified.value = false
    _cameraTiltStep.value = 0
    _flightState.value = FlightState.Disconnected
  }

  private fun isBlockedByExperimentalGate(proto: DroneProtocol): Boolean {
    if (proto.descriptor.verificationStatus == VerificationStatus.EXPERIMENTAL &&
      !allowExperimentalFlightCommands
    ) {
      _safetyNotice.value =
        "${proto.descriptor.displayName} is experimental — enable 'Allow Experimental Flight Commands' in Settings > Aircraft & Link to arm or fly."
      return true
    }
    return false
  }

  fun arm(): Boolean {
    val proto = activeProtocol
    if (proto != null && proto.capabilities.isVideoOnly) {
      _safetyNotice.value = "${proto.descriptor.displayName} is video-only; flight commands disabled."
      return false
    }
    if (proto != null && isBlockedByExperimentalGate(proto)) {
      return false
    }
    val state = _flightState.value
    if (state != FlightState.Ready && state != FlightState.Connected) {
      _safetyNotice.value = "Cannot arm from state ${state.label}."
      return false
    }
    proto?.setControlArmed(true)
    _flightState.value = FlightState.Armed
    if (proto != null && proto.capabilities.supportsUnlockCommand) {
      scope.launch { proto.sendCommand(FlightCommand.UnlockMotors) }
      _safetyNotice.value = "Motors unlocked & armed. Verify clear airspace."
    } else {
      _safetyNotice.value = "Motors armed. Verify clear airspace before takeoff."
    }
    AutopilotLog.info(SubsystemTag.FLIGHT_SAFETY, "FlightState -> ARMED")
    return true
  }

  suspend fun unlockMotors(): Boolean {
    val proto = activeProtocol ?: return false
    if (proto.capabilities.isVideoOnly) {
      _safetyNotice.value = "${proto.descriptor.displayName} is video-only; flight commands disabled."
      return false
    }
    if (isBlockedByExperimentalGate(proto)) {
      return false
    }
    if (!proto.capabilities.supportsUnlockCommand) {
      _safetyNotice.value = "${proto.descriptor.displayName} does not support motor unlock command."
      return false
    }
    proto.setControlArmed(true)
    proto.sendCommand(FlightCommand.UnlockMotors)
    if (_flightState.value == FlightState.Ready || _flightState.value == FlightState.Connected) {
      _flightState.value = FlightState.Armed
    }
    _safetyNotice.value = "Protocol motor unlock pulse transmitted."
    AutopilotLog.info(SubsystemTag.FLIGHT_SAFETY, "UnlockMotors transmitted on ${proto.descriptor.id}")
    return true
  }

  fun disarm() {
    activeProtocol?.setServoProbe(false, 9, 0)
    activeProtocol?.setControlArmed(false)
    val state = _flightState.value
    if (state == FlightState.Armed || state == FlightState.Emergency) {
      _flightState.value = FlightState.Ready
      _safetyNotice.value = null
    }
  }

  suspend fun sendStickInput(
    roll: Float,
    pitch: Float,
    throttle: Float,
    yaw: Float,
    preShapedRollPitch: Boolean = false,
    preShapedThrottleYaw: Boolean = false,
  ) {
    val proto = activeProtocol ?: return
    if (proto.capabilities.isVideoOnly) return
    val state = _flightState.value
    if (state is FlightState.Emergency ||
      state is FlightState.ConnectionLost ||
      state is FlightState.Fault ||
      state == FlightState.Disconnected
    ) {
      return
    }
    val normalized =
      InputNormalizer.normalizeSticks(
        rawRoll = roll,
        rawPitch = pitch,
        rawThrottle = throttle,
        rawYaw = yaw,
        config = _sensitivity.value,
        preShapedRollPitch = preShapedRollPitch,
        preShapedThrottleYaw = preShapedThrottleYaw,
      )
    proto.sendCommand(normalized)
  }

  suspend fun takeoff(): Boolean {
    val proto = activeProtocol ?: return false
    if (proto.capabilities.isVideoOnly || !proto.capabilities.supportsTakeoff) {
      _safetyNotice.value =
        if (proto.capabilities.isVideoOnly) {
          "${proto.descriptor.displayName} is video-only; flight commands disabled."
        } else {
          "${proto.descriptor.displayName} does not support takeoff command."
        }
      return false
    }
    if (isBlockedByExperimentalGate(proto)) return false
    if (_flightState.value != FlightState.Armed) {
      _safetyNotice.value = "Arm aircraft before commanding takeoff."
      return false
    }
    _flightState.value = FlightState.TakingOff
    proto.sendCommand(FlightCommand.Takeoff)
    AutopilotLog.info(SubsystemTag.FLIGHT_SAFETY, "Commanded TAKEOFF on ${proto.descriptor.id}")
    delay(600L)
    if (_flightState.value == FlightState.TakingOff) {
      _flightState.value = FlightState.Flying
    }
    return true
  }

  suspend fun flip360(direction: Int = 0): Boolean {
    val proto = activeProtocol ?: return false
    if (proto.capabilities.isVideoOnly || !proto.capabilities.supportsFlip) {
      _safetyNotice.value = "${proto.descriptor.displayName} does not support 360° flip stunts."
      return false
    }
    if (isBlockedByExperimentalGate(proto)) return false
    val state = _flightState.value
    if (state != FlightState.Armed && state != FlightState.Flying) {
      _safetyNotice.value = "Arm or fly aircraft before commanding 360° flip."
      return false
    }
    proto.sendCommand(FlightCommand.Flip360(direction))
    val dirLabel =
      when (direction) {
        1 -> "Forward "
        2 -> "Backward "
        3 -> "Left "
        4 -> "Right "
        else -> ""
      }
    _safetyNotice.value = "${dirLabel}360° flip transmitted."
    AutopilotLog.info(SubsystemTag.FLIGHT_SAFETY, "Commanded FLIP_360($direction) on ${proto.descriptor.id}")
    return true
  }

  suspend fun controlledLand(softDescentAssist: Boolean = false): Boolean {
    val proto = activeProtocol ?: return false
    if (proto.capabilities.isVideoOnly || !proto.capabilities.supportsControlledLanding) {
      _safetyNotice.value =
        if (proto.capabilities.isVideoOnly) {
          "${proto.descriptor.displayName} is video-only; flight commands disabled."
        } else {
          "${proto.descriptor.displayName} lacks verified controlled landing."
        }
      return false
    }
    if (isBlockedByExperimentalGate(proto)) return false
    _flightState.value = FlightState.Landing
    proto.sendCommand(FlightCommand.ControlledLand)
    AutopilotLog.info(SubsystemTag.FLIGHT_SAFETY, "Commanded CONTROLLED_LAND on ${proto.descriptor.id}")
    if (softDescentAssist) {
      val cfg = _sensitivity.value
      val trimR = cfg.trimRoll.coerceIn(-0.25f, 0.25f)
      val trimP = cfg.trimPitch.coerceIn(-0.25f, 0.25f)
      val steps = floatArrayOf(-0.22f, -0.34f, -0.45f, -0.58f)
      for (thr in steps) {
        if (_flightState.value != FlightState.Landing) break
        proto.sendCommand(FlightCommand.StickAxes(0f, 0f, thr, 0f, trimR, trimP))
        delay(175L)
      }
      if (_flightState.value == FlightState.Landing) {
        proto.sendCommand(FlightCommand.StickAxes(0f, 0f, 0f, 0f, trimR, trimP))
      }
    } else {
      delay(800L)
    }
    if (_flightState.value == FlightState.Landing) {
      _flightState.value = FlightState.Ready
    }
    return true
  }

  suspend fun triggerFirmwarePhoto() {
    val proto = activeProtocol ?: return
    if (proto.capabilities.supportsFirmwareSdCapture) {
      proto.sendCommand(FlightCommand.TriggerFirmwarePhoto)
    }
  }

  suspend fun setFirmwareRecord(active: Boolean) {
    val proto = activeProtocol ?: return
    if (proto.capabilities.supportsFirmwareSdCapture) {
      proto.sendCommand(FlightCommand.TriggerFirmwareRecord(active))
    }
  }

  suspend fun switchCamera(cameraIndex: Int): Boolean {
    val proto = activeProtocol ?: return false
    if (!proto.capabilities.supportsCameraSwitch) {
      _safetyNotice.value = "${proto.descriptor.displayName} does not support camera switching."
      return false
    }
    proto.sendCommand(FlightCommand.SwitchCamera(cameraIndex))
    _safetyNotice.value = "Switched to camera $cameraIndex."
    return true
  }

  suspend fun motorStop(): Boolean {
    val proto = activeProtocol ?: return false
    if (proto.capabilities.isVideoOnly || !proto.capabilities.supportsMotorStop) {
      _safetyNotice.value = "${proto.descriptor.displayName} does not support MotorStop."
      return false
    }
    proto.sendCommand(FlightCommand.StickAxes(0f, 0f, 0f, 0f))
    proto.sendCommand(FlightCommand.MotorStop)
    proto.setControlArmed(false)
    _flightState.value = FlightState.Ready
    _safetyNotice.value = "Motor stop transmitted."
    AutopilotLog.warn(SubsystemTag.FLIGHT_SAFETY, "Commanded MOTOR_STOP")
    return true
  }

  suspend fun emergencyStop() {
    val proto = activeProtocol
    if (proto != null && proto.capabilities.isVideoOnly) {
      _safetyNotice.value = "${proto.descriptor.displayName} is video-only; flight commands disabled."
      return
    }
    _flightState.value = FlightState.Emergency
    _safetyNotice.value =
      "EMERGENCY STOP TRIGGERED — Immediate motor cutoff sent; aircraft may free-fall."
    AutopilotLog.error(SubsystemTag.FLIGHT_SAFETY, "EMERGENCY_STOP activated by operator")
    if (proto != null && proto.capabilities.supportsEmergencyMotorStop) {
      proto.sendCommand(FlightCommand.StickAxes(0f, 0f, 0f, 0f))
      proto.sendCommand(FlightCommand.EmergencyStop)
    }
    proto?.setControlArmed(false)
  }

  suspend fun calibrateGyro() {
    val proto = activeProtocol ?: return
    if (proto.capabilities.isVideoOnly || !proto.capabilities.supportsGyroCalibration) {
      _safetyNotice.value = "Gyro calibration unsupported by ${proto.descriptor.displayName}."
      return
    }
    proto.sendCommand(FlightCommand.CalibrateGyro)
    _safetyNotice.value = "Gyro calibration pulse sent (keep aircraft level)."
  }

  suspend fun setSpeedTier(tier: Int) {
    val clamped = tier.coerceIn(0, 2)
    _speedTier.value = clamped
    val proto = activeProtocol ?: return
    if (!proto.capabilities.supportsSpeedTiers) return
    proto.sendCommand(FlightCommand.SetSpeedTier(clamped))
    if (proto.capabilities.supportsFastFly) {
      val fast = clamped == 2
      _fastFly.value = fast
      proto.sendCommand(FlightCommand.SetFastFly(fast))
    }
  }

  suspend fun setFastFly(enabled: Boolean) {
    val proto = activeProtocol
    if (proto != null && !proto.capabilities.supportsFastFly) return
    _fastFly.value = enabled
    if (enabled) {
      _speedTier.value = 2
    } else if (_speedTier.value == 2) {
      _speedTier.value = 1
    }
    proto?.sendCommand(FlightCommand.SetFastFly(enabled))
    proto?.sendCommand(FlightCommand.SetSpeedTier(_speedTier.value))
  }

  suspend fun setHeadlessMode(enabled: Boolean) {
    val proto = activeProtocol
    if (proto != null && !proto.capabilities.supportsHeadlessMode) return
    _headlessMode.value = enabled
    proto?.sendCommand(FlightCommand.SetHeadlessMode(enabled))
  }

  suspend fun tiltCamera(delta: Int) {
    val clamped = delta.coerceIn(-1, 1)
    _cameraTiltStep.value = clamped
    val proto = activeProtocol ?: return
    if (!proto.capabilities.supportsCameraTilt) return
    proto.sendCommand(FlightCommand.CameraTilt(clamped))
  }

  suspend fun toggleLight() {
    val proto = activeProtocol ?: return
    if (!proto.capabilities.supportsLightControl) return
    proto.sendCommand(FlightCommand.ToggleLight)
    _safetyNotice.value = "Navigation light toggle pulse transmitted."
  }

  suspend fun setFixedHeightMode(enabled: Boolean) {
    val proto = activeProtocol ?: return
    if (!proto.capabilities.supportsFixedHeightFlag) return
    _fixedHeightMode.value = enabled
    proto.sendCommand(FlightCommand.SetFixedHeightMode(enabled))
  }

  fun setServoProbe(enabled: Boolean, byteIndex: Int, hexValue: Int) {
    val proto = activeProtocol ?: return
    proto.setControlArmed(enabled)
    proto.setServoProbe(enabled, byteIndex, hexValue)
  }

  fun sendCooingdvPassword(digits8: String): Boolean {
    val proto = activeProtocol ?: return false
    return proto.sendCooingdvPassword(digits8)
  }

  fun readDiagSummary(): String = activeProtocol?.readDiagSummary().orEmpty()

  private fun expectsInboundPackets(descriptor: ProtocolDescriptor): Boolean =
    !descriptor.isSyntheticHarness &&
      when (descriptor.family) {
        ProtocolFamily.JIELI_AC79,
        ProtocolFamily.LEWEI,
        ProtocolFamily.COOINGDV,
        ProtocolFamily.COOINGDV_GL,
        ProtocolFamily.COOINGDV_JIELI,
        ProtocolFamily.FLOW_BL60X,
        ProtocolFamily.X69_LG -> true
        else -> false
      }

  private fun startWatchdog(protocol: DroneProtocol) {
    watchdogJob?.cancel()
    watchdogJob =
      scope.launch {
        while (true) {
          delay(500L)
          val state = _flightState.value
          val t = _telemetry.value
          val now = System.currentTimeMillis()
          if (state == FlightState.Ready &&
            !rxSilenceWarned &&
            expectsInboundPackets(protocol.descriptor) &&
            connectedAtMs > 0L &&
            (now - connectedAtMs) >= 5000L &&
            (t?.packetsReceived ?: 0L) == 0L
          ) {
            rxSilenceWarned = true
            _safetyNotice.value =
              "Link active with 0 RX datagrams — verify aircraft power, Wi-Fi subnet, and port."
          }

          if (state == FlightState.Flying || state == FlightState.Armed || state == FlightState.TakingOff) {
            val errno = t?.lastSocketErrno ?: 0
            if (!protocol.descriptor.isSyntheticHarness &&
              (errno == 1 || errno == 101 || errno == 113)
            ) {
              protocol.setControlArmed(false)
              _flightState.value = FlightState.ConnectionLost("Socket send error (errno=$errno)")
              _safetyNotice.value =
                "Watchdog: Wi-Fi socket unreachable (errno=$errno). Controls disarmed; verify drone AP."
              protocol.sendCommand(FlightCommand.StickAxes(0f, 0f, 0f, 0f))
              continue
            }
            val age = now - lastRxSeenAtMs
            // For real hardware sessions with inbound packets previously seen, warn after 4s silence.
            if (!protocol.descriptor.isSyntheticHarness &&
              (t?.packetsReceived ?: 0L) > 0L &&
              age > 4000L
            ) {
              protocol.setControlArmed(false)
              _flightState.value = FlightState.ConnectionLost("Telemetry timeout (${age}ms)")
              _safetyNotice.value =
                "Watchdog: no RX datagrams for ${age}ms. Controls centered; verify drone link."
              protocol.sendCommand(FlightCommand.StickAxes(0f, 0f, 0f, 0f))
            }
          }
        }
      }
  }
}
