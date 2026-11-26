package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.autopilot.designsystem.ThemeAppearanceMode
import com.autopilot.video.MvFilterPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.autopilotPrefsDataStore: DataStore<Preferences> by
  preferencesDataStore(name = "autopilot_preferences")

data class AutopilotUserPrefs(
  val activeThemeId: String = "system_adaptive",
  val appearanceMode: ThemeAppearanceMode = ThemeAppearanceMode.SYSTEM,
  val stickDeadzone: Float = 0.06f,
  val stickExpo: Float = 0.35f,
  val maxThrottleCap: Float = 0.85f,
  val stickTrimRoll: Float = 0f,
  val stickTrimPitch: Float = 0f,
  val preferGpuLiteRt: Boolean = true,
  val visionOverlayEnabled: Boolean = false,
  val visionConfidenceThreshold: Float = 0.40f,
  val visionDefaultMinDistanceM: Float = 2.5f,
  val visionTrackingAuthority: Float = 0.35f,
  val calibrationFocalLengthPx: Float = 520f,
  val calibrationVerified: Boolean = false,
  val thermalDegradeBlur: Boolean = false,
  val selectedProtocolId: String = "s2x",
  val selectedVideoTransport: String = "AUTO",
  val customHostIp: String = "",
  val rtspAutoFallback: Boolean = true,
  val customRtspPath: String = "",
  val customVideoPort: Int = 0,
  val videoRotationDeg: Int = 0,
  val videoMirrorHorizontal: Boolean = false,
  val mvFilterPreset: MvFilterPreset = MvFilterPreset.NATURAL,
  val firmwareSdTriggerEnabled: Boolean = true,
  val softDescentAssistEnabled: Boolean = false,
  val gamepadEnabled: Boolean = true,
  val gamepadThrottleHold: Boolean = false,
  val keyboardEnabled: Boolean = true,
  val gravityMaxAngleDeg: Float = 25f,
  val spinnerSpeedRatio: Float = 0.42f,
)

class UserPreferencesStore(private val context: Context) {

  private object Keys {
    val ACTIVE_THEME_ID = stringPreferencesKey("active_theme_id")
    val APPEARANCE_MODE = stringPreferencesKey("appearance_mode")
    val STICK_DEADZONE = floatPreferencesKey("stick_deadzone")
    val STICK_EXPO = floatPreferencesKey("stick_expo")
    val MAX_THROTTLE_CAP = floatPreferencesKey("max_throttle_cap")
    val STICK_TRIM_ROLL = floatPreferencesKey("stick_trim_roll")
    val STICK_TRIM_PITCH = floatPreferencesKey("stick_trim_pitch")
    val PREFER_GPU_LITERT = booleanPreferencesKey("prefer_gpu_litert")
    val VISION_OVERLAY_ENABLED = booleanPreferencesKey("vision_overlay_enabled")
    val VISION_CONFIDENCE = floatPreferencesKey("vision_confidence")
    val VISION_MIN_DISTANCE_M = floatPreferencesKey("vision_min_distance_m")
    val VISION_TRACKING_AUTHORITY = floatPreferencesKey("vision_tracking_authority")
    val CALIBRATION_FOCAL_PX = floatPreferencesKey("calibration_focal_px")
    val CALIBRATION_VERIFIED = booleanPreferencesKey("calibration_verified")
    val THERMAL_DEGRADE_BLUR = booleanPreferencesKey("thermal_degrade_blur")
    val SELECTED_PROTOCOL_ID = stringPreferencesKey("selected_protocol_id")
    val SELECTED_VIDEO_TRANSPORT = stringPreferencesKey("selected_video_transport")
    val CUSTOM_HOST_IP = stringPreferencesKey("custom_host_ip")
    val RTSP_AUTO_FALLBACK = booleanPreferencesKey("rtsp_auto_fallback")
    val CUSTOM_RTSP_PATH = stringPreferencesKey("custom_rtsp_path")
    val CUSTOM_VIDEO_PORT = intPreferencesKey("custom_video_port")
    val VIDEO_ROTATION_DEG = intPreferencesKey("video_rotation_deg")
    val VIDEO_MIRROR_H = booleanPreferencesKey("video_mirror_horizontal")
    val MV_FILTER_PRESET = stringPreferencesKey("mv_filter_preset")
    val FIRMWARE_SD_TRIGGER = booleanPreferencesKey("firmware_sd_trigger")
    val SOFT_DESCENT_ASSIST = booleanPreferencesKey("soft_descent_assist")
    val GAMEPAD_ENABLED = booleanPreferencesKey("gamepad_enabled")
    val GAMEPAD_THROTTLE_HOLD = booleanPreferencesKey("gamepad_throttle_hold")
    val KEYBOARD_ENABLED = booleanPreferencesKey("keyboard_enabled")
    val GRAVITY_MAX_ANGLE = floatPreferencesKey("gravity_max_angle_deg")
    val SPINNER_SPEED_RATIO = floatPreferencesKey("spinner_speed_ratio")
  }

