package com.example.ui.hud

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class HudElementId(
  val displayName: String,
  val essential: Boolean,
) {
  TOP_LEFT_STATUS("Aircraft & Link", essential = true),
  TOP_RIGHT_ACTIONS("Camera & Menu", essential = true),
  GRAVITY_INDICATOR("Gravity Horizon", essential = false),
  CAPABILITY_MODIFIERS("Flight Modifiers", essential = false),
  PRIMARY_ACTIONS("Arm / Land / E-Stop", essential = true),
  LEFT_STICK("Left Control Zone", essential = false),
  RIGHT_STICK("Right Control Zone", essential = false),
}

@Serializable
enum class HudAnchor {
  TOP_START,
  TOP_END,
  TOP_CENTER,
  BOTTOM_START,
  BOTTOM_END,
  BOTTOM_CENTER,
}

@Serializable
data class HudElementConfig(
  val id: HudElementId,
  val normalizedX: Float,
  val normalizedY: Float,
  val scale: Float = 1.0f,
  val opacity: Float = 1.0f,
  val visible: Boolean = true,
  val anchor: HudAnchor = HudAnchor.TOP_START,
  val locked: Boolean = false,
  val essential: Boolean = id.essential,
)

@Serializable
data class HudLayoutConfig(
  val landscape: Boolean,
  val elements: List<HudElementConfig>,
) {
  fun element(id: HudElementId): HudElementConfig =
    elements.firstOrNull { it.id == id }
      ?: HudLayoutEngine.defaultForOrientation(landscape).elements.first { it.id == id }
}

/**
 * Enforces usable safe-area viewport constraints, protects essential safety controls from
 * being hidden or dragged off-screen, and estimates the <= 30% persistent HUD budget.
 */
object HudViewportConstraints {
  const val MIN_SCALE = 0.78f
  const val MAX_SCALE = 1.25f
  const val MIN_OPACITY = 0.45f
  const val MAX_OPACITY = 1.0f
  const val MAX_PERSISTENT_HUD_FRACTION = 0.30f

  fun clampElement(el: HudElementConfig): HudElementConfig {
    val marginX = if (el.essential) 0.08f else 0.04f
    val marginY = if (el.essential) 0.08f else 0.04f
    return el.copy(
      normalizedX = el.normalizedX.coerceIn(marginX, 1f - marginX),
      normalizedY = el.normalizedY.coerceIn(marginY, 1f - marginY),
      scale = el.scale.coerceIn(MIN_SCALE, MAX_SCALE),
      opacity = el.opacity.coerceIn(if (el.essential) 0.70f else MIN_OPACITY, MAX_OPACITY),
      visible = if (el.essential) true else el.visible,
    )
  }
}

object HudLayoutEngine {
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  fun defaultPortrait(): HudLayoutConfig =
    HudLayoutConfig(
      landscape = false,
      elements =
        listOf(
          HudElementConfig(HudElementId.TOP_LEFT_STATUS, 0.24f, 0.05f, anchor = HudAnchor.TOP_START),
          HudElementConfig(HudElementId.TOP_RIGHT_ACTIONS, 0.78f, 0.05f, anchor = HudAnchor.TOP_END),
          HudElementConfig(HudElementId.GRAVITY_INDICATOR, 0.50f, 0.16f, anchor = HudAnchor.TOP_CENTER),
          HudElementConfig(HudElementId.CAPABILITY_MODIFIERS, 0.50f, 0.67f, anchor = HudAnchor.BOTTOM_CENTER),
          HudElementConfig(HudElementId.PRIMARY_ACTIONS, 0.50f, 0.74f, anchor = HudAnchor.BOTTOM_CENTER),
          HudElementConfig(HudElementId.LEFT_STICK, 0.18f, 0.89f, anchor = HudAnchor.BOTTOM_START),
          HudElementConfig(HudElementId.RIGHT_STICK, 0.82f, 0.89f, anchor = HudAnchor.BOTTOM_END),
        ),
    )

