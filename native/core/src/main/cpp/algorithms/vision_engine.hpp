#pragma once

#include <cstdint>
#include <vector>

namespace autopilot::vision {

struct FlowVector {
  float norm_x;
  float norm_y;
  float dx_norm;
  float dy_norm;
  float confidence;
};

struct RawDetection {
  int32_t class_id;
  float confidence;
  float left;
  float top;
  float right;
  float bottom;
};

struct TrackedObject {
  int32_t track_id;
  int32_t class_id;
  float confidence;
  float left;
  float top;
  float right;
  float bottom;
  float vx_norm;
  float vy_norm;
  float estimated_distance_m;
  float distance_uncertainty_m;
  bool distance_calibrated;
};

struct OpticalFlowSummary {
  std::vector<FlowVector> grid_vectors;
  float global_dx_norm = 0.0f;
  float global_dy_norm = 0.0f;
  float median_dx_norm = 0.0f;
  float median_dy_norm = 0.0f;
  float rotational_yaw_omega = 0.0f;
  int32_t valid_vector_count = 0;
};

/**
 * C++20 computational vision engine providing:
 * 1. Grid Lucas-Kanade optical flow and global motion estimation.
 * 2. Motion-compensated multi-object tracking fusing LiteRT YOLO26n detections.
 * 3. Calibrated/uncertainty-annotated pinhole monocular distance estimation.
 */
class NativeVisionEngine {
 public:
  NativeVisionEngine() = default;

  void reset();

  OpticalFlowSummary compute_optical_flow(const uint8_t* rgb24, int32_t width, int32_t height,
                                          int32_t grid_cols = 4, int32_t grid_rows = 4);

  std::vector<TrackedObject> update_tracks(const std::vector<RawDetection>& detections,
                                           float global_dx_norm, float global_dy_norm,
                                           float focal_length_px, int32_t frame_height_px,
                                           bool is_calibrated);

 private:
  struct TrackSlot {
    int32_t id = 0;
    int32_t class_id = 0;
    float confidence = 0.0f;
    float left = 0.0f;
    float top = 0.0f;
    float right = 0.0f;
    float bottom = 0.0f;
    float vx = 0.0f;
    float vy = 0.0f;
    int32_t missed_frames = 0;
  };

  std::vector<uint8_t> prev_luma_;
  int32_t prev_width_ = 0;
  int32_t prev_height_ = 0;

  std::vector<TrackSlot> tracks_;
  int32_t next_track_id_ = 1;
};

}  // namespace autopilot::vision
