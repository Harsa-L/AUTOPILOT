#pragma once

#include <array>
#include <cstdint>
#include <string>
#include <vector>

namespace autopilot::protocol {

enum class Family : int32_t {
  S2X = 1,
  WIFI_UAV = 2,
  COOINGDV = 3,
  COOINGDV_JIELI = 4,
  WIFI_CAM = 5,
  X69_LG = 6,
  FLOW_BL60X = 7,
  COOINGDV_GL = 8,
  FLOW_KY_Y_SERIES = 9,
  JIELI_AC79 = 10,
  LEWEI = 11,
  ISOLATED_TEST_HARNESS = 99,
};

struct StickState {
  float roll = 0.0f;       // -1.0 .. 1.0
  float pitch = 0.0f;      // -1.0 .. 1.0
  float throttle = 0.0f;   // -1.0 .. 1.0
  float yaw = 0.0f;        // -1.0 .. 1.0
  float trim_roll = 0.0f;
  float trim_pitch = 0.0f;
  uint32_t action_flags = 0;  // bitmask defined in ActionFlag
  uint8_t speed_tier = 1;     // 0=low (30%), 1=med (60%), 2=high/fast-fly (100%)
  int8_t camera_tilt = 0;     // -1, 0, +1
};

namespace ActionFlag {
inline constexpr uint32_t TAKEOFF = 1u << 0;
inline constexpr uint32_t CONTROLLED_LAND = 1u << 1;
inline constexpr uint32_t MOTOR_STOP = 1u << 2;
inline constexpr uint32_t EMERGENCY_STOP = 1u << 3;
inline constexpr uint32_t CALIBRATE_GYRO = 1u << 4;
inline constexpr uint32_t HEADLESS = 1u << 5;
inline constexpr uint32_t FLIP_360 = 1u << 6;
inline constexpr uint32_t UNLOCK_MOTORS = 1u << 7;
inline constexpr uint32_t FAST_FLY = 1u << 8;
inline constexpr uint32_t SD_CAPTURE_PULSE = 1u << 9;
inline constexpr uint32_t SD_RECORD_LATCH = 1u << 10;

inline constexpr uint32_t kLatchedMask =
    HEADLESS | EMERGENCY_STOP | FAST_FLY | SD_RECORD_LATCH;
}  // namespace ActionFlag

struct RxTelemetryUpdate {
  bool valid_battery = false;
  int32_t battery_percent = -1;
  bool valid_status_flags = false;
  int32_t status_flags = 0;
  int32_t detected_cooingdv_id = -1;
  bool cooingdv_is_gl = false;
  bool cooingdv_photo_sync = false;
  bool cooingdv_video_sync = false;
};

inline bool is_video_only_family(Family family) {
  return family == Family::FLOW_BL60X ||
         family == Family::JIELI_AC79 ||
         family == Family::LEWEI;
}

uint8_t float_to_u8_axis(float value, float trim = 0.0f);
uint8_t float_to_cooingdv_axis(float value, float trim = 0.0f);
uint8_t xor_checksum(const uint8_t* data, size_t len);

/**
 * Encodes a protocol-specific flight command packet.
 * Video-only families (FLOW_BL60X, JIELI_AC79, LEWEI) strictly return empty vectors.
 */
std::vector<uint8_t> encode_control_packet(Family family, const StickState& state);

/**
 * Encodes protocol-specific keep-alive / stream-start handshake packets.
 */
std::vector<uint8_t> encode_handshake_packet(Family family);

/**
 * Encodes protocol-specific 1 Hz heartbeat packet.
 */
std::vector<uint8_t> encode_heartbeat_packet(Family family);

/**
 * Encodes discrete disconnect/exit packet (e.g. CooingDV 08 01, Jieli CLOSE_RT_STREAM, Lewei cmd=3).
 */
std::vector<uint8_t> encode_stop_packet(Family family);

/**
 * Encodes discrete camera switch packet (e.g. CooingDV 06 01/02 or Jieli SWITCH_CAMERA).
 */
std::vector<uint8_t> encode_camera_switch_packet(Family family, int32_t camera_index);

/**
 * Encodes Jieli CTP framing: "CTP:" + u16_le(topic_len) + topic + u32_le(json_len) + json.
 */
std::vector<uint8_t> encode_ctp_message(const std::string& topic, const std::string& json_payload);

/**
 * Encodes Lewei 46-byte command framing ("lewei_cmd\0" + 9 little-endian uint32 fields).
 */
std::vector<uint8_t> encode_lewei_cmd(uint32_t cmd, uint32_t arg);

/**
 * Encodes FLOW_BL60X 0x02ef video acknowledgement with zeroed/empty flight command section.
 */
std::vector<uint8_t> encode_flow_bl60x_ack(uint32_t frame_id, uint32_t frag_mask,
                                           uint32_t qpara = 0x324B142Du,
                                           uint8_t camera_index = 0);

/**
 * Parses genuine hardware status/battery/variant datagrams when checksum & framing validate.
 */
RxTelemetryUpdate parse_rx_telemetry(Family family, const uint8_t* data, size_t len);

}  // namespace autopilot::protocol
