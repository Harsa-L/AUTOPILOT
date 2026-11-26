package com.autopilot.drone.protocolapi

import com.autopilot.core.common.ImplementationStatus
import com.autopilot.core.common.VerificationStatus

data class ProtocolRegistryEntry(
  val descriptor: ProtocolDescriptor,
  val capabilities: DroneCapabilities,
)

/**
 * Single source of truth for protocol metadata, transport bindings, capability flags,
 * and honest hardware provenance derived from the Apache-2.0 TurboDrone project
 * (github.com/Nero-Disney/turbodrone / marshallrichards/turbodrone).
 *
 * SSID hints are advisory discovery cues only and never establish protocol compatibility alone.
 */
object ProtocolCompatibilityRegistry {

  val entries: List<ProtocolRegistryEntry> =
    listOf(
      ProtocolRegistryEntry(
        descriptor =
          ProtocolDescriptor(
            id = "s2x",
            family = ProtocolFamily.S2X,
            displayName = "S2X (Macrochip / Vison)",
            companionAppFamily = "com.vison.macrochip",
            defaultHostIp = "192.168.0.1",
            controlPort = 40000,
            videoPort = 40000,
            heartbeatPort = null,
            rtspPath = null,
            controlRateHz = 25,
            videoTransport = VideoTransportType.RAW_UDP_JPEG,
            verificationStatus = VerificationStatus.IMPLEMENTED,
            implementationStatus = ImplementationStatus.IMPLEMENTED_UNVERIFIED,
            knownModels = listOf("Hiturbo S20", "Loiley S29"),
            ssidHints = listOf("Vison-", "FLOW-", "WiFi-S20", "S29-"),
            knownLimitations =
              listOf(
                "No barometer altitude, GPS, or battery telemetry exposed in standard UDP stream.",
                "Unverified against physical aircraft inside Android cloud build environment.",
                "Termination bit may cut motors immediately on some firmware revisions.",
              ),
            provenanceNote =
              "Protocol framing derived from TurboDrone (Apache-2.0) S2X reverse-engineered Macrochip UDP specification.",
          ),
        capabilities =
          DroneCapabilities(
            supportsTakeoff = true,
            supportsControlledLanding = true,
            supportsMotorStop = true,
            supportsEmergencyMotorStop = true,
            supportsGyroCalibration = true,
            supportsHeadlessMode = true,
            supportsAltitudeHold = false,
            supportsSpeedTiers = true,
            supportsCameraTilt = false,
            supportsPhotoCapture = false,
            supportsVideoStream = true,
            reportsBatteryPercent = false,
            reportsSignalRssi = false,
            reportsAltitudeMeters = false,
            reportsGps = false,
            primaryLandingSemantics = TerminationSemantics.CONTROLLED_LANDING,
          ),
      ),
      ProtocolRegistryEntry(
        descriptor =
          ProtocolDescriptor(
            id = "wifi_uav",
            family = ProtocolFamily.WIFI_UAV,
            displayName = "WiFi UAV (V88 / D16 / BL60x)",
            companionAppFamily = "WiFi UAV",
            defaultHostIp = "192.168.169.1",
            controlPort = 8800,
            videoPort = 8800,
            heartbeatPort = 8800,
            rtspPath = null,
            controlRateHz = 25,
            videoTransport = VideoTransportType.RAW_UDP_JPEG,
            verificationStatus = VerificationStatus.IMPLEMENTED,
            implementationStatus = ImplementationStatus.IMPLEMENTED_UNVERIFIED,
            knownModels = listOf("V88", "D16", "K417 (BL60x camera module)"),
            ssidHints = listOf("WIFI_UAV_", "FLOW_4K_", "V88_"),
            knownLimitations =
              listOf(
                "Subnet commonly 192.168.169.x; firmware variants differ on JPEG/H.264 stream triggers.",
                "No verified battery or altitude telemetry.",
                "Unverified on physical Android hardware in this workspace.",
              ),
            provenanceNote =
              "Control port 8800 and 8-byte flight frame structure sourced from TurboDrone (Apache-2.0) wifi_uav driver.",
          ),
        capabilities =
          DroneCapabilities(
            supportsTakeoff = true,
            supportsControlledLanding = true,
            supportsMotorStop = true,
            supportsEmergencyMotorStop = true,
            supportsGyroCalibration = true,
            supportsHeadlessMode = true,
            supportsAltitudeHold = false,
            supportsSpeedTiers = true,
            supportsCameraTilt = false,
            supportsPhotoCapture = false,
            supportsVideoStream = true,
            reportsBatteryPercent = false,
            reportsSignalRssi = false,
            reportsAltitudeMeters = false,
            reportsGps = false,
            primaryLandingSemantics = TerminationSemantics.CONTROLLED_LANDING,
          ),
      ),
      ProtocolRegistryEntry(
        descriptor =
          ProtocolDescriptor(
            id = "cooingdv",
            family = ProtocolFamily.COOINGDV,
            displayName = "CooingDV (RC UFO / KY UFO)",
            companionAppFamily = "com.cooingdv / KY UFO / RC UFO",
            defaultHostIp = "192.168.1.1",
            controlPort = 7099,
            videoPort = 7070,
            heartbeatPort = 7099,
            rtspPath = "rtsp://192.168.1.1:7070/webcam",
            controlRateHz = 25,
            videoTransport = VideoTransportType.RTSP_H264,
            verificationStatus = VerificationStatus.IMPLEMENTED,
            implementationStatus = ImplementationStatus.IMPLEMENTED_UNVERIFIED,
            knownModels = listOf("KY101", "RC UFO", "KY UFO"),
            ssidHints = listOf("KY-", "RC-UFO-", "WIFI-FPV-"),
            knownLimitations =
              listOf(
                "Requires continuous UDP heartbeat ({0x01, 0x01}) on port 7099 to keep link open.",
                "RTSP stream at :7070/webcam may drop frames if cellular network routing is not bypassed.",
                "Unverified on physical hardware in this build.",
              ),
            provenanceNote =
              "Heartbeat port 7099 and RTSP endpoint derived from TurboDrone (Apache-2.0) cooingdv documentation.",
          ),
        capabilities =
          DroneCapabilities(
            supportsTakeoff = true,
            supportsControlledLanding = true,
            supportsMotorStop = true,
            supportsEmergencyMotorStop = true,
            supportsGyroCalibration = true,
            supportsHeadlessMode = false,
            supportsAltitudeHold = false,
            supportsSpeedTiers = true,
            supportsCameraTilt = false,
            supportsPhotoCapture = false,
            supportsVideoStream = true,
            reportsBatteryPercent = false,
            reportsSignalRssi = false,
            reportsAltitudeMeters = false,
            reportsGps = false,
            primaryLandingSemantics = TerminationSemantics.CONTROLLED_LANDING,
          ),
      ),
      ProtocolRegistryEntry(
        descriptor =
          ProtocolDescriptor(
            id = "cooingdv_jieli",
            family = ProtocolFamily.COOINGDV_JIELI,
            displayName = "CooingDV Jieli (CTP / RTP)",
            companionAppFamily = "KY FPV / Jieli CTP",
            defaultHostIp = "192.168.1.1",
            controlPort = 3333,
            videoPort = 2222,
            heartbeatPort = 7099,
            rtspPath = null,
            controlRateHz = 25,
            videoTransport = VideoTransportType.RTP_JPEG,
            verificationStatus = VerificationStatus.EXPERIMENTAL,
            implementationStatus = ImplementationStatus.EXPERIMENTAL,
            knownModels = listOf("Jieli AC79xx / CTP Wi-Fi Camera Drones"),
            ssidHints = listOf("JL_FPV_", "KY_FPV_"),
            knownLimitations =
              listOf(
                "Experimental CTP TCP control + RTP/JPEG UDP stream negotiation.",
                "No battery, GPS, or optical altitude telemetry.",
                "Requires physical Jieli firmware capture for full validation.",
              ),
            provenanceNote =
              "CTP control and RTP/JPEG transport modeled after TurboDrone (Apache-2.0) cooingdv_jieli adapter.",
          ),
        capabilities =
          DroneCapabilities(
            supportsTakeoff = true,
            supportsControlledLanding = true,
            supportsMotorStop = true,
            supportsEmergencyMotorStop = true,
            supportsGyroCalibration = true,
            supportsHeadlessMode = false,
            supportsAltitudeHold = false,
            supportsSpeedTiers = false,
            supportsCameraTilt = false,
            supportsPhotoCapture = false,
            supportsVideoStream = true,
            reportsBatteryPercent = false,
            reportsSignalRssi = false,
            reportsAltitudeMeters = false,
            reportsGps = false,
            primaryLandingSemantics = TerminationSemantics.UNVERIFIED_TERMINATION,
          ),
      ),
      ProtocolRegistryEntry(
        descriptor =
          ProtocolDescriptor(
            id = "wifi_cam",
            family = ProtocolFamily.WIFI_CAM,
            displayName = "WiFi_CAM (Generic 4-Axis UDP)",
            companionAppFamily = "WiFi_CAM",
            defaultHostIp = "192.168.4.1",
            controlPort = 50000,
            videoPort = 8080,
            heartbeatPort = null,
            rtspPath = null,
            controlRateHz = 20,
            videoTransport = VideoTransportType.RAW_UDP_JPEG,
            verificationStatus = VerificationStatus.EXPERIMENTAL,
            implementationStatus = ImplementationStatus.EXPERIMENTAL,
            knownModels = listOf("Generic 4-axis WiFi_CAM quadcopters"),
            ssidHints = listOf("WiFi_CAM_", "WIFICAM-"),
            knownLimitations =
              listOf(
                "Control/video ports vary across OEM boards (50000 / 8080 default).",
                "No hardware telemetry feedback.",
                "Experimental implementation pending physical device verification.",
              ),
            provenanceNote =
              "Native UDP packet structure modeled from TurboDrone (Apache-2.0) wifi_cam backend.",
          ),
        capabilities =
          DroneCapabilities(
            supportsTakeoff = true,
            supportsControlledLanding = true,
            supportsMotorStop = true,
            supportsEmergencyMotorStop = true,
            supportsGyroCalibration = true,
            supportsHeadlessMode = false,
            supportsAltitudeHold = false,
            supportsSpeedTiers = false,
            supportsCameraTilt = false,
            supportsPhotoCapture = false,
            supportsVideoStream = true,
            reportsBatteryPercent = false,
            reportsSignalRssi = false,
            reportsAltitudeMeters = false,
            reportsGps = false,
            primaryLandingSemantics = TerminationSemantics.UNVERIFIED_TERMINATION,
          ),
      ),
      ProtocolRegistryEntry(
        descriptor =
          ProtocolDescriptor(
            id = "x69_lg",
            family = ProtocolFamily.X69_LG,
            displayName = "X69 LG (com.lg.drone / H.265)",
            companionAppFamily = "com.lg.drone / X69 Drone",
            defaultHostIp = "192.168.1.1",
            controlPort = 1234,
            videoPort = 1234,
            heartbeatPort = 7080,
            rtspPath = "rtsp://192.168.1.1:7070/webcam",
            controlRateHz = 25,
            videoTransport = VideoTransportType.RAW_UDP_H265,
            verificationStatus = VerificationStatus.EXPERIMENTAL,
            implementationStatus = ImplementationStatus.EXPERIMENTAL,
            knownModels = listOf("Loiley X69", "LG-series FPV mini drones"),
            ssidHints = listOf("X69-", "LG_DRONE_"),
            knownLimitations =
              listOf(
                "Supports dual H.265 UDP (:1234) or RTSP (:7070) modes depending on board revision.",
                "Camera tilt steps are uncalibrated relative increments.",
                "Experimental implementation not yet physically verified.",
              ),
            provenanceNote =
              "Packet format, camera tilt flags, and H.265 stream layout derived from TurboDrone (Apache-2.0) x69_lg driver.",
          ),
        capabilities =
          DroneCapabilities(
            supportsTakeoff = true,
            supportsControlledLanding = true,
            supportsMotorStop = true,
            supportsEmergencyMotorStop = true,
            supportsGyroCalibration = true,
            supportsHeadlessMode = true,
            supportsAltitudeHold = false,
            supportsSpeedTiers = true,
            supportsCameraTilt = true,
            supportsPhotoCapture = false,
            supportsVideoStream = true,
            reportsBatteryPercent = false,
            reportsSignalRssi = false,
            reportsAltitudeMeters = false,
            reportsGps = false,
            primaryLandingSemantics = TerminationSemantics.CONTROLLED_LANDING,
          ),
      ),
    )

