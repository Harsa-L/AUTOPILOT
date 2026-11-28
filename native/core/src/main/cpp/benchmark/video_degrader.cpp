#include "benchmark/video_degrader.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

namespace autopilot::benchmark {

namespace {

inline uint8_t clamp_u8(int32_t v) {
  return static_cast<uint8_t>(std::clamp(v, 0, 255));
}

inline uint8_t clamp_f_u8(float v) {
  return static_cast<uint8_t>(std::clamp(static_cast<int32_t>(std::lround(v)), 0, 255));
}

inline uint32_t xorshift32(uint32_t& state) {
  uint32_t x = state ? state : 0x9E3779B9u;
  x ^= x << 13;
  x ^= x >> 17;
  x ^= x << 5;
  state = x;
  return x;
}

inline float rand_signed(uint32_t& state) {
  const uint32_t r = xorshift32(state);
  return (static_cast<float>(r & 0xFFFFu) / 32767.5f) - 1.0f;
}

void compute_oriented_dims(int32_t src_w, int32_t src_h, int32_t rotation_deg,
                           int32_t& out_w, int32_t& out_h) {
  const int32_t norm_rot = ((rotation_deg % 360) + 360) % 360;
  if (norm_rot == 90 || norm_rot == 270) {
    out_w = src_h;
    out_h = src_w;
  } else {
    out_w = src_w;
    out_h = src_h;
  }
}

void map_dst_to_src(int32_t dx, int32_t dy, int32_t src_w, int32_t src_h,
                    int32_t norm_rot, bool mirror_h, int32_t& sx, int32_t& sy) {
  int32_t ox = dx;
  const int32_t out_w = (norm_rot == 90 || norm_rot == 270) ? src_h : src_w;
  const int32_t out_h = (norm_rot == 90 || norm_rot == 270) ? src_w : src_h;
  if (mirror_h) {
    ox = out_w - 1 - ox;
  }
  switch (norm_rot) {
    case 90:
      sx = dy;
      sy = src_h - 1 - ox;
      break;
    case 180:
      sx = src_w - 1 - ox;
      sy = src_h - 1 - dy;
      break;
    case 270:
      sx = src_w - 1 - dy;
      sy = ox;
      break;
    default:
      sx = ox;
      sy = dy;
      break;
  }
  sx = std::clamp(sx, 0, src_w - 1);
  sy = std::clamp(sy, 0, src_h - 1);
}

void box_blur_rgba(const uint8_t* src, uint8_t* dst, int32_t w, int32_t h, int32_t radius) {
  if (radius <= 0) {
    std::memcpy(dst, src, static_cast<size_t>(w) * h * 4);
    return;
  }
  const int32_t ksize = 2 * radius + 1;
  std::vector<uint8_t> horiz(static_cast<size_t>(w) * h * 4);

  // Horizontal pass
  for (int32_t y = 0; y < h; ++y) {
    const uint8_t* row = src + static_cast<size_t>(y) * w * 4;
    uint8_t* hrow = horiz.data() + static_cast<size_t>(y) * w * 4;
    int32_t sum_r = 0, sum_g = 0, sum_b = 0;
    for (int32_t k = -radius; k <= radius; ++k) {
      const int32_t x = std::clamp(k, 0, w - 1);
      sum_r += row[x * 4];
      sum_g += row[x * 4 + 1];
      sum_b += row[x * 4 + 2];
    }
    for (int32_t x = 0; x < w; ++x) {
      hrow[x * 4] = static_cast<uint8_t>(sum_r / ksize);
      hrow[x * 4 + 1] = static_cast<uint8_t>(sum_g / ksize);
      hrow[x * 4 + 2] = static_cast<uint8_t>(sum_b / ksize);
      hrow[x * 4 + 3] = 255;

      const int32_t add_x = std::clamp(x + radius + 1, 0, w - 1);
      const int32_t rem_x = std::clamp(x - radius, 0, w - 1);
      sum_r += row[add_x * 4] - row[rem_x * 4];
      sum_g += row[add_x * 4 + 1] - row[rem_x * 4 + 1];
      sum_b += row[add_x * 4 + 2] - row[rem_x * 4 + 2];
    }
  }

  // Vertical pass
  for (int32_t x = 0; x < w; ++x) {
    int32_t sum_r = 0, sum_g = 0, sum_b = 0;
    for (int32_t k = -radius; k <= radius; ++k) {
      const int32_t y = std::clamp(k, 0, h - 1);
      const size_t idx = (static_cast<size_t>(y) * w + x) * 4;
      sum_r += horiz[idx];
      sum_g += horiz[idx + 1];
      sum_b += horiz[idx + 2];
    }
    for (int32_t y = 0; y < h; ++y) {
      const size_t out_idx = (static_cast<size_t>(y) * w + x) * 4;
      dst[out_idx] = static_cast<uint8_t>(sum_r / ksize);
      dst[out_idx + 1] = static_cast<uint8_t>(sum_g / ksize);
      dst[out_idx + 2] = static_cast<uint8_t>(sum_b / ksize);
      dst[out_idx + 3] = 255;

      const int32_t add_y = std::clamp(y + radius + 1, 0, h - 1);
      const int32_t rem_y = std::clamp(y - radius, 0, h - 1);
      const size_t add_idx = (static_cast<size_t>(add_y) * w + x) * 4;
      const size_t rem_idx = (static_cast<size_t>(rem_y) * w + x) * 4;
      sum_r += horiz[add_idx] - horiz[rem_idx];
      sum_g += horiz[add_idx + 1] - horiz[rem_idx + 1];
      sum_b += horiz[add_idx + 2] - horiz[rem_idx + 2];
    }
  }
}

}  // namespace

int64_t NativeVideoDegrader::process_yuv420_to_rgba(
    const uint8_t* y_ptr, int64_t y_cap, int32_t y_stride,
    const uint8_t* u_ptr, int64_t u_cap, int32_t u_stride, int32_t u_pix_stride,
    const uint8_t* v_ptr, int64_t v_cap, int32_t v_stride, int32_t v_pix_stride,
    int32_t src_w, int32_t src_h, int32_t rotation_deg, bool mirror_h,
    uint8_t* out_clean_rgba, int32_t clean_w, int32_t clean_h,
    uint8_t* out_degraded_rgba, const DegradeParams& params) {
  const auto t0 = std::chrono::steady_clock::now();
  if (!y_ptr || !u_ptr || !v_ptr || src_w <= 0 || src_h <= 0 || !out_degraded_rgba) {
    return 0;
  }

  int32_t orient_w = 0, orient_h = 0;
  compute_oriented_dims(src_w, src_h, rotation_deg, orient_w, orient_h);
  const int32_t norm_rot = ((rotation_deg % 360) + 360) % 360;

  const size_t clean_bytes = static_cast<size_t>(orient_w) * orient_h * 4;
  if (clean_scratch_.size() != clean_bytes) {
    clean_scratch_.resize(clean_bytes);
  }

  uint8_t* oriented_dst = (out_clean_rgba && clean_w == orient_w && clean_h == orient_h)
                              ? out_clean_rgba
                              : clean_scratch_.data();

  for (int32_t dy = 0; dy < orient_h; ++dy) {
    uint8_t* dst_row = oriented_dst + static_cast<size_t>(dy) * orient_w * 4;
    for (int32_t dx = 0; dx < orient_w; ++dx) {
      int32_t sx = 0, sy = 0;
      map_dst_to_src(dx, dy, src_w, src_h, norm_rot, mirror_h, sx, sy);

      const int64_t y_idx = static_cast<int64_t>(sy) * y_stride + sx;
      const int32_t uv_x = sx >> 1;
      const int32_t uv_y = sy >> 1;
      const int64_t u_idx = static_cast<int64_t>(uv_y) * u_stride + static_cast<int64_t>(uv_x) * u_pix_stride;
      const int64_t v_idx = static_cast<int64_t>(uv_y) * v_stride + static_cast<int64_t>(uv_x) * v_pix_stride;

      const int32_t y_val = (y_idx >= 0 && y_idx < y_cap) ? (y_ptr[y_idx] & 0xFF) : 16;
      const int32_t u_val = (u_idx >= 0 && u_idx < u_cap) ? (u_ptr[u_idx] & 0xFF) : 128;
      const int32_t v_val = (v_idx >= 0 && v_idx < v_cap) ? (v_ptr[v_idx] & 0xFF) : 128;

      const int32_t c = std::max(0, y_val - 16);
      const int32_t d = u_val - 128;
      const int32_t e = v_val - 128;

      dst_row[dx * 4] = clamp_u8((298 * c + 409 * e + 128) >> 8);
      dst_row[dx * 4 + 1] = clamp_u8((298 * c - 100 * d - 208 * e + 128) >> 8);
      dst_row[dx * 4 + 2] = clamp_u8((298 * c + 516 * d + 128) >> 8);
      dst_row[dx * 4 + 3] = 255;
    }
  }

  if (out_clean_rgba && oriented_dst != out_clean_rgba && clean_w > 0 && clean_h > 0) {
    // Simple bilinear/nearest scale if clean output buffer dimensions differ
    for (int32_t cy = 0; cy < clean_h; ++cy) {
      const int32_t sy = (cy * orient_h) / clean_h;
      for (int32_t cx = 0; cx < clean_w; ++cx) {
        const int32_t sx = (cx * orient_w) / clean_w;
        const size_t s_idx = (static_cast<size_t>(sy) * orient_w + sx) * 4;
        const size_t d_idx = (static_cast<size_t>(cy) * clean_w + cx) * 4;
        std::memcpy(out_clean_rgba + d_idx, oriented_dst + s_idx, 4);
      }
    }
  }

  apply_degradation_pipeline(oriented_dst, orient_w, orient_h, out_degraded_rgba, params);
  const auto t1 = std::chrono::steady_clock::now();
  return std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count();
}

int64_t NativeVideoDegrader::process_rgba_to_rgba(
    const uint8_t* src_rgba, int32_t src_w, int32_t src_h,
    uint8_t* out_degraded_rgba, const DegradeParams& params) {
  const auto t0 = std::chrono::steady_clock::now();
  if (!src_rgba || src_w <= 0 || src_h <= 0 || !out_degraded_rgba) {
    return 0;
  }
  apply_degradation_pipeline(src_rgba, src_w, src_h, out_degraded_rgba, params);
  const auto t1 = std::chrono::steady_clock::now();
  return std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count();
}

void NativeVideoDegrader::apply_degradation_pipeline(
    const uint8_t* src_rgba, int32_t in_w, int32_t in_h,
    uint8_t* out_degraded_rgba, const DegradeParams& params) {
  const int32_t dst_w = std::max(16, params.target_width);
  const int32_t dst_h = std::max(16, params.target_height);
  const size_t dst_bytes = static_cast<size_t>(dst_w) * dst_h * 4;
  if (work_a_.size() != dst_bytes) work_a_.resize(dst_bytes);
  if (work_b_.size() != dst_bytes) work_b_.resize(dst_bytes);

  // 1. Resolution scaling, crop zoom, and aspect-ratio mapping (bilinear sampling)
  const float zoom = std::clamp(params.crop_zoom, 1.0f, 3.0f);
  const float src_aspect = static_cast<float>(in_w) / static_cast<float>(in_h);
  const float dst_aspect = static_cast<float>(dst_w) / static_cast<float>(dst_h);

  float roi_w = static_cast<float>(in_w);
  float roi_h = static_cast<float>(in_h);
  if (params.aspect_mode == 0) {
    // Center crop / cover
    if (src_aspect > dst_aspect) {
      roi_w = roi_h * dst_aspect;
    } else {
      roi_h = roi_w / dst_aspect;
    }
  }
  roi_w /= zoom;
  roi_h /= zoom;
  const float roi_x0 = 0.5f * (static_cast<float>(in_w) - roi_w);
  const float roi_y0 = 0.5f * (static_cast<float>(in_h) - roi_h);

  // Letterbox viewport bounds when aspect_mode == 1
  float vp_x0 = 0.0f, vp_y0 = 0.0f;
  float vp_w = static_cast<float>(dst_w), vp_h = static_cast<float>(dst_h);
  if (params.aspect_mode == 1) {
    if (src_aspect > dst_aspect) {
      vp_h = vp_w / src_aspect;
      vp_y0 = 0.5f * (static_cast<float>(dst_h) - vp_h);
    } else {
      vp_w = vp_h * src_aspect;
      vp_x0 = 0.5f * (static_cast<float>(dst_w) - vp_w);
    }
  }

  for (int32_t y = 0; y < dst_h; ++y) {
    uint8_t* dst_row = work_a_.data() + static_cast<size_t>(y) * dst_w * 4;
    const float v_norm = (static_cast<float>(y) + 0.5f - vp_y0) / vp_h;
    const bool in_y = (params.aspect_mode != 1) || (v_norm >= 0.0f && v_norm <= 1.0f);
    const float sy_f = std::clamp(roi_y0 + v_norm * roi_h - 0.5f, 0.0f, static_cast<float>(in_h - 1));
    const int32_t y0 = static_cast<int32_t>(sy_f);
    const int32_t y1 = std::min(y0 + 1, in_h - 1);
    const float fy = sy_f - static_cast<float>(y0);

    for (int32_t x = 0; x < dst_w; ++x) {
      const float u_norm = (static_cast<float>(x) + 0.5f - vp_x0) / vp_w;
      const bool in_x = (params.aspect_mode != 1) || (u_norm >= 0.0f && u_norm <= 1.0f);
      if (!in_x || !in_y) {
        dst_row[x * 4] = 0;
        dst_row[x * 4 + 1] = 0;
        dst_row[x * 4 + 2] = 0;
        dst_row[x * 4 + 3] = 255;
        continue;
      }

      const float sx_f = std::clamp(roi_x0 + u_norm * roi_w - 0.5f, 0.0f, static_cast<float>(in_w - 1));
      const int32_t x0 = static_cast<int32_t>(sx_f);
      const int32_t x1 = std::min(x0 + 1, in_w - 1);
      const float fx = sx_f - static_cast<float>(x0);

      const uint8_t* p00 = src_rgba + (static_cast<size_t>(y0) * in_w + x0) * 4;
      const uint8_t* p10 = src_rgba + (static_cast<size_t>(y0) * in_w + x1) * 4;
      const uint8_t* p01 = src_rgba + (static_cast<size_t>(y1) * in_w + x0) * 4;
      const uint8_t* p11 = src_rgba + (static_cast<size_t>(y1) * in_w + x1) * 4;

      const float w00 = (1.0f - fx) * (1.0f - fy);
      const float w10 = fx * (1.0f - fy);
      const float w01 = (1.0f - fx) * fy;
      const float w11 = fx * fy;

      for (int c = 0; c < 3; ++c) {
        dst_row[x * 4 + c] = clamp_f_u8(
            p00[c] * w00 + p10[c] * w10 + p01[c] * w01 + p11[c] * w11);
      }
      dst_row[x * 4 + 3] = 255;
    }
  }

  uint8_t* cur = work_a_.data();
  uint8_t* tmp = work_b_.data();

  // 2. Pixelation (block-hold before blur/compression if enabled)
  if (params.pixelation_block > 1) {
    const int32_t bs = std::clamp(params.pixelation_block, 2, 32);
    for (int32_t by = 0; by < dst_h; by += bs) {
      const int32_t sy = std::min(by + bs / 2, dst_h - 1);
      for (int32_t bx = 0; bx < dst_w; bx += bs) {
        const int32_t sx = std::min(bx + bs / 2, dst_w - 1);
        const uint8_t* ref = cur + (static_cast<size_t>(sy) * dst_w + sx) * 4;
        const int32_t ey = std::min(by + bs, dst_h);
        const int32_t ex = std::min(bx + bs, dst_w);
        for (int32_t y = by; y < ey; ++y) {
          uint8_t* row = cur + static_cast<size_t>(y) * dst_w * 4;
          for (int32_t x = bx; x < ex; ++x) {
            row[x * 4] = ref[0];
            row[x * 4 + 1] = ref[1];
            row[x * 4 + 2] = ref[2];
          }
        }
      }
    }
  }

  // 3. Gaussian blur (two-pass box approximation for Gaussian)
  if (params.gaussian_blur > 0.35f) {
    const int32_t radius = std::clamp(static_cast<int32_t>(std::lround(params.gaussian_blur)), 1, 8);
    box_blur_rgba(cur, tmp, dst_w, dst_h, radius);
    box_blur_rgba(tmp, cur, dst_w, dst_h, std::max(1, radius / 2));
  }

  // 4. Directional motion blur
  if (params.motion_blur_px >= 1.5f) {
    const int32_t taps = std::clamp(static_cast<int32_t>(std::lround(params.motion_blur_px)), 2, 24);
    const float rad = params.motion_blur_angle_deg * 0.0174532925f;
    const float dir_x = std::cos(rad);
    const float dir_y = std::sin(rad);
    for (int32_t y = 0; y < dst_h; ++y) {
      uint8_t* dst_row = tmp + static_cast<size_t>(y) * dst_w * 4;
      for (int32_t x = 0; x < dst_w; ++x) {
        int32_t sr = 0, sg = 0, sb = 0;
        for (int32_t t = 0; t < taps; ++t) {
          const float offset = (static_cast<float>(t) / static_cast<float>(taps - 1) - 0.5f) * params.motion_blur_px;
          const int32_t sx = std::clamp(static_cast<int32_t>(std::lround(x + offset * dir_x)), 0, dst_w - 1);
          const int32_t sy = std::clamp(static_cast<int32_t>(std::lround(y + offset * dir_y)), 0, dst_h - 1);
          const uint8_t* sp = cur + (static_cast<size_t>(sy) * dst_w + sx) * 4;
          sr += sp[0];
          sg += sp[1];
          sb += sp[2];
        }
        dst_row[x * 4] = static_cast<uint8_t>(sr / taps);
        dst_row[x * 4 + 1] = static_cast<uint8_t>(sg / taps);
        dst_row[x * 4 + 2] = static_cast<uint8_t>(sb / taps);
        dst_row[x * 4 + 3] = 255;
      }
    }
    std::swap(cur, tmp);
  }

  // 5. Unsharp-mask sharpening
  if (params.sharpen_amount > 0.02f) {
    box_blur_rgba(cur, tmp, dst_w, dst_h, 1);
    const float k = std::clamp(params.sharpen_amount, 0.0f, 2.5f);
    for (size_t i = 0; i < dst_bytes; i += 4) {
      for (int c = 0; c < 3; ++c) {
        const float orig = static_cast<float>(cur[i + c]);
        const float blur = static_cast<float>(tmp[i + c]);
        cur[i + c] = clamp_f_u8(orig + k * (orig - blur));
      }
    }
  }

  // 6. Chroma subsampling / color bleed degradation
  if (params.chroma_subsample > 1) {
    const int32_t cs = std::clamp(params.chroma_subsample, 2, 8);
    for (int32_t by = 0; by < dst_h; by += cs) {
      const int32_t ey = std::min(by + cs, dst_h);
      for (int32_t bx = 0; bx < dst_w; bx += cs) {
        const int32_t ex = std::min(bx + cs, dst_w);
        float sum_u = 0.0f, sum_v = 0.0f;
        int32_t count = 0;
        for (int32_t y = by; y < ey; ++y) {
          const uint8_t* row = cur + static_cast<size_t>(y) * dst_w * 4;
          for (int32_t x = bx; x < ex; ++x) {
            const float r = row[x * 4];
            const float g = row[x * 4 + 1];
            const float b = row[x * 4 + 2];
            sum_u += -0.14713f * r - 0.28886f * g + 0.436f * b;
            sum_v += 0.615f * r - 0.51499f * g - 0.10001f * b;
            ++count;
          }
        }
        const float avg_u = sum_u / std::max(1, count);
        const float avg_v = sum_v / std::max(1, count);
        for (int32_t y = by; y < ey; ++y) {
          uint8_t* row = cur + static_cast<size_t>(y) * dst_w * 4;
          for (int32_t x = bx; x < ex; ++x) {
            const float r = row[x * 4];
            const float g = row[x * 4 + 1];
            const float b = row[x * 4 + 2];
            const float luma = 0.299f * r + 0.587f * g + 0.114f * b;
            row[x * 4] = clamp_f_u8(luma + 1.13983f * avg_v);
            row[x * 4 + 1] = clamp_f_u8(luma - 0.39465f * avg_u - 0.58060f * avg_v);
            row[x * 4 + 2] = clamp_f_u8(luma + 2.03211f * avg_u);
          }
        }
      }
    }
  }

  // 7. Block quantization artifacts (simulated 8x8 macroblock ringing / quant stepping)
  if (params.block_artifact_strength > 0.02f) {
    const float strength = std::clamp(params.block_artifact_strength, 0.0f, 1.0f);
    const float q_step = 4.0f + strength * 42.0f;
    for (int32_t by = 0; by < dst_h; by += 8) {
      const int32_t ey = std::min(by + 8, dst_h);
      for (int32_t bx = 0; bx < dst_w; bx += 8) {
        const int32_t ex = std::min(bx + 8, dst_w);
        int32_t sr = 0, sg = 0, sb = 0, cnt = 0;
        for (int32_t y = by; y < ey; ++y) {
          const uint8_t* row = cur + static_cast<size_t>(y) * dst_w * 4;
          for (int32_t x = bx; x < ex; ++x) {
            sr += row[x * 4];
            sg += row[x * 4 + 1];
            sb += row[x * 4 + 2];
            ++cnt;
          }
        }
        const float ar = static_cast<float>(sr) / std::max(1, cnt);
        const float ag = static_cast<float>(sg) / std::max(1, cnt);
        const float ab = static_cast<float>(sb) / std::max(1, cnt);
        for (int32_t y = by; y < ey; ++y) {
          uint8_t* row = cur + static_cast<size_t>(y) * dst_w * 4;
          for (int32_t x = bx; x < ex; ++x) {
            const float r_mix = cur[(static_cast<size_t>(y) * dst_w + x) * 4] * (1.0f - strength * 0.55f) + ar * (strength * 0.55f);
            const float g_mix = cur[(static_cast<size_t>(y) * dst_w + x) * 4 + 1] * (1.0f - strength * 0.55f) + ag * (strength * 0.55f);
            const float b_mix = cur[(static_cast<size_t>(y) * dst_w + x) * 4 + 2] * (1.0f - strength * 0.55f) + ab * (strength * 0.55f);
            row[x * 4] = clamp_f_u8(std::round(r_mix / q_step) * q_step);
            row[x * 4 + 1] = clamp_f_u8(std::round(g_mix / q_step) * q_step);
            row[x * 4 + 2] = clamp_f_u8(std::round(b_mix / q_step) * q_step);
          }
        }
      }
    }
  }

  // 8. Photometric pass: Exposure, Contrast, Brightness, Saturation, Color distortion, Dynamic range, Noise
  const float exp_gain = std::pow(2.0f, std::clamp(params.exposure_ev, -3.5f, 3.5f));
  const float contrast = std::clamp(params.contrast, 0.25f, 2.5f);
  const float bright_offset = std::clamp(params.brightness, -0.6f, 0.6f) * 255.0f;
  const float sat = std::clamp(params.saturation, 0.0f, 2.5f);
  const float color_dist = std::clamp(params.color_distortion, 0.0f, 1.0f);
  const float r_cast = 1.0f + color_dist * 0.28f;
  const float g_cast = 1.0f - color_dist * 0.14f;
  const float b_cast = 1.0f + color_dist * 0.22f;
  const int32_t dr_bits = std::clamp(params.dynamic_range_bits, 2, 8);
  const int32_t dr_levels = (1 << dr_bits) - 1;
  const float noise_amp = std::clamp(params.noise_strength, 0.0f, 1.0f) * 58.0f;
  uint32_t rng = params.frame_seed;

  for (size_t i = 0; i < dst_bytes; i += 4) {
    float r = static_cast<float>(cur[i]) * exp_gain;
    float g = static_cast<float>(cur[i + 1]) * exp_gain;
    float b = static_cast<float>(cur[i + 2]) * exp_gain;

    // Contrast & brightness
    r = (r - 127.5f) * contrast + 127.5f + bright_offset;
    g = (g - 127.5f) * contrast + 127.5f + bright_offset;
    b = (b - 127.5f) * contrast + 127.5f + bright_offset;

    // Saturation & color distortion
    const float luma = 0.299f * r + 0.587f * g + 0.114f * b;
    r = (luma + (r - luma) * sat) * r_cast;
    g = (luma + (g - luma) * sat) * g_cast;
    b = (luma + (b - luma) * sat) * b_cast;

    // Dynamic range bit-depth quantization
    if (dr_bits < 8 && dr_levels > 0) {
      const float step = 255.0f / static_cast<float>(dr_levels);
      r = std::round(std::clamp(r, 0.0f, 255.0f) / step) * step;
      g = std::round(std::clamp(g, 0.0f, 255.0f) / step) * step;
      b = std::round(std::clamp(b, 0.0f, 255.0f) / step) * step;
    }

    // Sensor noise & grain (chroma + luma grain)
    if (noise_amp > 0.0f) {
      const float luma_n = rand_signed(rng) * noise_amp;
      r += luma_n + rand_signed(rng) * (noise_amp * 0.25f);
      g += luma_n + rand_signed(rng) * (noise_amp * 0.20f);
      b += luma_n + rand_signed(rng) * (noise_amp * 0.30f);
    }

    out_degraded_rgba[i] = clamp_f_u8(r);
    out_degraded_rgba[i + 1] = clamp_f_u8(g);
    out_degraded_rgba[i + 2] = clamp_f_u8(b);
    out_degraded_rgba[i + 3] = 255;
  }
}

}  // namespace autopilot::benchmark
