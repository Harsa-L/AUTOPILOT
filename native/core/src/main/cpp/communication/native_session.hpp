#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

#include "processing/video_reassembler.hpp"
#include "protocol/protocol_packets.hpp"

namespace autopilot::comm {

struct SessionConfig {
  protocol::Family family = protocol::Family::S2X;
  video::TransportKind video_transport = video::TransportKind::RAW_UDP_JPEG;
  std::string host_ip = "192.168.0.1";
  int32_t control_port = 40000;
  int32_t video_port = 40000;
  int32_t heartbeat_port = 0;
  int32_t control_rate_hz = 25;
  int64_t network_handle = 0;
  bool allow_synthetic_harness = false;
};

struct SessionSnapshot {
  bool running = false;
  bool connected = false;
  bool is_synthetic = false;
  uint64_t packets_sent = 0;
  uint64_t packets_received = 0;
  uint64_t dropped_packets = 0;
  int64_t last_rx_timestamp_ms = 0;
  int32_t last_socket_errno = 0;
  std::string last_command_hex;
};

class NativeSession {
 public:
  explicit NativeSession(const SessionConfig& config);
  ~NativeSession();

  NativeSession(const NativeSession&) = delete;
  NativeSession& operator=(const NativeSession&) = delete;

  bool start();
  void stop();

  void update_sticks(float roll, float pitch, float throttle, float yaw, float trim_roll,
                     float trim_pitch, uint8_t speed_tier, int8_t camera_tilt);
  void trigger_action(uint32_t action_flag_bit);
  void clear_actions();

  SessionSnapshot snapshot();
  bool pop_video_frame(video::ReassembledFrame& out);

 private:
  void worker_loop();
  void close_sockets();
  int open_bound_udp_socket(int64_t network_handle, bool bind_local, int local_port);

  SessionConfig config_;
  video::VideoReassembler reassembler_;

  std::atomic<bool> running_{false};
  std::atomic<bool> connected_{false};
  std::thread worker_;

  int control_fd_ = -1;
  int video_fd_ = -1;
  int heartbeat_fd_ = -1;

  std::mutex state_mutex_;
  protocol::StickState stick_state_;
  uint32_t pending_oneshot_actions_ = 0;

  std::atomic<uint64_t> packets_sent_{0};
  std::atomic<uint64_t> packets_received_{0};
  std::atomic<int64_t> last_rx_timestamp_ms_{0};
  std::atomic<int32_t> last_socket_errno_{0};

  std::mutex hex_mutex_;
  std::string last_command_hex_;
};

}  // namespace autopilot::comm
