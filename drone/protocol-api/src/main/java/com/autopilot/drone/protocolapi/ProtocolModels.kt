package com.autopilot.drone.protocolapi

import com.autopilot.core.common.ImplementationStatus
import com.autopilot.core.common.VerificationStatus
import kotlinx.serialization.Serializable

@Serializable
enum class ProtocolFamily(val wireId: Int) {
  S2X(1),
  WIFI_UAV(2),
  COOINGDV(3),
  COOINGDV_JIELI(4),
  WIFI_CAM(5),
  X69_LG(6),
  FLOW_BL60X(7),
  COOINGDV_GL(8),
  FLOW_KY_Y_SERIES(9),
  JIELI_AC79(10),
  LEWEI(11),
  ISOLATED_TEST_HARNESS(99),
}

@Serializable
enum class VideoTransportType {
  NONE,
  RTSP_H264,
  RTSP_H265,
  RTSP_MJPEG,
  RTP_JPEG,
  RAW_UDP_JPEG,
  RAW_UDP_H264,
  RAW_UDP_H265,
  LEWEI_TCP_H264,
  SYNTHETIC_TEST_PATTERN,
}

@Serializable
enum class TerminationSemantics {
  CONTROLLED_LANDING,
  MOTOR_STOP,
  EMERGENCY_STOP,
  UNVERIFIED_TERMINATION,
}

@Serializable
enum class FlipDirection(val wireId: Int, val label: String) {
  DEFAULT(0, "Flip"),
  FORWARD(1, "Fwd"),
  BACKWARD(2, "Back"),
  LEFT(3, "Left"),
  RIGHT(4, "Right"),
}

@Serializable
data class DroneCapabilities(
  val supportsTakeoff: Boolean,
  val supportsControlledLanding: Boolean,
  val supportsMotorStop: Boolean,
  val supportsEmergencyMotorStop: Boolean,
  val supportsGyroCalibration: Boolean,
  val supportsHeadlessMode: Boolean,
  val supportsAltitudeHold: Boolean,
  val supportsSpeedTiers: Boolean,
  val supportsCameraTilt: Boolean,
  val supportsPhotoCapture: Boolean,
  val supportsVideoStream: Boolean,
  val reportsBatteryPercent: Boolean,
  val reportsSignalRssi: Boolean,
  val reportsAltitudeMeters: Boolean,
  val reportsGps: Boolean,
  val primaryLandingSemantics: TerminationSemantics,
  val supportsFlip: Boolean = false,
  val supportsUnlockCommand: Boolean = false,
  val supportsFastFly: Boolean = false,
  val supportsVideoRecording: Boolean = false,
  val supportsFirmwareSdCapture: Boolean = false,
  val supportsCameraSwitch: Boolean = false,
  val isVideoOnly: Boolean = false,
  val supportsTrajectoryExecution: Boolean = false,
  val supportedVideoModes: List<VideoTransportType> = emptyList(),
  val supportsLightControl: Boolean = false,
  val supportsCooingdvPassword: Boolean = false,
  val supportsFixedHeightFlag: Boolean = false,
) {
  val supportsAnyFlightControl: Boolean
    get() =
      !isVideoOnly &&
        (supportsTakeoff ||
          supportsControlledLanding ||
          supportsMotorStop ||
          supportsEmergencyMotorStop ||
          supportsUnlockCommand)
}

@Serializable
data class ProtocolDescriptor(
  val id: String,
  val family: ProtocolFamily,
  val displayName: String,
  val companionAppFamily: String,
  val defaultHostIp: String,
  val controlPort: Int,
  val videoPort: Int,
  val heartbeatPort: Int?,
  val rtspPath: String?,
  val controlRateHz: Int,
  val videoTransport: VideoTransportType,
  val verificationStatus: VerificationStatus,
  val implementationStatus: ImplementationStatus,
  val knownModels: List<String>,
  val ssidHints: List<String>,
  val knownLimitations: List<String>,
  val provenanceNote: String,
  val isSyntheticHarness: Boolean = false,
  val alternateHostIps: List<String> = emptyList(),
  val defaultAutoRotationDeg: Int = 0,
) {
  fun resolvedVideoPort(transport: VideoTransportType, customPort: Int = 0): Int {
    if (customPort in 1..65535) return customPort
    if (family == ProtocolFamily.X69_LG && transport == VideoTransportType.RAW_UDP_JPEG) {
      return 7080
    }
    if (family == ProtocolFamily.X69_LG &&
      (transport == VideoTransportType.RTSP_H265 || transport == VideoTransportType.RTSP_H264)
    ) {
      return 554
    }
    return videoPort
  }

  fun resolvedRtspPath(customPath: String = ""): String? =
    customPath.trim().takeIf { it.isNotEmpty() } ?: rtspPath
}

