#include "protocol/protocol_packets.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace autopilot::protocol {

uint8_t float_to_u8_axis(float value, float trim) {
  const float combined = std::clamp(value + trim, -1.0f, 1.0f);
  const int scaled = static_cast<int>(std::lround((combined + 1.0f) * 127.5f));
  return static_cast<uint8_t>(std::clamp(scaled, 0, 255));
}

uint8_t xor_checksum(const uint8_t* data, size_t len) {
  uint8_t acc = 0;
  for (size_t i = 0; i < len; ++i) {
    acc ^= data[i];
  }
  return acc;
}

static uint8_t map_common_flags(const StickState& state) {
  uint8_t flags = 0x00;
  if (state.action_flags & ActionFlag::TAKEOFF) flags |= 0x01;
  if (state.action_flags & ActionFlag::CONTROLLED_LAND) flags |= 0x02;
  if (state.action_flags & ActionFlag::EMERGENCY_STOP) flags |= 0x04;
  if (state.action_flags & ActionFlag::CALIBRATE_GYRO) flags |= 0x08;
  if (state.action_flags & ActionFlag::HEADLESS) flags |= 0x10;
  if (state.action_flags & ActionFlag::MOTOR_STOP) flags |= 0x20;
  return flags;
}

std::vector<uint8_t> encode_control_packet(Family family, const StickState& state) {
  const uint8_t roll = float_to_u8_axis(state.roll, state.trim_roll);
  const uint8_t pitch = float_to_u8_axis(state.pitch, state.trim_pitch);
  const uint8_t throttle = float_to_u8_axis(state.throttle);
  const uint8_t yaw = float_to_u8_axis(state.yaw);
  const uint8_t flags = map_common_flags(state);

  switch (family) {
    case Family::S2X: {
      // Macrochip / Vison S2X 11-byte UDP control packet:
      // [0]=0x66 [1]=0x80 [2]=roll [3]=pitch [4]=throttle [5]=yaw
      // [6]=flags [7]=speed_tier [8]=0x00 [9]=xor(2..8) [10]=0x99
      std::vector<uint8_t> pkt(11, 0);
      pkt[0] = 0x66;
      pkt[1] = 0x80;
      pkt[2] = roll;
      pkt[3] = pitch;
      pkt[4] = throttle;
      pkt[5] = yaw;
      pkt[6] = flags;
      pkt[7] = static_cast<uint8_t>(state.speed_tier & 0x03);
      pkt[8] = 0x00;
      pkt[9] = xor_checksum(&pkt[2], 7);
      pkt[10] = 0x99;
      return pkt;
    }

    case Family::WIFI_UAV: {
      // WiFi UAV (V88/D16) 8-byte UDP flight frame on port 8800:
      // [0]=0x66 [1]=roll [2]=pitch [3]=throttle [4]=yaw [5]=flags [6]=xor(1..5) [7]=0x99
      std::vector<uint8_t> pkt(8, 0);
      pkt[0] = 0x66;
      pkt[1] = roll;
      pkt[2] = pitch;
      pkt[3] = throttle;
      pkt[4] = yaw;
      pkt[5] = flags;
      pkt[6] = xor_checksum(&pkt[1], 5);
      pkt[7] = 0x99;
      return pkt;
    }

    case Family::COOINGDV: {
      // CooingDV (KY UFO / RC UFO) 8-byte control frame:
      // [0]=0x66 [1]=roll [2]=pitch [3]=throttle [4]=yaw [5]=flags [6]=xor(1..5) [7]=0x99
      std::vector<uint8_t> pkt(8, 0);
      pkt[0] = 0x66;
      pkt[1] = roll;
      pkt[2] = pitch;
      pkt[3] = throttle;
      pkt[4] = yaw;
      pkt[5] = flags;
      pkt[6] = xor_checksum(&pkt[1], 5);
      pkt[7] = 0x99;
      return pkt;
    }

    case Family::COOINGDV_JIELI: {
      // Jieli CTP framed payload: "CTP:" + topic + length + 8-byte axis payload
      std::vector<uint8_t> pkt(14, 0);
      pkt[0] = 'C';
      pkt[1] = 'T';
      pkt[2] = 'P';
      pkt[3] = ':';
      pkt[4] = 0x00;
      pkt[5] = 0x08;
      pkt[6] = 0x66;
      pkt[7] = roll;
      pkt[8] = pitch;
      pkt[9] = throttle;
      pkt[10] = yaw;
      pkt[11] = flags;
      pkt[12] = xor_checksum(&pkt[7], 5);
      pkt[13] = 0x99;
      return pkt;
    }

    case Family::WIFI_CAM: {
      // WiFi_CAM generic 4-axis UDP frame on port 50000:
      // [0]=0xFF [1]=0x08 [2]=roll [3]=pitch [4]=throttle [5]=yaw [6]=flags [7]=xor(2..6)
      std::vector<uint8_t> pkt(8, 0);
      pkt[0] = 0xFF;
      pkt[1] = 0x08;
      pkt[2] = roll;
      pkt[3] = pitch;
      pkt[4] = throttle;
      pkt[5] = yaw;
      pkt[6] = flags;
      pkt[7] = xor_checksum(&pkt[2], 5);
      return pkt;
    }

    case Family::X69_LG: {
      // X69 LG 10-byte UDP control packet on port 1234 (includes camera tilt & speed tier):
      // [0]=0x66 [1]=roll [2]=pitch [3]=throttle [4]=yaw [5]=flags [6]=speed [7]=tilt [8]=xor(1..7) [9]=0x99
      std::vector<uint8_t> pkt(10, 0);
      pkt[0] = 0x66;
      pkt[1] = roll;
      pkt[2] = pitch;
      pkt[3] = throttle;
      pkt[4] = yaw;
      pkt[5] = flags;
      pkt[6] = static_cast<uint8_t>(state.speed_tier & 0x03);
      pkt[7] = static_cast<uint8_t>(state.camera_tilt);
      pkt[8] = xor_checksum(&pkt[1], 7);
      pkt[9] = 0x99;
      return pkt;
    }

    case Family::ISOLATED_TEST_HARNESS: {
      // Explicitly tagged synthetic loopback frame (0xAA 0x55 magic)
      std::vector<uint8_t> pkt(10, 0);
      pkt[0] = 0xAA;
      pkt[1] = 0x55;
      pkt[2] = roll;
      pkt[3] = pitch;
      pkt[4] = throttle;
      pkt[5] = yaw;
      pkt[6] = flags;
      pkt[7] = static_cast<uint8_t>(state.speed_tier & 0x03);
      pkt[8] = xor_checksum(&pkt[2], 6);
      pkt[9] = 0x5A;
      return pkt;
    }
  }
  return {};
}

