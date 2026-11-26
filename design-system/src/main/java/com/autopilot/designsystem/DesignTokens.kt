package com.autopilot.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ThemeAppearanceMode {
  SYSTEM,
  LIGHT,
  DARK,
}

@Immutable
data class AutopilotFontSet(
  val display: FontFamily = FontFamily.SansSerif,
  val body: FontFamily = FontFamily.SansSerif,
  val mono: FontFamily = FontFamily.Monospace,
)

@Immutable
data class PrimitiveTokens(
  val space4: Dp = 4.dp,
  val space8: Dp = 8.dp,
  val space12: Dp = 12.dp,
  val space16: Dp = 16.dp,
  val space24: Dp = 24.dp,
  val space32: Dp = 32.dp,
  val radiusSmall: Dp = 8.dp,
  val radiusMedium: Dp = 14.dp,
  val radiusLarge: Dp = 20.dp,
  val borderHairline: Dp = 1.dp,
  val motionFastMs: Int = 160,
  val motionNormalMs: Int = 260,
)

@Immutable
data class SemanticColorTokens(
  val canvas: Color,
  val surface: Color,
  val surfaceElevated: Color,
  val surfaceGlass: Color,
  val textPrimary: Color,
  val textSecondary: Color,
  val accent: Color,
  val onAccent: Color,
  val borderSubtle: Color,
  val statusNominal: Color,
  val statusWarning: Color,
  val statusEmergency: Color,
  val statusInfo: Color,
  val isDark: Boolean,
)

@Immutable
data class ComponentTokens(
  val buttonHeight: Dp = 48.dp,
  val buttonRadius: Dp = 12.dp,
  val cardRadius: Dp = 16.dp,
  val cardPadding: Dp = 16.dp,
  val hudCornerRadius: Dp = 14.dp,
  val hudBackdropAlpha: Float = 0.78f,
  val blurRadiusDp: Dp = 12.dp,
  val stickPadSize: Dp = 164.dp,
  val iconSizeSm: Dp = 18.dp,
  val iconSizeMd: Dp = 22.dp,
)

@Immutable
data class ContextualEffects(
  val flightModeActive: Boolean = false,
  val thermalDegradeBlur: Boolean = false,
  val highContrastHud: Boolean = false,
)

@Immutable
data class ResolvedTheme(
  val themeId: String,
  val themeName: String,
  val schemaVersion: String,
  val primitives: PrimitiveTokens,
  val colors: SemanticColorTokens,
  val components: ComponentTokens,
  val contextual: ContextualEffects,
  val fonts: AutopilotFontSet,
)