  val preferences: Flow<AutopilotUserPrefs> =
    context.autopilotPrefsDataStore.data.map { prefs ->
      val modeName = prefs[Keys.APPEARANCE_MODE] ?: ThemeAppearanceMode.SYSTEM.name
      val mode =
        runCatching { ThemeAppearanceMode.valueOf(modeName) }
          .getOrDefault(ThemeAppearanceMode.SYSTEM)
      val mvName = prefs[Keys.MV_FILTER_PRESET] ?: MvFilterPreset.NATURAL.name
      val mv =
        runCatching { MvFilterPreset.valueOf(mvName) }.getOrDefault(MvFilterPreset.NATURAL)
      AutopilotUserPrefs(
        activeThemeId = prefs[Keys.ACTIVE_THEME_ID] ?: "system_adaptive",
        appearanceMode = mode,
        stickDeadzone = prefs[Keys.STICK_DEADZONE] ?: 0.06f,
        stickExpo = prefs[Keys.STICK_EXPO] ?: 0.35f,
        maxThrottleCap = prefs[Keys.MAX_THROTTLE_CAP] ?: 0.85f,
        stickTrimRoll = prefs[Keys.STICK_TRIM_ROLL] ?: 0f,
        stickTrimPitch = prefs[Keys.STICK_TRIM_PITCH] ?: 0f,
        preferGpuLiteRt = prefs[Keys.PREFER_GPU_LITERT] ?: true,
        visionOverlayEnabled = prefs[Keys.VISION_OVERLAY_ENABLED] ?: false,
        visionConfidenceThreshold = prefs[Keys.VISION_CONFIDENCE] ?: 0.40f,
        visionDefaultMinDistanceM = prefs[Keys.VISION_MIN_DISTANCE_M] ?: 2.5f,
        visionTrackingAuthority = prefs[Keys.VISION_TRACKING_AUTHORITY] ?: 0.35f,
        calibrationFocalLengthPx = prefs[Keys.CALIBRATION_FOCAL_PX] ?: 520f,
        calibrationVerified = prefs[Keys.CALIBRATION_VERIFIED] ?: false,
        thermalDegradeBlur = prefs[Keys.THERMAL_DEGRADE_BLUR] ?: false,
        selectedProtocolId = prefs[Keys.SELECTED_PROTOCOL_ID] ?: "s2x",
        selectedVideoTransport = prefs[Keys.SELECTED_VIDEO_TRANSPORT] ?: "AUTO",
        customHostIp = prefs[Keys.CUSTOM_HOST_IP] ?: "",
        rtspAutoFallback = prefs[Keys.RTSP_AUTO_FALLBACK] ?: true,
        customRtspPath = prefs[Keys.CUSTOM_RTSP_PATH] ?: "",
        customVideoPort = prefs[Keys.CUSTOM_VIDEO_PORT] ?: 0,
        videoRotationDeg = prefs[Keys.VIDEO_ROTATION_DEG] ?: 0,
        videoMirrorHorizontal = prefs[Keys.VIDEO_MIRROR_H] ?: false,
        mvFilterPreset = mv,
        firmwareSdTriggerEnabled = prefs[Keys.FIRMWARE_SD_TRIGGER] ?: true,
        softDescentAssistEnabled = prefs[Keys.SOFT_DESCENT_ASSIST] ?: false,
        gamepadEnabled = prefs[Keys.GAMEPAD_ENABLED] ?: true,
        gamepadThrottleHold = prefs[Keys.GAMEPAD_THROTTLE_HOLD] ?: false,
        keyboardEnabled = prefs[Keys.KEYBOARD_ENABLED] ?: true,
        gravityMaxAngleDeg = prefs[Keys.GRAVITY_MAX_ANGLE] ?: 25f,
        spinnerSpeedRatio = prefs[Keys.SPINNER_SPEED_RATIO] ?: 0.42f,
      )
    }

  suspend fun updateTheme(themeId: String, mode: ThemeAppearanceMode) {
    context.autopilotPrefsDataStore.edit { prefs ->
      prefs[Keys.ACTIVE_THEME_ID] = themeId
      prefs[Keys.APPEARANCE_MODE] = mode.name
    }
  }