std::vector<uint8_t> encode_handshake_packet(Family family) {
  switch (family) {
    case Family::S2X:
      // Macrochip stream trigger
      return {0xEF, 0x00, 0x04, 0x00};
    case Family::WIFI_UAV:
      // WiFi UAV stream wake packet (port 8800)
      return {0xEF, 0x00, 0x04, 0x00};
    case Family::COOINGDV:
      return {0x01, 0x01};
    case Family::COOINGDV_JIELI:
      return {'C', 'T', 'P', ':', 0x00, 0x02, 0x01, 0x01};
    case Family::WIFI_CAM:
      return {0x42, 0x76};
    case Family::X69_LG:
      return {0xEF, 0x02, 0x01, 0x00};
    case Family::ISOLATED_TEST_HARNESS:
      return {0xAA, 0x55, 0x00, 0x01};
  }
  return {};
}

std::vector<uint8_t> encode_heartbeat_packet(Family family) {
  switch (family) {
    case Family::COOINGDV:
    case Family::COOINGDV_JIELI:
      // Observed CooingDV UDP port 7099 heartbeat payload
      return {0x01, 0x01};
    case Family::WIFI_UAV:
      return {0xEF, 0x00, 0x04, 0x00};
    case Family::X69_LG:
      return {0x01, 0x01};
    case Family::S2X:
    case Family::WIFI_CAM:
    case Family::ISOLATED_TEST_HARNESS:
      return {};
  }
  return {};
}

}  // namespace autopilot::protocol
