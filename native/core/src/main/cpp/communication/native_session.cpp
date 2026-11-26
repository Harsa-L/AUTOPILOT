#include "communication/native_session.hpp"

#include <android/multinetwork.h>
#include <arpa/inet.h>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
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
}  // namespace

NativeSession::NativeSession(const SessionConfig& config)
    : config_(config), reassembler_(config.video_transport) {}

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

bool NativeSession::start() {
  if (running_.load(std::memory_order_acquire)) {
    return true;
  }

  const bool is_harness = config_.family == protocol::Family::ISOLATED_TEST_HARNESS;
  if (is_harness && !config_.allow_synthetic_harness) {
    return false;
  }

  reassembler_.reset();
  packets_sent_.store(0, std::memory_order_relaxed);
  packets_received_.store(0, std::memory_order_relaxed);
  last_rx_timestamp_ms_.store(0, std::memory_order_relaxed);
  last_socket_errno_.store(0, std::memory_order_relaxed);

  if (!is_harness) {
    control_fd_ = open_bound_udp_socket(config_.network_handle, false, 0);
    if (control_fd_ < 0) {
      return false;
    }
    if (config_.video_port > 0 && config_.video_port != config_.control_port &&
        config_.video_transport != video::TransportKind::RTSP_H264) {
      video_fd_ = open_bound_udp_socket(config_.network_handle, true, config_.video_port);
    }
    if (config_.heartbeat_port > 0 && config_.heartbeat_port != config_.control_port) {
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
  stick_state_.camera_tilt = camera_tilt;
}

void NativeSession::trigger_action(uint32_t action_flag_bit) {
  std::lock_guard<std::mutex> lock(state_mutex_);
  pending_oneshot_actions_ |= action_flag_bit;
}

void NativeSession::clear_actions() {
  std::lock_guard<std::mutex> lock(state_mutex_);
  pending_oneshot_actions_ = 0;
  stick_state_.action_flags = 0;
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
  {
    std::lock_guard<std::mutex> lock(hex_mutex_);
    snap.last_command_hex = last_command_hex_;
  }
  return snap;
}

bool NativeSession::pop_video_frame(video::ReassembledFrame& out) {
  return reassembler_.pop_latest_frame(out);
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

    const auto handshake = protocol::encode_handshake_packet(config_.family);
    if (!handshake.empty() && control_fd_ >= 0) {
      ::sendto(control_fd_, handshake.data(), handshake.size(), 0,
               reinterpret_cast<const sockaddr*>(&control_addr), sizeof(control_addr));
      packets_sent_.fetch_add(1, std::memory_order_relaxed);
    }
  }

  std::vector<uint8_t> rx_buf(4096);
  uint64_t tick = 0;

  while (running_.load(std::memory_order_acquire)) {
    const auto loop_start = std::chrono::steady_clock::now();
    protocol::StickState current_state;
    {
      std::lock_guard<std::mutex> lock(state_mutex_);
      current_state = stick_state_;
      current_state.action_flags |= pending_oneshot_actions_;
      // Keep oneshot actions active for one control tick, except emergency stop which persists until cleared.
      pending_oneshot_actions_ &= protocol::ActionFlag::EMERGENCY_STOP;
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
        reassembler_.push_synthetic_test_frame(160, 120, now_us(), current_state.roll,
                                               current_state.pitch);
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
      const int target_fd = heartbeat_fd_ >= 0 ? heartbeat_fd_ : control_fd_;
      const sockaddr_in& target_addr =
          heartbeat_fd_ >= 0 ? heartbeat_addr : control_addr;
      if (!hb.empty() && target_fd >= 0) {
        ::sendto(target_fd, hb.data(), hb.size(), 0,
                 reinterpret_cast<const sockaddr*>(&target_addr), sizeof(target_addr));
      }
    }

    // Poll available RX datagrams without blocking the control cadence.
    if (!is_harness) {
      int fds_to_poll[2] = {control_fd_, video_fd_};
      for (int fd : fds_to_poll) {
        if (fd < 0) continue;
        for (int burst = 0; burst < 16; ++burst) {
          sockaddr_in src{};
          socklen_t src_len = sizeof(src);
          const ssize_t n = ::recvfrom(fd, rx_buf.data(), rx_buf.size(), 0,
                                       reinterpret_cast<sockaddr*>(&src), &src_len);
          if (n <= 0) break;
          packets_received_.fetch_add(1, std::memory_order_relaxed);
          last_rx_timestamp_ms_.store(now_ms(), std::memory_order_relaxed);
          connected_.store(true, std::memory_order_release);
          reassembler_.push_udp_packet(rx_buf.data(), static_cast<size_t>(n), now_us());
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
