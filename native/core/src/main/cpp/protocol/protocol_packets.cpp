#include "protocol/protocol_packets.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

namespace autopilot::protocol {

namespace {
void append_u16_le(std::vector<uint8_t>& dst, uint16_t v) {
  dst.push_back(static_cast<uint8_t>(v & 0xFF));
  dst.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
}

void append_u32_le(std::vector<uint8_t>& dst, uint32_t v) {
  dst.push_back(static_cast<uint8_t>(v & 0xFF));
  dst.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
  dst.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
  dst.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
}

bool is_cooingdv_gl_id(uint8_t id) {
  if (id == 82 || id == 85 || id == 103) return true;
  return id >= 90 && id <= 101;
}
}  // namespace

uint8_t float_to_u8_axis(float value, float trim) {
  const float combined = std::clamp(value + trim, -1.0f, 1.0f);
  const int scaled = static_cast<int>(std::lround((combined + 1.0f) * 127.5f));
  return static_cast<uint8_t>(std::clamp(scaled, 0, 255));
}

uint8_t float_to_cooingdv_axis(float value, float trim) {
  const float combined = std::clamp(value + trim, -1.0f, 1.0f);
  const int scaled = static_cast<int>(std::lround(128.0f + combined * 127.0f));
  return static_cast<uint8_t>(std::clamp(scaled, 1, 255));
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
  if (state.action_flags & ActionFlag::FLIP_360) flags |= 0x40;
  if (state.action_flags & ActionFlag::UNLOCK_MOTORS) flags |= 0x80;
  return flags;
}

static uint8_t map_cooingdv_tc_flags(const StickState& state) {
  uint8_t flags = 0x00;
  if (state.action_flags & ActionFlag::TAKEOFF) flags |= 0x01;
  if (state.action_flags & ActionFlag::CONTROLLED_LAND) flags |= 0x02;
  if ((state.action_flags & (ActionFlag::EMERGENCY_STOP | ActionFlag::MOTOR_STOP)) != 0) {
    flags |= 0x04;
  }
  if (state.action_flags & ActionFlag::FLIP_360) flags |= 0x08;
  if (state.action_flags & ActionFlag::HEADLESS) flags |= 0x10;
  if (state.action_flags & ActionFlag::UNLOCK_MOTORS) flags |= 0x20;
  if (state.action_flags & ActionFlag::LIGHT_TOGGLE) flags |= 0x40;
  if (state.action_flags & ActionFlag::CALIBRATE_GYRO) flags |= 0x80;
  return flags;
}

std::vector<uint8_t> encode_ctp_message(const std::string& topic,
                                        const std::string& json_payload) {
  std::vector<uint8_t> pkt;
  pkt.reserve(4 + 2 + topic.size() + 4 + json_payload.size());
  pkt.push_back('C');
  pkt.push_back('T');
  pkt.push_back('P');
  pkt.push_back(':');
  append_u16_le(pkt, static_cast<uint16_t>(topic.size()));
  pkt.insert(pkt.end(), topic.begin(), topic.end());
  append_u32_le(pkt, static_cast<uint32_t>(json_payload.size()));
  pkt.insert(pkt.end(), json_payload.begin(), json_payload.end());
  return pkt;
}

std::vector<uint8_t> encode_lewei_cmd(uint32_t cmd, uint32_t arg) {
  // 46 bytes: "lewei_cmd\0" (10 bytes) + 9 uint32_t little-endian fields (36 bytes)
  std::vector<uint8_t> pkt(46, 0);
  std::memcpy(pkt.data(), "lewei_cmd\0", 10);
  // Field 0: command (offset 10), Field 1: argument (offset 14)
  auto write_u32 = [&](size_t off, uint32_t val) {
    pkt[off] = static_cast<uint8_t>(val & 0xFF);
    pkt[off + 1] = static_cast<uint8_t>((val >> 8) & 0xFF);
    pkt[off + 2] = static_cast<uint8_t>((val >> 16) & 0xFF);
    pkt[off + 3] = static_cast<uint8_t>((val >> 24) & 0xFF);
  };
  write_u32(10, cmd);
  write_u32(14, arg);
  return pkt;
}

std::vector<uint8_t> encode_flow_bl60x_ack(uint32_t frame_id, uint32_t frag_mask,
                                           uint32_t qpara, uint8_t camera_index) {
  // BL60x video-only 0x02ef acknowledgement envelope (header + frame ack + zeroed RC block).
  std::vector<uint8_t> pkt(48, 0);
  pkt[0] = 0xEF;
  pkt[1] = 0x02;
  pkt[2] = 0x30;  // 48 bytes total
  pkt[3] = 0x00;
  // Frame ID being acknowledged
  pkt[4] = static_cast<uint8_t>(frame_id & 0xFF);
  pkt[5] = static_cast<uint8_t>((frame_id >> 8) & 0xFF);
  pkt[6] = static_cast<uint8_t>((frame_id >> 16) & 0xFF);
  pkt[7] = static_cast<uint8_t>((frame_id >> 24) & 0xFF);
  // Received fragment bitmask
  pkt[8] = static_cast<uint8_t>(frag_mask & 0xFF);
  pkt[9] = static_cast<uint8_t>((frag_mask >> 8) & 0xFF);
  pkt[10] = static_cast<uint8_t>((frag_mask >> 16) & 0xFF);
  pkt[11] = static_cast<uint8_t>((frag_mask >> 24) & 0xFF);
  // Quality parameters (default 0x324B142D) & active camera index
  pkt[12] = static_cast<uint8_t>((qpara >> 24) & 0xFF);
  pkt[13] = static_cast<uint8_t>((qpara >> 16) & 0xFF);
  pkt[14] = static_cast<uint8_t>((qpara >> 8) & 0xFF);
  pkt[15] = static_cast<uint8_t>(qpara & 0xFF);
  pkt[16] = camera_index;
  // Bytes 17..47 remain 0x00 (flight control command field intentionally omitted/zeroed).
  return pkt;
}

std::vector<uint8_t> encode_control_packet(Family family, const StickState& state) {
  if (is_video_only_family(family)) {
    return {};
  }

  const uint8_t roll = float_to_u8_axis(state.roll, state.trim_roll);
  const uint8_t pitch = float_to_u8_axis(state.pitch, state.trim_pitch);
  const uint8_t throttle = float_to_u8_axis(state.throttle);
  const uint8_t yaw = float_to_u8_axis(state.yaw);
  const uint8_t flags = map_common_flags(state);
  const uint8_t effective_speed =
      (state.action_flags & ActionFlag::FAST_FLY) ? 2 : static_cast<uint8_t>(state.speed_tier & 0x03);

  switch (family) {
    case Family::S2X: {
      std::vector<uint8_t> pkt(11, 0);
      pkt[0] = 0x66;
      pkt[1] = 0x80;
      pkt[2] = roll;
      pkt[3] = pitch;
      pkt[4] = throttle;
      pkt[5] = yaw;
      pkt[6] = flags;
      pkt[7] = effective_speed;
      pkt[8] = static_cast<uint8_t>(
          ((state.action_flags & ActionFlag::FAST_FLY) ? 0x01 : 0x00) |
          ((state.action_flags & ActionFlag::SD_CAPTURE_PULSE) ? 0x02 : 0x00) |
          ((state.action_flags & ActionFlag::SD_RECORD_LATCH) ? 0x04 : 0x00));
      pkt[9] = xor_checksum(&pkt[2], 7);
      pkt[10] = 0x99;
      return pkt;
    }

    case Family::WIFI_UAV: {
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
      // Classic CooingDV TC 9-byte UDP frame:
      // 03 66 B1 B2 ACC TURN FLAGS CHECKSUM 99
      const uint8_t b1 = float_to_cooingdv_axis(state.roll, state.trim_roll);
      const uint8_t b2 = float_to_cooingdv_axis(state.pitch, state.trim_pitch);
      uint8_t acc = float_to_cooingdv_axis(state.throttle);
      if (acc == 1) acc = 0;  // CooingDV FlyController coerces 1 -> 0 before XOR & TX
      const uint8_t turn = float_to_cooingdv_axis(state.yaw);
      const uint8_t tc_flags = map_cooingdv_tc_flags(state);

      std::vector<uint8_t> pkt(9, 0);
      pkt[0] = 0x03;
      pkt[1] = 0x66;
      pkt[2] = b1;
      pkt[3] = b2;
      pkt[4] = acc;
      pkt[5] = turn;
      pkt[6] = tc_flags;
      pkt[7] = xor_checksum(&pkt[2], 5);
      pkt[8] = 0x99;
      return pkt;
    }

    case Family::COOINGDV_GL: {
      // CooingDV GL extended 21-byte UDP frame:
      // 03 66 14 B1 B2 ACC TURN FLAGS1 FLAGS2 00..00 CHECKSUM 99
      const uint8_t b1 = float_to_cooingdv_axis(state.roll, state.trim_roll);
      const uint8_t b2 = float_to_cooingdv_axis(state.pitch, state.trim_pitch);
      uint8_t acc = float_to_cooingdv_axis(state.throttle);
      if (acc == 1) acc = 0;
      const uint8_t turn = float_to_cooingdv_axis(state.yaw);

      uint8_t flags1 = 0x00;
      if ((state.action_flags & (ActionFlag::TAKEOFF | ActionFlag::CONTROLLED_LAND)) != 0) {
        flags1 |= 0x01;
      }
      if ((state.action_flags & (ActionFlag::EMERGENCY_STOP | ActionFlag::MOTOR_STOP)) != 0) {
        flags1 |= 0x02;
      }
      if (state.action_flags & ActionFlag::CALIBRATE_GYRO) flags1 |= 0x04;
      if (state.action_flags & ActionFlag::FLIP_360) flags1 |= 0x08;
      if (state.action_flags & ActionFlag::LIGHT_TOGGLE) flags1 |= 0x10;

      uint8_t flags2 = 0x00;
      if (state.action_flags & ActionFlag::HEADLESS) flags2 |= 0x01;
      if (state.action_flags & ActionFlag::ALT_HOLD) flags2 |= 0x02;

      std::vector<uint8_t> pkt(21, 0);
      pkt[0] = 0x03;
      pkt[1] = 0x66;
      pkt[2] = 0x14;
      pkt[3] = b1;
      pkt[4] = b2;
      pkt[5] = acc;
      pkt[6] = turn;
      pkt[7] = flags1;
      pkt[8] = flags2;
      pkt[19] = xor_checksum(&pkt[3], 16);
      pkt[20] = 0x99;
      return pkt;
    }

    case Family::FLOW_KY_Y_SERIES: {
      // pyDroneWire FLOW-UFO / KY Y-Series 21-byte frame with 30/60/100% client scaling
      const float scale = (effective_speed == 0) ? 0.30f : ((effective_speed == 1) ? 0.60f : 1.00f);
      const uint8_t b1 = float_to_cooingdv_axis(state.roll * scale, state.trim_roll * scale);
      const uint8_t b2 = float_to_cooingdv_axis(state.pitch * scale, state.trim_pitch * scale);
      uint8_t acc = float_to_cooingdv_axis(state.throttle * scale);
      if (acc == 1) acc = 0;
      const uint8_t turn = float_to_cooingdv_axis(state.yaw * scale);

      uint8_t cmd = 0x00;
      if ((state.action_flags & (ActionFlag::EMERGENCY_STOP | ActionFlag::MOTOR_STOP)) != 0) {
        cmd = 0x02;
      } else if ((state.action_flags & (ActionFlag::TAKEOFF | ActionFlag::CONTROLLED_LAND)) != 0) {
        cmd = 0x01;
      } else if (state.action_flags & ActionFlag::CALIBRATE_GYRO) {
        cmd = 0x03;
      } else if (state.action_flags & ActionFlag::FLIP_360) {
        cmd = 0x05;
      } else if (state.action_flags & ActionFlag::HEADLESS) {
        cmd = 0x04;
      }

      std::vector<uint8_t> pkt(21, 0);
      pkt[0] = 0x03;
      pkt[1] = 0x66;
      pkt[2] = 0x14;
      pkt[3] = b1;
      pkt[4] = b2;
      pkt[5] = acc;
      pkt[6] = turn;
      pkt[7] = cmd;
      pkt[8] = 0x02;  // pyDroneWire constant wire speed byte
      pkt[19] = xor_checksum(&pkt[3], 16);
      pkt[20] = 0x99;
      return pkt;
    }

    case Family::COOINGDV_JIELI: {
      // KY FPV 2.0.0 Jieli DeviceClient UDP CTP JSON FLYING_CTRL envelope on :2228
      const uint8_t b1 = float_to_cooingdv_axis(state.roll, state.trim_roll);
      const uint8_t b2 = float_to_cooingdv_axis(state.pitch, state.trim_pitch);
      uint8_t acc = float_to_cooingdv_axis(state.throttle);
      if (acc == 1) acc = 0;
      const uint8_t turn = float_to_cooingdv_axis(state.yaw);
      const uint8_t tc_flags = map_cooingdv_tc_flags(state);
      const uint8_t sum_bytes[5] = {b1, b2, acc, turn, tc_flags};
      const uint8_t chk = xor_checksum(sum_bytes, 5);
      char json[256];
      std::snprintf(
          json, sizeof(json),
          "{\"op\":\"PUT\",\"param\":{\"BYTE0\":\"102\",\"BYTE1\":\"%u\",\"BYTE2\":\"%u\","
          "\"BYTE3\":\"%u\",\"BYTE4\":\"%u\",\"BYTE5\":\"%u\",\"BYTE6\":\"%u\",\"BYTE7\":\"153\"}}",
          static_cast<unsigned>(b1), static_cast<unsigned>(b2), static_cast<unsigned>(acc),
          static_cast<unsigned>(turn), static_cast<unsigned>(tc_flags), static_cast<unsigned>(chk));
      return encode_ctp_message("FLYING_CTRL", json);
    }

    case Family::WIFI_CAM: {
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
      // TurboDrone x69_lg wrapped 20-byte payload: CA 47 D5 00 14 <20B inner frame>
      std::vector<uint8_t> pkt(25, 0);
      pkt[0] = 0xCA;
      pkt[1] = 0x47;
      pkt[2] = 0xD5;
      pkt[3] = 0x00;
      pkt[4] = 0x14;
      pkt[5] = 0x66;
      pkt[6] = roll;
      pkt[7] = pitch;
      pkt[8] = throttle;
      pkt[9] = yaw;
      pkt[10] = flags;
      pkt[11] = effective_speed;
      pkt[12] = static_cast<uint8_t>(state.camera_tilt);
      pkt[23] = xor_checksum(&pkt[6], 17);
      pkt[24] = 0x99;
      return pkt;
    }

    case Family::FLOW_BL60X:
    case Family::JIELI_AC79:
    case Family::LEWEI:
      return {};

    case Family::ISOLATED_TEST_HARNESS: {
      std::vector<uint8_t> pkt(10, 0);
      pkt[0] = 0xAA;
      pkt[1] = 0x55;
      pkt[2] = roll;
      pkt[3] = pitch;
      pkt[4] = throttle;
      pkt[5] = yaw;
      pkt[6] = flags;
      pkt[7] = effective_speed;
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
    case Family::WIFI_UAV:
    case Family::FLOW_BL60X:
      return {0xEF, 0x00, 0x04, 0x00};
    case Family::COOINGDV:
    case Family::COOINGDV_GL:
    case Family::FLOW_KY_Y_SERIES:
      return {0x01, 0x01};
    case Family::COOINGDV_JIELI:
      return encode_ctp_message("CONTROL_MODE", "{\"op\":\"PUT\",\"param\":{\"state\":\"1\"}}");
    case Family::JIELI_AC79:
      return encode_ctp_message("APP_ACCESS", "{\"op\":\"PUT\",\"param\":{\"type\":\"1\",\"ver\":\"1.0\"}}");
    case Family::LEWEI:
      return encode_lewei_cmd(2, 1);
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
    case Family::COOINGDV_GL:
    case Family::FLOW_KY_Y_SERIES:
      return {0x01, 0x01};
    case Family::COOINGDV_JIELI:
    case Family::JIELI_AC79:
      return encode_ctp_message("CTP_KEEP_ALIVE", "{\"op\":\"PUT\",\"param\":{}}");
    case Family::LEWEI:
      return encode_lewei_cmd(1, 0);
    case Family::WIFI_UAV:
      return {0xEF, 0x00, 0x04, 0x00};
    case Family::X69_LG:
      return {0xD1};
    case Family::FLOW_BL60X:
    case Family::S2X:
    case Family::WIFI_CAM:
    case Family::ISOLATED_TEST_HARNESS:
      return {};
  }
  return {};
}

std::vector<uint8_t> encode_stop_packet(Family family) {
  switch (family) {
    case Family::COOINGDV:
    case Family::COOINGDV_GL:
    case Family::FLOW_KY_Y_SERIES:
      return {0x08, 0x01};
    case Family::JIELI_AC79:
      return encode_ctp_message("CLOSE_RT_STREAM", "{\"op\":\"PUT\",\"param\":{}}");
    case Family::LEWEI:
      return encode_lewei_cmd(3, 0);
    default:
      return {};
  }
}

std::vector<uint8_t> encode_camera_switch_packet(Family family, int32_t camera_index) {
  const uint8_t cam = (camera_index == 2) ? 0x02 : 0x01;
  switch (family) {
    case Family::COOINGDV:
    case Family::COOINGDV_GL:
    case Family::FLOW_KY_Y_SERIES:
      return {0x06, cam};
    case Family::COOINGDV_JIELI: {
      char json[64];
      std::snprintf(json, sizeof(json), "{\"op\":\"PUT\",\"param\":{\"cam\":\"%u\"}}",
                    static_cast<unsigned>(cam));
      return encode_ctp_message("SWITCH_CAMERA", json);
    }
    default:
      return {};
  }
}

std::vector<uint8_t> encode_cooingdv_password_packet(const std::string& digits_8) {
  if (digits_8.size() != 8) return {};
  std::vector<uint8_t> pkt(9, 0);
  pkt[0] = 0x0A;
  for (size_t i = 0; i < 8; ++i) {
    const char ch = digits_8[i];
    if (ch < '0' || ch > '9') return {};
    pkt[i + 1] = static_cast<uint8_t>(ch - '0');
  }
  return pkt;
}

std::vector<uint8_t> encode_servo_probe_packet(uint8_t byte_index_9_to_18, uint8_t hex_value) {
  if (byte_index_9_to_18 < 9 || byte_index_9_to_18 > 18) return {};
  std::vector<uint8_t> pkt(21, 0);
  pkt[0] = 0x03;
  pkt[1] = 0x66;
  pkt[2] = 0x14;
  pkt[3] = 0x80;
  pkt[4] = 0x80;
  pkt[5] = 0x80;
  pkt[6] = 0x80;
  pkt[7] = 0x00;
  pkt[8] = 0x02;
  pkt[byte_index_9_to_18] = hex_value;
  pkt[19] = xor_checksum(&pkt[3], 16);
  pkt[20] = 0x99;
  return pkt;
}

RxTelemetryUpdate parse_rx_telemetry(Family family, const uint8_t* data, size_t len) {
  RxTelemetryUpdate out;
  if (!data || len == 0) return out;

  // CooingDV UDP :7099 telemetry / capability / gallery sync datagrams
  if (family == Family::COOINGDV || family == Family::COOINGDV_GL ||
      family == Family::FLOW_KY_Y_SERIES) {
    out.valid_status_flags = true;
    out.status_flags = static_cast<int32_t>(data[0]);
    out.detected_cooingdv_id = static_cast<int32_t>(data[0]);
    if (is_cooingdv_gl_id(data[0])) {
      out.cooingdv_is_gl = true;
    }
    if (len >= 3 && data[2] == 0x4D) {
      out.cooingdv_photo_sync = true;
      out.cooingdv_photo_counter = (len >= 4) ? static_cast<int32_t>(data[3]) : -1;
    } else if (len >= 3 && data[2] == 0x58) {
      out.cooingdv_video_sync = true;
      out.cooingdv_video_counter = (len >= 5) ? static_cast<int32_t>(data[4])
                                              : ((len >= 4) ? static_cast<int32_t>(data[3]) : -1);
    }
    return out;
  }

  if (len < 4) return out;

  // Checksum-verified 8..16 byte telemetry ACK frame returned on control/heartbeat ports:
  // [0]=0x66 [1]=type(0x01/0x81 status) ... [len-2]=xor(1..len-3) [len-1]=0x99
  if (len >= 8 && len <= 16 && data[0] == 0x66 && data[len - 1] == 0x99) {
    const uint8_t expected = xor_checksum(&data[1], len - 3);
    if (expected == data[len - 2]) {
      out.valid_status_flags = true;
      out.status_flags = static_cast<int32_t>(data[1]);
      if (len >= 9 && (data[1] == 0x81 || data[1] == 0x02) && data[3] <= 100) {
        out.valid_battery = true;
        out.battery_percent = static_cast<int32_t>(data[3]);
      }
      return out;
    }
  }

  // Jieli CTP textual/JSON status reply: "CTP:" ... battery / BAT:
  if ((family == Family::COOINGDV_JIELI || family == Family::JIELI_AC79) &&
      len >= 10 && std::memcmp(data, "CTP:", 4) == 0) {
    out.valid_status_flags = true;
    out.status_flags = 0x01;
    std::string_view sv(reinterpret_cast<const char*>(data), len);
    for (const char* marker : {"BAT:", "\"battery\":", "\"bat\":"}) {
      const size_t mlen = std::strlen(marker);
      const size_t pos = sv.find(marker);
      if (pos != std::string_view::npos) {
        size_t j = pos + mlen;
        while (j < len && (data[j] == '"' || data[j] == ' ')) ++j;
        int val = 0;
        bool has_digit = false;
        while (j < len && data[j] >= '0' && data[j] <= '9' && val <= 100) {
          has_digit = true;
          val = val * 10 + (data[j] - '0');
          ++j;
        }
        if (has_digit && val >= 0 && val <= 100) {
          out.valid_battery = true;
          out.battery_percent = val;
          break;
        }
      }
    }
  }
  return out;
}

}  // namespace autopilot::protocol
