#include "communication/native_session.hpp"

#include <android/multinetwork.h>
#include <arpa/inet.h>
#include <cctype>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sstream>
#include <sys/socket.h>
#include <unistd.h>

namespace autopilot::comm {

namespace {
int64_t now_ms() {
  return std::chrono::duration_cast<std::chrono::milliseconds>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

int64_t now_us() {
  return std::chrono::duration_cast<std::chrono::microseconds>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

std::string bytes_to_hex(const std::vector<uint8_t>& bytes) {
  std::string out;
  out.reserve(bytes.size() * 3);
  char buf[4];
  for (size_t i = 0; i < bytes.size(); ++i) {
    std::snprintf(buf, sizeof(buf), i + 1 < bytes.size() ? "%02X " : "%02X", bytes[i]);
    out.append(buf);
  }
  return out;
}

std::vector<uint8_t> decode_base64(const std::string& in) {
  static constexpr int8_t kTable[256] = {
      -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
      -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
      -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,62,-1,-1,-1,63,
      52,53,54,55,56,57,58,59,60,61,-1,-1,-1,-1,-1,-1,
      -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,13,14,
      15,16,17,18,19,20,21,22,23,24,25,-1,-1,-1,-1,-1,
      -1,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,
      41,42,43,44,45,46,47,48,49,50,51,-1,-1,-1,-1,-1,
      -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
      -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
      -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
      -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
      -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
      -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
      -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
      -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1};
  std::vector<uint8_t> out;
  uint32_t val = 0;
  int valb = -8;
  for (unsigned char c : in) {
    if (c == '=') break;
    const int8_t d = kTable[c];
    if (d == -1) continue;
    val = (val << 6) + static_cast<uint32_t>(d);
    valb += 6;
    if (valb >= 0) {
      out.push_back(static_cast<uint8_t>((val >> valb) & 0xFF));
      valb -= 8;
    }
  }
  return out;
}

std::string to_lower_ascii(std::string s) {
  for (char& ch : s) {
    ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
  }
  return s;
}

std::string trim_ws(const std::string& s) {
  size_t b = 0;
  while (b < s.size() && (s[b] == ' ' || s[b] == '\t' || s[b] == '\r' || s[b] == '\n')) ++b;
  size_t e = s.size();
  while (e > b && (s[e - 1] == ' ' || s[e - 1] == '\t' || s[e - 1] == '\r' || s[e - 1] == '\n')) --e;
  return s.substr(b, e - b);
}

bool is_cooingdv_like(protocol::Family f) {
  return f == protocol::Family::COOINGDV ||
         f == protocol::Family::COOINGDV_GL ||
         f == protocol::Family::FLOW_KY_Y_SERIES;
}

bool is_rtsp_transport(video::TransportKind t) {
  return t == video::TransportKind::RTSP_H264 ||
         t == video::TransportKind::RTSP_H265 ||
         t == video::TransportKind::RTSP_MJPEG;
}

void resolve_rtsp_endpoint(const SessionConfig& cfg, std::string& out_url, int& out_port) {
  const int default_port =
      is_cooingdv_like(cfg.family) ? 7070 : (cfg.video_port > 0 ? cfg.video_port : 554);
  if (cfg.rtsp_path.rfind("rtsp://", 0) == 0) {
    const size_t after_scheme = 7;
    const size_t slash = cfg.rtsp_path.find('/', after_scheme);
    const size_t colon = cfg.rtsp_path.find(':', after_scheme);
    std::string path = (slash != std::string::npos) ? cfg.rtsp_path.substr(slash) : "/webcam";
    if (cfg.video_port > 0 && cfg.video_port != 7070 && cfg.video_port != 554 &&
        cfg.video_port != 1234) {
      out_port = cfg.video_port;
    } else if (colon != std::string::npos && (slash == std::string::npos || colon < slash)) {
      const int parsed = std::atoi(cfg.rtsp_path.substr(colon + 1, slash - colon - 1).c_str());
      out_port = parsed > 0 ? parsed : default_port;
    } else {
      out_port = default_port;
    }
    out_url = "rtsp://" + cfg.host_ip + ":" + std::to_string(out_port) + path;
  } else {
    std::string path = cfg.rtsp_path.empty() ? "/webcam" : cfg.rtsp_path;
    if (path[0] != '/') path = "/" + path;
    out_port = default_port;
    if (out_port == 1234 && cfg.family == protocol::Family::X69_LG) {
      out_port = (path.find("ch00") != std::string::npos) ? 554 : 7070;
    }
    out_url = "rtsp://" + cfg.host_ip + ":" + std::to_string(out_port) + path;
  }
}

uint16_t read_u16_le_s(const uint8_t* p) {
  return static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8);
}

uint32_t read_u32_le_s(const uint8_t* p) {
  return static_cast<uint32_t>(p[0]) |
         (static_cast<uint32_t>(p[1]) << 8) |
         (static_cast<uint32_t>(p[2]) << 16) |
         (static_cast<uint32_t>(p[3]) << 24);
}
}  // namespace

NativeSession::NativeSession(const SessionConfig& config)
    : config_(config), reassembler_(config.video_transport) {
  rtsp_rx_buf_.reserve(128 * 1024);
  jieli_rx_buf_.reserve(16 * 1024);
}

NativeSession::~NativeSession() { stop(); }

int NativeSession::open_bound_udp_socket(int64_t network_handle, bool bind_local, int local_port) {
  const int fd = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  if (fd < 0) {
    last_socket_errno_.store(errno, std::memory_order_relaxed);
    return -1;
  }

  const int flags = ::fcntl(fd, F_GETFL, 0);
  if (flags >= 0) {
    ::fcntl(fd, F_SETFL, flags | O_NONBLOCK);
  }

  const int rcvbuf = 512 * 1024;
  ::setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));

  if (network_handle > 0) {
    const int rc = ::android_setsocknetwork(static_cast<net_handle_t>(network_handle), fd);
    if (rc != 0) {
      // Fail-closed: refuse unbound UDP transmission when an AP handle was requested.
      last_socket_errno_.store(errno, std::memory_order_relaxed);
      ::close(fd);
      return -1;
    }
  }

  if (bind_local && local_port > 0) {
    int reuse = 1;
    ::setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(static_cast<uint16_t>(local_port));
    if (::bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
      last_socket_errno_.store(errno, std::memory_order_relaxed);
      ::close(fd);
      return -1;
    }
  }
  return fd;
}

int NativeSession::open_bound_tcp_socket(int64_t network_handle, const std::string& host_ip,
                                         int port, bool& out_connected_now) {
  out_connected_now = false;
  const int fd = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  if (fd < 0) {
    last_socket_errno_.store(errno, std::memory_order_relaxed);
    return -1;
  }

  const int flags = ::fcntl(fd, F_GETFL, 0);
  if (flags >= 0) {
    ::fcntl(fd, F_SETFL, flags | O_NONBLOCK);
  }
  int nodelay = 1;
  ::setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));

  if (network_handle > 0) {
    const int rc = ::android_setsocknetwork(static_cast<net_handle_t>(network_handle), fd);
    if (rc != 0) {
      last_socket_errno_.store(errno, std::memory_order_relaxed);
      ::close(fd);
      return -1;
    }
  }

  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_port = htons(static_cast<uint16_t>(port));
  if (::inet_pton(AF_INET, host_ip.c_str(), &addr.sin_addr) != 1) {
    ::close(fd);
    return -1;
  }

  const int rc = ::connect(fd, reinterpret_cast<const sockaddr*>(&addr), sizeof(addr));
  if (rc == 0) {
    out_connected_now = true;
  } else if (errno != EINPROGRESS) {
    last_socket_errno_.store(errno, std::memory_order_relaxed);
    ::close(fd);
    return -1;
  }
  return fd;
}

void NativeSession::open_or_reopen_rtsp(int64_t now_millis) {
  if (rtsp_fd_ >= 0) {
    ::close(rtsp_fd_);
    rtsp_fd_ = -1;
  }
  rtsp_rx_buf_.clear();
  rtsp_cseq_ = 1;
  rtsp_session_id_.clear();
  rtsp_udp_fallback_attempted_ = false;
  rtsp_using_udp_ = config_.rtsp_prefer_udp;

  resolve_rtsp_endpoint(config_, rtsp_url_, rtsp_port_);
  rtsp_control_url_ = rtsp_url_;
  bool rtsp_now = false;
  rtsp_fd_ = open_bound_tcp_socket(config_.network_handle, config_.host_ip, rtsp_port_, rtsp_now);
  if (rtsp_fd_ >= 0) {
    rtsp_stage_ = rtsp_now ? RtspStage::IDLE : RtspStage::TCP_CONNECTING;
    rtsp_stage_started_ms_ = now_millis;
    rtsp_last_video_rx_ms_ = now_millis;
  } else {
    rtsp_stage_ = RtspStage::FAILED;
    rtsp_stage_started_ms_ = now_millis;
  }
}

