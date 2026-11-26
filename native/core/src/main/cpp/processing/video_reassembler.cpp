#include "processing/video_reassembler.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace autopilot::video {

VideoReassembler::VideoReassembler(TransportKind transport) : transport_(transport) {
  staging_.reserve(kMaxFrameBytes);
  for (auto& slot : ring_) {
    slot.payload.reserve(64 * 1024);
  }
}

void VideoReassembler::reset() {
  staging_.clear();
  in_frame_ = false;
  std::lock_guard<std::mutex> lock(ring_mutex_);
  write_idx_ = 0;
  read_idx_ = 0;
  count_ = 0;
}

void VideoReassembler::commit_frame(const uint8_t* data, size_t len, int64_t timestamp_us,
                                    int32_t width, int32_t height, bool is_keyframe,
                                    bool is_synthetic) {
  if (len == 0 || len > kMaxFrameBytes) {
    return;
  }
  std::lock_guard<std::mutex> lock(ring_mutex_);
  if (count_ == kRingSlots) {
    // Drop oldest frame under backpressure to keep latency minimal.
    read_idx_ = (read_idx_ + 1) % kRingSlots;
    --count_;
    dropped_frames_.fetch_add(1, std::memory_order_relaxed);
  }
  ReassembledFrame& slot = ring_[write_idx_];
  slot.sequence = next_sequence_++;
  slot.timestamp_us = timestamp_us;
  slot.transport = transport_;
  slot.width = width;
  slot.height = height;
  slot.is_keyframe = is_keyframe;
  slot.is_synthetic = is_synthetic;
  slot.payload.assign(data, data + len);

  write_idx_ = (write_idx_ + 1) % kRingSlots;
  ++count_;
  assembled_frames_.fetch_add(1, std::memory_order_relaxed);
}

void VideoReassembler::push_udp_packet(const uint8_t* data, size_t len, int64_t timestamp_us) {
  if (!data || len < 2) return;

  if (transport_ == TransportKind::RTP_JPEG) {
    // Minimal RFC 2435 RTP/JPEG header stripping (12-byte RTP + 8-byte JPEG header).
    if (len <= 20) return;
    const uint8_t* payload = data + 20;
    const size_t payload_len = len - 20;
    if (staging_.size() + payload_len > kMaxFrameBytes) {
      staging_.clear();
      dropped_frames_.fetch_add(1, std::memory_order_relaxed);
      return;
    }
    staging_.insert(staging_.end(), payload, payload + payload_len);
    const bool marker = (data[1] & 0x80) != 0;
    if (marker && !staging_.empty()) {
      commit_frame(staging_.data(), staging_.size(), timestamp_us, 0, 0, true, false);
      staging_.clear();
    }
    return;
  }

  if (transport_ == TransportKind::RAW_UDP_JPEG) {
    // Scan for JPEG SOI (0xFF 0xD8) and EOI (0xFF 0xD9).
    for (size_t i = 0; i < len; ++i) {
      if (!in_frame_) {
        if (i + 1 < len && data[i] == 0xFF && data[i + 1] == 0xD8) {
          staging_.clear();
          in_frame_ = true;
          staging_.push_back(0xFF);
        }
      } else {
        if (staging_.size() >= kMaxFrameBytes) {
          staging_.clear();
          in_frame_ = false;
          dropped_frames_.fetch_add(1, std::memory_order_relaxed);
          return;
        }
        staging_.push_back(data[i]);
        if (staging_.size() >= 2 &&
            staging_[staging_.size() - 2] == 0xFF &&
            staging_[staging_.size() - 1] == 0xD9) {
          commit_frame(staging_.data(), staging_.size(), timestamp_us, 0, 0, true, false);
          staging_.clear();
          in_frame_ = false;
        }
      }
    }
    return;
  }

  if (transport_ == TransportKind::RAW_UDP_H264 || transport_ == TransportKind::RAW_UDP_H265) {
    // Strip optional 4-byte vendor frame header if Annex-B start code starts at offset 4.
    size_t offset = 0;
    if (len > 8 && !(data[0] == 0x00 && data[1] == 0x00 && data[2] == 0x00 && data[3] == 0x01)) {
      if (data[4] == 0x00 && data[5] == 0x00 && data[6] == 0x00 && data[7] == 0x01) {
        offset = 4;
      }
    }
    const uint8_t* nal = data + offset;
    const size_t nal_len = len - offset;
    if (nal_len >= 5 && nal[0] == 0x00 && nal[1] == 0x00 &&
        ((nal[2] == 0x00 && nal[3] == 0x01) || nal[2] == 0x01)) {
      if (!staging_.empty()) {
        commit_frame(staging_.data(), staging_.size(), timestamp_us, 0, 0, true, false);
        staging_.clear();
      }
    }
    if (staging_.size() + nal_len <= kMaxFrameBytes) {
      staging_.insert(staging_.end(), nal, nal + nal_len);
    } else {
      staging_.clear();
      dropped_frames_.fetch_add(1, std::memory_order_relaxed);
    }
  }
}

void VideoReassembler::push_synthetic_test_frame(int32_t width, int32_t height,
                                                 int64_t timestamp_us, float stick_x,
                                                 float stick_y) {
  if (width <= 0 || height <= 0 || static_cast<size_t>(width) * height * 3 > kMaxFrameBytes) {
    return;
  }
  std::vector<uint8_t> rgb(static_cast<size_t>(width) * height * 3);
  const float phase = static_cast<float>(next_sequence_ % 120) / 120.0f;
  const int target_cx = static_cast<int>((0.5f + stick_x * 0.28f + 0.12f * std::sin(phase * 6.28318f)) * width);
  const int target_cy = static_cast<int>((0.5f - stick_y * 0.28f + 0.08f * std::cos(phase * 6.28318f)) * height);

  for (int y = 0; y < height; ++y) {
    for (int x = 0; x < width; ++x) {
      const size_t idx = static_cast<size_t>(y * width + x) * 3;
      const bool grid = (x % 32 == 0) || (y % 32 == 0);
      uint8_t r = grid ? 28 : 12;
      uint8_t g = grid ? 44 : 19;
      uint8_t b = grid ? 62 : 28;

      const int dx = x - target_cx;
      const int dy = y - target_cy;
      const int dist2 = dx * dx + dy * dy;
      if (dist2 < 18 * 18) {
        r = 56;
        g = 189;
        b = 248;
      } else if (std::abs(dx) <= 26 && std::abs(dy) <= 2) {
        r = 248;
        g = 250;
        b = 252;
      } else if (std::abs(dy) <= 26 && std::abs(dx) <= 2) {
        r = 248;
        g = 250;
        b = 252;
      }

      rgb[idx] = r;
      rgb[idx + 1] = g;
      rgb[idx + 2] = b;
    }
  }

  commit_frame(rgb.data(), rgb.size(), timestamp_us, width, height, true, true);
}

bool VideoReassembler::pop_latest_frame(ReassembledFrame& out) {
  std::lock_guard<std::mutex> lock(ring_mutex_);
  if (count_ == 0) {
    return false;
  }
  // Jump to the newest frame to minimize display latency.
  const size_t newest_idx = (write_idx_ + kRingSlots - 1) % kRingSlots;
  out = std::move(ring_[newest_idx]);
  ring_[newest_idx].payload.clear();
  read_idx_ = write_idx_;
  count_ = 0;
  return true;
}

}  // namespace autopilot::video
