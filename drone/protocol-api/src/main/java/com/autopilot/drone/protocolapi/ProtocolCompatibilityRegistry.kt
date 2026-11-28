package com.autopilot.drone.protocolapi

import com.autopilot.core.common.ImplementationStatus
import com.autopilot.core.common.VerificationStatus

data class ProtocolRegistryEntry(
  val descriptor: ProtocolDescriptor,
  val capabilities: DroneCapabilities,
)

/**
 * Single source of truth for protocol metadata, transport bindings, capability flags,
 * and honest hardware provenance derived from TurboDrone, pyDroneWire, cooingdv.md,
 * and chinese_toys_drones.md reverse-engineering references.
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
            knownModels = listOf("Hiturbo S20", "Loiley S29", "PL-515"),
            ssidHints = listOf("Vison-", "WiFi-S20", "S29-", "PL-515"),
            knownLimitations =
              listOf(
                "Legacy Macrochip 11-byte frame on UDP 40000; TurboDrone s2x adapter targets 20-byte frame on UDP 8080 (video 8888).",
                "No barometer altitude, GPS, or battery telemetry exposed in standard UDP stream.",
                "Unverified against physical aircraft inside Android cloud build environment.",
              ),
            provenanceNote =
              "Legacy 11-byte Macrochip S2X UDP specification (:40000); distinct from FLOW-UFO profiles.",
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
            supportsPhotoCapture = true,
            supportsVideoStream = true,
            reportsBatteryPercent = false,
            reportsSignalRssi = false,
            reportsAltitudeMeters = false,
            reportsGps = false,
            primaryLandingSemantics = TerminationSemantics.CONTROLLED_LANDING,
            supportsFlip = true,
            supportsUnlockCommand = true,
            supportsFastFly = true,
            supportsVideoRecording = true,
            supportsFirmwareSdCapture = true,
            supportedVideoModes = listOf(VideoTransportType.RAW_UDP_JPEG),
          ),
      ),
      ProtocolRegistryEntry(
        descriptor =
          ProtocolDescriptor(
            id = "flow_bl60x",
            family = ProtocolFamily.FLOW_BL60X,
            displayName = "FLOW-UFO BL60x (Camera Video Only)",
            companionAppFamily = "BL608 / BL618 (WIFI_UAV / FLOW_4K)",
            defaultHostIp = "192.168.169.1",
            controlPort = 8800,
            videoPort = 8800,
            heartbeatPort = 8800,
            rtspPath = null,
            controlRateHz = 25,
            videoTransport = VideoTransportType.RAW_UDP_JPEG,
            verificationStatus = VerificationStatus.IMPLEMENTED,
            implementationStatus = ImplementationStatus.IMPLEMENTED_UNVERIFIED,
            knownModels = listOf("D16 (BL608/BL618)", "FLOW_4K BL60x Camera Module", "K417 BL60x"),
            ssidHints = listOf("FLOW-UFO-", "FLOW_", "FLOW_4K_", "D16_"),
            knownLimitations =
              listOf(
                "VIDEO-ONLY PROFILE: dual-port UDP 8800/8801 probe + 0x93 fragmented MJPEG reassembly + 0x02ef ACKs.",
                "Transmits zero flight commands (neutral RC payload intentionally omitted per chinese toys drones audit).",
                "FLOW-UFO SSID may also belong to KY Y-series RTSP drones (192.168.1.x); verify gateway IP before choosing profile.",
              ),
            provenanceNote =
              "0x93 56-byte header fragmented MJPEG + dual 8800/8801 probe + Q5..Q100 reconstruction from chinese_toys_drones.md and cooingdv.md.",
          ),
        capabilities =
          DroneCapabilities(
            supportsTakeoff = false,
            supportsControlledLanding = false,
            supportsMotorStop = false,
            supportsEmergencyMotorStop = false,
            supportsGyroCalibration = false,
            supportsHeadlessMode = false,
            supportsAltitudeHold = false,
            supportsSpeedTiers = false,
            supportsCameraTilt = false,
            supportsPhotoCapture = true,
            supportsVideoStream = true,
            reportsBatteryPercent = false,
            reportsSignalRssi = false,
            reportsAltitudeMeters = false,
            reportsGps = false,
            primaryLandingSemantics = TerminationSemantics.UNVERIFIED_TERMINATION,
            supportsFlip = false,
            supportsUnlockCommand = false,
            supportsFastFly = false,
            supportsVideoRecording = true,
            supportsFirmwareSdCapture = false,
            supportsCameraSwitch = true,
            isVideoOnly = true,
            supportedVideoModes = listOf(VideoTransportType.RAW_UDP_JPEG),
          ),
      ),
      ProtocolRegistryEntry(
        descriptor =
          ProtocolDescriptor(
            id = "flow_ky_y_series",
            family = ProtocolFamily.FLOW_KY_Y_SERIES,
            displayName = "FLOW-UFO KY Y-Series (pyDroneWire)",
            companionAppFamily = "KY UFO / FLOW-UFO Y-Series (192.168.1.1)",
            defaultHostIp = "192.168.1.1",
            controlPort = 7099,
            videoPort = 7070,
            heartbeatPort = 7099,
            rtspPath = "rtsp://192.168.1.1:7070/webcam",
            controlRateHz = 20,
            videoTransport = VideoTransportType.RTSP_MJPEG,
            verificationStatus = VerificationStatus.IMPLEMENTED,
            implementationStatus = ImplementationStatus.IMPLEMENTED_UNVERIFIED,
            knownModels = listOf("FLOW-UFO Y-Series (dual camera)", "KY Y-Series 720p"),
            ssidHints = listOf("FLOW-UFO-", "FLOW_"),
            knownLimitations =
              listOf(
                "21-byte CooingDV extended control frame at 20 Hz with discrete cmd byte (0x01..0x05) and 30/60/100% client stick scaling.",
                "Shares FLOW- prefix with BL60x drones; verify subnet 192.168.1.x + RTSP :7070/webcam.",
                "Unverified against physical hardware in cloud build environment.",
              ),
            provenanceNote =
              "21-byte 03 66 14 frame, cmd 0x01..0x05, 1 Hz {0x01,0x01} heartbeat, and 06 01/02 camera switch from pyDroneWire.",
            defaultAutoRotationDeg = 270,
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
            supportsPhotoCapture = true,
            supportsVideoStream = true,
            reportsBatteryPercent = false,
            reportsSignalRssi = false,
            reportsAltitudeMeters = false,
            reportsGps = false,
            primaryLandingSemantics = TerminationSemantics.CONTROLLED_LANDING,
            supportsFlip = true,
            supportsUnlockCommand = false,
            supportsFastFly = true,
            supportsVideoRecording = true,
            supportsFirmwareSdCapture = false,
            supportsCameraSwitch = true,
            supportedVideoModes =
              listOf(VideoTransportType.RTSP_MJPEG, VideoTransportType.RTSP_H264),
          ),
      ),
      ProtocolRegistryEntry(
        descriptor =
          ProtocolDescriptor(
            id = "cooingdv",
            family = ProtocolFamily.COOINGDV,
            displayName = "CooingDV TC/Auto (KY UFO / RC UFO)",
            companionAppFamily = "com.cooingdv / KY UFO / RC UFO / 4DRC FPV",
            defaultHostIp = "192.168.1.1",
            controlPort = 7099,
            videoPort = 7070,
            heartbeatPort = 7099,
            rtspPath = "rtsp://192.168.1.1:7070/webcam",
            controlRateHz = 20,
            videoTransport = VideoTransportType.RTSP_MJPEG,
            verificationStatus = VerificationStatus.IMPLEMENTED,
            implementationStatus = ImplementationStatus.IMPLEMENTED_UNVERIFIED,
            knownModels = listOf("K200", "KY101", "RC UFO", "KY UFO", "4DRC Classic"),
            ssidHints = listOf("FLY-UFO-", "KY-", "RC-UFO-", "WIFI-FPV-", "4DRC-"),
            knownLimitations =
              listOf(
                "Starts with 9-byte TC packet (03 66 ...) and auto-switches to 21-byte GL if telemetry ID in {82,85,90..101,103} arrives.",
                "Sends 1 Hz {0x01, 0x01} heartbeat on UDP 7099 and {0x08, 0x01} exit-control on disconnect.",
                "Throttle byte 1 is coerced to 0 per Android FlyController; 270 deg auto-rotation applied by default.",
              ),
            provenanceNote =
              "9-byte TC / auto-GL telemetry detector, RTSP/MJPEG :7070/webcam, and 1 Hz heartbeat derived from cooingdv.md & chinese_toys_drones.md.",
            defaultAutoRotationDeg = 270,
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
            supportsPhotoCapture = true,
            supportsVideoStream = true,
            reportsBatteryPercent = false,
            reportsSignalRssi = false,
            reportsAltitudeMeters = false,
            reportsGps = false,
            primaryLandingSemantics = TerminationSemantics.CONTROLLED_LANDING,
            supportsFlip = true,
            supportsUnlockCommand = true,
            supportsFastFly = true,
            supportsVideoRecording = true,
            supportsFirmwareSdCapture = false,
            supportsCameraSwitch = true,
            supportedVideoModes =
              listOf(VideoTransportType.RTSP_MJPEG, VideoTransportType.RTSP_H264),
            supportsLightControl = true,
            supportsCooingdvPassword = true,
            supportsFixedHeightFlag = true,
          ),
      ),
      ProtocolRegistryEntry(
        descriptor =
          ProtocolDescriptor(
            id = "cooingdv_gl",
            family = ProtocolFamily.COOINGDV_GL,
            displayName = "CooingDV GL Extended (RC UFO / M10)",
            companionAppFamily = "com.cooingdv.rcufo / M10 GL",
            defaultHostIp = "192.168.1.1",
            controlPort = 7099,
            videoPort = 7070,
            heartbeatPort = 7099,
            rtspPath = "rtsp://192.168.1.1:7070/webcam",
            controlRateHz = 20,
            videoTransport = VideoTransportType.RTSP_MJPEG,
            verificationStatus = VerificationStatus.IMPLEMENTED,
            implementationStatus = ImplementationStatus.IMPLEMENTED_UNVERIFIED,
            knownModels = listOf("M10", "RC UFO GL", "KY UFO GL (IDs 82, 85, 90..101, 103)"),
            ssidHints = listOf("FLY-UFO-", "RC-UFO-", "M10-"),
            knownLimitations =
              listOf(
                "Forced 21-byte GL extended packet (03 66 14 ... flags1 flags2 00..00 xor 99) for drones that filter or omit telemetry.",
                "GL flags1 0x01 combines fast-up/fast-down actions; 0x02 is emergency motor stop.",
                "Unverified on physical hardware in cloud build environment.",
              ),
            provenanceNote =
              "Forced CooingDV GL variant matching COOINGDV_VARIANT=gl in cooingdv.md.",
            defaultAutoRotationDeg = 270,
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
            supportsPhotoCapture = true,
            supportsVideoStream = true,
            reportsBatteryPercent = false,
            reportsSignalRssi = false,
            reportsAltitudeMeters = false,
            reportsGps = false,
            primaryLandingSemantics = TerminationSemantics.CONTROLLED_LANDING,
            supportsFlip = true,
            supportsUnlockCommand = false,
            supportsFastFly = true,
            supportsVideoRecording = true,
            supportsFirmwareSdCapture = false,
            supportsCameraSwitch = true,
            supportedVideoModes =
              listOf(VideoTransportType.RTSP_MJPEG, VideoTransportType.RTSP_H264),
            supportsLightControl = true,
            supportsCooingdvPassword = true,
            supportsFixedHeightFlag = true,
          ),
      ),
      ProtocolRegistryEntry(
        descriptor =
          ProtocolDescriptor(
            id = "cooingdv_jieli",
            family = ProtocolFamily.COOINGDV_JIELI,
            displayName = "CooingDV Jieli (KY FPV UDP CTP)",
            companionAppFamily = "com.cooingdv.kyfpv (DeviceClient UDP 2228)",
            defaultHostIp = "192.168.8.15",
            controlPort = 2228,
            videoPort = 6666,
            heartbeatPort = 2228,
            rtspPath = null,
            controlRateHz = 20,
            videoTransport = VideoTransportType.RTP_JPEG,
            verificationStatus = VerificationStatus.EXPERIMENTAL,
            implementationStatus = ImplementationStatus.EXPERIMENTAL,
            knownModels = listOf("KY FPV 2.0.0 Jieli DeviceClient Drones"),
            ssidHints = listOf("JL_FPV_", "KY_FPV_"),
            knownLimitations =
              listOf(
                "UDP :2228 CTP JSON FLYING_CTRL (BYTE0..BYTE7) + RTP/JPEG on UDP :6666.",
                "Distinct from Jieli AC79/HF TCP :3333 camera-only profile.",
                "Requires physical KY FPV DeviceClient drone capture for full validation.",
              ),
            provenanceNote =
              "KY FPV 2.0.0 DeviceClient CTP FLYING_CTRL JSON over UDP 192.168.8.15:2228 + RTP/JPEG :6666 per cooingdv.md.",
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
            supportsSpeedTiers = false,
            supportsCameraTilt = false,
            supportsPhotoCapture = true,
            supportsVideoStream = true,
            reportsBatteryPercent = false,
            reportsSignalRssi = false,
            reportsAltitudeMeters = false,
            reportsGps = false,
            primaryLandingSemantics = TerminationSemantics.UNVERIFIED_TERMINATION,
            supportsFlip = true,
            supportsUnlockCommand = true,
            supportsFastFly = false,
            supportsVideoRecording = true,
            supportsFirmwareSdCapture = false,
            supportsCameraSwitch = true,
            supportedVideoModes =
              listOf(VideoTransportType.RTP_JPEG, VideoTransportType.RAW_UDP_H264),
          ),
      ),
      ProtocolRegistryEntry(
        descriptor =
          ProtocolDescriptor(
            id = "jieli_ac79",
            family = ProtocolFamily.JIELI_AC79,
            displayName = "Jieli AC79 (HF / YL3 Camera Video Only)",
            companionAppFamily = "DVRunning 2 / HF UFO (AC79X_wifi_car_camera)",
            defaultHostIp = "192.168.80.1",
            controlPort = 3333,
            videoPort = 2224,
            heartbeatPort = 6666,
            rtspPath = null,
            controlRateHz = 25,
            videoTransport = VideoTransportType.RAW_UDP_JPEG,
            verificationStatus = VerificationStatus.IMPLEMENTED,
            implementationStatus = ImplementationStatus.IMPLEMENTED_UNVERIFIED,
            knownModels = listOf("YL3", "HF_ AC79xx Camera Drones"),
            ssidHints = listOf("HF_"),
            knownLimitations =
              listOf(
                "VIDEO-ONLY PROFILE: TCP :3333 CTP JSON (APP_ACCESS, DATE_TIME, OPEN_RT_STREAM, 7.5s CTP_KEEP_ALIVE).",
                "Receives multi-piece 20-byte header JPEG frames on UDP :2224 AND RTP/JPEG or H.264 on UDP :6666.",
                "Flight control over UDP 3456 / 0139 tunnel is intentionally disabled per chinese_toys_drones.md.",
              ),
            provenanceNote =
              "Full TCP :3333 CTP state machine + multi-piece UDP 2224 + UDP 6666 + 90 deg CCW auto-rotation per chinese_toys_drones.md.",
            defaultAutoRotationDeg = 90,
          ),
        capabilities =
          DroneCapabilities(
            supportsTakeoff = false,
            supportsControlledLanding = false,
            supportsMotorStop = false,
            supportsEmergencyMotorStop = false,
            supportsGyroCalibration = false,
            supportsHeadlessMode = false,
            supportsAltitudeHold = false,
            supportsSpeedTiers = false,
            supportsCameraTilt = false,
            supportsPhotoCapture = true,
            supportsVideoStream = true,
            reportsBatteryPercent = true,
            reportsSignalRssi = false,
            reportsAltitudeMeters = false,
            reportsGps = false,
            primaryLandingSemantics = TerminationSemantics.UNVERIFIED_TERMINATION,
            supportsFlip = false,
            supportsUnlockCommand = false,
            supportsFastFly = false,
            supportsVideoRecording = true,
            supportsFirmwareSdCapture = false,
            isVideoOnly = true,
            supportedVideoModes =
              listOf(
                VideoTransportType.RAW_UDP_JPEG,
                VideoTransportType.RTP_JPEG,
                VideoTransportType.RAW_UDP_H264,
              ),
          ),
      ),
      ProtocolRegistryEntry(
        descriptor =
          ProtocolDescriptor(
            id = "lewei",
            family = ProtocolFamily.LEWEI,
            displayName = "Lewei VISUO (TCP 7060 Video Only)",
            companionAppFamily = "WiFi UFO (com.lewei.multiple.lewei)",
            defaultHostIp = "192.168.0.1",
            controlPort = 7060,
            videoPort = 7060,
            heartbeatPort = null,
            rtspPath = null,
            controlRateHz = 20,
            videoTransport = VideoTransportType.LEWEI_TCP_H264,
            verificationStatus = VerificationStatus.IMPLEMENTED,
            implementationStatus = ImplementationStatus.IMPLEMENTED_UNVERIFIED,
            knownModels = listOf("VISUO (WiFi-720P-)", "Lewei 720p H.264 Drones"),
            ssidHints = listOf("WiFi-720P-"),
            knownLimitations =
              listOf(
                "VIDEO-ONLY PROFILE: TCP :7060 46-byte lewei_cmd protocol (cmd=2 start, cmd=1 1Hz keepalive, cmd=3 stop).",
                "Extracts cmd=257 32-byte frame headers + Annex-B H.264 (reconnects after 5s silence); flight UDP protocol disabled.",
                "Stream type 129 hardware quirk is unsupported.",
              ),
            provenanceNote =
              "Lewei TCP 7060 46-byte command header and 32-byte H.264 Annex-B video frame parser per chinese_toys_drones.md & pylwdrone notes.",
          ),
        capabilities =
          DroneCapabilities(
            supportsTakeoff = false,
            supportsControlledLanding = false,
            supportsMotorStop = false,
            supportsEmergencyMotorStop = false,
            supportsGyroCalibration = false,
            supportsHeadlessMode = false,
            supportsAltitudeHold = false,
            supportsSpeedTiers = false,
            supportsCameraTilt = false,
            supportsPhotoCapture = true,
            supportsVideoStream = true,
            reportsBatteryPercent = false,
            reportsSignalRssi = false,
            reportsAltitudeMeters = false,
            reportsGps = false,
            primaryLandingSemantics = TerminationSemantics.UNVERIFIED_TERMINATION,
            supportsFlip = false,
            supportsUnlockCommand = false,
            supportsFastFly = false,
            supportsVideoRecording = true,
            supportsFirmwareSdCapture = false,
            isVideoOnly = true,
            supportedVideoModes = listOf(VideoTransportType.LEWEI_TCP_H264),
          ),
      ),
      ProtocolRegistryEntry(
        descriptor =
          ProtocolDescriptor(
            id = "wifi_uav",
            family = ProtocolFamily.WIFI_UAV,
            displayName = "WiFi UAV Experimental (V88 / V66 / GT3)",
            companionAppFamily = "WiFi UAV (Legacy Experimental)",
            defaultHostIp = "192.168.169.1",
            controlPort = 8800,
            videoPort = 8800,
            heartbeatPort = 8800,
            rtspPath = null,
            controlRateHz = 25,
            videoTransport = VideoTransportType.RAW_UDP_JPEG,
            verificationStatus = VerificationStatus.EXPERIMENTAL,
            implementationStatus = ImplementationStatus.EXPERIMENTAL,
            knownModels = listOf("V88", "V66", "GT3 (Experimental 8-byte)"),
            ssidHints = listOf("WIFI_UAV_", "V88_", "V66_", "GT3_"),
            knownLimitations =
              listOf(
                "EXPERIMENTAL: uses generic 8-byte 0x66 frame; full TurboDrone extended 20+ byte rolling-counter RC adapter not yet validated.",
                "For FLOW-UFO / D16 camera viewing, use flow_bl60x video-only profile instead.",
                "Unverified on physical Android hardware in this workspace.",
              ),
            provenanceNote =
              "Experimental 8-byte control frame + BL60x 0x93 video reassembler fallback on UDP 8800.",
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
            supportsPhotoCapture = true,
            supportsVideoStream = true,
            reportsBatteryPercent = false,
            reportsSignalRssi = false,
            reportsAltitudeMeters = false,
            reportsGps = false,
            primaryLandingSemantics = TerminationSemantics.CONTROLLED_LANDING,
            supportsFlip = true,
            supportsUnlockCommand = true,
            supportsFastFly = true,
            supportsVideoRecording = true,
            supportsFirmwareSdCapture = false,
            supportedVideoModes =
              listOf(VideoTransportType.RAW_UDP_JPEG, VideoTransportType.RAW_UDP_H264),
          ),
      ),
      ProtocolRegistryEntry(
        descriptor =
          ProtocolDescriptor(
            id = "wifi_cam",
            family = ProtocolFamily.WIFI_CAM,
            displayName = "WiFi_CAM / E88 Experimental (UDP 8090)",
            companionAppFamily = "WiFi_CAM / E88",
            defaultHostIp = "192.168.4.153",
            controlPort = 8090,
            videoPort = 8080,
            heartbeatPort = null,
            rtspPath = null,
            controlRateHz = 20,
            videoTransport = VideoTransportType.RAW_UDP_JPEG,
            verificationStatus = VerificationStatus.EXPERIMENTAL,
            implementationStatus = ImplementationStatus.EXPERIMENTAL,
            knownModels = listOf("WiFi_CAM", "E88-family (192.168.4.153 / 192.168.4.1)"),
            ssidHints = listOf("WiFi_CAM_", "WIFICAM-", "E88-"),
            knownLimitations =
              listOf(
                "Experimental: TurboDrone targets 192.168.4.153:8090 while generic OEM boards may use 192.168.4.1:50000.",
                "No hardware telemetry feedback.",
                "Experimental implementation pending physical device verification.",
              ),
            provenanceNote =
              "Updated host/port defaults (192.168.4.153:8090, alt 192.168.4.1) and 0x42 0x76 mode handshake per README audit.",
            alternateHostIps = listOf("192.168.4.1"),
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
            supportsPhotoCapture = true,
            supportsVideoStream = true,
            reportsBatteryPercent = false,
            reportsSignalRssi = false,
            reportsAltitudeMeters = false,
            reportsGps = false,
            primaryLandingSemantics = TerminationSemantics.UNVERIFIED_TERMINATION,
            supportsFlip = true,
            supportsUnlockCommand = false,
            supportsFastFly = false,
            supportsVideoRecording = true,
            supportedVideoModes = listOf(VideoTransportType.RAW_UDP_JPEG),
          ),
      ),
      ProtocolRegistryEntry(
        descriptor =
          ProtocolDescriptor(
            id = "x69_lg",
            family = ProtocolFamily.X69_LG,
            displayName = "X69 LG (com.lg.drone / 25B Wrapped)",
            companionAppFamily = "com.lg.drone / X69 Drone",
            defaultHostIp = "192.168.1.1",
            controlPort = 23458,
            videoPort = 1234,
            heartbeatPort = 7080,
            rtspPath = "/live/ch00_1",
            controlRateHz = 25,
            videoTransport = VideoTransportType.RAW_UDP_H265,
            verificationStatus = VerificationStatus.EXPERIMENTAL,
            implementationStatus = ImplementationStatus.EXPERIMENTAL,
            knownModels = listOf("Loiley X69", "LG-series FPV mini drones"),
            ssidHints = listOf("X69-", "LG_DRONE_"),
            knownLimitations =
              listOf(
                "Uses CA 47 D5 00 14 + 20-byte wrapped control payload on UDP 23458 + 1 Hz D1 keepalive.",
                "Supports H.265 UDP (:1234 with 32B header strip), RTSP HEVC (/live/ch00_1), or legacy UDP JPEG (:7080).",
                "Experimental implementation not yet physically verified.",
              ),
            provenanceNote =
              "CA 47 D5 00 wrapped 20-byte control payload on :23458, 1 Hz D1 keepalive, and H.265 32B header stripping per TurboDrone audit.",
            alternateHostIps = listOf("172.16.11.1"),
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
            supportsPhotoCapture = true,
            supportsVideoStream = true,
            reportsBatteryPercent = false,
            reportsSignalRssi = false,
            reportsAltitudeMeters = false,
            reportsGps = false,
            primaryLandingSemantics = TerminationSemantics.CONTROLLED_LANDING,
            supportsFlip = true,
            supportsUnlockCommand = true,
            supportsFastFly = true,
            supportsVideoRecording = true,
            supportsFirmwareSdCapture = false,
            supportedVideoModes =
              listOf(
                VideoTransportType.RAW_UDP_H265,
                VideoTransportType.RTSP_H265,
                VideoTransportType.RTSP_H264,
                VideoTransportType.RAW_UDP_JPEG,
              ),
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
          supportsPhotoCapture = true,
          supportsVideoStream = true,
          reportsBatteryPercent = false,
          reportsSignalRssi = false,
          reportsAltitudeMeters = false,
          reportsGps = false,
          primaryLandingSemantics = TerminationSemantics.CONTROLLED_LANDING,
          supportsFlip = true,
          supportsUnlockCommand = true,
          supportsFastFly = true,
          supportsVideoRecording = true,
          supportsTrajectoryExecution = true,
          supportedVideoModes = listOf(VideoTransportType.SYNTHETIC_TEST_PATTERN),
        ),
    )

  fun productionEntries(): List<ProtocolRegistryEntry> = entries

  fun findById(id: String, includeSyntheticHarness: Boolean = false): ProtocolRegistryEntry? {
    if (includeSyntheticHarness && id == isolatedTestHarness.descriptor.id) {
      return isolatedTestHarness
    }
    return entries.firstOrNull { it.descriptor.id == id }
  }

  fun findMatchesBySsid(ssid: String): List<ProtocolRegistryEntry> {
    if (ssid.isBlank()) return emptyList()
    return entries.filter { entry ->
      entry.descriptor.ssidHints.any { hint -> ssid.startsWith(hint, ignoreCase = true) }
    }
  }
}
