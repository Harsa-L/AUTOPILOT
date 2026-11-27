# AUTOPILOT — Field Usability, Protocol Audit & Remediation Report

**Audit & Remediation Date:** 2026-11-27  
**Scope:** Full protocol, video-pipeline, link-management, flight-safety, and UI/UX audit against attached reference reverse-engineering specs (`chinese toys drones.md`, `cooingdv.md`, `pyDroneWire`, and `TurboDrone`), plus implemented code fixes across Kotlin/Compose and C++20 NDK modules.

> **Hardware Verification Boundary:** Because this project is built in a cloud Android environment without physical aircraft, RF links, or live hardware emulators, **all changes have been verified by static inspection, Gradle/NDK compilation, and JVM/Robolectric tests only — zero live physical drone flight tests have been run**. Never treat `IMPLEMENTED_UNVERIFIED` or `EXPERIMENTAL` profiles as flight-certified until tested with rotors removed / tethered in safe airspace.

---

## 1. Audited Findings & Implemented Remediations

### A. Control Wire Protocols & Safety Arming (`protocol_packets.*`, `NativeDroneProtocolAdapter.kt`, `ProtocolCompatibilityRegistry.kt`)

| Finding from Attachments | Previous Behavior | Implemented Fix |
| :--- | :--- | :--- |
| **Control TX gated only in UI (`NativeSession`)** | C++ worker transmitted neutral/stick frames immediately upon `nativeStartSession()` before operator armed. | Added `control_armed_` (`nativeSetControlArmed`) in `NativeSession` + `DroneProtocol.setControlArmed()`; `FlightStateController.arm()`/`unlockMotors()` arm TX, while `disarm()`, `motorStop()`, `emergencyStop()`, watchdog link-loss, and `disconnect()` disarm immediately. |
| **Video-only profiles (`FLOW_BL60X`, `JIELI_AC79`, `LEWEI`)** | `FLOW-` routed to `s2x`/`wifi_uav`; Jieli TCP 3333 & Lewei TCP 7060 did not exist; could attempt flight commands. | Added dedicated video-only profiles (`flow_bl60x`, `jieli_ac79`, `lewei`) with `isVideoOnly = true`, empty `encode_control_packet()`, zeroed RC section in BL60x `0x02ef` ACKs, and UI/state-machine blocking of Arm/Takeoff/Land/Stop/Sticks. |
| **Classic CooingDV TC & GL protocols (`cooingdv`, `cooingdv_gl`)** | Sent generic 8-byte `0x66 ... 0x99` frame (`0..255`), missing `1..255` axis scale, `thr==1->0` quirk, TC flag bitmask, 21-byte GL variant, `0x01 0x01` heartbeat, and `0x08 0x01` exit control. | Implemented exact 9-byte TC (`03 66 B1 B2 ACC TURN FLAGS XOR 99`), `1..255` + `acc==1 -> 0` mapping, TC flag bitmask (`0x80` cal, `0x08` flip), auto-switch to 21-byte GL on RX IDs `{82,85,90..101,103}`, forced `cooingdv_gl` profile, 1 Hz `{0x01,0x01}` heartbeat, `{0x08,0x01}` stop packet, and `0x06 01/02` dual-camera switching. |
| **FLOW-UFO KY Y-Series (`flow_ky_y_series`)** | Missing pyDroneWire 20 Hz 21-byte protocol. | Implemented 21-byte `03 66 14 ... cmd 0x02 ... xor 99` with client-side 30%/60%/100% stick scaling, constant `0x02` wire speed byte, discrete `cmd` (`0x01..0x05`), 20 Hz rate, and RTSP `:7070/webcam`. |
| **CooingDV Jieli DeviceClient (`cooingdv_jieli`)** | Used TCP `:3333` with binary 14B `"CTP:\x00\x08..."` payload. | Updated to UDP `192.168.8.15:2228` CTP `FLYING_CTRL` JSON (`BYTE0..BYTE7`), `CONTROL_MODE`, 1 Hz `CTP_KEEP_ALIVE`, `SWITCH_CAMERA`, and RTP/JPEG on UDP `:6666`. |
| **X69 LG (`x69_lg`) & WiFi_CAM (`wifi_cam`)** | `x69_lg` used 10B frame on `:1234` and falsely advertised `supportsFirmwareSdCapture`; `wifi_cam` defaulted only to `192.168.4.1:50000`. | Updated `x69_lg` to 25-byte `CA 47 D5 00 14` wrapped 20B frame on UDP `:23458`, 1 Hz `0xD1` keepalive, and `supportsFirmwareSdCapture = false`. Updated `wifi_cam` default to `192.168.4.153:8090` (keeping `192.168.4.1` alternate preset). |
| **S2X / WiFi_UAV scope clarity** | `s2x` claimed `FLOW-` SSID hint; `wifi_uav` claimed `FLOW_4K_` & SD capture. | Removed `FLOW-` from `s2x` and `FLOW_4K_` / SD capture from `wifi_uav`; documented `s2x` 11B `:40000` vs TurboDrone 20B `:8080` and marked `wifi_uav` `EXPERIMENTAL`. |

