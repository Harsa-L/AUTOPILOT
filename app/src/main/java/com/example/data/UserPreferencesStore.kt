package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.autopilot.designsystem.ThemeAppearanceMode
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
  }

  val preferences: Flow<AutopilotUserPrefs> =
    context.autopilotPrefsDataStore.data.map { prefs ->
      val modeName = prefs[Keys.APPEARANCE_MODE] ?: ThemeAppearanceMode.SYSTEM.name
      val mode =
        runCatching { ThemeAppearanceMode.valueOf(modeName) }
          .getOrDefault(ThemeAppearanceMode.SYSTEM)
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
}