bool NativeSession::start() {
  if (running_.load(std::memory_order_acquire)) {
    return true;
  }

  const bool is_harness = config_.family == protocol::Family::ISOLATED_TEST_HARNESS;
  if (is_harness && !config_.allow_synthetic_harness) {
    return false;
  }

  reassembler_.reset();
  reassembler_.set_transport(config_.video_transport);
  rtsp_rx_buf_.clear();
  jieli_rx_buf_.clear();
  rtsp_stage_ = RtspStage::IDLE;
  rtsp_cseq_ = 1;
  rtsp_session_id_.clear();
  rtsp_control_url_.clear();
  rtsp_using_udp_ = config_.rtsp_prefer_udp;
  rtsp_udp_fallback_attempted_ = false;
  rtsp_udp_client_port_ =
      (config_.video_port >= 1024 && (config_.video_port % 2 == 0)) ? config_.video_port : 40010;
  rtsp_reconnect_at_ms_ = 0;

  flip_burst_ticks_ = 0;
  flip_roll_override_ = 0.0f;
  flip_pitch_override_ = 0.0f;
  tcp_handshake_sent_ = false;
  control_tcp_connected_ = false;
  cooingdv_gl_detected_.store(config_.family == protocol::Family::COOINGDV_GL,
                              std::memory_order_relaxed);
  control_armed_.store(is_harness, std::memory_order_relaxed);

  bl60x_locked_fd_ = -1;
  bl60x_locked_is_8801_ = false;
  bl60x_last_probe_8800_ms_ = 0;
  bl60x_last_probe_8801_ms_ = 0;
  bl60x_last_ack_ms_ = 0;
  bl60x_last_video_rx_ms_ = now_ms();
  bl60x_last_frame_id_ = 0;
  bl60x_last_frag_mask_ = 0;

  jieli_date_sent_ = false;
  jieli_stream_requested_ = false;
  jieli_udp_access_sent_ = false;
  jieli_udp_date_sent_ = false;
  jieli_udp_stream_requested_ = false;
  jieli_udp_last_stream_req_ms_ = 0;
  jieli_video_w_ = 640;
  jieli_video_h_ = 480;
  jieli_video_fps_ = 25;
  jieli_keepalive_interval_ms_ = 7500;
  jieli_last_stream_req_ms_ = 0;
  jieli_last_video_rx_ms_ = now_ms();
  jieli_last_keepalive_ms_ = 0;
  jieli_diag_started_ms_ = 0;
  jieli_diag_finished_ = false;
  jieli_diag_pkt_count_.store(0, std::memory_order_relaxed);
  {
    std::lock_guard<std::mutex> lock(hex_mutex_);
    jieli_diag_sample_hex_.clear();
  }

  lewei_last_video_rx_ms_ = now_ms();
  lewei_last_keepalive_ms_ = 0;
  last_heartbeat_ms_ = 0;
  last_cooingdv_photo_ctr_ = -1;
  last_cooingdv_video_ctr_ = -1;
  detected_cooingdv_id_.store(-1, std::memory_order_relaxed);
  {
    std::lock_guard<std::mutex> lock(state_mutex_);
    servo_probe_enabled_ = false;
  }

  packets_sent_.store(0, std::memory_order_relaxed);
  packets_received_.store(0, std::memory_order_relaxed);
  control_packets_received_.store(0, std::memory_order_relaxed);
  video_packets_received_.store(0, std::memory_order_relaxed);
  last_rx_timestamp_ms_.store(0, std::memory_order_relaxed);
  last_socket_errno_.store(0, std::memory_order_relaxed);
  battery_percent_.store(-1, std::memory_order_relaxed);
  firmware_status_flags_.store(-1, std::memory_order_relaxed);

  if (!is_harness) {
    control_is_tcp_ = (config_.family == protocol::Family::JIELI_AC79 ||
                       config_.family == protocol::Family::LEWEI);
    if (control_is_tcp_) {
      control_fd_ = open_bound_tcp_socket(config_.network_handle, config_.host_ip,
                                          config_.control_port, control_tcp_connected_);
      if (control_fd_ < 0) {
        return false;
      }
    } else {
      control_fd_ = open_bound_udp_socket(config_.network_handle, false, 0);
      if (control_fd_ < 0) {
        return false;
      }
    }

    if (config_.family == protocol::Family::FLOW_BL60X && config_.control_port == 8800) {
      bl60x_alt_fd_ = open_bound_udp_socket(config_.network_handle, false, 0);
    }

    if (config_.family == protocol::Family::JIELI_AC79) {
      // Jieli AC79 binds both local UDP :2224 (multi-piece JPEG) and UDP :6666 (RTP/JPEG or H.264)
      video_fd_ = open_bound_udp_socket(config_.network_handle, true, 2224);
      jieli_rtp_fd_ = open_bound_udp_socket(config_.network_handle, true, 6666);
      if (video_fd_ < 0 && jieli_rtp_fd_ < 0) {
        close_sockets();
        return false;
      }
    } else if (is_rtsp_transport(config_.video_transport)) {
      rtsp_is_hevc_ = (config_.video_transport == video::TransportKind::RTSP_H265);
      rtsp_is_mjpeg_ = (config_.video_transport == video::TransportKind::RTSP_MJPEG);
      open_or_reopen_rtsp(now_ms());
      if (config_.video_port > 0 && config_.video_port != config_.control_port &&
          config_.video_port != rtsp_port_) {
        video_fd_ = open_bound_udp_socket(config_.network_handle, true, config_.video_port);
      }
    } else if (!control_is_tcp_ && config_.video_port > 0 &&
               config_.video_port != config_.control_port) {
      video_fd_ = open_bound_udp_socket(config_.network_handle, true, config_.video_port);
    }

    if (config_.family != protocol::Family::JIELI_AC79 &&
        config_.family != protocol::Family::FLOW_BL60X &&
        config_.family != protocol::Family::LEWEI &&
        config_.heartbeat_port > 0 &&
        (config_.heartbeat_port != config_.control_port || control_is_tcp_)) {
      heartbeat_fd_ = open_bound_udp_socket(config_.network_handle, false, 0);
    }
  }

  running_.store(true, std::memory_order_release);
  connected_.store(is_harness, std::memory_order_release);
  worker_ = std::thread(&NativeSession::worker_loop, this);
  return true;
}

void NativeSession::close_sockets() {
  if (control_fd_ >= 0) {
    ::close(control_fd_);
    control_fd_ = -1;
  }
  if (video_fd_ >= 0) {
    ::close(video_fd_);
    video_fd_ = -1;
  }
  if (jieli_rtp_fd_ >= 0) {
    ::close(jieli_rtp_fd_);
    jieli_rtp_fd_ = -1;
  }
  if (bl60x_alt_fd_ >= 0) {
    ::close(bl60x_alt_fd_);
    bl60x_alt_fd_ = -1;
  }
  if (heartbeat_fd_ >= 0) {
    ::close(heartbeat_fd_);
    heartbeat_fd_ = -1;
  }
  if (rtsp_fd_ >= 0) {
    ::close(rtsp_fd_);
    rtsp_fd_ = -1;
  }
  if (rtsp_rtp_udp_fd_ >= 0) {
    ::close(rtsp_rtp_udp_fd_);
    rtsp_rtp_udp_fd_ = -1;
  }
  if (jieli_diag_1234_fd_ >= 0) {
    ::close(jieli_diag_1234_fd_);
    jieli_diag_1234_fd_ = -1;
  }
}

void NativeSession::stop() {
  if (!running_.exchange(false, std::memory_order_acq_rel)) {
    return;
  }
  {
    std::lock_guard<std::mutex> lock(state_mutex_);
    servo_probe_enabled_ = false;
  }
  if (worker_.joinable()) {
    worker_.join();
  }
  // Transmit discrete disconnect packet before tearing down sockets
  const auto stop_pkt = protocol::encode_stop_packet(config_.family);
  if (!stop_pkt.empty() && control_fd_ >= 0) {
    if (control_is_tcp_ && control_tcp_connected_) {
      ::send(control_fd_, stop_pkt.data(), stop_pkt.size(), MSG_NOSIGNAL);
    } else if (!control_is_tcp_) {
      sockaddr_in addr{};
      addr.sin_family = AF_INET;
      addr.sin_port = htons(static_cast<uint16_t>(config_.control_port));
      ::inet_pton(AF_INET, config_.host_ip.c_str(), &addr.sin_addr);
      ::sendto(control_fd_, stop_pkt.data(), stop_pkt.size(), 0,
               reinterpret_cast<const sockaddr*>(&addr), sizeof(addr));
    }
  }
  connected_.store(false, std::memory_order_release);
  control_armed_.store(false, std::memory_order_release);
  close_sockets();
}

void NativeSession::set_control_armed(bool armed) {
  if (protocol::is_video_only_family(config_.family)) {
    control_armed_.store(false, std::memory_order_release);
    return;
  }
  if (!armed) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    servo_probe_enabled_ = false;
  }
  control_armed_.store(armed, std::memory_order_release);
}

