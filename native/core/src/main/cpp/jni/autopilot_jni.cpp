#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include "algorithms/vision_engine.hpp"
#include "benchmark/video_degrader.hpp"
#include "communication/native_session.hpp"
#include "protocol/protocol_packets.hpp"

namespace {
std::string jstring_to_std(JNIEnv* env, jstring jstr) {
  if (!jstr) return {};
  const char* chars = env->GetStringUTFChars(jstr, nullptr);
  std::string out(chars ? chars : "");
  if (chars) env->ReleaseStringUTFChars(jstr, chars);
  return out;
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeCreateSession(
    JNIEnv* env, jobject /* thiz */, jint family_wire_id, jint transport_kind, jstring host_ip,
    jint control_port, jint video_port, jint heartbeat_port, jstring rtsp_path,
    jint control_rate_hz, jlong network_handle, jboolean allow_synthetic_harness,
    jboolean rtsp_allow_udp_fallback, jboolean rtsp_prefer_udp) {
  autopilot::comm::SessionConfig cfg;
  cfg.family = static_cast<autopilot::protocol::Family>(family_wire_id);
  cfg.video_transport = static_cast<autopilot::video::TransportKind>(transport_kind);
  cfg.host_ip = jstring_to_std(env, host_ip);
  cfg.control_port = control_port;
  cfg.video_port = video_port;
  cfg.heartbeat_port = heartbeat_port;
  cfg.rtsp_path = jstring_to_std(env, rtsp_path);
  cfg.control_rate_hz = control_rate_hz;
  cfg.network_handle = network_handle;
  cfg.allow_synthetic_harness = (allow_synthetic_harness == JNI_TRUE);
  cfg.rtsp_allow_udp_fallback = (rtsp_allow_udp_fallback == JNI_TRUE);
  cfg.rtsp_prefer_udp = (rtsp_prefer_udp == JNI_TRUE);

  auto* session = new autopilot::comm::NativeSession(cfg);
  return reinterpret_cast<jlong>(session);
}

JNIEXPORT jboolean JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeStartSession(JNIEnv* /* env */,
                                                                       jobject /* thiz */,
                                                                       jlong handle) {
  auto* session = reinterpret_cast<autopilot::comm::NativeSession*>(handle);
  if (!session) return JNI_FALSE;
  return session->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeStopSession(JNIEnv* /* env */,
                                                                      jobject /* thiz */,
                                                                      jlong handle) {
  auto* session = reinterpret_cast<autopilot::comm::NativeSession*>(handle);
  if (session) session->stop();
}

JNIEXPORT void JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeDestroySession(JNIEnv* /* env */,
                                                                         jobject /* thiz */,
                                                                         jlong handle) {
  auto* session = reinterpret_cast<autopilot::comm::NativeSession*>(handle);
  delete session;
}

JNIEXPORT void JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeUpdateSticks(
    JNIEnv* /* env */, jobject /* thiz */, jlong handle, jfloat roll, jfloat pitch,
    jfloat throttle, jfloat yaw, jfloat trim_roll, jfloat trim_pitch, jint speed_tier,
    jint camera_tilt) {
  auto* session = reinterpret_cast<autopilot::comm::NativeSession*>(handle);
  if (!session) return;
  session->update_sticks(roll, pitch, throttle, yaw, trim_roll, trim_pitch,
                         static_cast<uint8_t>(speed_tier), static_cast<int8_t>(camera_tilt));
}

JNIEXPORT void JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeTriggerAction(JNIEnv* /* env */,
                                                                        jobject /* thiz */,
                                                                        jlong handle,
                                                                        jint action_flag) {
  auto* session = reinterpret_cast<autopilot::comm::NativeSession*>(handle);
  if (!session) return;
  session->trigger_action(static_cast<uint32_t>(action_flag));
}

JNIEXPORT void JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeTriggerFlip(JNIEnv* /* env */,
                                                                      jobject /* thiz */,
                                                                      jlong handle,
                                                                      jint direction) {
  auto* session = reinterpret_cast<autopilot::comm::NativeSession*>(handle);
  if (session) session->trigger_flip(direction);
}

JNIEXPORT void JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeSetLatchedAction(JNIEnv* /* env */,
                                                                           jobject /* thiz */,
                                                                           jlong handle,
                                                                           jint action_flag,
                                                                           jboolean enabled) {
  auto* session = reinterpret_cast<autopilot::comm::NativeSession*>(handle);
  if (!session) return;
  session->set_latched_action(static_cast<uint32_t>(action_flag), enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeSetControlArmed(JNIEnv* /* env */,
                                                                          jobject /* thiz */,
                                                                          jlong handle,
                                                                          jboolean armed) {
  auto* session = reinterpret_cast<autopilot::comm::NativeSession*>(handle);
  if (session) session->set_control_armed(armed == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeSwitchCamera(JNIEnv* /* env */,
                                                                       jobject /* thiz */,
                                                                       jlong handle,
                                                                       jint camera_index) {
  auto* session = reinterpret_cast<autopilot::comm::NativeSession*>(handle);
  if (session) session->switch_camera(camera_index);
}

JNIEXPORT void JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeRequestKeyframe(JNIEnv* /* env */,
                                                                          jobject /* thiz */,
                                                                          jlong handle) {
  auto* session = reinterpret_cast<autopilot::comm::NativeSession*>(handle);
  if (session) session->request_keyframe();
}

JNIEXPORT void JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeClearActions(JNIEnv* /* env */,
                                                                       jobject /* thiz */,
                                                                       jlong handle) {
  auto* session = reinterpret_cast<autopilot::comm::NativeSession*>(handle);
  if (!session) return;
  session->clear_actions();
}

JNIEXPORT jlongArray JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeReadSessionCounters(JNIEnv* env,
                                                                             jobject /* thiz */,
                                                                             jlong handle) {
  auto* session = reinterpret_cast<autopilot::comm::NativeSession*>(handle);
  jlongArray out = env->NewLongArray(13);
  jlong vals[13] = {0, 0, 0, 0, 0, 0, 0, -1, -1, 0, 0, 0, 0};
  if (session) {
    const auto snap = session->snapshot();
    vals[0] = snap.running ? 1 : 0;
    vals[1] = snap.connected ? 1 : 0;
    vals[2] = snap.is_synthetic ? 1 : 0;
    vals[3] = static_cast<jlong>(snap.packets_sent);
    vals[4] = static_cast<jlong>(snap.packets_received);
    vals[5] = static_cast<jlong>(snap.dropped_packets);
    vals[6] = static_cast<jlong>(snap.last_rx_timestamp_ms);
    vals[7] = static_cast<jlong>(snap.battery_percent);
    vals[8] = static_cast<jlong>(snap.firmware_status_flags);
    vals[9] = static_cast<jlong>(snap.control_packets_received);
    vals[10] = static_cast<jlong>(snap.video_packets_received);
    vals[11] = snap.control_armed ? 1 : 0;
    vals[12] = static_cast<jlong>(snap.last_socket_errno);
  }
  env->SetLongArrayRegion(out, 0, 13, vals);
  return out;
}

JNIEXPORT jstring JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeLastCommandHex(JNIEnv* env,
                                                                         jobject /* thiz */,
                                                                         jlong handle) {
  auto* session = reinterpret_cast<autopilot::comm::NativeSession*>(handle);
  if (!session) return env->NewStringUTF("");
  const auto snap = session->snapshot();
  return env->NewStringUTF(snap.last_command_hex.c_str());
}

JNIEXPORT void JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeSetServoProbe(JNIEnv* /* env */,
                                                                        jobject /* thiz */,
                                                                        jlong handle,
                                                                        jboolean enabled,
                                                                        jint byte_index,
                                                                        jint hex_value) {
  auto* session = reinterpret_cast<autopilot::comm::NativeSession*>(handle);
  if (session) {
    session->set_servo_probe(enabled == JNI_TRUE, byte_index, hex_value);
  }
}

JNIEXPORT jboolean JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeSendCooingdvPassword(JNIEnv* env,
                                                                               jobject /* thiz */,
                                                                               jlong handle,
                                                                               jstring digits_8) {
  auto* session = reinterpret_cast<autopilot::comm::NativeSession*>(handle);
  if (!session) return JNI_FALSE;
  return session->send_cooingdv_password(jstring_to_std(env, digits_8)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeReadDiagSummary(JNIEnv* env,
                                                                          jobject /* thiz */,
                                                                          jlong handle) {
  auto* session = reinterpret_cast<autopilot::comm::NativeSession*>(handle);
  if (!session) return env->NewStringUTF("");
  const auto snap = session->snapshot();
  char buf[384];
  std::snprintf(
      buf, sizeof(buf),
      "cooingdv_id=%d;gl=%d;jieli_1234_pkts=%d;jieli_1234_hex=%s",
      snap.detected_cooingdv_id, snap.cooingdv_gl_detected ? 1 : 0,
      snap.jieli_diag_1234_packets, snap.jieli_diag_sample_hex.c_str());
  return env->NewStringUTF(buf);
}

/**
 * Copies the latest reassembled frame into a reusable direct ByteBuffer.
 * Writes metadata into [outMeta] = [sequence, timestampUs, transportKind, width, height, byteLen, isSynthetic, isKeyframe].
 */
JNIEXPORT jboolean JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativePopVideoFrame(
    JNIEnv* env, jobject /* thiz */, jlong handle, jobject direct_buffer, jlongArray out_meta) {
  auto* session = reinterpret_cast<autopilot::comm::NativeSession*>(handle);
  if (!session || !direct_buffer || !out_meta) return JNI_FALSE;

  void* dst = env->GetDirectBufferAddress(direct_buffer);
  const jlong capacity = env->GetDirectBufferCapacity(direct_buffer);
  if (!dst || capacity <= 0) return JNI_FALSE;

  autopilot::video::ReassembledFrame frame;
  if (!session->pop_video_frame(frame)) return JNI_FALSE;
  if (static_cast<jlong>(frame.payload.size()) > capacity) return JNI_FALSE;

  std::memcpy(dst, frame.payload.data(), frame.payload.size());
  jlong meta[8] = {
      static_cast<jlong>(frame.sequence),
      static_cast<jlong>(frame.timestamp_us),
      static_cast<jlong>(frame.transport),
      static_cast<jlong>(frame.width),
      static_cast<jlong>(frame.height),
      static_cast<jlong>(frame.payload.size()),
      frame.is_synthetic ? 1L : 0L,
      frame.is_keyframe ? 1L : 0L,
  };
  env->SetLongArrayRegion(out_meta, 0, 8, meta);
  return JNI_TRUE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeEncodePacketPreview(
    JNIEnv* env, jobject /* thiz */, jint family_wire_id, jfloat roll, jfloat pitch,
    jfloat throttle, jfloat yaw, jint action_flags, jint speed_tier, jint camera_tilt) {
  autopilot::protocol::StickState st;
  st.roll = roll;
  st.pitch = pitch;
  st.throttle = throttle;
  st.yaw = yaw;
  st.action_flags = static_cast<uint32_t>(action_flags);
  st.speed_tier = static_cast<uint8_t>(speed_tier);
  st.camera_tilt = static_cast<int8_t>(camera_tilt);

  const auto pkt = autopilot::protocol::encode_control_packet(
      static_cast<autopilot::protocol::Family>(family_wire_id), st);
  jbyteArray arr = env->NewByteArray(static_cast<jsize>(pkt.size()));
  if (!pkt.empty()) {
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(pkt.size()),
                            reinterpret_cast<const jbyte*>(pkt.data()));
  }
  return arr;
}

JNIEXPORT jlong JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeCreateVisionEngine(JNIEnv* /* env */,
                                                                             jobject /* thiz */) {
  return reinterpret_cast<jlong>(new autopilot::vision::NativeVisionEngine());
}

JNIEXPORT void JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeResetVisionEngine(JNIEnv* /* env */,
                                                                            jobject /* thiz */,
                                                                            jlong handle) {
  auto* engine = reinterpret_cast<autopilot::vision::NativeVisionEngine*>(handle);
  if (engine) engine->reset();
}

JNIEXPORT void JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeDestroyVisionEngine(JNIEnv* /* env */,
                                                                              jobject /* thiz */,
                                                                              jlong handle) {
  auto* engine = reinterpret_cast<autopilot::vision::NativeVisionEngine*>(handle);
  delete engine;
}

JNIEXPORT jfloatArray JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeProcessVisionFrame(
    JNIEnv* env, jobject /* thiz */, jlong handle, jobject rgb24_direct_buffer, jint width,
    jint height, jfloatArray detections_packed, jfloat focal_length_px, jboolean is_calibrated) {
  auto* engine = reinterpret_cast<autopilot::vision::NativeVisionEngine*>(handle);
  if (!engine) return env->NewFloatArray(0);

  const uint8_t* rgb = rgb24_direct_buffer
                           ? reinterpret_cast<const uint8_t*>(
                                 env->GetDirectBufferAddress(rgb24_direct_buffer))
                           : nullptr;
  const auto flow = engine->compute_optical_flow(rgb, width, height, 4, 4);

  std::vector<autopilot::vision::RawDetection> dets;
  if (detections_packed) {
    const jsize len = env->GetArrayLength(detections_packed);
    if (len >= 6) {
      std::vector<jfloat> buf(static_cast<size_t>(len));
      env->GetFloatArrayRegion(detections_packed, 0, len, buf.data());
      for (jsize i = 0; i + 5 < len; i += 6) {
        dets.push_back(autopilot::vision::RawDetection{
            .class_id = static_cast<int32_t>(buf[i]),
            .confidence = buf[i + 1],
            .left = buf[i + 2],
            .top = buf[i + 3],
            .right = buf[i + 4],
            .bottom = buf[i + 5],
        });
      }
    }
  }

  const auto tracks = engine->update_tracks(
      dets, flow.global_dx_norm, flow.global_dy_norm, focal_length_px, height,
      is_calibrated == JNI_TRUE);

  const size_t flow_count = flow.grid_vectors.size();
  const size_t track_count = tracks.size();
  const size_t total_floats = 4 + flow_count * 5 + track_count * 11 + 2;
  std::vector<jfloat> packed(total_floats);
  packed[0] = flow.global_dx_norm;
  packed[1] = flow.global_dy_norm;
  packed[2] = static_cast<jfloat>(flow_count);
  packed[3] = static_cast<jfloat>(track_count);

  size_t idx = 4;
  for (const auto& v : flow.grid_vectors) {
    packed[idx++] = v.norm_x;
    packed[idx++] = v.norm_y;
    packed[idx++] = v.dx_norm;
    packed[idx++] = v.dy_norm;
    packed[idx++] = v.confidence;
  }
  for (const auto& t : tracks) {
    packed[idx++] = static_cast<jfloat>(t.track_id);
    packed[idx++] = static_cast<jfloat>(t.class_id);
    packed[idx++] = t.confidence;
    packed[idx++] = t.left;
    packed[idx++] = t.top;
    packed[idx++] = t.right;
    packed[idx++] = t.bottom;
    packed[idx++] = t.vx_norm;
    packed[idx++] = t.vy_norm;
    packed[idx++] = t.estimated_distance_m;
    packed[idx++] = t.distance_uncertainty_m;
  }
  packed[idx++] = flow.rotational_yaw_omega;
  packed[idx++] = static_cast<jfloat>(flow.valid_vector_count);

  jfloatArray result = env->NewFloatArray(static_cast<jsize>(total_floats));
  env->SetFloatArrayRegion(result, 0, static_cast<jsize>(total_floats), packed.data());
  return result;
}

namespace {
autopilot::benchmark::DegradeParams unpack_degrade_params(const jfloat* f, jsize flen,
                                                          const jint* iv, jsize ilen) {
  autopilot::benchmark::DegradeParams p;
  if (iv && ilen >= 6) {
    p.target_width = iv[0];
    p.target_height = iv[1];
    p.aspect_mode = iv[2];
    p.pixelation_block = iv[3];
    p.dynamic_range_bits = iv[4];
    p.chroma_subsample = iv[5];
    if (ilen >= 7) {
      p.frame_seed = static_cast<uint32_t>(iv[6]);
    }
  }
  if (f && flen >= 12) {
    p.crop_zoom = f[0];
    p.gaussian_blur = f[1];
    p.motion_blur_px = f[2];
    p.motion_blur_angle_deg = f[3];
    p.noise_strength = f[4];
    p.sharpen_amount = f[5];
    p.contrast = f[6];
    p.brightness = f[7];
    p.saturation = f[8];
    p.color_distortion = f[9];
    p.exposure_ev = f[10];
    p.block_artifact_strength = f[11];
  }
  return p;
}
}  // namespace

JNIEXPORT jlong JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeCreateDegraderEngine(JNIEnv* /* env */,
                                                                               jobject /* thiz */) {
  return reinterpret_cast<jlong>(new autopilot::benchmark::NativeVideoDegrader());
}

JNIEXPORT void JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeDestroyDegraderEngine(
    JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
  auto* engine = reinterpret_cast<autopilot::benchmark::NativeVideoDegrader*>(handle);
  delete engine;
}

JNIEXPORT jlong JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeDegradeYuv420(
    JNIEnv* env, jobject /* thiz */, jlong handle,
    jobject y_buf, jint y_stride, jobject u_buf, jint u_stride, jint u_pix_stride,
    jobject v_buf, jint v_stride, jint v_pix_stride,
    jint src_w, jint src_h, jint rotation_deg, jboolean mirror_h,
    jobject out_clean_buf, jint clean_w, jint clean_h,
    jobject out_degraded_buf, jintArray int_params, jfloatArray float_params) {
  auto* engine = reinterpret_cast<autopilot::benchmark::NativeVideoDegrader*>(handle);
  if (!engine || !y_buf || !u_buf || !v_buf || !out_degraded_buf) return 0L;

  const auto* y_ptr = reinterpret_cast<const uint8_t*>(env->GetDirectBufferAddress(y_buf));
  const auto* u_ptr = reinterpret_cast<const uint8_t*>(env->GetDirectBufferAddress(u_buf));
  const auto* v_ptr = reinterpret_cast<const uint8_t*>(env->GetDirectBufferAddress(v_buf));
  const jlong y_cap = env->GetDirectBufferCapacity(y_buf);
  const jlong u_cap = env->GetDirectBufferCapacity(u_buf);
  const jlong v_cap = env->GetDirectBufferCapacity(v_buf);

  auto* clean_ptr = out_clean_buf
                        ? reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(out_clean_buf))
                        : nullptr;
  auto* deg_ptr = reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(out_degraded_buf));
  if (!y_ptr || !u_ptr || !v_ptr || !deg_ptr) return 0L;

  jint* iv = int_params ? env->GetIntArrayElements(int_params, nullptr) : nullptr;
  jfloat* fv = float_params ? env->GetFloatArrayElements(float_params, nullptr) : nullptr;
  const jsize ilen = int_params ? env->GetArrayLength(int_params) : 0;
  const jsize flen = float_params ? env->GetArrayLength(float_params) : 0;
  const auto params = unpack_degrade_params(fv, flen, iv, ilen);

  const int64_t elapsed_ns = engine->process_yuv420_to_rgba(
      y_ptr, y_cap, y_stride, u_ptr, u_cap, u_stride, u_pix_stride,
      v_ptr, v_cap, v_stride, v_pix_stride, src_w, src_h, rotation_deg,
      mirror_h == JNI_TRUE, clean_ptr, clean_w, clean_h, deg_ptr, params);

  if (iv) env->ReleaseIntArrayElements(int_params, iv, JNI_ABORT);
  if (fv) env->ReleaseFloatArrayElements(float_params, fv, JNI_ABORT);
  return static_cast<jlong>(elapsed_ns);
}

JNIEXPORT jlong JNICALL
Java_com_autopilot_nativecore_AutopilotNativeBridge_nativeDegradeRgba(
    JNIEnv* env, jobject /* thiz */, jlong handle,
    jobject src_rgba_buf, jint src_w, jint src_h,
    jobject out_degraded_buf, jintArray int_params, jfloatArray float_params) {
  auto* engine = reinterpret_cast<autopilot::benchmark::NativeVideoDegrader*>(handle);
  if (!engine || !src_rgba_buf || !out_degraded_buf) return 0L;

  const auto* src_ptr = reinterpret_cast<const uint8_t*>(env->GetDirectBufferAddress(src_rgba_buf));
  auto* deg_ptr = reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(out_degraded_buf));
  if (!src_ptr || !deg_ptr) return 0L;

  jint* iv = int_params ? env->GetIntArrayElements(int_params, nullptr) : nullptr;
  jfloat* fv = float_params ? env->GetFloatArrayElements(float_params, nullptr) : nullptr;
  const jsize ilen = int_params ? env->GetArrayLength(int_params) : 0;
  const jsize flen = float_params ? env->GetArrayLength(float_params) : 0;
  const auto params = unpack_degrade_params(fv, flen, iv, ilen);

  const int64_t elapsed_ns = engine->process_rgba_to_rgba(src_ptr, src_w, src_h, deg_ptr, params);

  if (iv) env->ReleaseIntArrayElements(int_params, iv, JNI_ABORT);
  if (fv) env->ReleaseFloatArrayElements(float_params, fv, JNI_ABORT);
  return static_cast<jlong>(elapsed_ns);
}

}  // extern "C"
