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
  RTSP_MJPEG = 8,
  LEWEI_TCP_H264 = 9,
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
 * Bounded, reusable-buffer video packet reassembler supporting:
 * - RFC 2435 RTP/JPEG synthesis & passthrough (RTSP_MJPEG / RTP_JPEG)
 * - RFC 6184 H.264 RTP (Single/STAP-A/FU-A)
 * - RFC 7798 H.265 RTP (Single/AP/FU)
 * - FLOW-UFO BL60x 0x93 56-byte fragmented MJPEG + Q5..Q100 JPEG header assembly
 * - Jieli AC79 multi-piece 20-byte UDP :2224 JPEG assembly
 * - Lewei TCP :7060 32-byte H.264 frame stream extraction
 * - Annex-B H.264/H.265 UDP payloads with 4/8/32-byte vendor header stripping
 */
class VideoReassembler {
 public:
  static constexpr size_t kMaxFrameBytes = 512 * 1024;
  static constexpr size_t kRingSlots = 3;

  explicit VideoReassembler(TransportKind transport);

  void reset();
  void set_transport(TransportKind transport);
  TransportKind transport() const { return transport_; }

  void push_udp_packet(const uint8_t* data, size_t len, int64_t timestamp_us);
  void push_rtp_packet(const uint8_t* data, size_t len, int64_t timestamp_us, bool is_hevc);
  void push_rtp_jpeg_packet(const uint8_t* data, size_t len, int64_t timestamp_us);
  void push_annex_b_csd(const uint8_t* data, size_t len, int64_t timestamp_us, bool is_hevc);

  // Protocol-specific parsers:
  // Returns true if packet was a BL60x 0x93 frame fragment (also fills latest frame_id & frag_mask).
  bool push_bl60x_packet(const uint8_t* data, size_t len, int64_t timestamp_us,
                         uint32_t* out_ack_frame_id = nullptr, uint32_t* out_ack_mask = nullptr,
                         uint8_t* out_cam_status0 = nullptr, uint8_t* out_cam_status1 = nullptr);
  void push_jieli_2224_packet(const uint8_t* data, size_t len, int64_t timestamp_us);
  void push_lewei_tcp_bytes(const uint8_t* data, size_t len, int64_t timestamp_us);

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
  struct Bl60xSlot {
    bool active = false;
    uint32_t frame_id = 0;
    uint16_t total_frags = 0;
    uint32_t received_mask = 0;
    uint8_t quality = 50;
    int32_t width = 640;
    int32_t height = 360;
    uint32_t expected_total_len = 0;
    std::array<std::vector<uint8_t>, 32> frags;
    void reset() {
      active = false;
      frame_id = 0;
      total_frags = 0;
      received_mask = 0;
      quality = 50;
      width = 640;
      height = 360;
      expected_total_len = 0;
      for (auto& f : frags) f.clear();
    }
  };

  struct Jieli2224Slot {
    bool active = false;
    uint32_t frame_id = 0;
    uint32_t total_len = 0;
    uint32_t bytes_received = 0;
    std::vector<uint8_t> buf;
    std::vector<uint8_t> filled;
  };

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

  // RFC 2435 RTP/JPEG assembly state
  uint16_t rtp_jpeg_width_ = 0;
  uint16_t rtp_jpeg_height_ = 0;
  uint8_t rtp_jpeg_type_ = 1;
  uint8_t rtp_jpeg_q_ = 50;
  std::vector<uint8_t> rtp_jpeg_custom_q_;
  std::vector<uint8_t> rtp_jpeg_scan_;

  // FLOW-UFO BL60x 4-slot fragment assembly
  std::array<Bl60xSlot, 4> bl60x_slots_;
  size_t bl60x_next_slot_ = 0;

  // Jieli UDP 2224 multi-piece assembly
  Jieli2224Slot jieli_slot_;

  // Lewei TCP 7060 stream buffer
  std::vector<uint8_t> lewei_buf_;

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