void NativeSession::switch_camera(int32_t camera_index) {
  bl60x_cam_index_ = (camera_index == 2) ? 1 : 0;
  const auto pkt = protocol::encode_camera_switch_packet(config_.family, camera_index);
  if (!pkt.empty() && control_fd_ >= 0 && !control_is_tcp_) {
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(config_.control_port));
    ::inet_pton(AF_INET, config_.host_ip.c_str(), &addr.sin_addr);
    ::sendto(control_fd_, pkt.data(), pkt.size(), 0,
             reinterpret_cast<const sockaddr*>(&addr), sizeof(addr));
    packets_sent_.fetch_add(1, std::memory_order_relaxed);
  }
  reassembler_.reset();
  if (is_rtsp_transport(config_.video_transport)) {
    rtsp_reconnect_at_ms_ = now_ms() + 1500;
  }
}

void NativeSession::update_sticks(float roll, float pitch, float throttle, float yaw,
                                  float trim_roll, float trim_pitch, uint8_t speed_tier,
                                  int8_t camera_tilt) {
  std::lock_guard<std::mutex> lock(state_mutex_);
  stick_state_.roll = roll;
  stick_state_.pitch = pitch;
  stick_state_.throttle = throttle;
  stick_state_.yaw = yaw;
  stick_state_.trim_roll = trim_roll;
  stick_state_.trim_pitch = trim_pitch;
  stick_state_.speed_tier = speed_tier;
  if (camera_tilt != 0) {
    stick_state_.camera_tilt = camera_tilt;
    tilt_ticks_remaining_ = 3;
  }
}

void NativeSession::trigger_action(uint32_t action_flag_bit) {
  if (protocol::is_video_only_family(config_.family)) return;
  std::lock_guard<std::mutex> lock(state_mutex_);
  uint32_t effective_latch_mask = protocol::ActionFlag::kLatchedMask;
  int pulse_ticks = 4;
  if (config_.family == protocol::Family::FLOW_KY_Y_SERIES &&
      (action_flag_bit & protocol::ActionFlag::HEADLESS) != 0) {
    // FLOW_KY_Y_SERIES cmd=0x04 toggles firmware headless mode; send as finite 0.5s pulse @ 20 Hz.
    effective_latch_mask &= ~protocol::ActionFlag::HEADLESS;
    pulse_ticks = 10;
  }
  if ((action_flag_bit & effective_latch_mask) != 0) {
    latched_actions_ |= (action_flag_bit & effective_latch_mask);
  }
  const uint32_t pulse_bits = action_flag_bit & ~effective_latch_mask;
  if (pulse_bits == 0) return;

  for (auto& slot : active_pulses_) {
    if (slot.flag == pulse_bits || slot.ticks_remaining <= 0) {
      slot.flag = pulse_bits;
      slot.ticks_remaining = pulse_ticks;
      return;
    }
  }
  active_pulses_[0] = PulseEntry{pulse_bits, pulse_ticks};
}

void NativeSession::trigger_flip(int32_t direction) {
  if (protocol::is_video_only_family(config_.family)) return;
  trigger_action(protocol::ActionFlag::FLIP_360);
  std::lock_guard<std::mutex> lock(state_mutex_);
  if (direction == 1) {
    flip_roll_override_ = 0.0f;
    flip_pitch_override_ = 0.85f;
    flip_burst_ticks_ = 4;
  } else if (direction == 2) {
    flip_roll_override_ = 0.0f;
    flip_pitch_override_ = -0.85f;
    flip_burst_ticks_ = 4;
  } else if (direction == 3) {
    flip_roll_override_ = -0.85f;
    flip_pitch_override_ = 0.0f;
    flip_burst_ticks_ = 4;
  } else if (direction == 4) {
    flip_roll_override_ = 0.85f;
    flip_pitch_override_ = 0.0f;
    flip_burst_ticks_ = 4;
  } else {
    flip_burst_ticks_ = 0;
  }
}

void NativeSession::set_latched_action(uint32_t action_flag_bit, bool enabled) {
  if (config_.family == protocol::Family::FLOW_KY_Y_SERIES &&
      (action_flag_bit & protocol::ActionFlag::HEADLESS) != 0) {
    // FLOW_KY_Y_SERIES cmd=0x04 is a toggle edge: always pulse finite 0.5s rather than latching.
    trigger_action(protocol::ActionFlag::HEADLESS);
    return;
  }
  std::lock_guard<std::mutex> lock(state_mutex_);
  const uint32_t masked = action_flag_bit & protocol::ActionFlag::kLatchedMask;
  if (enabled) {
    latched_actions_ |= masked;
  } else {
    latched_actions_ &= ~masked;
  }
}

void NativeSession::clear_actions() {
  std::lock_guard<std::mutex> lock(state_mutex_);
  latched_actions_ = 0;
  servo_probe_enabled_ = false;
  for (auto& slot : active_pulses_) {
    slot = PulseEntry{};
  }
  tilt_ticks_remaining_ = 0;
  flip_burst_ticks_ = 0;
  flip_roll_override_ = 0.0f;
  flip_pitch_override_ = 0.0f;
  stick_state_.camera_tilt = 0;
  stick_state_.action_flags = 0;
}

void NativeSession::request_keyframe() {
  keyframe_requested_.store(true, std::memory_order_release);
}

void NativeSession::set_servo_probe(bool enabled, int32_t byte_index, int32_t hex_value) {
  std::lock_guard<std::mutex> lock(state_mutex_);
  servo_probe_enabled_ = enabled && !control_is_tcp_ && !protocol::is_video_only_family(config_.family);
  servo_probe_byte_index_ = static_cast<uint8_t>(std::clamp(byte_index, 9, 18));
  servo_probe_hex_value_ = static_cast<uint8_t>(std::clamp(hex_value, 0, 255));
}