  /**
   * Strictly isolated loopback test harness entry.
   * Never returned by [productionEntries] and never allowed to pose as real hardware.
   */
  val isolatedTestHarness: ProtocolRegistryEntry =
    ProtocolRegistryEntry(
      descriptor =
        ProtocolDescriptor(
          id = "isolated_test_harness",
          family = ProtocolFamily.ISOLATED_TEST_HARNESS,
          displayName = "Isolated Loopback Test Harness",
          companionAppFamily = "AUTOPILOT Local Verification Only",
          defaultHostIp = "127.0.0.1",
          controlPort = 0,
          videoPort = 0,
          heartbeatPort = null,
          rtspPath = null,
          controlRateHz = 25,
          videoTransport = VideoTransportType.SYNTHETIC_TEST_PATTERN,
          verificationStatus = VerificationStatus.TESTED,
          implementationStatus = ImplementationStatus.SIMULATED,
          knownModels = listOf("In-Process C++20 Loopback Fixture (NO AIRCRAFT)"),
          ssidHints = emptyList(),
          knownLimitations =
            listOf(
              "SYNTHETIC TEST MODE ONLY — transmits zero radio/Wi-Fi packets.",
              "Exists solely to validate packet framing, HUD rendering, and vision pipelines without physical hardware.",
            ),
          provenanceNote =
            "Isolated verification harness per anti-fabrication governance; segregated from hardware protocols.",
          isSyntheticHarness = true,
        ),
      capabilities =
        DroneCapabilities(
          supportsTakeoff = true,
          supportsControlledLanding = true,
          supportsMotorStop = true,
          supportsEmergencyMotorStop = true,
          supportsGyroCalibration = true,
          supportsHeadlessMode = false,
          supportsAltitudeHold = false,
          supportsSpeedTiers = true,
          supportsCameraTilt = true,
          supportsPhotoCapture = false,
          supportsVideoStream = true,
          reportsBatteryPercent = false,
          reportsSignalRssi = false,
          reportsAltitudeMeters = false,
          reportsGps = false,
          primaryLandingSemantics = TerminationSemantics.CONTROLLED_LANDING,
        ),
    )

  fun productionEntries(): List<ProtocolRegistryEntry> = entries

  fun findById(id: String, includeSyntheticHarness: Boolean = false): ProtocolRegistryEntry? {
    if (includeSyntheticHarness && id == isolatedTestHarness.descriptor.id) {
      return isolatedTestHarness
    }
    return entries.firstOrNull { it.descriptor.id == id }
  }
}
