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
  ISOLATED_TEST_HARNESS(99),
}

@Serializable
enum class VideoTransportType {
  NONE,
  RTSP_H264,
  RTP_JPEG,
  RAW_UDP_JPEG,
  RAW_UDP_H264,
  RAW_UDP_H265,
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
)

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
)

data class ConnectionConfig(
  val hostIp: String,
  val controlPort: Int,
  val videoPort: Int,
  val heartbeatPort: Int?,
  val rtspPath: String? = null,
  val ssid: String? = null,
  val networkHandle: Long? = null,
  val allowSyntheticHarness: Boolean = false,
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

  data object RequestKeyframe : FlightCommand
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
