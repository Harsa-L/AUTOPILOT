#include "processing/video_reassembler.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace autopilot::video {

namespace {
bool has_start_code4(const uint8_t* p, size_t len) {
  return len >= 4 && p[0] == 0x00 && p[1] == 0x00 && p[2] == 0x00 && p[3] == 0x01;
}

bool has_start_code3(const uint8_t* p, size_t len) {
  return len >= 3 && p[0] == 0x00 && p[1] == 0x00 && p[2] == 0x01;
}

bool is_h264_csd(uint8_t nal_byte) {
  const uint8_t type = nal_byte & 0x1F;
  return type == 7 || type == 8;  // SPS or PPS
}

bool is_h264_idr(uint8_t nal_byte) {
  return (nal_byte & 0x1F) == 5;
}

bool is_h265_csd(uint8_t nal_byte) {
  const uint8_t type = (nal_byte >> 1) & 0x3F;
  return type == 32 || type == 33 || type == 34;  // VPS, SPS, PPS
}

bool is_h265_idr(uint8_t nal_byte) {
  const uint8_t type = (nal_byte >> 1) & 0x3F;
  return type >= 16 && type <= 21;  // BLA/IDR/CRA
}
}  // namespace

VideoReassembler::VideoReassembler(TransportKind transport) : transport_(transport) {
  staging_.reserve(kMaxFrameBytes);
  rtp_fu_staging_.reserve(256 * 1024);
  for (auto& slot : ring_) {
    slot.payload.reserve(64 * 1024);
  }
}

void VideoReassembler::reset() {
  staging_.clear();
  rtp_fu_staging_.clear();
  csd_annex_b_.clear();
  in_frame_ = false;
  rtp_fu_active_ = false;
  std::lock_guard<std::mutex> lock(ring_mutex_);
  write_idx_ = 0;
  read_idx_ = 0;
  count_ = 0;
}

void VideoReassembler::append_start_code(std::vector<uint8_t>& dst) {
  dst.push_back(0x00);
  dst.push_back(0x00);
  dst.push_back(0x00);
  dst.push_back(0x01);
}

