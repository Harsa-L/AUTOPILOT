#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <vector>

namespace autopilot::video {

enum class TransportKind : int32_t {
  NONE = 0,
  RTSP_H264 = 1,
  RTP_JPEG = 2,
  RAW_UDP_JPEG = 3,
  RAW_UDP_H264 = 4,
  RAW_UDP_H265 = 5,
  SYNTHETIC_TEST_PATTERN = 6,
  RTSP_H265 = 7,
};

struct ReassembledFrame {
  uint64_t sequence = 0;
  int64_t timestamp_us = 0;
  TransportKind transport = TransportKind::NONE;
  int32_t width = 0;
  int32_t height = 0;
  bool is_keyframe = false;
  bool is_synthetic = false;
  std::vector<uint8_t> payload;
};

/**
 * Bounded, reusable-buffer video packet reassembler supporting JPEG, RTP/JPEG,
 * RFC 6184 H.264 RTP (Single/STAP-A/FU-A), RFC 7798 H.265 RTP (Single/AP/FU),
 * and Annex-B H.264/H.265 UDP payloads with drop-oldest backpressure.
 */
class VideoReassembler {
 public:
  static constexpr size_t kMaxFrameBytes = 512 * 1024;
  static constexpr size_t kRingSlots = 3;

  explicit VideoReassembler(TransportKind transport);

  void reset();
  void push_udp_packet(const uint8_t* data, size_t len, int64_t timestamp_us);
  void push_rtp_packet(const uint8_t* data, size_t len, int64_t timestamp_us, bool is_hevc);
  void push_annex_b_csd(const uint8_t* data, size_t len, int64_t timestamp_us, bool is_hevc);

  /**
   * Generates an explicitly tagged synthetic test-pattern RGB24 frame.
   * Callable only when the session is explicitly configured as ISOLATED_TEST_HARNESS.
   */
  void push_synthetic_test_frame(int32_t width, int32_t height, int64_t timestamp_us,
                                 float stick_x, float stick_y);

  bool pop_latest_frame(ReassembledFrame& out);
  uint64_t dropped_frames() const { return dropped_frames_.load(std::memory_order_relaxed); }
  uint64_t assembled_frames() const { return assembled_frames_.load(std::memory_order_relaxed); }

 private:
  void commit_frame(const uint8_t* data, size_t len, int64_t timestamp_us, int32_t width,
                    int32_t height, bool is_keyframe, bool is_synthetic);
  void commit_annex_b_unit(bool is_hevc, bool is_keyframe, int64_t timestamp_us);
  void cache_csd_nal(const uint8_t* nal, size_t len, bool is_hevc);
  void append_start_code(std::vector<uint8_t>& dst);

  TransportKind transport_;
  std::vector<uint8_t> staging_;
  std::vector<uint8_t> rtp_fu_staging_;
  std::vector<uint8_t> csd_annex_b_;
  bool in_frame_ = false;
  bool rtp_fu_active_ = false;
  uint64_t next_sequence_ = 1;
  std::atomic<uint64_t> dropped_frames_{0};
  std::atomic<uint64_t> assembled_frames_{0};

  std::mutex ring_mutex_;
  std::array<ReassembledFrame, kRingSlots> ring_;
  size_t write_idx_ = 0;
  size_t read_idx_ = 0;
  size_t count_ = 0;
};

}  // namespace autopilot::video
