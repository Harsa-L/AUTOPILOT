package com.autopilot.designsystem

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

val LocalResolvedTheme =
  staticCompositionLocalOf<ResolvedTheme> {
    ThemeCascadeResolver.resolve(
      baseDoc = BuiltInThemeDocuments.minimalLight,
      parentDoc = null,
      isSystemDark = false,
      appearanceMode = ThemeAppearanceMode.SYSTEM,
      contextual = ContextualEffects(),
      fonts = AutopilotFontSet(),
    )
  }

data class ThemeRuntimeState(
  val activeThemeId: String = "system_adaptive",
  val appearanceMode: ThemeAppearanceMode = ThemeAppearanceMode.SYSTEM,
  val availableThemes: List<W3cThemeDocument> =
    listOf(
      W3cThemeDocument(
        id = "system_adaptive",
        name = "System Adaptive",
        appearance = "system",
      )
    ) + BuiltInThemeDocuments.allPresets,
  val contextualEffects: ContextualEffects = ContextualEffects(),
)

/**
 * Centralized reactive ThemeManager distributing immutable resolved configurations
 * across the entire application.
 */
class ThemeManager {
  private val _state = MutableStateFlow(ThemeRuntimeState())
  val state: StateFlow<ThemeRuntimeState> = _state.asStateFlow()

  fun selectTheme(themeId: String) {
    val selected = _state.value.availableThemes.firstOrNull { it.id == themeId } ?: return
    val newAppearance =
      when (selected.appearance) {
        "light" -> ThemeAppearanceMode.LIGHT
        "dark" -> ThemeAppearanceMode.DARK
        else -> ThemeAppearanceMode.SYSTEM
      }
    _state.value = _state.value.copy(activeThemeId = themeId, appearanceMode = newAppearance)
  }

  fun setAppearanceMode(mode: ThemeAppearanceMode) {
    _state.value = _state.value.copy(appearanceMode = mode)
  }

  fun setContextualEffects(effects: ContextualEffects) {
    _state.value = _state.value.copy(contextualEffects = effects)
  }

  fun registerCustomTheme(doc: W3cThemeDocument, selectImmediately: Boolean = true) {
    val existing = _state.value.availableThemes.filterNot { it.id == doc.id }
    _state.value =
      _state.value.copy(
        availableThemes = existing + doc,
        activeThemeId = if (selectImmediately) doc.id else _state.value.activeThemeId,
        appearanceMode =
          if (selectImmediately) {
            when (doc.appearance) {
              "light" -> ThemeAppearanceMode.LIGHT
              "dark" -> ThemeAppearanceMode.DARK
              else -> ThemeAppearanceMode.SYSTEM
            }
          } else {
            _state.value.appearanceMode
          },
      )
  }

  fun resolveForSystem(isSystemDark: Boolean, fonts: AutopilotFontSet): ResolvedTheme {
    val current = _state.value
    val activeDoc =
      current.availableThemes.firstOrNull { it.id == current.activeThemeId }
        ?: current.availableThemes.first()
    val parentDoc =
      activeDoc.extendsThemeId?.let { pid -> current.availableThemes.firstOrNull { it.id == pid } }
    return ThemeCascadeResolver.resolve(
      baseDoc = activeDoc,
      parentDoc = parentDoc,
      isSystemDark = isSystemDark,
      appearanceMode = current.appearanceMode,
      contextual = current.contextualEffects,
      fonts = fonts,
    )
  }
}

@Composable
fun AutopilotTheme(
  runtimeState: ThemeRuntimeState,
  fonts: AutopilotFontSet,
  content: @Composable () -> Unit,
) {
  val isSystemDark = isSystemInDarkTheme()
  val activeDoc =
    runtimeState.availableThemes.firstOrNull { it.id == runtimeState.activeThemeId }
      ?: runtimeState.availableThemes.first()
  val parentDoc =
    activeDoc.extendsThemeId?.let { pid -> runtimeState.availableThemes.firstOrNull { it.id == pid } }

  val resolved =
    ThemeCascadeResolver.resolve(
      baseDoc = activeDoc,
      parentDoc = parentDoc,
      isSystemDark = isSystemDark,
      appearanceMode = runtimeState.appearanceMode,
      contextual = runtimeState.contextualEffects,
      fonts = fonts,
    )

  val c = resolved.colors
  val colorScheme =
    if (c.isDark) {
      darkColorScheme(
        primary = c.accent,
        onPrimary = c.onAccent,
        background = c.canvas,
        onBackground = c.textPrimary,
        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.surfaceElevated,
        onSurfaceVariant = c.textSecondary,
        outline = c.borderSubtle,
        error = c.statusEmergency,
      )
    } else {
      lightColorScheme(
        primary = c.accent,
        onPrimary = c.onAccent,
        background = c.canvas,
        onBackground = c.textPrimary,
        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.surfaceElevated,
        onSurfaceVariant = c.textSecondary,
        outline = c.borderSubtle,
        error = c.statusEmergency,
      )
    }

  val typography =
    Typography(
      headlineMedium =
        TextStyle(
          fontFamily = fonts.display,
          fontWeight = FontWeight.SemiBold,
          fontSize = 24.sp,
          lineHeight = 30.sp,
          letterSpacing = (-0.4).sp,
        ),
      titleLarge =
        TextStyle(
          fontFamily = fonts.display,
          fontWeight = FontWeight.SemiBold,
          fontSize = 18.sp,
          lineHeight = 24.sp,
        ),
      titleMedium =
        TextStyle(
          fontFamily = fonts.display,
          fontWeight = FontWeight.Medium,
          fontSize = 15.sp,
          lineHeight = 20.sp,
        ),
      bodyMedium =
        TextStyle(
          fontFamily = fonts.body,
          fontWeight = FontWeight.Normal,
          fontSize = 14.sp,
          lineHeight = 20.sp,
        ),
      bodySmall =
        TextStyle(
          fontFamily = fonts.body,
          fontWeight = FontWeight.Normal,
          fontSize = 12.sp,
          lineHeight = 16.sp,
        ),
      labelMedium =
        TextStyle(
          fontFamily = fonts.mono,
          fontWeight = FontWeight.Medium,
          fontSize = 12.sp,
          lineHeight = 16.sp,
          fontFeatureSettings = "tnum",
        ),
      labelSmall =
        TextStyle(
          fontFamily = fonts.mono,
          fontWeight = FontWeight.Medium,
          fontSize = 10.sp,
          lineHeight = 14.sp,
          fontFeatureSettings = "tnum",
        ),
    )

  val shapes =
    Shapes(
      small = RoundedCornerShape(resolved.primitives.radiusSmall),
      medium = RoundedCornerShape(resolved.components.cardRadius),
      large = RoundedCornerShape(resolved.primitives.radiusLarge),
    )

  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as? Activity)?.window
      if (window != null) {
        WindowCompat.getInsetsController(window, view).apply {
          isAppearanceLightStatusBars = !c.isDark
          isAppearanceLightNavigationBars = !c.isDark
        }
      }
    }
  }

  CompositionLocalProvider(LocalResolvedTheme provides resolved) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = typography,
      shapes = shapes,
      content = content,
    )
  }
}