---

### B. Video Transport, Reassembly & Decoding (`video_reassembler.*`, `native_session.cpp`, `AnnexBVideoDecoder.kt`, `VideoPipelineController.kt`)

- **BL60x Fragmented MJPEG (`FLOW_BL60X`):** Added dual-port UDP `8800`/`8801` probe/keepalive (`EF 00 04 00`), `0x93` 56-byte header fragment reassembly (4 slots by `frame_id`), `0x02ef` fragment-mask ACK generation (with zeroed RC section), and Q5..Q100 JFIF header reconstruction.
- **RFC 2435 RTP/JPEG (`RTP_JPEG` & `RTSP_MJPEG`):** Replaced fixed 20-byte header strip with proper RFC 2435 Main/Q/Restart header parsing and JFIF `SOI/DQT/SOF0/DHT/SOS` synthesis so CooingDV RTSP MJPEG (`/webcam`) and Jieli UDP `:6666` decode reliably.
- **RTSP SDP Auto-Detection & Resilience:** Added SDP `a=rtpmap` parsing (dynamic H.264/H.265/JPEG payload types), `VideoReassembler::set_transport()` dynamic switching, 64×16 KB TCP socket drain per tick, and 3s/5s automatic reconnect backoff.
- **Jieli AC79 (`JIELI_AC79`) & Lewei (`LEWEI`) Video Streams:** Implemented TCP `:3333` CTP state machine (`APP_ACCESS`, `DATE_TIME`, `OPEN_RT_STREAM`, `CLOSE_RT_STREAM`) + multi-piece 20-byte UDP `:2224` JPEG reassembly + UDP `:6666`, and Lewei TCP `:7060` 46-byte `lewei_cmd` (`cmd=2/1/3`) + 32-byte H.264 Annex-B extraction (with 5s silence reconnect).
- **X69 H.265 Header Strip (`RAW_UDP_H265`):** Added 32-byte frame-header strip (`pkt[0]==0x01` / `pkt[3]==0x26`) before Annex-B start-code reassembly.
- **Dynamic Decoder Surface & Auto-Orientation:** Updated `AnnexBVideoDecoder` to recreate `ImageReader` on `MediaCodec.INFO_OUTPUT_FORMAT_CHANGED` (`setOutputSurface` with safe re-init fallback), added a 3.5s stale-frame watchdog in `VideoPipelineController`, and added per-protocol Auto camera rotation (`270°` for CooingDV/KY Y-series, `90°` for Jieli AC79) in `UserPreferencesStore`/`SettingsScreen`.

---

### C. Connectivity, Telemetry Honesty, Inputs & UX (`WifiConnectivityController.kt`, `FlightStateController.kt`, `GamepadInputHandler.kt`, `AutopilotViewModel.kt`, `FlightCockpitScreen.kt`)

- **Wi-Fi Binding & Subnet Security:** Refreshes active Wi-Fi state inside `connectSelectedProtocol()`, matches already-connected SSIDs across all `ssidHints` in `WifiConnectivityController`, exposes ambiguous SSID multi-match chips (`findMatchesBySsid`, e.g., `FLOW-` showing `flow_bl60x` vs `flow_ky_y_series`), and adds `192.168.4.153`, `192.168.8.15`, `192.168.80.1`, and `172.16.11.1` to `network_security_config.xml`.
- **Fail-Closed Sockets & Watchdog:** `native_session.cpp` treats `android_setsocknetwork()` failures as fatal when a valid `networkHandle` was supplied. Watchdog splits `controlPacketsReceived` vs `videoPacketsReceived`, surfaces `RX` vs `1W` (one-way UDP) vs `VID` + battery % on the HUD aircraft pill, and trips `ConnectionLost` on fatal `EPERM`/`ENETUNREACH`/`EHOSTUNREACH` socket errors as well as RX timeouts.
- **Gamepad & Keyboard Safety:** Eliminated the `L2+R2` vs `R2` `MOTOR_STOP` race in `GamepadInputHandler` by deferring single shoulder trigger actions (`MOTOR_STOP`/`TOGGLE_FAST_FLY`) until release unless the `EMERGENCY_STOP_CHORD` fired; initialized `leftStickY = 0f` (safe neutral hover); added continuous 50 ms keyboard held-key throttle ramping; preserved `trimRoll`/`trimPitch` during soft descent assist; rate-limited Vision `tiltCamera` commands to `>= 350 ms`; and cleared cross-protocol RTSP path/port overrides on protocol switch.

