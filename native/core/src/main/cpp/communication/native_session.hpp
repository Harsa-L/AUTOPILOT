#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

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
  std::string rtsp_path;
  int32_t control_rate_hz = 25;
  int64_t network_handle = 0;
  bool allow_synthetic_harness = false;
  bool rtsp_allow_udp_fallback = true;
  bool rtsp_prefer_udp = false;
};

struct SessionSnapshot {
  bool running = false;
  bool connected = false;
  bool is_synthetic = false;
  uint64_t packets_sent = 0;
  uint64_t packets_received = 0;
  uint64_t control_packets_received = 0;
  uint64_t video_packets_received = 0;
  uint64_t dropped_packets = 0;
  int64_t last_rx_timestamp_ms = 0;
  int32_t last_socket_errno = 0;
  int32_t battery_percent = -1;
  int32_t firmware_status_flags = -1;
  bool control_armed = false;
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

  void set_control_armed(bool armed);
  void update_sticks(float roll, float pitch, float throttle, float yaw, float trim_roll,
                     float trim_pitch, uint8_t speed_tier, int8_t camera_tilt);
  void trigger_action(uint32_t action_flag_bit);
  void trigger_flip(int32_t direction);
  void set_latched_action(uint32_t action_flag_bit, bool enabled);
  void switch_camera(int32_t camera_index);
  void clear_actions();
  void request_keyframe();

  SessionSnapshot snapshot();
  bool pop_video_frame(video::ReassembledFrame& out);

 private:
  enum class RtspStage {
    IDLE = 0,
    TCP_CONNECTING,
    SENT_OPTIONS,
    SENT_DESCRIBE,
    SENT_SETUP,
    SENT_PLAY,
    STREAMING,
    FAILED,
  };

  void worker_loop();
  void close_sockets();
  int open_bound_udp_socket(int64_t network_handle, bool bind_local, int local_port);
  int open_bound_tcp_socket(int64_t network_handle, const std::string& host_ip, int port,
                            bool& out_connected_now);

  // Protocol-specific session state machines
  void poll_jieli_tcp(int64_t now_millis);
  void parse_jieli_ctp_buffer();
  void poll_lewei_tcp(int64_t now_millis, int64_t loop_us);
  void step_bl60x_discovery_and_ack(int64_t now_millis, int64_t loop_us);
  void open_or_reopen_rtsp(int64_t now_millis);
  void step_rtsp_client(int64_t now_micros);
  void parse_rtsp_text_and_interleaved();
  void send_rtsp_request(const std::string& method, const std::string& uri,
                         const std::string& extra_headers);
  bool ensure_rtsp_udp_socket();

  SessionConfig config_;
  video::VideoReassembler reassembler_;

  std::atomic<bool> running_{false};
  std::atomic<bool> connected_{false};
  std::atomic<bool> control_armed_{false};
  std::atomic<bool> cooingdv_gl_detected_{false};
  std::thread worker_;

  int control_fd_ = -1;
  bool control_is_tcp_ = false;
  bool control_tcp_connected_ = false;
  bool tcp_handshake_sent_ = false;
  int video_fd_ = -1;
  int jieli_rtp_fd_ = -1;
  int bl60x_alt_fd_ = -1;
  int heartbeat_fd_ = -1;

  // BL60x probing & ACK state
  int bl60x_locked_fd_ = -1;
  bool bl60x_locked_is_8801_ = false;
  int64_t bl60x_last_probe_8800_ms_ = 0;
  int64_t bl60x_last_probe_8801_ms_ = 0;
  int64_t bl60x_last_ack_ms_ = 0;
  int64_t bl60x_last_video_rx_ms_ = 0;
  uint32_t bl60x_last_frame_id_ = 0;
  uint32_t bl60x_last_frag_mask_ = 0;
  uint8_t bl60x_cam_index_ = 0;

  // Jieli AC79 TCP CTP session state
  std::vector<uint8_t> jieli_rx_buf_;
  bool jieli_date_sent_ = false;
  bool jieli_stream_requested_ = false;
  int32_t jieli_video_w_ = 640;
  int32_t jieli_video_h_ = 480;
  int32_t jieli_video_fps_ = 25;
  int64_t jieli_last_stream_req_ms_ = 0;
  int64_t jieli_last_video_rx_ms_ = 0;
  int64_t jieli_last_keepalive_ms_ = 0;

  // Lewei TCP state
  int64_t lewei_last_video_rx_ms_ = 0;
  int64_t lewei_last_keepalive_ms_ = 0;

  // RTSP client state
  int rtsp_fd_ = -1;
  int rtsp_rtp_udp_fd_ = -1;
  int rtsp_udp_client_port_ = 40010;
  bool rtsp_using_udp_ = false;
  bool rtsp_udp_fallback_attempted_ = false;
  RtspStage rtsp_stage_ = RtspStage::IDLE;
  bool rtsp_is_hevc_ = false;
  bool rtsp_is_mjpeg_ = false;
  int rtsp_cseq_ = 1;
  int rtsp_port_ = 554;
  std::string rtsp_url_;
  std::string rtsp_control_url_;
  std::string rtsp_session_id_;
  std::vector<uint8_t> rtsp_rx_buf_;
  int64_t rtsp_stage_started_ms_ = 0;
  int64_t rtsp_last_keepalive_ms_ = 0;
  int64_t rtsp_last_video_rx_ms_ = 0;
  int64_t rtsp_reconnect_at_ms_ = 0;

  // CooingDV / general timing
  int64_t last_heartbeat_ms_ = 0;

  std::mutex state_mutex_;
  protocol::StickState stick_state_;
  uint32_t latched_actions_ = 0;
  struct PulseEntry {
    uint32_t flag = 0;
    int ticks_remaining = 0;
  };
  PulseEntry active_pulses_[6]{};
  int tilt_ticks_remaining_ = 0;
  int flip_burst_ticks_ = 0;
  float flip_roll_override_ = 0.0f;
  float flip_pitch_override_ = 0.0f;
  std::atomic<bool> keyframe_requested_{false};

  std::atomic<uint64_t> packets_sent_{0};
  std::atomic<uint64_t> packets_received_{0};
  std::atomic<uint64_t> control_packets_received_{0};
  std::atomic<uint64_t> video_packets_received_{0};
  std::atomic<int64_t> last_rx_timestamp_ms_{0};
  std::atomic<int32_t> last_socket_errno_{0};
  std::atomic<int32_t> battery_percent_{-1};
  std::atomic<int32_t> firmware_status_flags_{-1};

  std::mutex hex_mutex_;
  std::string last_command_hex_;
};

}  // namespace autopilot::comm