  fun defaultLandscape(): HudLayoutConfig =
    HudLayoutConfig(
      landscape = true,
      elements =
        listOf(
          HudElementConfig(HudElementId.TOP_LEFT_STATUS, 0.20f, 0.08f, anchor = HudAnchor.TOP_START),
          HudElementConfig(HudElementId.TOP_RIGHT_ACTIONS, 0.82f, 0.08f, anchor = HudAnchor.TOP_END),
          HudElementConfig(HudElementId.GRAVITY_INDICATOR, 0.50f, 0.22f, anchor = HudAnchor.TOP_CENTER),
          HudElementConfig(HudElementId.CAPABILITY_MODIFIERS, 0.50f, 0.74f, anchor = HudAnchor.BOTTOM_CENTER),
          HudElementConfig(HudElementId.PRIMARY_ACTIONS, 0.50f, 0.89f, anchor = HudAnchor.BOTTOM_CENTER),
          HudElementConfig(HudElementId.LEFT_STICK, 0.12f, 0.82f, anchor = HudAnchor.BOTTOM_START),
          HudElementConfig(HudElementId.RIGHT_STICK, 0.88f, 0.82f, anchor = HudAnchor.BOTTOM_END),
        ),
    )

  fun defaultForOrientation(landscape: Boolean): HudLayoutConfig =
    if (landscape) defaultLandscape() else defaultPortrait()

  fun moveElement(
    config: HudLayoutConfig,
    id: HudElementId,
    dxNorm: Float,
    dyNorm: Float,
  ): HudLayoutConfig {
    val next =
      config.elements.map { el ->
        if (el.id != id || el.locked) el
        else {
          val rawX = el.normalizedX + dxNorm
          val rawY = el.normalizedY + dyNorm
          val snappedAnchor =
            when {
              rawY < 0.35f && rawX < 0.35f -> HudAnchor.TOP_START
              rawY < 0.35f && rawX > 0.65f -> HudAnchor.TOP_END
              rawY < 0.35f -> HudAnchor.TOP_CENTER
              rawX < 0.35f -> HudAnchor.BOTTOM_START
              rawX > 0.65f -> HudAnchor.BOTTOM_END
              else -> HudAnchor.BOTTOM_CENTER
            }
          HudViewportConstraints.clampElement(
            el.copy(normalizedX = rawX, normalizedY = rawY, anchor = snappedAnchor)
          )
        }
      }
    return config.copy(elements = next)
  }

  fun updateElementStyle(
    config: HudLayoutConfig,
    id: HudElementId,
    scaleDelta: Float = 0f,
    opacityDelta: Float = 0f,
    visible: Boolean? = null,
    locked: Boolean? = null,
  ): HudLayoutConfig {
    val next =
      config.elements.map { el ->
        if (el.id != id) el
        else {
          HudViewportConstraints.clampElement(
            el.copy(
              scale = el.scale + scaleDelta,
              opacity = el.opacity + opacityDelta,
              visible = visible ?: el.visible,
              locked = locked ?: el.locked,
            )
          )
        }
      }
    return config.copy(elements = next)
  }

  fun toggleLock(config: HudLayoutConfig, id: HudElementId): HudLayoutConfig {
    val current = config.element(id)
    return updateElementStyle(config, id, locked = !current.locked)
  }

  /**
   * Computes an approximate persistent HUD area fraction relative to the safe-area viewport,
   * accounting for scale and visibility. Non-essential floating sticks in ATC mode are temporary
   * and excluded when requested.
   */
  fun estimatePersistentCoverageFraction(
    config: HudLayoutConfig,
    includeSticks: Boolean,
  ): Float {
    var total = 0f
    for (el in config.elements) {
      if (!el.visible) continue
      if (!includeSticks && (el.id == HudElementId.LEFT_STICK || el.id == HudElementId.RIGHT_STICK)) {
        continue
      }
      val baseFraction =
        when (el.id) {
          HudElementId.TOP_LEFT_STATUS -> 0.032f
          HudElementId.TOP_RIGHT_ACTIONS -> 0.030f
          HudElementId.GRAVITY_INDICATOR -> 0.018f
          HudElementId.CAPABILITY_MODIFIERS -> 0.045f
          HudElementId.PRIMARY_ACTIONS -> 0.052f
          HudElementId.LEFT_STICK, HudElementId.RIGHT_STICK -> 0.044f
        }
      total += baseFraction * el.scale * el.scale
    }
    return total.coerceIn(0f, 1f)
  }

  fun serialize(config: HudLayoutConfig): String = json.encodeToString(config)

  fun deserialize(raw: String, landscape: Boolean): HudLayoutConfig {
    if (raw.isBlank()) return defaultForOrientation(landscape)
    return runCatching {
        val decoded = json.decodeFromString<HudLayoutConfig>(raw)
        val merged =
          defaultForOrientation(landscape).elements.map { defEl ->
            val saved = decoded.elements.firstOrNull { it.id == defEl.id }
            HudViewportConstraints.clampElement(saved ?: defEl)
          }
        HudLayoutConfig(landscape = landscape, elements = merged)
      }
      .getOrElse { defaultForOrientation(landscape) }
  }
}