---

## 2. Supported Protocol Matrix (Current Implementation)

| ID | Family | Default IP & Ports | Control Payload | Video Transport | Auto Rot |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`s2x`** | `S2X` | `192.168.0.1` `:40000` | 11B `0x66 0x80 ... 0x99` (25 Hz) | `RAW_UDP_JPEG` | `0°` |
| **`flow_bl60x`** | `FLOW_BL60X` | `192.168.169.1` `:8800/:8801` | **Video-Only** (`0x02ef` ACK, zeroed RC) | `RAW_UDP_JPEG` (`0x93` 56B) | `0°` |
| **`flow_ky_y_series`** | `FLOW_KY_Y_SERIES` | `192.168.1.1` `:7099/:7070` | 21B `03 66 14 ... cmd 0x02 ... 99` (20 Hz) | `RTSP_MJPEG` / `RTSP_H264` | `270°` |
| **`cooingdv`** | `COOINGDV` | `192.168.1.1` `:7099/:7070` | 9B TC `03 66 ... 99` + auto-21B GL (20 Hz) | `RTSP_MJPEG` / `RTSP_H264` | `270°` |
| **`cooingdv_gl`** | `COOINGDV_GL` | `192.168.1.1` `:7099/:7070` | Forced 21B GL `03 66 14 ... 99` (20 Hz) | `RTSP_MJPEG` / `RTSP_H264` | `270°` |
| **`cooingdv_jieli`** | `COOINGDV_JIELI` | `192.168.8.15` `:2228/:6666` | UDP `CTP:` JSON `FLYING_CTRL` (20 Hz) | `RTP_JPEG` / `RAW_UDP_H264` | `0°` |
| **`jieli_ac79`** | `JIELI_AC79` | `192.168.80.1` TCP `3333`, UDP `2224`/`6666` | **Video-Only** (`CTP:` TCP state machine) | `RAW_UDP_JPEG` / `RTP_JPEG` / `H264` | `90°` |
| **`lewei`** | `LEWEI` | `192.168.0.1` TCP `:7060` | **Video-Only** (46B `lewei_cmd`) | `LEWEI_TCP_H264` | `0°` |
| **`wifi_uav`** | `WIFI_UAV` | `192.168.169.1` `:8800` | 8B `0x66 ... 0x99` (Experimental, 25 Hz) | `RAW_UDP_JPEG` / `RAW_UDP_H264` | `0°` |
| **`wifi_cam`** | `WIFI_CAM` | `192.168.4.153` `:8090/:8080` | 8B `0xFF 0x08 ...` (Experimental, 20 Hz) | `RAW_UDP_JPEG` | `0°` |
| **`x69_lg`** | `X69_LG` | `192.168.1.1` `:23458/:1234`, HB `:7080` | 25B `CA 47 D5 00 14` + 20B (Experimental, 25 Hz) | `RAW_UDP_H265` / `RTSP_H265` / `RTSP_H264` / `JPEG` | `0°` |

---

## 3. Remaining Non-Hardware-Testable Gaps

1. **No Bundled YOLO `.tflite` Model:** `LiteRtYolo26Detector` ships without an embedded `assets/models/yolo26n.tflite` file; visual target tracking requires importing a compatible model file in Vision settings.
2. **CPU YUV→ARGB Loop & Open-Loop Automation:** `AnnexBVideoDecoder.yuv420ToBitmap` converts decoder planes on CPU; trajectory draw / timed macros remain open-loop timed pulses without optical-flow or GPS position hold.
3. **Physical Hardware Packet Verification:** All wire codecs and RF link recovery paths (`s2x`, `flow_bl60x`, `flow_ky_y_series`, `cooingdv*`, `jieli_ac79`, `lewei`, `wifi_uav`, `wifi_cam`, `x69_lg`) require validation against real OEM aircraft.