  suspend fun updateFlightControls(
    deadzone: Float,
    expo: Float,
    maxThrottleCap: Float,
    trimRoll: Float = 0f,
    trimPitch: Float = 0f,
  ) {
    context.autopilotPrefsDataStore.edit { prefs ->
      prefs[Keys.STICK_DEADZONE] = deadzone.coerceIn(0f, 0.25f)
      prefs[Keys.STICK_EXPO] = expo.coerceIn(0f, 0.85f)
      prefs[Keys.MAX_THROTTLE_CAP] = maxThrottleCap.coerceIn(0.25f, 1.0f)
      prefs[Keys.STICK_TRIM_ROLL] = trimRoll.coerceIn(-0.25f, 0.25f)
      prefs[Keys.STICK_TRIM_PITCH] = trimPitch.coerceIn(-0.25f, 0.25f)
    }
  }

  suspend fun updateVisionSettings(
    overlayEnabled: Boolean,
    preferGpu: Boolean,
    focalLengthPx: Float,
    calibrated: Boolean,
    confidenceThreshold: Float = 0.40f,
    defaultMinDistanceM: Float = 2.5f,
    trackingAuthority: Float = 0.35f,
  ) {
    context.autopilotPrefsDataStore.edit { prefs ->
      prefs[Keys.VISION_OVERLAY_ENABLED] = overlayEnabled
      prefs[Keys.PREFER_GPU_LITERT] = preferGpu
      prefs[Keys.CALIBRATION_FOCAL_PX] = focalLengthPx.coerceIn(100f, 3000f)
      prefs[Keys.CALIBRATION_VERIFIED] = calibrated
      prefs[Keys.VISION_CONFIDENCE] = confidenceThreshold.coerceIn(0.15f, 0.90f)
      prefs[Keys.VISION_MIN_DISTANCE_M] = defaultMinDistanceM.coerceIn(0.8f, 10.0f)
      prefs[Keys.VISION_TRACKING_AUTHORITY] = trackingAuthority.coerceIn(0.15f, 0.65f)
    }
  }

  suspend fun updateThermalBlurDegradation(degrade: Boolean) {
    context.autopilotPrefsDataStore.edit { prefs -> prefs[Keys.THERMAL_DEGRADE_BLUR] = degrade }
  }

  suspend fun updateSelectedProtocol(protocolId: String) {
    context.autopilotPrefsDataStore.edit { prefs -> prefs[Keys.SELECTED_PROTOCOL_ID] = protocolId }
  }

  suspend fun updateStreamOverrides(videoTransportName: String, hostIp: String) {
    context.autopilotPrefsDataStore.edit { prefs ->
      prefs[Keys.SELECTED_VIDEO_TRANSPORT] = videoTransportName
      prefs[Keys.CUSTOM_HOST_IP] = hostIp
    }
  }

  suspend fun updateLinkEndpointPrefs(
    rtspAutoFallback: Boolean,
    customRtspPath: String,
    customVideoPort: Int,
  ) {
    context.autopilotPrefsDataStore.edit { prefs ->
      prefs[Keys.RTSP_AUTO_FALLBACK] = rtspAutoFallback
      prefs[Keys.CUSTOM_RTSP_PATH] = customRtspPath.trim()
      prefs[Keys.CUSTOM_VIDEO_PORT] = if (customVideoPort in 1..65535) customVideoPort else 0
    }
  }

  suspend fun updateVideoTransformPrefs(
    rotationDeg: Int,
    mirrorHorizontal: Boolean,
    mvFilterPreset: MvFilterPreset,
    firmwareSdTriggerEnabled: Boolean,
  ) {
    context.autopilotPrefsDataStore.edit { prefs ->
      prefs[Keys.VIDEO_ROTATION_DEG] = ((rotationDeg % 360) + 360) % 360
      prefs[Keys.VIDEO_MIRROR_H] = mirrorHorizontal
      prefs[Keys.MV_FILTER_PRESET] = mvFilterPreset.name
      prefs[Keys.FIRMWARE_SD_TRIGGER] = firmwareSdTriggerEnabled
    }
  }

  suspend fun updateGamepadPrefs(enabled: Boolean, throttleHold: Boolean) {
    context.autopilotPrefsDataStore.edit { prefs ->
      prefs[Keys.GAMEPAD_ENABLED] = enabled
      prefs[Keys.GAMEPAD_THROTTLE_HOLD] = throttleHold
    }
  }

  suspend fun updateAssistAndInputPrefs(
    keyboardEnabled: Boolean,
    softDescentAssistEnabled: Boolean,
    gravityMaxAngleDeg: Float,
    spinnerSpeedRatio: Float,
  ) {
    context.autopilotPrefsDataStore.edit { prefs ->
      prefs[Keys.KEYBOARD_ENABLED] = keyboardEnabled
      prefs[Keys.SOFT_DESCENT_ASSIST] = softDescentAssistEnabled
      prefs[Keys.GRAVITY_MAX_ANGLE] = gravityMaxAngleDeg.coerceIn(10f, 45f)
      prefs[Keys.SPINNER_SPEED_RATIO] = spinnerSpeedRatio.coerceIn(0.18f, 0.75f)
    }
  }
}
