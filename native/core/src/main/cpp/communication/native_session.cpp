#include "communication/native_session.hpp"

#include <android/multinetwork.h>
#include <arpa/inet.h>
#include <cctype>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
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

void resolve_rtsp_endpoint(const SessionConfig& cfg, std::string& out_url, int& out_port) {
  const int default_port =
      (cfg.family == protocol::Family::COOINGDV) ? 7070 : (cfg.video_port > 0 ? cfg.video_port : 554);
  if (cfg.rtsp_path.rfind("rtsp://", 0) == 0) {
    // Parse port from rtsp://host:port/path if present, otherwise default to 554/7070.
    const size_t after_scheme = 7;
    const size_t slash = cfg.rtsp_path.find('/', after_scheme);
    const size_t colon = cfg.rtsp_path.find(':', after_scheme);
    std::string path = (slash != std::string::npos) ? cfg.rtsp_path.substr(slash) : "/webcam";
    if (colon != std::string::npos && (slash == std::string::npos || colon < slash)) {
      const int parsed = std::atoi(cfg.rtsp_path.substr(colon + 1, slash - colon - 1).c_str());
      out_port = parsed > 0 ? parsed : default_port;
    } else {
      out_port = 554;
    }
    out_url = "rtsp://" + cfg.host_ip + ":" + std::to_string(out_port) + path;
  } else {
    std::string path = cfg.rtsp_path.empty() ? "/webcam" : cfg.rtsp_path;
    if (path[0] != '/') path = "/" + path;
    out_port = (path.find("ch00") != std::string::npos) ? 554 : default_port;
    if (out_port == 1234) out_port = 554;  // X69 RTSP typically serves on 554 (/live/ch00_1) or 7070 (/webcam)
    if (path == "/webcam" && cfg.family == protocol::Family::COOINGDV) out_port = 7070;
    out_url = "rtsp://" + cfg.host_ip + ":" + std::to_string(out_port) + path;
  }
}
}  // namespace

