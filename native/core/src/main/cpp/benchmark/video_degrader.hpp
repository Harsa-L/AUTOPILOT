#pragma once

#include <cstdint>
#include <vector>

namespace autopilot::benchmark {

struct DegradeParams {
  int32_t target_width = 640;
  int32_t target_height = 480;
  int32_t aspect_mode = 0;  // 0 = center crop/cover, 1 = letterbox, 2 = stretch
  float crop_zoom = 1.0f;
  float gaussian_blur = 0.0f;
  float motion_blur_px = 0.0f;
  float motion_blur_angle_deg = 0.0f;
  float noise_strength = 0.0f;
  int32_t pixelation_block = 1;
  float sharpen_amount = 0.0f;
  float contrast = 1.0f;
  float brightness = 0.0f;
  float saturation = 1.0f;
  float color_distortion = 0.0f;
  float exposure_ev = 0.0f;
  int32_t dynamic_range_bits = 8;
  int32_t chroma_subsample = 1;
  float block_artifact_strength = 0.0f;
  uint32_t frame_seed = 1337u;
};

/**
 * Reusable C++20 pixel processing engine for Vision Benchmark Studio.
 * Converts Camera2 YUV_420_888 planes directly into oriented clean RGBA8888
 * and applies deterministic, bounded image-quality degradation into RGBA8888
 * with minimal allocations.
 */
class NativeVideoDegrader {
 public:
  NativeVideoDegrader() = default;

  int64_t process_yuv420_to_rgba(
      const uint8_t* y_ptr, int64_t y_cap, int32_t y_stride,
      const uint8_t* u_ptr, int64_t u_cap, int32_t u_stride, int32_t u_pix_stride,
      const uint8_t* v_ptr, int64_t v_cap, int32_t v_stride, int32_t v_pix_stride,
      int32_t src_w, int32_t src_h, int32_t rotation_deg, bool mirror_h,
      uint8_t* out_clean_rgba, int32_t clean_w, int32_t clean_h,
      uint8_t* out_degraded_rgba, const DegradeParams& params);

  int64_t process_rgba_to_rgba(
      const uint8_t* src_rgba, int32_t src_w, int32_t src_h,
      uint8_t* out_degraded_rgba, const DegradeParams& params);

 private:
  void apply_degradation_pipeline(
      const uint8_t* oriented_rgba, int32_t in_w, int32_t in_h,
      uint8_t* out_degraded_rgba, const DegradeParams& params);

  std::vector<uint8_t> clean_scratch_;
  std::vector<uint8_t> work_a_;
  std::vector<uint8_t> work_b_;
};

}  // namespace autopilot::benchmark