void VideoReassembler::cache_csd_nal(const uint8_t* nal, size_t len, bool is_hevc) {
  if (!nal || len == 0 || len > 4096) return;
  const bool csd = is_hevc ? is_h265_csd(nal[0]) : is_h264_csd(nal[0]);
  if (!csd) return;

  // Keep bounded CSD buffer containing most recent VPS/SPS/PPS set.
  if (csd_annex_b_.size() + len + 4 > 4096) {
    csd_annex_b_.clear();
  }
  // Avoid duplicating identical consecutive NALs.
  if (csd_annex_b_.size() >= len + 4) {
    const size_t tail_off = csd_annex_b_.size() - len;
    if (std::memcmp(csd_annex_b_.data() + tail_off, nal, len) == 0) {
      return;
    }
  }
  append_start_code(csd_annex_b_);
  csd_annex_b_.insert(csd_annex_b_.end(), nal, nal + len);
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

void VideoReassembler::commit_annex_b_unit(bool is_hevc, bool is_keyframe, int64_t timestamp_us) {
  if (staging_.empty()) return;
  if (is_keyframe && !csd_annex_b_.empty()) {
    // Check if CSD start-code + VPS/SPS is already at start of staging_.
    bool has_csd = false;
    if (staging_.size() >= 5 && has_start_code4(staging_.data(), staging_.size())) {
      has_csd = is_hevc ? is_h265_csd(staging_[4]) : is_h264_csd(staging_[4]);
    }
    if (!has_csd && csd_annex_b_.size() + staging_.size() <= kMaxFrameBytes) {
      std::vector<uint8_t> combined;
      combined.reserve(csd_annex_b_.size() + staging_.size());
      combined.insert(combined.end(), csd_annex_b_.begin(), csd_annex_b_.end());
      combined.insert(combined.end(), staging_.begin(), staging_.end());
      commit_frame(combined.data(), combined.size(), timestamp_us, 0, 0, true, false);
      staging_.clear();
      return;
    }
  }
  commit_frame(staging_.data(), staging_.size(), timestamp_us, 0, 0, is_keyframe, false);
  staging_.clear();
}

void VideoReassembler::push_annex_b_csd(const uint8_t* data, size_t len, int64_t timestamp_us,
                                        bool is_hevc) {
  if (!data || len < 5) return;
  if (has_start_code4(data, len)) {
    cache_csd_nal(data + 4, len - 4, is_hevc);
  } else if (has_start_code3(data, len)) {
    cache_csd_nal(data + 3, len - 3, is_hevc);
  } else {
    cache_csd_nal(data, len, is_hevc);
  }
  if (!csd_annex_b_.empty()) {
    commit_frame(csd_annex_b_.data(), csd_annex_b_.size(), timestamp_us, 0, 0, true, false);
  }
}

void VideoReassembler::push_rtp_packet(const uint8_t* data, size_t len, int64_t timestamp_us,
                                       bool is_hevc) {
  if (!data || len < 13) return;
  const uint8_t version = (data[0] >> 6) & 0x03;
  if (version != 2) return;

  const bool padding = (data[0] & 0x20) != 0;
  const bool extension = (data[0] & 0x10) != 0;
  const uint8_t csrc_count = data[0] & 0x0F;
  const bool marker = (data[1] & 0x80) != 0;

  size_t header_len = 12 + static_cast<size_t>(csrc_count) * 4;
  if (header_len >= len) return;

  if (extension) {
    if (header_len + 4 > len) return;
    const uint16_t ext_words =
        (static_cast<uint16_t>(data[header_len + 2]) << 8) | data[header_len + 3];
    header_len += 4 + static_cast<size_t>(ext_words) * 4;
  }
  size_t end_len = len;
  if (padding && end_len > header_len) {
    const uint8_t pad_bytes = data[end_len - 1];
    if (pad_bytes > 0 && pad_bytes <= end_len - header_len) {
      end_len -= pad_bytes;
    }
  }
  if (header_len >= end_len) return;

  const uint8_t* payload = data + header_len;
  const size_t payload_len = end_len - header_len;

  if (!is_hevc) {
    // RFC 6184 H.264 RTP depacketization
    const uint8_t nal_hdr = payload[0];
    const uint8_t nal_type = nal_hdr & 0x1F;
    if (nal_type >= 1 && nal_type <= 23) {
      cache_csd_nal(payload, payload_len, false);
      if (staging_.size() + 4 + payload_len > kMaxFrameBytes) {
        staging_.clear();
        dropped_frames_.fetch_add(1, std::memory_order_relaxed);
        return;
      }
      append_start_code(staging_);
      staging_.insert(staging_.end(), payload, payload + payload_len);
      if (marker) {
        commit_annex_b_unit(false, is_h264_idr(nal_hdr) || is_h264_csd(nal_hdr), timestamp_us);
      }
    } else if (nal_type == 24) {
      // STAP-A aggregated NAL units
      size_t off = 1;
      bool any_idr = false;
      while (off + 2 <= payload_len) {
        const uint16_t nsize = (static_cast<uint16_t>(payload[off]) << 8) | payload[off + 1];
        off += 2;
        if (nsize == 0 || off + nsize > payload_len) break;
        cache_csd_nal(payload + off, nsize, false);
        if (is_h264_idr(payload[off])) any_idr = true;
        if (staging_.size() + 4 + nsize <= kMaxFrameBytes) {
          append_start_code(staging_);
          staging_.insert(staging_.end(), payload + off, payload + off + nsize);
        }
        off += nsize;
      }
      if (marker) {
        commit_annex_b_unit(false, any_idr, timestamp_us);
      }
    } else if (nal_type == 28 && payload_len >= 2) {
      // FU-A fragmented NAL unit
      const uint8_t fu_indicator = payload[0];
      const uint8_t fu_header = payload[1];
      const bool start = (fu_header & 0x80) != 0;
      const bool end = (fu_header & 0x40) != 0;
      const uint8_t reconstructed = (fu_indicator & 0xE0) | (fu_header & 0x1F);
      if (start) {
        rtp_fu_staging_.clear();
        rtp_fu_staging_.push_back(reconstructed);
        rtp_fu_active_ = true;
      }
      if (rtp_fu_active_ && payload_len > 2) {
        if (rtp_fu_staging_.size() + (payload_len - 2) <= kMaxFrameBytes) {
          rtp_fu_staging_.insert(rtp_fu_staging_.end(), payload + 2, payload + payload_len);
        } else {
          rtp_fu_staging_.clear();
          rtp_fu_active_ = false;
          dropped_frames_.fetch_add(1, std::memory_order_relaxed);
          return;
        }
      }
      if (end && rtp_fu_active_) {
        const bool key = is_h264_idr(reconstructed);
        if (staging_.size() + 4 + rtp_fu_staging_.size() <= kMaxFrameBytes) {
          append_start_code(staging_);
          staging_.insert(staging_.end(), rtp_fu_staging_.begin(), rtp_fu_staging_.end());
        }
        rtp_fu_staging_.clear();
        rtp_fu_active_ = false;
        if (marker) {
          commit_annex_b_unit(false, key, timestamp_us);
        }
      }
    }
    return;
  }

  // RFC 7798 H.265/HEVC RTP depacketization
  if (payload_len < 2) return;
  const uint8_t nal_type = (payload[0] >> 1) & 0x3F;
  if (nal_type <= 47) {
    cache_csd_nal(payload, payload_len, true);
    if (staging_.size() + 4 + payload_len > kMaxFrameBytes) {
      staging_.clear();
      dropped_frames_.fetch_add(1, std::memory_order_relaxed);
      return;
    }
    append_start_code(staging_);
    staging_.insert(staging_.end(), payload, payload + payload_len);
    if (marker) {
      commit_annex_b_unit(true, is_h265_idr(payload[0]) || is_h265_csd(payload[0]), timestamp_us);
    }
  } else if (nal_type == 48) {
    // AP aggregated packet
    size_t off = 2;
    bool any_idr = false;
    while (off + 2 <= payload_len) {
      const uint16_t nsize = (static_cast<uint16_t>(payload[off]) << 8) | payload[off + 1];
      off += 2;
      if (nsize == 0 || off + nsize > payload_len) break;
      cache_csd_nal(payload + off, nsize, true);
      if (is_h265_idr(payload[off])) any_idr = true;
      if (staging_.size() + 4 + nsize <= kMaxFrameBytes) {
        append_start_code(staging_);
        staging_.insert(staging_.end(), payload + off, payload + off + nsize);
      }
      off += nsize;
    }
    if (marker) {
      commit_annex_b_unit(true, any_idr, timestamp_us);
    }
  } else if (nal_type == 49 && payload_len >= 3) {
    // FU fragmented packet
    const uint8_t fu_hdr = payload[2];
    const bool start = (fu_hdr & 0x80) != 0;
    const bool end = (fu_hdr & 0x40) != 0;
    const uint8_t orig_type = fu_hdr & 0x3F;
    const uint8_t b0 = (payload[0] & 0x81) | (orig_type << 1);
    const uint8_t b1 = payload[1];
    if (start) {
      rtp_fu_staging_.clear();
      rtp_fu_staging_.push_back(b0);
      rtp_fu_staging_.push_back(b1);
      rtp_fu_active_ = true;
    }
    if (rtp_fu_active_ && payload_len > 3) {
      if (rtp_fu_staging_.size() + (payload_len - 3) <= kMaxFrameBytes) {
        rtp_fu_staging_.insert(rtp_fu_staging_.end(), payload + 3, payload + payload_len);
      } else {
        rtp_fu_staging_.clear();
        rtp_fu_active_ = false;
        dropped_frames_.fetch_add(1, std::memory_order_relaxed);
        return;
      }
    }
    if (end && rtp_fu_active_) {
      const bool key = is_h265_idr(b0);
      if (staging_.size() + 4 + rtp_fu_staging_.size() <= kMaxFrameBytes) {
        append_start_code(staging_);
        staging_.insert(staging_.end(), rtp_fu_staging_.begin(), rtp_fu_staging_.end());
      }
      rtp_fu_staging_.clear();
      rtp_fu_active_ = false;
      if (marker) {
        commit_annex_b_unit(true, key, timestamp_us);
      }
    }
  }
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

  if (transport_ == TransportKind::RTSP_H264 || transport_ == TransportKind::RTSP_H265) {
    push_rtp_packet(data, len, timestamp_us, transport_ == TransportKind::RTSP_H265);
    return;
  }

  if (transport_ == TransportKind::RAW_UDP_H264 || transport_ == TransportKind::RAW_UDP_H265) {
    const bool is_hevc = (transport_ == TransportKind::RAW_UDP_H265);
    // Support RTP-wrapped UDP H.264/H.265 if packet starts with RTP v2 header and no Annex-B start code.
    if (!has_start_code4(data, len) && !has_start_code3(data, len) &&
        !(len > 8 && has_start_code4(data + 4, len - 4)) &&
        !(len > 12 && has_start_code4(data + 8, len - 8)) &&
        len >= 14 && ((data[0] >> 6) & 0x03) == 2) {
      push_rtp_packet(data, len, timestamp_us, is_hevc);
      return;
    }

    // Strip optional 4-byte or 8-byte vendor frame header if Annex-B start code starts at offset 4 or 8.
    size_t offset = 0;
    if (len > 8 && !has_start_code4(data, len) && !has_start_code3(data, len)) {
      if (has_start_code4(data + 4, len - 4) || has_start_code3(data + 4, len - 4)) {
        offset = 4;
      } else if (len > 12 && (has_start_code4(data + 8, len - 8) || has_start_code3(data + 8, len - 8))) {
        offset = 8;
      }
    }
    const uint8_t* nal = data + offset;
    const size_t nal_len = len - offset;
    const bool starts_annex_b = has_start_code4(nal, nal_len) || has_start_code3(nal, nal_len);
    if (starts_annex_b) {
      const size_t sc_len = has_start_code4(nal, nal_len) ? 4 : 3;
      if (nal_len > sc_len) {
        const uint8_t hdr = nal[sc_len];
        cache_csd_nal(nal + sc_len, nal_len - sc_len, is_hevc);
        // Only flush previous staged access unit on non-CSD boundary (IDR/P slice) so SPS+PPS+IDR pack together.
        const bool is_csd = is_hevc ? is_h265_csd(hdr) : is_h264_csd(hdr);
        if (!staging_.empty() && !is_csd) {
          bool prev_has_slice = false;
          if (staging_.size() >= 5 && has_start_code4(staging_.data(), staging_.size())) {
            const uint8_t first_hdr = staging_[4];
            prev_has_slice = !(is_hevc ? is_h265_csd(first_hdr) : is_h264_csd(first_hdr));
          } else {
            prev_has_slice = true;
          }
          if (prev_has_slice) {
            const bool prev_idr =
                staging_.size() >= 5 &&
                (is_hevc ? is_h265_idr(staging_[4]) : is_h264_idr(staging_[4]));
            commit_annex_b_unit(is_hevc, prev_idr, timestamp_us);
          }
        }
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