NativeSession::NativeSession(const SessionConfig& config)
    : config_(config), reassembler_(config.video_transport) {
  rtsp_rx_buf_.reserve(64 * 1024);
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
      last_socket_errno_.store(errno, std::memory_order_relaxed);
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

bool NativeSession::start() {
  if (running_.load(std::memory_order_acquire)) {
    return true;
  }

  const bool is_harness = config_.family == protocol::Family::ISOLATED_TEST_HARNESS;
  if (is_harness && !config_.allow_synthetic_harness) {
    return false;
  }

  reassembler_.reset();
  rtsp_rx_buf_.clear();
  rtsp_stage_ = RtspStage::IDLE;
  rtsp_cseq_ = 1;
  rtsp_session_id_.clear();
  rtsp_control_url_.clear();
  rtsp_using_udp_ = config_.rtsp_prefer_udp;
  rtsp_udp_fallback_attempted_ = false;
  rtsp_udp_client_port_ =
      (config_.video_port >= 1024 && (config_.video_port % 2 == 0)) ? config_.video_port : 40010;
  flip_burst_ticks_ = 0;
  flip_roll_override_ = 0.0f;
  flip_pitch_override_ = 0.0f;
  tcp_handshake_sent_ = false;
  control_tcp_connected_ = false;

  packets_sent_.store(0, std::memory_order_relaxed);
  packets_received_.store(0, std::memory_order_relaxed);
  last_rx_timestamp_ms_.store(0, std::memory_order_relaxed);
  last_socket_errno_.store(0, std::memory_order_relaxed);
  battery_percent_.store(-1, std::memory_order_relaxed);
  firmware_status_flags_.store(-1, std::memory_order_relaxed);

  if (!is_harness) {
    // Jieli CTP uses TCP port 3333 for control negotiation; other families use UDP control sockets.
    control_is_tcp_ = (config_.family == protocol::Family::COOINGDV_JIELI);
    if (control_is_tcp_) {
      control_fd_ = open_bound_tcp_socket(config_.network_handle, config_.host_ip,
                                          config_.control_port, control_tcp_connected_);
      if (control_fd_ < 0) {
        // Fallback to UDP if kernel refuses TCP connect immediately.
        control_is_tcp_ = false;
        control_fd_ = open_bound_udp_socket(config_.network_handle, false, 0);
      }
    } else {
      control_fd_ = open_bound_udp_socket(config_.network_handle, false, 0);
    }
    if (control_fd_ < 0) {
      return false;
    }

    const bool is_rtsp = (config_.video_transport == video::TransportKind::RTSP_H264 ||
                          config_.video_transport == video::TransportKind::RTSP_H265);
    rtsp_is_hevc_ = (config_.video_transport == video::TransportKind::RTSP_H265);

    if (is_rtsp) {
      resolve_rtsp_endpoint(config_, rtsp_url_, rtsp_port_);
      rtsp_control_url_ = rtsp_url_;
      bool rtsp_now = false;
      rtsp_fd_ = open_bound_tcp_socket(config_.network_handle, config_.host_ip, rtsp_port_, rtsp_now);
      if (rtsp_fd_ >= 0) {
        rtsp_stage_ = rtsp_now ? RtspStage::IDLE : RtspStage::TCP_CONNECTING;
        rtsp_stage_started_ms_ = now_ms();
      }
      // Optional UDP RTP receive port alongside RTSP interleaved TCP.
      if (config_.video_port > 0 && config_.video_port != config_.control_port) {
        video_fd_ = open_bound_udp_socket(config_.network_handle, true, config_.video_port);
      }
    } else if (config_.video_port > 0 && config_.video_port != config_.control_port) {
      video_fd_ = open_bound_udp_socket(config_.network_handle, true, config_.video_port);
    }

    if (config_.heartbeat_port > 0 &&
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
}

void NativeSession::stop() {
  if (!running_.exchange(false, std::memory_order_acq_rel)) {
    return;
  }
  if (worker_.joinable()) {
    worker_.join();
  }
  connected_.store(false, std::memory_order_release);
  close_sockets();
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
  std::lock_guard<std::mutex> lock(state_mutex_);
  if ((action_flag_bit & protocol::ActionFlag::kLatchedMask) != 0) {
    latched_actions_ |= (action_flag_bit & protocol::ActionFlag::kLatchedMask);
  }
  const uint32_t pulse_bits = action_flag_bit & ~protocol::ActionFlag::kLatchedMask;
  if (pulse_bits == 0) return;

  for (auto& slot : active_pulses_) {
    if (slot.flag == pulse_bits || slot.ticks_remaining <= 0) {
      slot.flag = pulse_bits;
      slot.ticks_remaining = 4;  // Transmit across 4 cycles (~160 ms) against UDP packet loss
      return;
    }
  }
  active_pulses_[0] = PulseEntry{pulse_bits, 4};
}

void NativeSession::trigger_flip(int32_t direction) {
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

SessionSnapshot NativeSession::snapshot() {
  SessionSnapshot snap;
  snap.running = running_.load(std::memory_order_acquire);
  snap.connected = connected_.load(std::memory_order_acquire);
  snap.is_synthetic = (config_.family == protocol::Family::ISOLATED_TEST_HARNESS);
  snap.packets_sent = packets_sent_.load(std::memory_order_relaxed);
  snap.packets_received = packets_received_.load(std::memory_order_relaxed);
  snap.dropped_packets = reassembler_.dropped_frames();
  snap.last_rx_timestamp_ms = last_rx_timestamp_ms_.load(std::memory_order_relaxed);
  snap.last_socket_errno = last_socket_errno_.load(std::memory_order_relaxed);
  snap.battery_percent = battery_percent_.load(std::memory_order_relaxed);
  snap.firmware_status_flags = firmware_status_flags_.load(std::memory_order_relaxed);
  {
    std::lock_guard<std::mutex> lock(hex_mutex_);
    snap.last_command_hex = last_command_hex_;
  }
  return snap;
}

bool NativeSession::pop_video_frame(video::ReassembledFrame& out) {
  return reassembler_.pop_latest_frame(out);
}

void NativeSession::poll_jieli_tcp() {
  if (!control_is_tcp_ || control_fd_ < 0) return;

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
  }

  if (control_tcp_connected_ && !tcp_handshake_sent_) {
    const auto handshake = protocol::encode_handshake_packet(config_.family);
    if (!handshake.empty()) {
      const ssize_t sent = ::send(control_fd_, handshake.data(), handshake.size(), MSG_NOSIGNAL);
      if (sent > 0) {
        packets_sent_.fetch_add(1, std::memory_order_relaxed);
        tcp_handshake_sent_ = true;
      }
    }
  }

  if (control_tcp_connected_) {
    uint8_t buf[1024];
    const ssize_t n = ::recv(control_fd_, buf, sizeof(buf), 0);
    if (n > 0) {
      packets_received_.fetch_add(1, std::memory_order_relaxed);
      last_rx_timestamp_ms_.store(now_ms(), std::memory_order_relaxed);
      connected_.store(true, std::memory_order_release);
      const auto parsed = protocol::parse_rx_telemetry(config_.family, buf, static_cast<size_t>(n));
      if (parsed.valid_battery) {
        battery_percent_.store(parsed.battery_percent, std::memory_order_relaxed);
      }
      if (parsed.valid_status_flags) {
        firmware_status_flags_.store(parsed.status_flags, std::memory_order_relaxed);
      }
    }
  }
}

bool NativeSession::ensure_rtsp_udp_socket() {
  if (rtsp_rtp_udp_fd_ >= 0) return true;
  if (rtsp_udp_client_port_ == config_.video_port && video_fd_ >= 0) {
    // Pick a dedicated even RTP port so we do not double-bind video_fd_
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
  size_t consumed = 0;

  while (consumed < rtsp_rx_buf_.size()) {
    const uint8_t* ptr = rtsp_rx_buf_.data() + consumed;
    const size_t avail = rtsp_rx_buf_.size() - consumed;

    // 1. Interleaved RTP/RTCP frame: '$' (0x24) + channel(1) + length(2 big-endian)
    if (ptr[0] == '$') {
      if (avail < 4) break;
      const uint8_t channel = ptr[1];
      const uint16_t pkt_len = (static_cast<uint16_t>(ptr[2]) << 8) | ptr[3];
      if (avail < 4u + pkt_len) break;
      if (channel == 0 && pkt_len > 0) {
        reassembler_.push_rtp_packet(ptr + 4, pkt_len, ts_us, rtsp_is_hevc_);
      }
      consumed += 4u + pkt_len;
      continue;
    }

    // 2. RTSP Response Header (+ optional SDP body)
    if (avail >= 4 && std::memcmp(ptr, "RTSP", 4) == 0) {
      std::string text(reinterpret_cast<const char*>(ptr), avail);
      const size_t hdr_end = text.find("\r\n\r\n");
      if (hdr_end == std::string::npos) break;

      const std::string headers = text.substr(0, hdr_end);
      const std::string lower_hdr = to_lower_ascii(headers);

      // Parse Content-Length
      size_t content_len = 0;
      const size_t cl_pos = lower_hdr.find("content-length:");
      if (cl_pos != std::string::npos) {
        content_len = static_cast<size_t>(std::atoi(headers.c_str() + cl_pos + 15));
      }
      const size_t total_msg_len = hdr_end + 4 + content_len;
      if (avail < total_msg_len) break;

      // Parse Session header
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
        // Parse SDP video track a=control:... and sprop-parameter-sets / sprop-vps/sps/pps
        std::istringstream lines(body);
        std::string line;
        bool in_video_media = false;
        while (std::getline(lines, line)) {
          line = trim_ws(line);
          if (line.rfind("m=video", 0) == 0) {
            in_video_media = true;
          } else if (line.rfind("m=", 0) == 0) {
            in_video_media = false;
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

      // Parse numeric RTSP status code (e.g. 200 OK, 461 Unsupported Transport)
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
        consumed += total_msg_len;
        continue;
      }

      // Advance RTSP state machine
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
        rtsp_last_keepalive_ms_ = now_ms();
      }

      consumed += total_msg_len;
      continue;
    }

    // Resync if unexpected byte encountered in TCP buffer
    ++consumed;
  }

  if (consumed > 0) {
    rtsp_rx_buf_.erase(rtsp_rx_buf_.begin(), rtsp_rx_buf_.begin() + static_cast<ptrdiff_t>(consumed));
  }
}

void NativeSession::step_rtsp_client(int64_t /* now_micros */) {
  if (rtsp_fd_ < 0 || rtsp_stage_ == RtspStage::FAILED) return;
  const int64_t tms = now_ms();

  if (rtsp_stage_ == RtspStage::TCP_CONNECTING) {
    pollfd pfd{rtsp_fd_, POLLOUT, 0};
    if (::poll(&pfd, 1, 0) > 0 && (pfd.revents & POLLOUT)) {
      int err = 0;
      socklen_t len = sizeof(err);
      if (::getsockopt(rtsp_fd_, SOL_SOCKET, SO_ERROR, &err, &len) == 0 && err == 0) {
        rtsp_stage_ = RtspStage::IDLE;
      } else {
        rtsp_stage_ = RtspStage::FAILED;
        return;
      }
    } else if (tms - rtsp_stage_started_ms_ > 3000) {
      rtsp_stage_ = RtspStage::FAILED;
      return;
    } else {
      return;
    }
  }

  if (rtsp_stage_ == RtspStage::IDLE) {
    rtsp_stage_ = RtspStage::SENT_OPTIONS;
    send_rtsp_request("OPTIONS", rtsp_url_, "");
  }

  uint8_t tcp_buf[8192];
  for (int i = 0; i < 8; ++i) {
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

    if (!control_is_tcp_) {
      const auto handshake = protocol::encode_handshake_packet(config_.family);
      if (!handshake.empty() && control_fd_ >= 0) {
        ::sendto(control_fd_, handshake.data(), handshake.size(), 0,
                 reinterpret_cast<const sockaddr*>(&control_addr), sizeof(control_addr));
        packets_sent_.fetch_add(1, std::memory_order_relaxed);
      }
    }
  }

  std::vector<uint8_t> rx_buf(4096);
  uint64_t tick = 0;

  while (running_.load(std::memory_order_acquire)) {
    const auto loop_start = std::chrono::steady_clock::now();
    const int64_t loop_us = now_us();

    if (!is_harness && keyframe_requested_.exchange(false, std::memory_order_acq_rel)) {
      const auto handshake = protocol::encode_handshake_packet(config_.family);
      if (!handshake.empty() && control_fd_ >= 0 && !control_is_tcp_) {
        ::sendto(control_fd_, handshake.data(), handshake.size(), 0,
                 reinterpret_cast<const sockaddr*>(&control_addr), sizeof(control_addr));
      }
      if (rtsp_stage_ == RtspStage::STREAMING) {
        send_rtsp_request("PLAY", rtsp_url_, "");
      }
    }

    protocol::StickState current_state;
    {
      std::lock_guard<std::mutex> lock(state_mutex_);
      current_state = stick_state_;
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

    const auto packet = protocol::encode_control_packet(config_.family, current_state);
    if (!packet.empty()) {
      {
        std::lock_guard<std::mutex> lock(hex_mutex_);
        last_command_hex_ = bytes_to_hex(packet);
      }
      if (is_harness) {
        packets_sent_.fetch_add(1, std::memory_order_relaxed);
        packets_received_.fetch_add(1, std::memory_order_relaxed);
        last_rx_timestamp_ms_.store(now_ms(), std::memory_order_relaxed);
        reassembler_.push_synthetic_test_frame(160, 120, loop_us, current_state.roll,
                                               current_state.pitch);
      } else if (control_is_tcp_) {
        poll_jieli_tcp();
        if (control_tcp_connected_ && control_fd_ >= 0) {
          const ssize_t sent = ::send(control_fd_, packet.data(), packet.size(), MSG_NOSIGNAL);
          if (sent > 0) {
            packets_sent_.fetch_add(1, std::memory_order_relaxed);
          }
        }
      } else if (control_fd_ >= 0) {
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

    // Periodic heartbeat every 5 ticks (~200ms at 25Hz).
    if (!is_harness && (tick % 5 == 0)) {
      const auto hb = protocol::encode_heartbeat_packet(config_.family);
      const int target_fd = heartbeat_fd_ >= 0 ? heartbeat_fd_ : (control_is_tcp_ ? -1 : control_fd_);
      const sockaddr_in& target_addr =
          heartbeat_fd_ >= 0 ? heartbeat_addr : control_addr;
      if (!hb.empty() && target_fd >= 0) {
        ::sendto(target_fd, hb.data(), hb.size(), 0,
                 reinterpret_cast<const sockaddr*>(&target_addr), sizeof(target_addr));
      }
    }

    // Step RTSP client if active
    if (!is_harness && rtsp_fd_ >= 0) {
      step_rtsp_client(loop_us);
    }

    // Poll available UDP RX datagrams without blocking control cadence.
    if (!is_harness) {
      int fds_to_poll[4] = {
          control_is_tcp_ ? -1 : control_fd_,
          video_fd_,
          heartbeat_fd_,
          rtsp_rtp_udp_fd_,
      };
      for (int fd : fds_to_poll) {
        if (fd < 0) continue;
        for (int burst = 0; burst < 24; ++burst) {
          sockaddr_in src{};
          socklen_t src_len = sizeof(src);
          const ssize_t n = ::recvfrom(fd, rx_buf.data(), rx_buf.size(), 0,
                                       reinterpret_cast<sockaddr*>(&src), &src_len);
          if (n <= 0) break;
          packets_received_.fetch_add(1, std::memory_order_relaxed);
          last_rx_timestamp_ms_.store(now_ms(), std::memory_order_relaxed);
          connected_.store(true, std::memory_order_release);
          if (fd == rtsp_rtp_udp_fd_) {
            reassembler_.push_rtp_packet(rx_buf.data(), static_cast<size_t>(n), loop_us, rtsp_is_hevc_);
            continue;
          }
          if (fd != video_fd_) {
            const auto parsed =
                protocol::parse_rx_telemetry(config_.family, rx_buf.data(), static_cast<size_t>(n));
            if (parsed.valid_battery) {
              battery_percent_.store(parsed.battery_percent, std::memory_order_relaxed);
            }
            if (parsed.valid_status_flags) {
              firmware_status_flags_.store(parsed.status_flags, std::memory_order_relaxed);
            }
          }
          reassembler_.push_udp_packet(rx_buf.data(), static_cast<size_t>(n), loop_us);
        }
      }
    }

    ++tick;
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - loop_start);
    const auto sleep_ms = period_ms - static_cast<int>(elapsed.count());
    if (sleep_ms > 0) {
      std::this_thread::sleep_for(std::chrono::milliseconds(sleep_ms));
    }
  }
}

}  // namespace autopilot::comm