bool NativeSession::send_cooingdv_password(const std::string& digits_8) {
  if (control_fd_ < 0 || control_is_tcp_) return false;
  const auto pkt = protocol::encode_cooingdv_password_packet(digits_8);
  if (pkt.empty()) return false;
  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_port = htons(static_cast<uint16_t>(config_.control_port));
  if (::inet_pton(AF_INET, config_.host_ip.c_str(), &addr.sin_addr) != 1) return false;
  const ssize_t sent = ::sendto(control_fd_, pkt.data(), pkt.size(), 0,
                                reinterpret_cast<const sockaddr*>(&addr), sizeof(addr));
  if (sent > 0) {
    packets_sent_.fetch_add(1, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(hex_mutex_);
    last_command_hex_ = bytes_to_hex(pkt);
    return true;
  }
  return false;
}

SessionSnapshot NativeSession::snapshot() {
  SessionSnapshot snap;
  snap.running = running_.load(std::memory_order_acquire);
  snap.connected = connected_.load(std::memory_order_acquire);
  snap.is_synthetic = (config_.family == protocol::Family::ISOLATED_TEST_HARNESS);
  snap.packets_sent = packets_sent_.load(std::memory_order_relaxed);
  snap.packets_received = packets_received_.load(std::memory_order_relaxed);
  snap.control_packets_received = control_packets_received_.load(std::memory_order_relaxed);
  snap.video_packets_received = video_packets_received_.load(std::memory_order_relaxed);
  snap.dropped_packets = reassembler_.dropped_frames();
  snap.last_rx_timestamp_ms = last_rx_timestamp_ms_.load(std::memory_order_relaxed);
  snap.last_socket_errno = last_socket_errno_.load(std::memory_order_relaxed);
  snap.battery_percent = battery_percent_.load(std::memory_order_relaxed);
  snap.firmware_status_flags = firmware_status_flags_.load(std::memory_order_relaxed);
  snap.control_armed = control_armed_.load(std::memory_order_relaxed);
  snap.jieli_diag_1234_packets = jieli_diag_pkt_count_.load(std::memory_order_relaxed);
  snap.detected_cooingdv_id = detected_cooingdv_id_.load(std::memory_order_relaxed);
  snap.cooingdv_gl_detected = cooingdv_gl_detected_.load(std::memory_order_relaxed);
  {
    std::lock_guard<std::mutex> lock(hex_mutex_);
    snap.last_command_hex = last_command_hex_;
    snap.jieli_diag_sample_hex = jieli_diag_sample_hex_;
  }
  return snap;
}

bool NativeSession::pop_video_frame(video::ReassembledFrame& out) {
  return reassembler_.pop_latest_frame(out);
}

void NativeSession::parse_jieli_ctp_buffer() {
  size_t consumed = 0;
  while (consumed + 10 <= jieli_rx_buf_.size()) {
    const uint8_t* ptr = jieli_rx_buf_.data() + consumed;
    const size_t avail = jieli_rx_buf_.size() - consumed;
    if (std::memcmp(ptr, "CTP:", 4) != 0) {
      ++consumed;
      continue;
    }
    const uint16_t topic_len = read_u16_le_s(ptr + 4);
    if (topic_len > 128 || avail < 6u + topic_len + 4u) break;
    const std::string topic(reinterpret_cast<const char*>(ptr + 6), topic_len);
    const uint32_t json_len = read_u32_le_s(ptr + 6 + topic_len);
    if (json_len > 8192) {
      consumed += 4;
      continue;
    }
    if (avail < 10u + topic_len + json_len) break;
    const std::string json(reinterpret_cast<const char*>(ptr + 10 + topic_len), json_len);

    if (topic == "VIDEO_PARAM" || topic == "KEEP_ALIVE_INTERVAL") {
      auto parse_int = [&](const char* key, int32_t fallback) -> int32_t {
        const size_t pos = json.find(key);
        if (pos == std::string::npos) return fallback;
        size_t j = pos + std::strlen(key);
        while (j < json.size() && (json[j] == '"' || json[j] == ':' || json[j] == ' ')) ++j;
        const int v = std::atoi(json.c_str() + j);
        return v > 0 ? v : fallback;
      };
      if (topic == "VIDEO_PARAM") {
        jieli_video_w_ = parse_int("\"w\"", jieli_video_w_);
        jieli_video_h_ = parse_int("\"h\"", jieli_video_h_);
        jieli_video_fps_ = parse_int("\"fps\"", jieli_video_fps_);
      } else {
        int32_t timeout_val = parse_int("\"timeout\"", -1);
        if (timeout_val > 0) {
          if (timeout_val < 1000) timeout_val *= 1000;
          jieli_keepalive_interval_ms_ = std::clamp<int64_t>(timeout_val / 4, 2000, 15000);
        }
      }
    }

    const auto parsed =
        protocol::parse_rx_telemetry(config_.family, ptr, 10u + topic_len + json_len);
    if (parsed.valid_battery) {
      battery_percent_.store(parsed.battery_percent, std::memory_order_relaxed);
    }
    if (parsed.valid_status_flags) {
      firmware_status_flags_.store(parsed.status_flags, std::memory_order_relaxed);
    }
    consumed += 10u + topic_len + json_len;
  }
  if (consumed > 0) {
    jieli_rx_buf_.erase(jieli_rx_buf_.begin(), jieli_rx_buf_.begin() + static_cast<ptrdiff_t>(consumed));
  }
}

void NativeSession::poll_jieli_tcp(int64_t now_millis) {
  if (!control_is_tcp_ || control_fd_ < 0 || config_.family != protocol::Family::JIELI_AC79) return;

  if (!control_tcp_connected_) {
    pollfd pfd{control_fd_, POLLOUT, 0};
    if (::poll(&pfd, 1, 0) > 0 && (pfd.revents & POLLOUT)) {
      int err = 0;
      socklen_t len = sizeof(err);
      if (::getsockopt(control_fd_, SOL_SOCKET, SO_ERROR, &err, &len) == 0 && err == 0) {
        control_tcp_connected_ = true;
      } else {
        last_socket_errno_.store(err, std::memory_order_relaxed);
      }
    }
    if (!control_tcp_connected_) return;
  }

  if (!tcp_handshake_sent_) {
    const auto access = protocol::encode_handshake_packet(protocol::Family::JIELI_AC79);
    if (::send(control_fd_, access.data(), access.size(), MSG_NOSIGNAL) > 0) {
      packets_sent_.fetch_add(1, std::memory_order_relaxed);
      tcp_handshake_sent_ = true;
    }
  }

  // Drain incoming CTP messages first
  uint8_t buf[2048];
  for (int i = 0; i < 8; ++i) {
    const ssize_t n = ::recv(control_fd_, buf, sizeof(buf), 0);
    if (n <= 0) break;
    packets_received_.fetch_add(1, std::memory_order_relaxed);
    control_packets_received_.fetch_add(1, std::memory_order_relaxed);
    last_rx_timestamp_ms_.store(now_millis, std::memory_order_relaxed);
    connected_.store(true, std::memory_order_release);
    if (jieli_rx_buf_.size() + static_cast<size_t>(n) <= 16384) {
      jieli_rx_buf_.insert(jieli_rx_buf_.end(), buf, buf + n);
      parse_jieli_ctp_buffer();
    } else {
      jieli_rx_buf_.clear();
    }
  }

  if (tcp_handshake_sent_ && !jieli_date_sent_) {
    const std::time_t t = std::time(nullptr);
    std::tm tm_now{};
    localtime_r(&t, &tm_now);
    char json[96];
    std::snprintf(json, sizeof(json),
                  "{\"op\":\"PUT\",\"param\":{\"date\":\"%04d%02d%02d%02d%02d%02d\"}}",
                  tm_now.tm_year + 1900, tm_now.tm_mon + 1, tm_now.tm_mday,
                  tm_now.tm_hour, tm_now.tm_min, tm_now.tm_sec);
    const auto dt = protocol::encode_ctp_message("DATE_TIME", json);
    if (::send(control_fd_, dt.data(), dt.size(), MSG_NOSIGNAL) > 0) {
      packets_sent_.fetch_add(1, std::memory_order_relaxed);
      jieli_date_sent_ = true;
    }
  }

  const bool should_retry_stream =
      jieli_stream_requested_ &&
      (now_millis - jieli_last_video_rx_ms_ >= 5000) &&
      (now_millis - jieli_last_stream_req_ms_ >= 10000);
  if ((jieli_date_sent_ && !jieli_stream_requested_) || should_retry_stream) {
    const int fmt = (config_.video_transport == video::TransportKind::RAW_UDP_H264) ? 1 : 0;
    char json[128];
    std::snprintf(json, sizeof(json),
                  "{\"op\":\"PUT\",\"param\":{\"w\":\"%d\",\"h\":\"%d\",\"fps\":\"%d\",\"format\":\"%d\"}}",
                  jieli_video_w_, jieli_video_h_, jieli_video_fps_, fmt);
    const auto req = protocol::encode_ctp_message("OPEN_RT_STREAM", json);
    if (::send(control_fd_, req.data(), req.size(), MSG_NOSIGNAL) > 0) {
      packets_sent_.fetch_add(1, std::memory_order_relaxed);
      jieli_stream_requested_ = true;
      jieli_last_stream_req_ms_ = now_millis;
      jieli_last_keepalive_ms_ = now_millis;
    }
  }

  if (jieli_stream_requested_ && now_millis - jieli_last_keepalive_ms_ >= jieli_keepalive_interval_ms_) {
    const auto ka = protocol::encode_heartbeat_packet(protocol::Family::JIELI_AC79);
    if (::send(control_fd_, ka.data(), ka.size(), MSG_NOSIGNAL) > 0) {
      packets_sent_.fetch_add(1, std::memory_order_relaxed);
      jieli_last_keepalive_ms_ = now_millis;
    }
  }

  // Diagnostic UDP :1234 sniffer for 30s or 20 packets when 5s elapses with 0 video packets
  const uint64_t vid_pkts = video_packets_received_.load(std::memory_order_relaxed);
  if (vid_pkts > 0 && jieli_diag_1234_fd_ >= 0) {
    ::close(jieli_diag_1234_fd_);
    jieli_diag_1234_fd_ = -1;
    jieli_diag_finished_ = true;
  } else if (!jieli_diag_finished_ && vid_pkts == 0 && jieli_stream_requested_ &&
             now_millis - jieli_last_stream_req_ms_ >= 5000) {
    if (jieli_diag_1234_fd_ < 0) {
      jieli_diag_1234_fd_ = open_bound_udp_socket(config_.network_handle, true, 1234);
      jieli_diag_started_ms_ = now_millis;
      if (jieli_diag_1234_fd_ < 0) {
        jieli_diag_finished_ = true;
      }
    }
    if (jieli_diag_1234_fd_ >= 0) {
      uint8_t d_buf[512];
      for (int burst = 0; burst < 8; ++burst) {
        sockaddr_in src{};
        socklen_t slen = sizeof(src);
        const ssize_t dn = ::recvfrom(jieli_diag_1234_fd_, d_buf, sizeof(d_buf), 0,
                                      reinterpret_cast<sockaddr*>(&src), &slen);
        if (dn <= 0) break;
        const int32_t cnt = jieli_diag_pkt_count_.fetch_add(1, std::memory_order_relaxed) + 1;
        if (cnt == 1) {
          const size_t sample_len = std::min<size_t>(static_cast<size_t>(dn), 24);
          std::vector<uint8_t> sample(d_buf, d_buf + sample_len);
          std::lock_guard<std::mutex> lock(hex_mutex_);
          jieli_diag_sample_hex_ = bytes_to_hex(sample);
        }
        if (cnt >= 20) break;
      }
      if (jieli_diag_pkt_count_.load(std::memory_order_relaxed) >= 20 ||
          now_millis - jieli_diag_started_ms_ >= 30000) {
        ::close(jieli_diag_1234_fd_);
        jieli_diag_1234_fd_ = -1;
        jieli_diag_finished_ = true;
      }
    }
  }
}

void NativeSession::poll_lewei_tcp(int64_t now_millis, int64_t loop_us) {
  if (!control_is_tcp_ || config_.family != protocol::Family::LEWEI) return;

  // Reconnect TCP 7060 if video silent > 5s
  if (control_fd_ < 0 ||
      (tcp_handshake_sent_ && now_millis - lewei_last_video_rx_ms_ >= 5000)) {
    if (control_fd_ >= 0) {
      const auto stop_cmd = protocol::encode_stop_packet(protocol::Family::LEWEI);
      if (control_tcp_connected_ && !stop_cmd.empty()) {
        ::send(control_fd_, stop_cmd.data(), stop_cmd.size(), MSG_NOSIGNAL);
      }
      ::close(control_fd_);
      control_fd_ = -1;
    }
    control_tcp_connected_ = false;
    tcp_handshake_sent_ = false;
    reassembler_.reset();
    control_fd_ = open_bound_tcp_socket(config_.network_handle, config_.host_ip,
                                        config_.control_port, control_tcp_connected_);
    lewei_last_video_rx_ms_ = now_millis;
    if (control_fd_ < 0) return;
  }

  if (!control_tcp_connected_) {
    pollfd pfd{control_fd_, POLLOUT, 0};
    if (::poll(&pfd, 1, 0) > 0 && (pfd.revents & POLLOUT)) {
      int err = 0;
      socklen_t len = sizeof(err);
      if (::getsockopt(control_fd_, SOL_SOCKET, SO_ERROR, &err, &len) == 0 && err == 0) {
        control_tcp_connected_ = true;
      } else {
        last_socket_errno_.store(err, std::memory_order_relaxed);
      }
    }
    if (!control_tcp_connected_) return;
  }

  if (!tcp_handshake_sent_) {
    const auto start_cmd = protocol::encode_handshake_packet(protocol::Family::LEWEI);
    if (::send(control_fd_, start_cmd.data(), start_cmd.size(), MSG_NOSIGNAL) > 0) {
      packets_sent_.fetch_add(1, std::memory_order_relaxed);
      tcp_handshake_sent_ = true;
      lewei_last_keepalive_ms_ = now_millis;
      lewei_last_video_rx_ms_ = now_millis;
    }
  }

  uint8_t buf[16384];
  for (int i = 0; i < 32; ++i) {
    const ssize_t n = ::recv(control_fd_, buf, sizeof(buf), 0);
    if (n <= 0) break;
    packets_received_.fetch_add(1, std::memory_order_relaxed);
    video_packets_received_.fetch_add(1, std::memory_order_relaxed);
    last_rx_timestamp_ms_.store(now_millis, std::memory_order_relaxed);
    lewei_last_video_rx_ms_ = now_millis;
    connected_.store(true, std::memory_order_release);
    reassembler_.push_lewei_tcp_bytes(buf, static_cast<size_t>(n), loop_us);
  }

  if (tcp_handshake_sent_ && now_millis - lewei_last_keepalive_ms_ >= 1000) {
    const auto ka = protocol::encode_heartbeat_packet(protocol::Family::LEWEI);
    if (::send(control_fd_, ka.data(), ka.size(), MSG_NOSIGNAL) > 0) {
      packets_sent_.fetch_add(1, std::memory_order_relaxed);
      lewei_last_keepalive_ms_ = now_millis;
    }
  }
}

void NativeSession::step_bl60x_discovery_and_ack(int64_t now_millis, int64_t loop_us) {
  if (config_.family != protocol::Family::FLOW_BL60X) return;

  // Check for 3s video silence -> restart dual-port probing
  if (bl60x_locked_fd_ >= 0 && now_millis - bl60x_last_video_rx_ms_ >= 3000) {
    bl60x_locked_fd_ = -1;
    reassembler_.reset();
  }

  if (bl60x_locked_fd_ < 0) {
    const auto probe = protocol::encode_handshake_packet(protocol::Family::FLOW_BL60X);
    if (control_fd_ >= 0 && now_millis - bl60x_last_probe_8800_ms_ >= 100) {
      sockaddr_in addr{};
      addr.sin_family = AF_INET;
      addr.sin_port = htons(static_cast<uint16_t>(config_.control_port));
      ::inet_pton(AF_INET, config_.host_ip.c_str(), &addr.sin_addr);
      if (::sendto(control_fd_, probe.data(), probe.size(), 0,
                   reinterpret_cast<const sockaddr*>(&addr), sizeof(addr)) > 0) {
        packets_sent_.fetch_add(1, std::memory_order_relaxed);
      }
      bl60x_last_probe_8800_ms_ = now_millis;
    }
    if (bl60x_alt_fd_ >= 0 && now_millis - bl60x_last_probe_8801_ms_ >= 200) {
      sockaddr_in addr{};
      addr.sin_family = AF_INET;
      addr.sin_port = htons(8801);
      ::inet_pton(AF_INET, config_.host_ip.c_str(), &addr.sin_addr);
      if (::sendto(bl60x_alt_fd_, probe.data(), probe.size(), 0,
                   reinterpret_cast<const sockaddr*>(&addr), sizeof(addr)) > 0) {
        packets_sent_.fetch_add(1, std::memory_order_relaxed);
      }
      bl60x_last_probe_8801_ms_ = now_millis;
    }
  } else {
    const int64_t ack_interval = bl60x_locked_is_8801_ ? 50 : 25;
    if (now_millis - bl60x_last_ack_ms_ >= ack_interval) {
      const auto ack = protocol::encode_flow_bl60x_ack(
          bl60x_last_frame_id_, bl60x_last_frag_mask_, 0x324B142Du, bl60x_cam_index_);
      const int target_port = bl60x_locked_is_8801_ ? 8801 : config_.control_port;
      sockaddr_in addr{};
      addr.sin_family = AF_INET;
      addr.sin_port = htons(static_cast<uint16_t>(target_port));
      ::inet_pton(AF_INET, config_.host_ip.c_str(), &addr.sin_addr);
      if (::sendto(bl60x_locked_fd_, ack.data(), ack.size(), 0,
                   reinterpret_cast<const sockaddr*>(&addr), sizeof(addr)) > 0) {
        packets_sent_.fetch_add(1, std::memory_order_relaxed);
      }
      bl60x_last_ack_ms_ = now_millis;
    }
  }

  // Drain packets from both 8800 and 8801 sockets
  uint8_t buf[2048];
  const int fds[2] = {control_fd_, bl60x_alt_fd_};
  for (int i = 0; i < 2; ++i) {
    const int fd = fds[i];
    if (fd < 0) continue;
    for (int burst = 0; burst < 32; ++burst) {
      sockaddr_in src{};
      socklen_t slen = sizeof(src);
      const ssize_t n = ::recvfrom(fd, buf, sizeof(buf), 0,
                                   reinterpret_cast<sockaddr*>(&src), &slen);
      if (n <= 0) break;
      packets_received_.fetch_add(1, std::memory_order_relaxed);
      last_rx_timestamp_ms_.store(now_millis, std::memory_order_relaxed);
      connected_.store(true, std::memory_order_release);

      uint32_t fid = 0;
      uint32_t fmask = 0;
      uint8_t cam0 = 0;
      uint8_t cam1 = 0;
      if (reassembler_.push_bl60x_packet(buf, static_cast<size_t>(n), loop_us,
                                         &fid, &fmask, &cam0, &cam1)) {
        video_packets_received_.fetch_add(1, std::memory_order_relaxed);
        bl60x_last_video_rx_ms_ = now_millis;
        bl60x_last_frame_id_ = fid;
        bl60x_last_frag_mask_ = fmask;
        firmware_status_flags_.store((static_cast<int32_t>(cam0) << 8) | cam1,
                                     std::memory_order_relaxed);
        if (bl60x_locked_fd_ < 0) {
          bl60x_locked_fd_ = fd;
          bl60x_locked_is_8801_ = (fd == bl60x_alt_fd_);
        }
      }
    }
  }
}

bool NativeSession::ensure_rtsp_udp_socket() {
  if (rtsp_rtp_udp_fd_ >= 0) return true;
  if (rtsp_udp_client_port_ == config_.video_port && video_fd_ >= 0) {
    rtsp_udp_client_port_ = 40010;
  }
  rtsp_rtp_udp_fd_ = open_bound_udp_socket(config_.network_handle, true, rtsp_udp_client_port_);
  return rtsp_rtp_udp_fd_ >= 0;
}

void NativeSession::send_rtsp_request(const std::string& method, const std::string& uri,
                                      const std::string& extra_headers) {
  if (rtsp_fd_ < 0) return;
  std::ostringstream oss;
  oss << method << " " << uri << " RTSP/1.0\r\n"
      << "CSeq: " << rtsp_cseq_++ << "\r\n"
      << "User-Agent: AUTOPILOT/1.0\r\n";
  if (!rtsp_session_id_.empty()) {
    oss << "Session: " << rtsp_session_id_ << "\r\n";
  }
  if (!extra_headers.empty()) {
    oss << extra_headers;
  }
  oss << "\r\n";
  const std::string req = oss.str();
  ::send(rtsp_fd_, req.data(), req.size(), MSG_NOSIGNAL);
  rtsp_stage_started_ms_ = now_ms();
}

void NativeSession::parse_rtsp_text_and_interleaved() {
  const int64_t ts_us = now_us();
  const int64_t tms = now_ms();
  size_t consumed = 0;

  while (consumed < rtsp_rx_buf_.size()) {
    const uint8_t* ptr = rtsp_rx_buf_.data() + consumed;
    const size_t avail = rtsp_rx_buf_.size() - consumed;

    if (ptr[0] == '$') {
      if (avail < 4) break;
      const uint8_t channel = ptr[1];
      const uint16_t pkt_len = (static_cast<uint16_t>(ptr[2]) << 8) | ptr[3];
      if (avail < 4u + pkt_len) break;
      if (channel == 0 && pkt_len > 0) {
        video_packets_received_.fetch_add(1, std::memory_order_relaxed);
        rtsp_last_video_rx_ms_ = tms;
        if (rtsp_is_mjpeg_) {
          reassembler_.push_rtp_jpeg_packet(ptr + 4, pkt_len, ts_us);
        } else {
          reassembler_.push_rtp_packet(ptr + 4, pkt_len, ts_us, rtsp_is_hevc_);
        }
      }
      consumed += 4u + pkt_len;
      continue;
    }

    if (avail >= 4 && std::memcmp(ptr, "RTSP", 4) == 0) {
      std::string text(reinterpret_cast<const char*>(ptr), avail);
      const size_t hdr_end = text.find("\r\n\r\n");
      if (hdr_end == std::string::npos) break;

      const std::string headers = text.substr(0, hdr_end);
      const std::string lower_hdr = to_lower_ascii(headers);

      size_t content_len = 0;
      const size_t cl_pos = lower_hdr.find("content-length:");
      if (cl_pos != std::string::npos) {
        content_len = static_cast<size_t>(std::atoi(headers.c_str() + cl_pos + 15));
      }
      const size_t total_msg_len = hdr_end + 4 + content_len;
      if (avail < total_msg_len) break;

      const size_t sess_pos = lower_hdr.find("session:");
      if (sess_pos != std::string::npos) {
        const size_t line_end = headers.find("\r\n", sess_pos);
        std::string raw_sess = headers.substr(
            sess_pos + 8, (line_end == std::string::npos ? headers.size() : line_end) - (sess_pos + 8));
        const size_t semi = raw_sess.find(';');
        if (semi != std::string::npos) raw_sess = raw_sess.substr(0, semi);
        rtsp_session_id_ = trim_ws(raw_sess);
      }

      const std::string body = content_len > 0 ? text.substr(hdr_end + 4, content_len) : "";
      if (!body.empty()) {
        std::istringstream lines(body);
        std::string line;
        bool in_video_media = false;
        while (std::getline(lines, line)) {
          line = trim_ws(line);
          const std::string lower_line = to_lower_ascii(line);
          if (lower_line.rfind("m=video", 0) == 0) {
            in_video_media = true;
            // Check payload 26 (JPEG) static payload type in m=video line if present
            if (lower_line.find(" 26") != std::string::npos &&
                lower_line.find("rtp/avp") != std::string::npos &&
                lower_line.find(" 96") == std::string::npos) {
              rtsp_is_mjpeg_ = true;
              rtsp_is_hevc_ = false;
              reassembler_.set_transport(video::TransportKind::RTSP_MJPEG);
            }
          } else if (lower_line.rfind("m=", 0) == 0) {
            in_video_media = false;
          } else if (in_video_media && lower_line.rfind("a=rtpmap:", 0) == 0) {
            if (lower_line.find("jpeg/") != std::string::npos) {
              rtsp_is_mjpeg_ = true;
              rtsp_is_hevc_ = false;
              reassembler_.set_transport(video::TransportKind::RTSP_MJPEG);
            } else if (lower_line.find("h265/") != std::string::npos ||
                       lower_line.find("hevc/") != std::string::npos) {
              rtsp_is_mjpeg_ = false;
              rtsp_is_hevc_ = true;
              reassembler_.set_transport(video::TransportKind::RTSP_H265);
            } else if (lower_line.find("h264/") != std::string::npos) {
              rtsp_is_mjpeg_ = false;
              rtsp_is_hevc_ = false;
              reassembler_.set_transport(video::TransportKind::RTSP_H264);
            }
          } else if (in_video_media && line.rfind("a=control:", 0) == 0) {
            const std::string ctrl = trim_ws(line.substr(10));
            if (ctrl.rfind("rtsp://", 0) == 0) {
              rtsp_control_url_ = ctrl;
            } else if (!ctrl.empty() && ctrl != "*") {
              rtsp_control_url_ =
                  rtsp_url_ + (rtsp_url_.back() == '/' || ctrl.front() == '/' ? "" : "/") + ctrl;
            }
          } else if (in_video_media && line.rfind("a=fmtp:", 0) == 0) {
            for (const char* key :
                 {"sprop-parameter-sets=", "sprop-vps=", "sprop-sps=", "sprop-pps="}) {
              const size_t kpos = line.find(key);
              if (kpos == std::string::npos) continue;
              const size_t vstart = kpos + std::strlen(key);
              const size_t vend = line.find(';', vstart);
              const std::string val =
                  line.substr(vstart, vend == std::string::npos ? std::string::npos : vend - vstart);
              std::istringstream parts(val);
              std::string b64;
              while (std::getline(parts, b64, ',')) {
                auto nal = decode_base64(trim_ws(b64));
                if (!nal.empty()) {
                  reassembler_.push_annex_b_csd(nal.data(), nal.size(), ts_us, rtsp_is_hevc_);
                }
              }
            }
          }
        }
      }

      int status_code = 200;
      const size_t first_sp = headers.find(' ');
      if (first_sp != std::string::npos) {
        status_code = std::atoi(headers.c_str() + first_sp + 1);
      }

      if (rtsp_stage_ == RtspStage::SENT_SETUP && status_code == 461 &&
          config_.rtsp_allow_udp_fallback && !rtsp_udp_fallback_attempted_) {
        rtsp_udp_fallback_attempted_ = true;
        rtsp_using_udp_ = true;
        if (ensure_rtsp_udp_socket()) {
          std::ostringstream tr;
          tr << "Transport: RTP/AVP;unicast;client_port=" << rtsp_udp_client_port_ << "-"
             << (rtsp_udp_client_port_ + 1) << "\r\n";
          send_rtsp_request("SETUP", rtsp_control_url_.empty() ? rtsp_url_ : rtsp_control_url_,
                            tr.str());
          consumed += total_msg_len;
          continue;
        }
      }

      if (status_code >= 400) {
        rtsp_stage_ = RtspStage::FAILED;
        rtsp_stage_started_ms_ = tms;
        consumed += total_msg_len;
        continue;
      }

      if (rtsp_stage_ == RtspStage::SENT_OPTIONS) {
        rtsp_stage_ = RtspStage::SENT_DESCRIBE;
        send_rtsp_request("DESCRIBE", rtsp_url_, "Accept: application/sdp\r\n");
      } else if (rtsp_stage_ == RtspStage::SENT_DESCRIBE) {
        rtsp_stage_ = RtspStage::SENT_SETUP;
        if (rtsp_using_udp_ && ensure_rtsp_udp_socket()) {
          rtsp_udp_fallback_attempted_ = true;
          std::ostringstream tr;
          tr << "Transport: RTP/AVP;unicast;client_port=" << rtsp_udp_client_port_ << "-"
             << (rtsp_udp_client_port_ + 1) << "\r\n";
          send_rtsp_request("SETUP", rtsp_control_url_.empty() ? rtsp_url_ : rtsp_control_url_,
                            tr.str());
        } else {
          send_rtsp_request("SETUP", rtsp_control_url_.empty() ? rtsp_url_ : rtsp_control_url_,
                            "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
        }
      } else if (rtsp_stage_ == RtspStage::SENT_SETUP) {
        rtsp_stage_ = RtspStage::SENT_PLAY;
        send_rtsp_request("PLAY", rtsp_url_, "Range: npt=0.000-\r\n");
      } else if (rtsp_stage_ == RtspStage::SENT_PLAY) {
        rtsp_stage_ = RtspStage::STREAMING;
        rtsp_last_keepalive_ms_ = tms;
        rtsp_last_video_rx_ms_ = tms;
      }

      consumed += total_msg_len;
      continue;
    }

    ++consumed;
  }

  if (consumed > 0) {
    rtsp_rx_buf_.erase(rtsp_rx_buf_.begin(), rtsp_rx_buf_.begin() + static_cast<ptrdiff_t>(consumed));
  }
}

void NativeSession::step_rtsp_client(int64_t /* now_micros */) {
  const int64_t tms = now_ms();

  if (rtsp_reconnect_at_ms_ > 0 && tms >= rtsp_reconnect_at_ms_) {
    rtsp_reconnect_at_ms_ = 0;
    open_or_reopen_rtsp(tms);
    return;
  }

  if (rtsp_stage_ == RtspStage::FAILED) {
    if (tms - rtsp_stage_started_ms_ >= 3000) {
      open_or_reopen_rtsp(tms);
    }
    return;
  }

  if (rtsp_stage_ == RtspStage::STREAMING && tms - rtsp_last_video_rx_ms_ >= 5000) {
    open_or_reopen_rtsp(tms);
    return;
  }

  if (rtsp_fd_ < 0) return;

  if (rtsp_stage_ == RtspStage::TCP_CONNECTING) {
    pollfd pfd{rtsp_fd_, POLLOUT, 0};
    if (::poll(&pfd, 1, 0) > 0 && (pfd.revents & POLLOUT)) {
      int err = 0;
      socklen_t len = sizeof(err);
      if (::getsockopt(rtsp_fd_, SOL_SOCKET, SO_ERROR, &err, &len) == 0 && err == 0) {
        rtsp_stage_ = RtspStage::IDLE;
      } else {
        rtsp_stage_ = RtspStage::FAILED;
        rtsp_stage_started_ms_ = tms;
        return;
      }
    } else if (tms - rtsp_stage_started_ms_ > 3000) {
      rtsp_stage_ = RtspStage::FAILED;
      rtsp_stage_started_ms_ = tms;
      return;
    } else {
      return;
    }
  }

  if (rtsp_stage_ == RtspStage::IDLE) {
    rtsp_stage_ = RtspStage::SENT_OPTIONS;
    send_rtsp_request("OPTIONS", rtsp_url_, "");
  }

  uint8_t tcp_buf[16384];
  for (int i = 0; i < 64; ++i) {
    const ssize_t n = ::recv(rtsp_fd_, tcp_buf, sizeof(tcp_buf), 0);
    if (n <= 0) break;
    packets_received_.fetch_add(1, std::memory_order_relaxed);
    last_rx_timestamp_ms_.store(tms, std::memory_order_relaxed);
    connected_.store(true, std::memory_order_release);
    if (rtsp_rx_buf_.size() + static_cast<size_t>(n) <= 512 * 1024) {
      rtsp_rx_buf_.insert(rtsp_rx_buf_.end(), tcp_buf, tcp_buf + n);
    } else {
      rtsp_rx_buf_.clear();
    }
    parse_rtsp_text_and_interleaved();
  }

  if (rtsp_stage_ == RtspStage::STREAMING && tms - rtsp_last_keepalive_ms_ >= 5000) {
    send_rtsp_request("GET_PARAMETER", rtsp_url_, "");
    rtsp_last_keepalive_ms_ = tms;
  }
}

void NativeSession::worker_loop() {
  const bool is_harness = config_.family == protocol::Family::ISOLATED_TEST_HARNESS;
  const int period_ms = std::max(10, 1000 / std::max(1, config_.control_rate_hz));

  sockaddr_in control_addr{};
  sockaddr_in heartbeat_addr{};
  if (!is_harness) {
    control_addr.sin_family = AF_INET;
    control_addr.sin_port = htons(static_cast<uint16_t>(config_.control_port));
    ::inet_pton(AF_INET, config_.host_ip.c_str(), &control_addr.sin_addr);

    if (config_.heartbeat_port > 0) {
      heartbeat_addr.sin_family = AF_INET;
      heartbeat_addr.sin_port = htons(static_cast<uint16_t>(config_.heartbeat_port));
      ::inet_pton(AF_INET, config_.host_ip.c_str(), &heartbeat_addr.sin_addr);
    }

    if (!control_is_tcp_ && config_.family != protocol::Family::FLOW_BL60X) {
      const auto handshake = protocol::encode_handshake_packet(config_.family);
      if (!handshake.empty() && control_fd_ >= 0) {
        ::sendto(control_fd_, handshake.data(), handshake.size(), 0,
                 reinterpret_cast<const sockaddr*>(&control_addr), sizeof(control_addr));
        packets_sent_.fetch_add(1, std::memory_order_relaxed);
      }
    }
  }

  std::vector<uint8_t> rx_buf(8192);

  while (running_.load(std::memory_order_acquire)) {
    const auto loop_start = std::chrono::steady_clock::now();
    const int64_t loop_us = now_us();
    const int64_t tms = now_ms();

    if (!is_harness && keyframe_requested_.exchange(false, std::memory_order_acq_rel)) {
      if (!control_is_tcp_ && config_.family != protocol::Family::FLOW_BL60X) {
        const auto handshake = protocol::encode_handshake_packet(config_.family);
        if (!handshake.empty() && control_fd_ >= 0) {
          ::sendto(control_fd_, handshake.data(), handshake.size(), 0,
                   reinterpret_cast<const sockaddr*>(&control_addr), sizeof(control_addr));
        }
      }
      if (rtsp_stage_ == RtspStage::STREAMING) {
        send_rtsp_request("PLAY", rtsp_url_, "");
      }
    }

    protocol::StickState current_state;
    bool probe_active = false;
    uint8_t probe_byte = 9;
    uint8_t probe_val = 0;
    {
      std::lock_guard<std::mutex> lock(state_mutex_);
      current_state = stick_state_;
      probe_active = servo_probe_enabled_;
      probe_byte = servo_probe_byte_index_;
      probe_val = servo_probe_hex_value_;
      uint32_t flags = latched_actions_;
      for (auto& slot : active_pulses_) {
        if (slot.ticks_remaining > 0) {
          flags |= slot.flag;
          --slot.ticks_remaining;
          if (slot.ticks_remaining == 0) {
            slot.flag = 0;
          }
        }
      }
      if (tilt_ticks_remaining_ > 0) {
        --tilt_ticks_remaining_;
        if (tilt_ticks_remaining_ == 0) {
          stick_state_.camera_tilt = 0;
        }
      }
      if (flip_burst_ticks_ > 0) {
        current_state.roll = flip_roll_override_;
        current_state.pitch = flip_pitch_override_;
        --flip_burst_ticks_;
      }
      current_state.action_flags = flags;
    }

    const bool emergency_active =
        (current_state.action_flags &
         (protocol::ActionFlag::EMERGENCY_STOP | protocol::ActionFlag::MOTOR_STOP)) != 0;
    const bool allow_rc_tx =
        !protocol::is_video_only_family(config_.family) &&
        (is_harness || control_armed_.load(std::memory_order_acquire) || emergency_active);

    if (allow_rc_tx) {
      protocol::Family encode_family = config_.family;
      if (config_.family == protocol::Family::COOINGDV &&
          cooingdv_gl_detected_.load(std::memory_order_relaxed)) {
        encode_family = protocol::Family::COOINGDV_GL;
      }
      const auto packet =
          (probe_active && !emergency_active && !is_harness)
              ? protocol::encode_servo_probe_packet(probe_byte, probe_val)
              : protocol::encode_control_packet(encode_family, current_state);
      if (!packet.empty()) {
        {
          std::lock_guard<std::mutex> lock(hex_mutex_);
          last_command_hex_ = bytes_to_hex(packet);
        }
        if (is_harness) {
          packets_sent_.fetch_add(1, std::memory_order_relaxed);
          packets_received_.fetch_add(1, std::memory_order_relaxed);
          control_packets_received_.fetch_add(1, std::memory_order_relaxed);
          video_packets_received_.fetch_add(1, std::memory_order_relaxed);
          last_rx_timestamp_ms_.store(tms, std::memory_order_relaxed);
          reassembler_.push_synthetic_test_frame(160, 120, loop_us, current_state.roll,
                                                 current_state.pitch);
        } else if (!control_is_tcp_ && control_fd_ >= 0) {
          const ssize_t sent = ::sendto(control_fd_, packet.data(), packet.size(), 0,
                                        reinterpret_cast<const sockaddr*>(&control_addr),
                                        sizeof(control_addr));
          if (sent > 0) {
            packets_sent_.fetch_add(1, std::memory_order_relaxed);
          } else {
            last_socket_errno_.store(errno, std::memory_order_relaxed);
          }
        }
      }
    }

    if (!is_harness) {
      // Protocol-specific session loops
      if (config_.family == protocol::Family::FLOW_BL60X) {
        step_bl60x_discovery_and_ack(tms, loop_us);
      } else if (config_.family == protocol::Family::JIELI_AC79) {
        poll_jieli_tcp(tms);
      } else if (config_.family == protocol::Family::LEWEI) {
        poll_lewei_tcp(tms, loop_us);
      } else {
        // CooingDV Jieli UDP :2228 full CTP startup sequence (APP_ACCESS -> DATE_TIME -> OPEN_RT_STREAM)
        if (config_.family == protocol::Family::COOINGDV_JIELI && control_fd_ >= 0) {
          if (!jieli_udp_access_sent_) {
            const auto acc = protocol::encode_ctp_message(
                "APP_ACCESS", "{\"op\":\"PUT\",\"param\":{\"type\":\"1\",\"ver\":\"1.0\"}}");
            if (::sendto(control_fd_, acc.data(), acc.size(), 0,
                         reinterpret_cast<const sockaddr*>(&control_addr), sizeof(control_addr)) > 0) {
              packets_sent_.fetch_add(1, std::memory_order_relaxed);
              jieli_udp_access_sent_ = true;
            }
          } else if (!jieli_udp_date_sent_) {
            const std::time_t t = std::time(nullptr);
            std::tm tm_now{};
            localtime_r(&t, &tm_now);
            char json[96];
            std::snprintf(json, sizeof(json),
                          "{\"op\":\"PUT\",\"param\":{\"date\":\"%04d%02d%02d%02d%02d%02d\"}}",
                          tm_now.tm_year + 1900, tm_now.tm_mon + 1, tm_now.tm_mday,
                          tm_now.tm_hour, tm_now.tm_min, tm_now.tm_sec);
            const auto dt = protocol::encode_ctp_message("DATE_TIME", json);
            if (::sendto(control_fd_, dt.data(), dt.size(), 0,
                         reinterpret_cast<const sockaddr*>(&control_addr), sizeof(control_addr)) > 0) {
              packets_sent_.fetch_add(1, std::memory_order_relaxed);
              jieli_udp_date_sent_ = true;
            }
          } else if (!jieli_udp_stream_requested_ ||
                     (video_packets_received_.load(std::memory_order_relaxed) == 0 &&
                      tms - jieli_udp_last_stream_req_ms_ >= 5000)) {
            const auto req = protocol::encode_ctp_message(
                "OPEN_RT_STREAM",
                "{\"op\":\"PUT\",\"param\":{\"w\":\"640\",\"h\":\"480\",\"fps\":\"25\",\"format\":\"0\"}}");
            if (::sendto(control_fd_, req.data(), req.size(), 0,
                         reinterpret_cast<const sockaddr*>(&control_addr), sizeof(control_addr)) > 0) {
              packets_sent_.fetch_add(1, std::memory_order_relaxed);
              jieli_udp_stream_requested_ = true;
              jieli_udp_last_stream_req_ms_ = tms;
            }
          }
        }

        // Periodic heartbeat: 200ms for WIFI_UAV; 1000ms for CooingDV / KY Y-series / X69_LG / COOINGDV_JIELI
        const int64_t hb_interval_ms = (config_.family == protocol::Family::WIFI_UAV) ? 200 : 1000;
        if (tms - last_heartbeat_ms_ >= hb_interval_ms) {
          const auto hb = protocol::encode_heartbeat_packet(config_.family);
          const int target_fd = heartbeat_fd_ >= 0 ? heartbeat_fd_ : control_fd_;
          const sockaddr_in& target_addr =
              heartbeat_fd_ >= 0 ? heartbeat_addr : control_addr;
          if (!hb.empty() && target_fd >= 0) {
            ::sendto(target_fd, hb.data(), hb.size(), 0,
                     reinterpret_cast<const sockaddr*>(&target_addr), sizeof(target_addr));
            packets_sent_.fetch_add(1, std::memory_order_relaxed);
          }
          last_heartbeat_ms_ = tms;
        }
      }

      if (is_rtsp_transport(config_.video_transport)) {
        step_rtsp_client(loop_us);
      }

      if (config_.family != protocol::Family::FLOW_BL60X &&
          config_.family != protocol::Family::LEWEI) {
        int fds_to_poll[4] = {
            control_is_tcp_ ? -1 : control_fd_,
            video_fd_,
            jieli_rtp_fd_,
            rtsp_rtp_udp_fd_,
        };
        for (int fd : fds_to_poll) {
          if (fd < 0) continue;
          for (int burst = 0; burst < 32; ++burst) {
            sockaddr_in src{};
            socklen_t src_len = sizeof(src);
            const ssize_t n = ::recvfrom(fd, rx_buf.data(), rx_buf.size(), 0,
                                         reinterpret_cast<sockaddr*>(&src), &src_len);
            if (n <= 0) break;
            packets_received_.fetch_add(1, std::memory_order_relaxed);
            last_rx_timestamp_ms_.store(tms, std::memory_order_relaxed);
            connected_.store(true, std::memory_order_release);

            if (fd == rtsp_rtp_udp_fd_) {
              video_packets_received_.fetch_add(1, std::memory_order_relaxed);
              rtsp_last_video_rx_ms_ = tms;
              if (rtsp_is_mjpeg_) {
                reassembler_.push_rtp_jpeg_packet(rx_buf.data(), static_cast<size_t>(n), loop_us);
              } else {
                reassembler_.push_rtp_packet(rx_buf.data(), static_cast<size_t>(n), loop_us,
                                             rtsp_is_hevc_);
              }
              continue;
            }

            if (fd == jieli_rtp_fd_) {
              video_packets_received_.fetch_add(1, std::memory_order_relaxed);
              jieli_last_video_rx_ms_ = tms;
              if (n >= 12 && ((rx_buf[0] >> 6) & 0x03) == 2) {
                const uint8_t pt = rx_buf[1] & 0x7F;
                if (pt == 26) {
                  reassembler_.push_rtp_jpeg_packet(rx_buf.data(), static_cast<size_t>(n), loop_us);
                } else {
                  reassembler_.push_rtp_packet(rx_buf.data(), static_cast<size_t>(n), loop_us, false);
                }
              } else {
                reassembler_.push_udp_packet(rx_buf.data(), static_cast<size_t>(n), loop_us);
              }
              continue;
            }

            if (fd == video_fd_ && config_.family == protocol::Family::JIELI_AC79) {
              video_packets_received_.fetch_add(1, std::memory_order_relaxed);
              jieli_last_video_rx_ms_ = tms;
              reassembler_.push_jieli_2224_packet(rx_buf.data(), static_cast<size_t>(n), loop_us);
              continue;
            }

            if (fd != video_fd_) {
              control_packets_received_.fetch_add(1, std::memory_order_relaxed);
              const auto parsed =
                  protocol::parse_rx_telemetry(config_.family, rx_buf.data(), static_cast<size_t>(n));
              if (parsed.valid_battery) {
                battery_percent_.store(parsed.battery_percent, std::memory_order_relaxed);
              }
              if (parsed.valid_status_flags) {
                firmware_status_flags_.store(parsed.status_flags, std::memory_order_relaxed);
              }
              if (parsed.detected_cooingdv_id >= 0) {
                detected_cooingdv_id_.store(parsed.detected_cooingdv_id, std::memory_order_relaxed);
              }
              if (parsed.cooingdv_is_gl && config_.family == protocol::Family::COOINGDV) {
                cooingdv_gl_detected_.store(true, std::memory_order_relaxed);
              }
              if (parsed.cooingdv_photo_sync) {
                const int32_t ctr = parsed.cooingdv_photo_counter;
                if (ctr < 0 || ctr != last_cooingdv_photo_ctr_) {
                  last_cooingdv_photo_ctr_ = (ctr >= 0) ? ctr : -2;
                  const uint8_t sync[2] = {0x09, 0x01};
                  ::sendto(fd, sync, sizeof(sync), 0,
                           reinterpret_cast<const sockaddr*>(&control_addr), sizeof(control_addr));
                }
              } else if (parsed.cooingdv_video_sync) {
                const int32_t ctr = parsed.cooingdv_video_counter;
                if (ctr < 0 || ctr != last_cooingdv_video_ctr_) {
                  last_cooingdv_video_ctr_ = (ctr >= 0) ? ctr : -2;
                  const uint8_t sync[2] = {0x09, 0x02};
                  ::sendto(fd, sync, sizeof(sync), 0,
                           reinterpret_cast<const sockaddr*>(&control_addr), sizeof(control_addr));
                }
              }
            } else {
              video_packets_received_.fetch_add(1, std::memory_order_relaxed);
            }

            // Route shared port 8800 0x93 fragments cleanly for experimental WIFI_UAV too
            if (rx_buf[0] == 0x93 && reassembler_.push_bl60x_packet(rx_buf.data(), static_cast<size_t>(n), loop_us)) {
              video_packets_received_.fetch_add(1, std::memory_order_relaxed);
            } else if (fd == video_fd_ || config_.control_port == config_.video_port) {
              reassembler_.push_udp_packet(rx_buf.data(), static_cast<size_t>(n), loop_us);
            }
          }
        }
      }
    }

    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - loop_start);
    const auto sleep_ms = period_ms - static_cast<int>(elapsed.count());
    if (sleep_ms > 0) {
      std::this_thread::sleep_for(std::chrono::milliseconds(sleep_ms));
    }
  }
}

}  // namespace autopilot::comm