data class ConnectionConfig(
  val hostIp: String,
  val controlPort: Int,
  val videoPort: Int,
  val heartbeatPort: Int?,
  val rtspPath: String? = null,
  val ssid: String? = null,
  val networkHandle: Long? = null,
  val allowSyntheticHarness: Boolean = false,
  val videoTransportOverride: VideoTransportType? = null,
  val rtspAllowUdpFallback: Boolean = true,
  val preferRtspUdp: Boolean = false,
)

sealed interface ConnectionState {
  data object Disconnected : ConnectionState

  data object Discovering : ConnectionState

  data class Connecting(val targetHost: String) : ConnectionState

  data class Connected(val targetHost: String, val connectedAtMs: Long) : ConnectionState

  data class Reconnecting(val attempt: Int, val reason: String) : ConnectionState

  data class ConnectionLost(val reason: String) : ConnectionState

  data class Error(val message: String) : ConnectionState
}

sealed interface FlightCommand {
  data class StickAxes(
    val roll: Float,
    val pitch: Float,
    val throttle: Float,
    val yaw: Float,
    val trimRoll: Float = 0f,
    val trimPitch: Float = 0f,
  ) : FlightCommand

  data object Takeoff : FlightCommand

  data object ControlledLand : FlightCommand

  data object MotorStop : FlightCommand

  data object EmergencyStop : FlightCommand

  data object CalibrateGyro : FlightCommand

  data class SetSpeedTier(val tier: Int) : FlightCommand

  data class SetHeadlessMode(val enabled: Boolean) : FlightCommand

  data class CameraTilt(val delta: Int) : FlightCommand

  data class Flip360(val direction: Int = 0) : FlightCommand

  data object UnlockMotors : FlightCommand

  data class SetFastFly(val enabled: Boolean) : FlightCommand

  data object TriggerFirmwarePhoto : FlightCommand

  data class TriggerFirmwareRecord(val active: Boolean) : FlightCommand

  data class SwitchCamera(val cameraIndex: Int) : FlightCommand

  data object RequestKeyframe : FlightCommand

  data object ToggleLight : FlightCommand

  data class SetFixedHeightMode(val enabled: Boolean) : FlightCommand
}

/**
 * Real link and sensor telemetry. Any sensor not physically reported by the active protocol
 * remains null so the UI never fabricates values.
 */
data class DroneTelemetry(
  val timestampMs: Long,
  val protocolId: String,
  val packetsSent: Long,
  val packetsReceived: Long,
  val droppedPackets: Long,
  val linkRttMs: Long?,
  val batteryPercent: Int?,
  val rssiDbm: Int?,
  val altitudeMeters: Float?,
  val lastCommandHex: String,
  val isSyntheticHarness: Boolean = false,
  val firmwareStatusFlags: Int? = null,
  val controlPacketsReceived: Long = 0L,
  val videoPacketsReceived: Long = 0L,
  val controlArmed: Boolean = false,
  val lastSocketErrno: Int = 0,
)

data class VideoFramePacket(
  val sequence: Long,
  val timestampUs: Long,
  val transport: VideoTransportType,
  val width: Int,
  val height: Int,
  val payload: ByteArray,
  val isKeyFrame: Boolean,
  val isSyntheticHarness: Boolean = false,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is VideoFramePacket) return false
    return sequence == other.sequence &&
      timestampUs == other.timestampUs &&
      transport == other.transport &&
      payload.contentEquals(other.payload)
  }

  override fun hashCode(): Int {
    var result = sequence.hashCode()
    result = 31 * result + timestampUs.hashCode()
    result = 31 * result + transport.hashCode()
    result = 31 * result + payload.contentHashCode()
    return result
  }
}
