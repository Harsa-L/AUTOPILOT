#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include "algorithms/vision_engine.hpp"
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
    jint control_port, jint video_port, jint heartbeat_port, jint control_rate_hz,
    jlong network_handle, jboolean allow_synthetic_harness) {
  autopilot::comm::SessionConfig cfg;
  cfg.family = static_cast<autopilot::protocol::Family>(family_wire_id);
  cfg.video_transport = static_cast<autopilot::video::TransportKind>(transport_kind);
  cfg.host_ip = jstring_to_std(env, host_ip);
  cfg.control_port = control_port;
  cfg.video_port = video_port;
  cfg.heartbeat_port = heartbeat_port;
  cfg.control_rate_hz = control_rate_hz;
  cfg.network_handle = network_handle;
  cfg.allow_synthetic_harness = (allow_synthetic_harness == JNI_TRUE);

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
  jlongArray out = env->NewLongArray(7);
  jlong vals[7] = {0, 0, 0, 0, 0, 0, 0};
  if (session) {
    const auto snap = session->snapshot();
    vals[0] = snap.running ? 1 : 0;
    vals[1] = snap.connected ? 1 : 0;
    vals[2] = snap.is_synthetic ? 1 : 0;
    vals[3] = static_cast<jlong>(snap.packets_sent);
    vals[4] = static_cast<jlong>(snap.packets_received);
    vals[5] = static_cast<jlong>(snap.dropped_packets);
    vals[6] = static_cast<jlong>(snap.last_rx_timestamp_ms);
  }
  env->SetLongArrayRegion(out, 0, 7, vals);
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

/**
 * Copies the latest reassembled frame into a reusable direct ByteBuffer.
 * Writes metadata into [outMeta] = [sequence, timestampUs, transportKind, width, height, byteLen, isSynthetic].
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
  jlong meta[7] = {
      static_cast<jlong>(frame.sequence),
      static_cast<jlong>(frame.timestamp_us),
      static_cast<jlong>(frame.transport),
      static_cast<jlong>(frame.width),
      static_cast<jlong>(frame.height),
      static_cast<jlong>(frame.payload.size()),
      frame.is_synthetic ? 1L : 0L,
  };
  env->SetLongArrayRegion(out_meta, 0, 7, meta);
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

/**
 * Computes C++20 optical flow and updates tracks in one coarse-grained JNI call.
 * detectionsPacked: stride 6 floats per detection [classId, conf, left, top, right, bottom].
 * Returns flat float array:
 *   [0] = globalDxNorm, [1] = globalDyNorm, [2] = flowCount, [3] = trackCount,
 *   followed by flowCount * 5 floats [normX, normY, dxNorm, dyNorm, conf],
 *   followed by trackCount * 11 floats [trackId, classId, conf, l, t, r, b, vx, vy, distM, uncM].
 */
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
  const size_t total_floats = 4 + flow_count * 5 + track_count * 11;
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

  jfloatArray result = env->NewFloatArray(static_cast<jsize>(total_floats));
  env->SetFloatArrayRegion(result, 0, static_cast<jsize>(total_floats), packed.data());
  return result;
}

}  // extern "C"
