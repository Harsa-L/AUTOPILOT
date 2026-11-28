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
  return type == 7 || type == 8;
}

bool is_h264_idr(uint8_t nal_byte) {
  return (nal_byte & 0x1F) == 5;
}

bool is_h265_csd(uint8_t nal_byte) {
  const uint8_t type = (nal_byte >> 1) & 0x3F;
  return type == 32 || type == 33 || type == 34;
}

bool is_h265_idr(uint8_t nal_byte) {
  const uint8_t type = (nal_byte >> 1) & 0x3F;
  return type >= 16 && type <= 21;
}

uint16_t read_u16_le(const uint8_t* p) {
  return static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8);
}

uint32_t read_u32_le(const uint8_t* p) {
  return static_cast<uint32_t>(p[0]) |
         (static_cast<uint32_t>(p[1]) << 8) |
         (static_cast<uint32_t>(p[2]) << 16) |
         (static_cast<uint32_t>(p[3]) << 24);
}

// ISO/IEC 10918-1 Annex K zig-zag order and base quantization tables
constexpr uint8_t kZigZag[64] = {
    0,  1,  8,  16, 9,  2,  3,  10,
    17, 24, 32, 25, 18, 11, 4,  5,
    12, 19, 26, 33, 40, 48, 41, 34,
    27, 20, 13, 6,  7,  14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36,
    29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46,
    53, 60, 61, 54, 47, 55, 62, 63};

constexpr uint8_t kStdLumaQ[64] = {
    16, 11, 10, 16, 24, 40, 51, 61,
    12, 12, 14, 19, 26, 58, 60, 55,
    14, 13, 16, 24, 40, 57, 69, 56,
    14, 17, 22, 29, 51, 87, 80, 62,
    18, 22, 37, 56, 68, 109, 103, 77,
    24, 35, 55, 64, 81, 104, 113, 92,
    49, 64, 78, 87, 103, 121, 120, 101,
    72, 92, 95, 98, 112, 100, 103, 99};

constexpr uint8_t kStdChromaQ[64] = {
    17, 18, 24, 47, 99, 99, 99, 99,
    18, 21, 26, 66, 99, 99, 99, 99,
    24, 26, 56, 99, 99, 99, 99, 99,
    47, 66, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99};

constexpr uint8_t kLumDcBits[] = {0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
constexpr uint8_t kLumDcVals[] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};

constexpr uint8_t kLumAcBits[] = {0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d};
constexpr uint8_t kLumAcVals[] = {
    0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
    0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
    0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
    0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
    0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
    0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
    0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
    0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
    0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
    0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
    0xf9, 0xfa};

constexpr uint8_t kChmDcBits[] = {0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
constexpr uint8_t kChmDcVals[] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};

constexpr uint8_t kChmAcBits[] = {0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77};
constexpr uint8_t kChmAcVals[] = {
    0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
    0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
    0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
    0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
    0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
    0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
    0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
    0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
    0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
    0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
    0xf9, 0xfa};

void append_scaled_dqt(std::vector<uint8_t>& dst, const uint8_t* base_q, int quality, uint8_t id) {
  const int q = std::clamp(quality, 1, 99);
  const int scale = (q < 50) ? (5000 / q) : (200 - q * 2);
  dst.push_back(0xFF);
  dst.push_back(0xDB);
  dst.push_back(0x00);
  dst.push_back(67);
  dst.push_back(id);
  for (int i = 0; i < 64; ++i) {
    int val = (static_cast<int>(base_q[kZigZag[i]]) * scale + 50) / 100;
    dst.push_back(static_cast<uint8_t>(std::clamp(val, 1, 255)));
  }
}

void append_dht(std::vector<uint8_t>& dst, uint8_t tc_th, const uint8_t* bits,
                const uint8_t* vals, size_t vals_len) {
  const uint16_t seg_len = static_cast<uint16_t>(2 + 1 + 16 + vals_len);
  dst.push_back(0xFF);
  dst.push_back(0xC4);
  dst.push_back(static_cast<uint8_t>((seg_len >> 8) & 0xFF));
  dst.push_back(static_cast<uint8_t>(seg_len & 0xFF));
  dst.push_back(tc_th);
  dst.insert(dst.end(), bits, bits + 16);
  dst.insert(dst.end(), vals, vals + vals_len);
}

