# AUTOPILOT Technical Architecture & Engineering Documentation

## 1. Overview & Attribution

**AUTOPILOT** is a native Android (API 26–36) flight-controller, telemetry, and computer-vision platform built with **Kotlin**, **Jetpack Compose (Material 3)**, **Android NDK (`29.0.14206865`)**, **CMake (`3.31.6`)**, and **C++20**.

- **Reference Project**: Protocol framing, port defaults, and hardware families are informed by the open-source [TurboDrone](https://github.com/Nero-Disney/turbodrone) (`marshallrichards/turbodrone`) project, distributed under the **Apache-2.0 License**.
- **Design Principle**: AUTOPILOT does not wrap Python/FastAPI or Vite web dashboards. All UDP sockets, binary framing, video packet reassembly, optical flow, and multi-object tracking run natively in C++20 and Kotlin.

---

## 2. Module Responsibilities & Dependency Graph

```text
:app (UI screens, Room, DataStore, ForegroundService, MainActivity)
 ├── :design-system (W3C DTCG tokens, 5-layer cascade, ThemeManager, HUD components)
 ├── :flight:control (12-state FlightState machine, InputNormalizer, SafetyWatchdog)
 ├── :drone:protocols (NativeDroneProtocolAdapter, API 26-36 WifiConnectivityController)
 │     ├── :drone:protocol-api (DroneProtocol contract, ProtocolCompatibilityRegistry)
 │     └── :native:core (C++20 NDK engines + coarse-grained JNI bridge)
 ├── :video (VideoPipelineController, JPEG/RTP/MediaCodec status, FPS/latency metrics)
 └── :vision (ObjectDetector contract, LiteRT 2.3 YOLO26n + C++20 VisionEngine)
       └── :core:common (Dispatchers, VerificationStatus, AutopilotLog, ByteFraming)
```

---

## 3. Native C++20 & JNI Architecture

- **Pinned Toolchain**: Android NDK `29.0.14206865`, CMake `3.31.6`, C++20 (`-std=c++20`), `minSdk = 26`.
- **Coarse-Grained JNI (`AutopilotNativeBridge`)**:
  - `nativeCreateSession` / `nativeStartSession` / `nativeStopSession` / `nativeDestroySession`: manages an isolated C++20 `NativeSession` owning non-blocking POSIX UDP sockets bound via `android_setsocknetwork(net_handle_t, fd)` and a dedicated 25 Hz control worker thread.
  - `nativeUpdateSticks` / `nativeTriggerAction`: updates atomic stick state without per-packet heap allocations.
  - `nativePopVideoFrame`: copies the newest reassembled frame from a 3-slot drop-oldest ring buffer into a reusable direct `ByteBuffer`.
  - `nativeProcessVisionFrame`: runs C++20 4x4 grid **Lucas-Kanade optical flow**, IoU + flow-compensated **multi-object tracking**, and **pinhole monocular distance estimation** in one JNI transition.

---

## 4. Hardware Compatibility Registry & Provenance

| Protocol ID | Companion App Family | Default Target | Video Transport | Implementation Status | Known Limitations |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `s2x` | `com.vison.macrochip` (S20, S29) | `192.168.0.1:40000` | `RAW_UDP_JPEG` | `IMPLEMENTED_UNVERIFIED` | No battery/altitude/GPS telemetry; unverified on physical hardware in cloud build |
| `wifi_uav` | `WiFi UAV` (V88, D16, K417) | `192.168.169.1:8800` | `RAW_UDP_JPEG` | `IMPLEMENTED_UNVERIFIED` | Subnet varies by OEM firmware; no verified battery telemetry |
| `cooingdv` | `KY UFO / RC UFO` | `192.168.1.1:7099` | `RTSP_H264` (`:7070/webcam`) | `IMPLEMENTED_UNVERIFIED` | Requires `0x01 0x01` heartbeat on UDP `:7099`; RTSP requires physical AP |
| `cooingdv_jieli` | `KY FPV / Jieli CTP` | `192.168.1.1:3333` | `RTP_JPEG` (`:2222`) | `EXPERIMENTAL` | Experimental CTP + RFC 2435 RTP/JPEG framing |
| `wifi_cam` | `WiFi_CAM` | `192.168.4.1:50000` | `RAW_UDP_JPEG` (`:8080`) | `EXPERIMENTAL` | Ports vary by OEM board revision |
| `x69_lg` | `com.lg.drone` (Loiley X69) | `192.168.1.1:1234` | `RAW_UDP_H265` / `RTSP` | `EXPERIMENTAL` | Relative camera tilt steps; H.265/RTSP requires physical stream capture |
| `isolated_test_harness` | Local Loopback Only | `127.0.0.1` (no RF) | `SYNTHETIC_TEST_PATTERN` | `SIMULATED` | Strictly isolated verification fixture; never transmits radio frames |

---

## 5. Video & Computer Vision Pipeline

- **Video (`:video`)**: Decodes `RAW_UDP_JPEG` / `RTP_JPEG` frames into `ARGB_8888` bitmaps, reports hardware `MediaCodec` capabilities (`video/avc`, `video/hevc`), and exposes real FPS and decode latency without fabricating frames.
- **Vision (`:vision`)**:
  - **LiteRT (`2.3.0`) + YOLO26n**: Uses `CompiledModel` (`Accelerator.GPU` with warm-up inference and automatic `Accelerator.CPU` fallback) on a single-thread background executor with reused `TensorBuffer` allocations. Users can bundle `assets/models/yolo26n.tflite` or import weights via Android SAF (`OpenDocument`). When no `.tflite` weights are provisioned, the detector reports `ModelNotProvisioned` cleanly while C++20 optical flow remains active.
  - **Monocular Distance**: Pinhole projection outputs `estimatedDistanceMeters ± uncertaintyMeters` (`±10%` when marked calibrated, `±30%` when uncalibrated).

---

## 6. Android 8.0+ (API 26–36) Compatibility

- **API 29+**: Uses `WifiNetworkSpecifier` + `ConnectivityManager.requestNetwork` (excluding `NET_CAPABILITY_INTERNET`) and binds native sockets via `android_setsocknetwork`.
- **API 26–28**: Inspects active Wi-Fi `Network` handles via `ConnectivityManager` / `WifiManager` and provides direct System Wi-Fi settings navigation.
- **API 33+**: Uses `NEARBY_WIFI_DEVICES` (`neverForLocation`) and `POST_NOTIFICATIONS`; API 26–32 uses `ACCESS_FINE_LOCATION` (`maxSdkVersion="32"`).
- **Foreground Service**: Declares `foregroundServiceType="connectedDevice"` for `FlightLinkForegroundService`.

---

## 7. Known Verification & Hardware Testing Boundaries

- Unit tests (`ProtocolPacketEncoderTest`, `FlightStateControllerTest`, `ThemeRuntimeTest`) and Robolectric UI tests (`AutopilotRobolectricTest`) execute on the JVM.
- Physical Wi-Fi drone discovery, RF link stability, and live RTSP/H.265 hardware video decoding require real aircraft hardware and could not be executed in the cloud container.
