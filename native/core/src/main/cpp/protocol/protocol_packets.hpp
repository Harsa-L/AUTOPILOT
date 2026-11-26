#pragma once

#include <array>
#include <cstdint>
#include <vector>

namespace autopilot::protocol {

enum class Family : int32_t {
  S2X = 1,
  WIFI_UAV = 2,
  COOINGDV = 3,
  COOINGDV_JIELI = 4,
  WIFI_CAM = 5,
  X69_LG = 6,
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
  uint8_t speed_tier = 1;     // 0=low, 1=med, 2=high
  int8_t camera_tilt = 0;     // -1, 0, +1
};

namespace ActionFlag {
inline constexpr uint32_t TAKEOFF = 1u << 0;
inline constexpr uint32_t CONTROLLED_LAND = 1u << 1;
inline constexpr uint32_t MOTOR_STOP = 1u << 2;
inline constexpr uint32_t EMERGENCY_STOP = 1u << 3;
inline constexpr uint32_t CALIBRATE_GYRO = 1u << 4;
inline constexpr uint32_t HEADLESS = 1u << 5;
}  // namespace ActionFlag

uint8_t float_to_u8_axis(float value, float trim = 0.0f);
uint8_t xor_checksum(const uint8_t* data, size_t len);

/**
 * Encodes a protocol-specific flight command packet according to TurboDrone
 * reverse-engineered specifications for each supported family.
 */
std::vector<uint8_t> encode_control_packet(Family family, const StickState& state);

/**
 * Encodes protocol-specific keep-alive / stream-start handshake packets.
 */
std::vector<uint8_t> encode_handshake_packet(Family family);

/**
 * Encodes protocol-specific heartbeat packet (e.g. CooingDV 0x01 0x01 on 7099).
 */
std::vector<uint8_t> encode_heartbeat_packet(Family family);

}  // namespace autopilot::protocol