void build_standard_jpeg_header(std::vector<uint8_t>& dst, int width, int height, int quality,
                                uint8_t rtp_type, const std::vector<uint8_t>& custom_q) {
  dst.clear();
  // SOI
  dst.push_back(0xFF);
  dst.push_back(0xD8);
  // APP0 JFIF
  const uint8_t app0[] = {0xFF, 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00,
                          0x00, 0x01, 0x00, 0x01, 0x00, 0x00};
  dst.insert(dst.end(), std::begin(app0), std::end(app0));

  // DQT tables
  if (custom_q.size() >= 128) {
    dst.push_back(0xFF);
    dst.push_back(0xDB);
    dst.push_back(0x00);
    dst.push_back(67);
    dst.push_back(0x00);
    dst.insert(dst.end(), custom_q.begin(), custom_q.begin() + 64);

    dst.push_back(0xFF);
    dst.push_back(0xDB);
    dst.push_back(0x00);
    dst.push_back(67);
    dst.push_back(0x01);
    dst.insert(dst.end(), custom_q.begin() + 64, custom_q.begin() + 128);
  } else {
    append_scaled_dqt(dst, kStdLumaQ, quality, 0);
    append_scaled_dqt(dst, kStdChromaQ, quality, 1);
  }

  // SOF0 Baseline DCT
  const uint8_t y_samp = ((rtp_type & 0x01) == 0) ? 0x21 : 0x22;  // type 0 = 4:2:2, type 1 = 4:2:0
  const uint8_t sof0[] = {
      0xFF, 0xC0, 0x00, 0x11, 0x08,
      static_cast<uint8_t>((height >> 8) & 0xFF), static_cast<uint8_t>(height & 0xFF),
      static_cast<uint8_t>((width >> 8) & 0xFF), static_cast<uint8_t>(width & 0xFF),
      0x03,
      0x01, y_samp, 0x00,
      0x02, 0x11, 0x01,
      0x03, 0x11, 0x01};
  dst.insert(dst.end(), std::begin(sof0), std::end(sof0));

  // Standard Huffman tables
  append_dht(dst, 0x00, kLumDcBits, kLumDcVals, sizeof(kLumDcVals));
  append_dht(dst, 0x10, kLumAcBits, kLumAcVals, sizeof(kLumAcVals));
  append_dht(dst, 0x01, kChmDcBits, kChmDcVals, sizeof(kChmDcVals));
  append_dht(dst, 0x11, kChmAcBits, kChmAcVals, sizeof(kChmAcVals));

  // SOS
  const uint8_t sos[] = {0xFF, 0xDA, 0x00, 0x0C, 0x03, 0x01, 0x00, 0x02, 0x11, 0x03, 0x11,
                         0x00, 0x3F, 0x00};
  dst.insert(dst.end(), std::begin(sos), std::end(sos));
}
}  // namespace

VideoReassembler::VideoReassembler(TransportKind transport) : transport_(transport) {
  staging_.reserve(kMaxFrameBytes);
  rtp_fu_staging_.reserve(256 * 1024);
  rtp_jpeg_scan_.reserve(256 * 1024);
  lewei_buf_.reserve(256 * 1024);
  for (auto& slot : ring_) {
    slot.payload.reserve(64 * 1024);
  }
}

void VideoReassembler::set_transport(TransportKind transport) {
  transport_ = transport;
}

