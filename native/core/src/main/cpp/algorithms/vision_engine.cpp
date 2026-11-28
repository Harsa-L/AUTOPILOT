#include "algorithms/vision_engine.hpp"

#include <algorithm>
#include <cmath>

namespace autopilot::vision {

namespace {
float box_iou(float al, float at, float ar, float ab, float bl, float bt, float br, float bb) {
  const float il = std::max(al, bl);
  const float it = std::max(at, bt);
  const float ir = std::min(ar, br);
  const float ib = std::min(ab, bb);
  const float iw = std::max(0.0f, ir - il);
  const float ih = std::max(0.0f, ib - it);
  const float inter = iw * ih;
  const float area_a = std::max(0.0f, ar - al) * std::max(0.0f, ab - at);
  const float area_b = std::max(0.0f, br - bl) * std::max(0.0f, bb - bt);
  const float denom = area_a + area_b - inter;
  return denom > 1e-6f ? inter / denom : 0.0f;
}

float nominal_class_height_meters(int32_t class_id) {
  // Approximate real-world reference heights (person=1.7m, vehicle/chair/generic=1.2m).
  if (class_id == 0) return 1.70f;
  return 1.20f;
}
}  // namespace

void NativeVisionEngine::reset() {
  prev_luma_.clear();
  prev_width_ = 0;
  prev_height_ = 0;
  tracks_.clear();
  next_track_id_ = 1;
}

OpticalFlowSummary NativeVisionEngine::compute_optical_flow(const uint8_t* rgb24, int32_t width,
                                                              int32_t height, int32_t grid_cols,
                                                              int32_t grid_rows) {
  OpticalFlowSummary summary;
  if (!rgb24 || width < 16 || height < 16 || grid_cols <= 0 || grid_rows <= 0) {
    return summary;
  }

  std::vector<uint8_t> curr_luma(static_cast<size_t>(width) * height);
  for (int32_t i = 0; i < width * height; ++i) {
    const uint8_t r = rgb24[i * 3];
    const uint8_t g = rgb24[i * 3 + 1];
    const uint8_t b = rgb24[i * 3 + 2];
    curr_luma[i] = static_cast<uint8_t>((77 * r + 150 * g + 29 * b) >> 8);
  }

  if (prev_width_ != width || prev_height_ != height || prev_luma_.size() != curr_luma.size()) {
    prev_luma_ = std::move(curr_luma);
    prev_width_ = width;
    prev_height_ = height;
    return summary;
  }

  std::vector<float> valid_dx;
  std::vector<float> valid_dy;
  std::vector<const FlowVector*> valid_ptrs;
  const int32_t half_win = 4;
  float sum_dx = 0.0f;
  float sum_dy = 0.0f;
  int32_t valid_count = 0;

  for (int32_t gy = 0; gy < grid_rows; ++gy) {
    const int32_t cy = ((gy * 2 + 1) * height) / (grid_rows * 2);
    for (int32_t gx = 0; gx < grid_cols; ++gx) {
      const int32_t cx = ((gx * 2 + 1) * width) / (grid_cols * 2);
      if (cx - half_win - 1 < 0 || cx + half_win + 1 >= width ||
          cy - half_win - 1 < 0 || cy + half_win + 1 >= height) {
        continue;
      }

      // Lucas-Kanade structure tensor [Ix^2, IxIy; IxIy, Iy^2] and temporal gradient It.
      double sxx = 0.0, syy = 0.0, sxy = 0.0, sxt = 0.0, syt = 0.0;
      for (int32_t y = cy - half_win; y <= cy + half_win; ++y) {
        for (int32_t x = cx - half_win; x <= cx + half_win; ++x) {
          const int32_t idx = y * width + x;
          const double ix = 0.5 * (static_cast<double>(curr_luma[idx + 1]) -
                                   static_cast<double>(curr_luma[idx - 1]));
          const double iy = 0.5 * (static_cast<double>(curr_luma[idx + width]) -
                                   static_cast<double>(curr_luma[idx - width]));
          const double it = static_cast<double>(curr_luma[idx]) -
                            static_cast<double>(prev_luma_[idx]);
          sxx += ix * ix;
          syy += iy * iy;
          sxy += ix * iy;
          sxt += ix * it;
          syt += iy * it;
        }
      }

      const double det = sxx * syy - sxy * sxy;
      float dx_px = 0.0f;
      float dy_px = 0.0f;
      float conf = 0.0f;
      if (det > 2500.0) {
        dx_px = static_cast<float>((-syy * sxt + sxy * syt) / det);
        dy_px = static_cast<float>((sxy * sxt - sxx * syt) / det);
        dx_px = std::clamp(dx_px, -12.0f, 12.0f);
        dy_px = std::clamp(dy_px, -12.0f, 12.0f);
        conf = static_cast<float>(std::min(1.0, det / 150000.0));
      }

      const float dx_norm = dx_px / static_cast<float>(width);
      const float dy_norm = dy_px / static_cast<float>(height);
      summary.grid_vectors.push_back(FlowVector{
          .norm_x = static_cast<float>(cx) / static_cast<float>(width),
          .norm_y = static_cast<float>(cy) / static_cast<float>(height),
          .dx_norm = dx_norm,
          .dy_norm = dy_norm,
          .confidence = conf,
      });

      if (conf > 0.1f) {
        sum_dx += dx_norm;
        sum_dy += dy_norm;
        valid_dx.push_back(dx_norm);
        valid_dy.push_back(dy_norm);
        ++valid_count;
      }
    }
  }

  summary.valid_vector_count = valid_count;
  if (valid_count > 0) {
    auto median_of = [](std::vector<float> vals) -> float {
      const size_t n = vals.size();
      const size_t mid = n / 2;
      std::nth_element(vals.begin(), vals.begin() + static_cast<ptrdiff_t>(mid), vals.end());
      float m = vals[mid];
      if ((n % 2) == 0 && mid > 0) {
        const auto max_low = *std::max_element(vals.begin(), vals.begin() + static_cast<ptrdiff_t>(mid));
        m = 0.5f * (m + max_low);
      }
      return m;
    };
    summary.median_dx_norm = median_of(valid_dx);
    summary.median_dy_norm = median_of(valid_dy);
    summary.global_dx_norm = (valid_count >= 3) ? summary.median_dx_norm : (sum_dx / static_cast<float>(valid_count));
    summary.global_dy_norm = (valid_count >= 3) ? summary.median_dy_norm : (sum_dy / static_cast<float>(valid_count));

    std::vector<float> omegas;
    for (const auto& fv : summary.grid_vectors) {
      if (fv.confidence <= 0.1f) continue;
      const float rx = fv.norm_x - 0.5f;
      const float ry = fv.norm_y - 0.5f;
      const float r2 = rx * rx + ry * ry;
      if (r2 > 0.01f) {
        const float res_dx = fv.dx_norm - summary.median_dx_norm;
        const float res_dy = fv.dy_norm - summary.median_dy_norm;
        omegas.push_back((rx * res_dy - ry * res_dx) / r2);
      }
    }
    if (omegas.size() >= 3) {
      summary.rotational_yaw_omega = std::clamp(median_of(omegas), -1.0f, 1.0f);
    }
  }

  prev_luma_ = std::move(curr_luma);
  return summary;
}

std::vector<TrackedObject> NativeVisionEngine::update_tracks(
    const std::vector<RawDetection>& detections, float global_dx_norm, float global_dy_norm,
    float focal_length_px, int32_t frame_height_px, bool is_calibrated) {
  // Predict existing tracks forward using previous velocity + optical flow ego-motion.
  for (auto& track : tracks_) {
    const float shift_x = track.vx * 0.5f + global_dx_norm;
    const float shift_y = track.vy * 0.5f + global_dy_norm;
    track.left += shift_x;
    track.right += shift_x;
    track.top += shift_y;
    track.bottom += shift_y;
    ++track.missed_frames;
  }

  std::vector<bool> matched_det(detections.size(), false);
  for (auto& track : tracks_) {
    float best_iou = 0.25f;
    int best_idx = -1;
    for (size_t i = 0; i < detections.size(); ++i) {
      if (matched_det[i] || detections[i].class_id != track.class_id) continue;
      const float iou = box_iou(track.left, track.top, track.right, track.bottom,
                                detections[i].left, detections[i].top, detections[i].right,
                                detections[i].bottom);
      if (iou > best_iou) {
        best_iou = iou;
        best_idx = static_cast<int>(i);
      }
    }
    if (best_idx >= 0) {
      const auto& d = detections[best_idx];
      const float prev_cx = 0.5f * (track.left + track.right);
      const float prev_cy = 0.5f * (track.top + track.bottom);
      const float new_cx = 0.5f * (d.left + d.right);
      const float new_cy = 0.5f * (d.top + d.bottom);
      track.vx = new_cx - prev_cx;
      track.vy = new_cy - prev_cy;
      track.left = d.left;
      track.top = d.top;
      track.right = d.right;
      track.bottom = d.bottom;
      track.confidence = d.confidence;
      track.missed_frames = 0;
      matched_det[best_idx] = true;
    }
  }

  for (size_t i = 0; i < detections.size(); ++i) {
    if (!matched_det[i]) {
      const auto& d = detections[i];
      tracks_.push_back(TrackSlot{
          .id = next_track_id_++,
          .class_id = d.class_id,
          .confidence = d.confidence,
          .left = d.left,
          .top = d.top,
          .right = d.right,
          .bottom = d.bottom,
          .vx = 0.0f,
          .vy = 0.0f,
          .missed_frames = 0,
      });
    }
  }

  tracks_.erase(
      std::remove_if(tracks_.begin(), tracks_.end(),
                     [](const TrackSlot& t) { return t.missed_frames > 4; }),
      tracks_.end());

  std::vector<TrackedObject> output;
  output.reserve(tracks_.size());
  for (const auto& t : tracks_) {
    const float box_h_px = std::max(4.0f, (t.bottom - t.top) * static_cast<float>(frame_height_px));
    float est_dist = -1.0f;
    float uncertainty = -1.0f;
    if (focal_length_px > 0.0f && frame_height_px > 0) {
      const float ref_h = nominal_class_height_meters(t.class_id);
      est_dist = (ref_h * focal_length_px) / box_h_px;
      // Calibrated lens estimates carry ~10% error; uncalibrated focal assumptions carry ~30% uncertainty.
      uncertainty = est_dist * (is_calibrated ? 0.10f : 0.30f);
    }
    output.push_back(TrackedObject{
        .track_id = t.id,
        .class_id = t.class_id,
        .confidence = t.confidence,
        .left = t.left,
        .top = t.top,
        .right = t.right,
        .bottom = t.bottom,
        .vx_norm = t.vx,
        .vy_norm = t.vy,
        .estimated_distance_m = est_dist,
        .distance_uncertainty_m = uncertainty,
        .distance_calibrated = is_calibrated,
    });
  }
  return output;
}

}  // namespace autopilot::vision