void VideoReassembler::reset() {
  staging_.clear();
  rtp_fu_staging_.clear();
  csd_annex_b_.clear();
  rtp_jpeg_scan_.clear();
  rtp_jpeg_custom_q_.clear();
  lewei_buf_.clear();
  jieli_slot_ = Jieli2224Slot{};
  for (auto& s : bl60x_slots_) s.reset();
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

  if (csd_annex_b_.size() + len + 4 > 4096) {
    csd_annex_b_.clear();
  }
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

void VideoReassembler::push_rtp_jpeg_packet(const uint8_t* data, size_t len, int64_t timestamp_us) {
  if (!data || len < 20) return;
  const uint8_t version = (data[0] >> 6) & 0x03;
  if (version != 2) return;

  const bool padding = (data[0] & 0x20) != 0;
  const bool extension = (data[0] & 0x10) != 0;
  const uint8_t csrc_count = data[0] & 0x0F;
  const bool marker = (data[1] & 0x80) != 0;

  size_t off = 12 + static_cast<size_t>(csrc_count) * 4;
  if (extension) {
    if (off + 4 > len) return;
    const uint16_t ext_words = (static_cast<uint16_t>(data[off + 2]) << 8) | data[off + 3];
    off += 4 + static_cast<size_t>(ext_words) * 4;
  }
  size_t end_len = len;
  if (padding && end_len > off) {
    const uint8_t pad = data[end_len - 1];
    if (pad > 0 && pad <= end_len - off) end_len -= pad;
  }
  if (off + 8 > end_len) return;

  // 8-byte RFC 2435 main JPEG header
  const uint32_t frag_offset = (static_cast<uint32_t>(data[off + 1]) << 16) |
                               (static_cast<uint32_t>(data[off + 2]) << 8) |
                               static_cast<uint32_t>(data[off + 3]);
  const uint8_t type = data[off + 4];
  const uint8_t q = data[off + 5];
  const uint16_t width = static_cast<uint16_t>(data[off + 6]) * 8;
  const uint16_t height = static_cast<uint16_t>(data[off + 7]) * 8;
  off += 8;

  // Optional 4-byte Restart Marker Header (type 64..127)
  if ((type & 0x3F) != type && type >= 64 && type <= 127) {
    if (off + 4 > end_len) return;
    off += 4;
  }

  // Optional Quantization Table Header on first fragment when q >= 128
  if (frag_offset == 0) {
    rtp_jpeg_scan_.clear();
    rtp_jpeg_custom_q_.clear();
    rtp_jpeg_width_ = width;
    rtp_jpeg_height_ = height;
    rtp_jpeg_type_ = type & 0x3F;
    rtp_jpeg_q_ = q;
    if (q >= 128 && off + 4 <= end_len) {
      const uint16_t qt_len = (static_cast<uint16_t>(data[off + 2]) << 8) | data[off + 3];
      off += 4;
      if (off + qt_len <= end_len) {
        rtp_jpeg_custom_q_.assign(data + off, data + off + qt_len);
        off += qt_len;
      }
    }
  }

  if (off < end_len) {
    const size_t chunk_len = end_len - off;
    if (rtp_jpeg_scan_.size() + chunk_len <= kMaxFrameBytes) {
      rtp_jpeg_scan_.insert(rtp_jpeg_scan_.end(), data + off, data + end_len);
    } else {
      rtp_jpeg_scan_.clear();
      dropped_frames_.fetch_add(1, std::memory_order_relaxed);
      return;
    }
  }

  if (marker && !rtp_jpeg_scan_.empty()) {
    if (rtp_jpeg_scan_.size() >= 2 && rtp_jpeg_scan_[0] == 0xFF && rtp_jpeg_scan_[1] == 0xD8) {
      // Server already embedded full JPEG headers inside RTP/JPEG payload
      commit_frame(rtp_jpeg_scan_.data(), rtp_jpeg_scan_.size(), timestamp_us,
                   rtp_jpeg_width_, rtp_jpeg_height_, true, false);
    } else if (rtp_jpeg_width_ > 0 && rtp_jpeg_height_ > 0) {
      // Synthesize valid JPEG JFIF headers around raw entropy scan bytes
      std::vector<uint8_t> jpeg;
      jpeg.reserve(rtp_jpeg_scan_.size() + 768);
      build_standard_jpeg_header(jpeg, rtp_jpeg_width_, rtp_jpeg_height_,
                                 rtp_jpeg_q_ >= 128 ? 75 : std::max<int>(rtp_jpeg_q_, 10),
                                 rtp_jpeg_type_, rtp_jpeg_custom_q_);
      jpeg.insert(jpeg.end(), rtp_jpeg_scan_.begin(), rtp_jpeg_scan_.end());
      if (jpeg.size() < 2 || jpeg[jpeg.size() - 2] != 0xFF || jpeg[jpeg.size() - 1] != 0xD9) {
        jpeg.push_back(0xFF);
        jpeg.push_back(0xD9);
      }
      commit_frame(jpeg.data(), jpeg.size(), timestamp_us,
                   rtp_jpeg_width_, rtp_jpeg_height_, true, false);
    }
    rtp_jpeg_scan_.clear();
  }
}

void VideoReassembler::push_rtp_packet(const uint8_t* data, size_t len, int64_t timestamp_us,
                                       bool is_hevc) {
  if (!data || len < 13) return;
  const uint8_t version = (data[0] >> 6) & 0x03;
  if (version != 2) return;

  const uint8_t pt = data[1] & 0x7F;
  if (pt == 26 || transport_ == TransportKind::RTSP_MJPEG || transport_ == TransportKind::RTP_JPEG) {
    push_rtp_jpeg_packet(data, len, timestamp_us);
    return;
  }

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

bool VideoReassembler::push_bl60x_packet(const uint8_t* data, size_t len, int64_t timestamp_us,
                                         uint32_t* out_ack_frame_id, uint32_t* out_ack_mask,
                                         uint8_t* out_cam_status0, uint8_t* out_cam_status1) {
  if (!data || len < 12 || data[0] != 0x93) return false;
  const uint8_t msg_type = data[1];
  const uint16_t pkt_len = read_u16_le(data + 2);
  if (pkt_len != len && pkt_len > len) return false;
  if (msg_type != 0x01 || len <= 56) {
    // Non-fragment control/ACK/status 0x93 packet recognized for connection handshake
    return true;
  }

  // 56-byte BL60x fragment header layout (per cooingdv.md & chinese_toys_drones.md):
  // [0]=0x93, [1]=0x01, [2..3]=total packet len, [4..7]=frame_id, [8..11]=body_len
  // [12]=frag_idx, [13]=total_frags, [14]=quality(5/10/25/50/75/100)
  // [0x34]=main camera status, [0x35]=flow camera status
  const uint32_t frame_id = read_u32_le(data + 4);
  const uint32_t body_len = read_u32_le(data + 8);
  const uint8_t frag_idx = data[12];
  const uint8_t total_frags_raw = data[13];
  const uint8_t quality = data[14] > 0 ? data[14] : 50;
  const uint16_t cand_w = read_u16_le(data + 16);
  const uint16_t cand_h = read_u16_le(data + 18);
  const int32_t w = (cand_w >= 160 && cand_w <= 4096) ? cand_w : 640;
  const int32_t h = (cand_h >= 120 && cand_h <= 2160) ? cand_h : 360;
  if (out_cam_status0) *out_cam_status0 = data[0x34];
  if (out_cam_status1) *out_cam_status1 = data[0x35];

  const uint16_t total_frags =
      total_frags_raw > 0 ? total_frags_raw
                          : static_cast<uint16_t>(std::max<uint32_t>(1, (body_len + 1023u) / 1024u));
  if (frag_idx >= 32 || total_frags > 32 || frag_idx >= total_frags) {
    return true;
  }

  Bl60xSlot* target = nullptr;
  for (auto& slot : bl60x_slots_) {
    if (slot.active && slot.frame_id == frame_id) {
      target = &slot;
      break;
    }
  }
  if (!target) {
    target = &bl60x_slots_[bl60x_next_slot_];
    bl60x_next_slot_ = (bl60x_next_slot_ + 1) % bl60x_slots_.size();
    target->reset();
    target->active = true;
    target->frame_id = frame_id;
    target->total_frags = total_frags;
    target->quality = quality;
    target->width = w;
    target->height = h;
    target->expected_total_len = body_len;
  }

  const uint8_t* frag_payload = data + 56;
  const size_t avail = len - 56;
  const size_t copy_len =
      (frag_idx + 1 == total_frags && body_len > 0 && (body_len & 0x3FFu) > 0)
          ? std::min<size_t>(avail, body_len & 0x3FFu)
          : std::min<size_t>(avail, 1024u);

  target->frags[frag_idx].assign(frag_payload, frag_payload + copy_len);
  target->received_mask |= (1u << frag_idx);

  if (out_ack_frame_id) *out_ack_frame_id = frame_id;
  if (out_ack_mask) *out_ack_mask = target->received_mask;

  const uint32_t full_mask =
      (total_frags == 32) ? 0xFFFFFFFFu : ((1u << total_frags) - 1u);
  if ((target->received_mask & full_mask) == full_mask) {
    std::vector<uint8_t> jpeg;
    jpeg.reserve(64 * 1024);
    const bool already_framed =
        target->frags[0].size() >= 2 && target->frags[0][0] == 0xFF && target->frags[0][1] == 0xD8;
    if (!already_framed) {
      build_standard_jpeg_header(jpeg, target->width, target->height, target->quality, 1, {});
    }
    for (uint16_t i = 0; i < target->total_frags; ++i) {
      jpeg.insert(jpeg.end(), target->frags[i].begin(), target->frags[i].end());
    }
    if (jpeg.size() < 2 || jpeg[jpeg.size() - 2] != 0xFF || jpeg[jpeg.size() - 1] != 0xD9) {
      jpeg.push_back(0xFF);
      jpeg.push_back(0xD9);
    }
    commit_frame(jpeg.data(), jpeg.size(), timestamp_us, target->width, target->height, true, false);
    target->reset();
  }
  return true;
}

void VideoReassembler::push_jieli_2224_packet(const uint8_t* data, size_t len, int64_t timestamp_us) {
  if (!data || len < 20) return;
  size_t pos = 0;
  // A single UDP 2224 datagram can contain multiple concatenated 20-byte-header pieces!
  while (pos + 20 <= len) {
    const uint8_t* hdr = data + pos;
    const uint8_t type = hdr[0] & 0x7F;
    const uint16_t piece_len = read_u16_le(hdr + 2);
    const uint32_t frame_id = read_u32_le(hdr + 4);
    const uint32_t total_len = read_u32_le(hdr + 8);
    const uint32_t offset = read_u32_le(hdr + 12);
    pos += 20;

    if (type != 2 || piece_len == 0 || pos + piece_len > len || total_len == 0 ||
        total_len > kMaxFrameBytes || offset + piece_len > total_len) {
      break;
    }

    if (!jieli_slot_.active || jieli_slot_.frame_id != frame_id ||
        jieli_slot_.total_len != total_len) {
      jieli_slot_.active = true;
      jieli_slot_.frame_id = frame_id;
      jieli_slot_.total_len = total_len;
      jieli_slot_.bytes_received = 0;
      jieli_slot_.buf.assign(total_len, 0);
      jieli_slot_.filled.assign(total_len, 0);
    }

    const uint8_t* chunk = data + pos;
    pos += piece_len;

    for (size_t i = 0; i < piece_len; ++i) {
      if (jieli_slot_.filled[offset + i] == 0) {
        jieli_slot_.filled[offset + i] = 1;
        jieli_slot_.buf[offset + i] = chunk[i];
        ++jieli_slot_.bytes_received;
      }
    }

    if (jieli_slot_.bytes_received >= jieli_slot_.total_len) {
      commit_frame(jieli_slot_.buf.data(), jieli_slot_.total_len, timestamp_us, 0, 0, true, false);
      jieli_slot_.active = false;
    }
  }
}

void VideoReassembler::push_lewei_tcp_bytes(const uint8_t* data, size_t len, int64_t timestamp_us) {
  if (!data || len == 0) return;
  if (lewei_buf_.size() + len > kMaxFrameBytes * 2) {
    lewei_buf_.clear();
  }
  lewei_buf_.insert(lewei_buf_.end(), data, data + len);

  size_t consumed = 0;
  while (consumed + 46 <= lewei_buf_.size()) {
    const uint8_t* ptr = lewei_buf_.data() + consumed;
    const size_t avail = lewei_buf_.size() - consumed;
    if (std::memcmp(ptr, "lewei_cmd\0", 10) != 0) {
      ++consumed;
      continue;
    }
    const uint32_t cmd = read_u32_le(ptr + 10);
    const uint32_t body_len = read_u32_le(ptr + 22);
    const uint32_t stream_type = read_u32_le(ptr + 26);

    if (body_len > kMaxFrameBytes) {
      consumed += 10;
      continue;
    }
    if (avail < 46u + body_len) {
      break;
    }
    const uint8_t* body = ptr + 46;
    if (cmd == 257 && body_len > 32) {
      // 32-byte Lewei video frame header: [0..3]=flags, [4..7]=frame_len, [8..11]=ts_ms
      const uint32_t frame_len = read_u32_le(body + 4);
      const uint32_t frame_ts = read_u32_le(body + 8);
      if (frame_len > 0 && 32u + frame_len <= body_len && stream_type != 129) {
        std::vector<uint8_t> h264(body + 32, body + 32 + frame_len);
        if (stream_type == 1 && !h264.empty()) {
          // pylwdrone quirk: stream_type 1 inverts 1 byte at offset derived from len & ts
          const size_t quirk_idx = (frame_len ^ frame_ts) % h264.size();
          if (quirk_idx >= 5) {
            h264[quirk_idx] ^= 0xFF;
          }
        }
        bool is_key = false;
        if (h264.size() >= 5 && has_start_code4(h264.data(), h264.size())) {
          is_key = is_h264_idr(h264[4]) || is_h264_csd(h264[4]);
        }
        commit_frame(h264.data(), h264.size(), timestamp_us, 0, 0, is_key, false);
      }
    }
    consumed += 46u + body_len;
  }

  if (consumed > 0) {
    lewei_buf_.erase(lewei_buf_.begin(), lewei_buf_.begin() + static_cast<ptrdiff_t>(consumed));
  }
}

void VideoReassembler::push_udp_packet(const uint8_t* data, size_t len, int64_t timestamp_us) {
  if (!data || len < 2) return;

  if (transport_ == TransportKind::RTP_JPEG) {
    if (len >= 12 && ((data[0] >> 6) & 0x03) == 2) {
      const uint8_t pt = data[1] & 0x7F;
      if (pt == 96) {
        push_rtp_packet(data, len, timestamp_us, false);
      } else {
        push_rtp_jpeg_packet(data, len, timestamp_us);
      }
    } else {
      // Fallback: check complete standalone JPEG datagram (e.g. Jieli UDP 6666 non-RTP JPEG)
      if (len >= 4 && data[0] == 0xFF && data[1] == 0xD8 &&
          data[len - 2] == 0xFF && data[len - 1] == 0xD9) {
        commit_frame(data, len, timestamp_us, 0, 0, true, false);
      }
    }
    return;
  }

  if (transport_ == TransportKind::RAW_UDP_JPEG) {
    for (size_t i = 0; i < len; ++i) {
      if (i + 1 < len && data[i] == 0xFF && data[i + 1] == 0xD8) {
        staging_.clear();
        in_frame_ = true;
        staging_.push_back(0xFF);
        continue;
      }
      if (in_frame_) {
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

  if (transport_ == TransportKind::RTSP_H264 ||
      transport_ == TransportKind::RTSP_H265 ||
      transport_ == TransportKind::RTSP_MJPEG) {
    if (transport_ == TransportKind::RTSP_MJPEG) {
      push_rtp_jpeg_packet(data, len, timestamp_us);
    } else {
      push_rtp_packet(data, len, timestamp_us, transport_ == TransportKind::RTSP_H265);
    }
    return;
  }

  if (transport_ == TransportKind::RAW_UDP_H264 || transport_ == TransportKind::RAW_UDP_H265) {
    const bool is_hevc = (transport_ == TransportKind::RAW_UDP_H265);
    if (!has_start_code4(data, len) && !has_start_code3(data, len) &&
        !(len > 8 && has_start_code4(data + 4, len - 4)) &&
        !(len > 12 && has_start_code4(data + 8, len - 8)) &&
        !(len > 36 && has_start_code4(data + 32, len - 32)) &&
        len >= 14 && ((data[0] >> 6) & 0x03) == 2) {
      push_rtp_packet(data, len, timestamp_us, is_hevc);
      return;
    }

    // Strip optional 4-byte, 8-byte, or X69 32-byte vendor frame header
    size_t offset = 0;
    if (len > 8 && !has_start_code4(data, len) && !has_start_code3(data, len)) {
      if (has_start_code4(data + 4, len - 4) || has_start_code3(data + 4, len - 4)) {
        offset = 4;
      } else if (len > 12 && (has_start_code4(data + 8, len - 8) || has_start_code3(data + 8, len - 8))) {
        offset = 8;
      } else if (len > 36 && (has_start_code4(data + 32, len - 32) || has_start_code3(data + 32, len - 32))) {
        offset = 32;
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
  const size_t newest_idx = (write_idx_ + kRingSlots - 1) % kRingSlots;
  out = std::move(ring_[newest_idx]);
  ring_[newest_idx].payload.clear();
  read_idx_ = write_idx_;
  count_ = 0;
  return true;
}

}  // namespace autopilot::video
