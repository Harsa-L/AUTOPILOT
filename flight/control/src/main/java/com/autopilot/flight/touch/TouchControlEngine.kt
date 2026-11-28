package com.autopilot.flight.touch

import com.autopilot.flight.gravity.GravityResponseCurve
import com.autopilot.flight.input.FlightAxis
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sign

enum class JoystickMode(val label: String) {
  FLOATING("Floating"),
  FIXED("Fixed"),
  HYBRID("Hybrid"),
}

data class NormalizedBounds(
  val xMin: Float,
  val yMin: Float,
  val xMax: Float,
  val yMax: Float,
) {
  fun contains(normX: Float, normY: Float): Boolean =
    normX in xMin..xMax && normY in yMin..yMax
}

data class TouchPointPx(val x: Float, val y: Float)

data class TouchZoneConfig(
  val id: String,
  val enabled: Boolean = true,
  val bounds: NormalizedBounds,
  val joystickMode: JoystickMode = JoystickMode.FLOATING,
  val horizontalAxis: FlightAxis = FlightAxis.YAW,
  val verticalAxis: FlightAxis = FlightAxis.THROTTLE,
  val invertX: Boolean = false,
  val invertY: Boolean = false,
  val sensitivityX: Float = 1.0f,
  val sensitivityY: Float = 1.0f,
  val deadzone: Float = 0.06f,
  val responseCurve: GravityResponseCurve = GravityResponseCurve.PROGRESSIVE_EXPO,
  val maxTravelPx: Float = 140f,
  val visualRadiusDp: Float = 56f,
  val opacity: Float = 0.85f,
  val autoCenterVertical: Boolean = true,
  val fixedCenterNormalized: TouchPointPx = TouchPointPx(0.2f, 0.78f),
  val zPriority: Int = 1,
)

data class TouchZoneRuntime(
  val config: TouchZoneConfig,
  val activePointerId: Int? = null,
  val anchorPx: TouchPointPx? = null,
  val thumbPx: TouchPointPx? = null,
  val axisX: Float = 0f,
  val axisY: Float = 0f,
) {
  val isVisible: Boolean
    get() =
      config.enabled &&
        (activePointerId != null || config.joystickMode == JoystickMode.FIXED)

  val showRestAnchor: Boolean
    get() = config.enabled && config.joystickMode != JoystickMode.FLOATING
}

data class TouchZoneCommand(
  val zoneId: String,
  val axes: Map<FlightAxis, Float>,
  val released: Boolean,
)

/**
 * Pure multi-pointer Adaptive Touch Control (ATC) engine keyed strictly by Android pointerId.
 * Guarantees neutral first contact on DOWN, relative MOVE actuation, independent release per zone,
 * no mid-gesture transfer across boundaries, and deterministic CANCEL neutralization.
 */
class TouchControlEngine(
  leftConfig: TouchZoneConfig = defaultLeftZone(),
  rightConfig: TouchZoneConfig = defaultRightZone(),
) {
  var leftRuntime: TouchZoneRuntime = TouchZoneRuntime(config = leftConfig)
    private set
  var rightRuntime: TouchZoneRuntime = TouchZoneRuntime(config = rightConfig)
    private set

  fun updateZoneConfigs(left: TouchZoneConfig, right: TouchZoneConfig) {
    leftRuntime = leftRuntime.copy(config = left)
    rightRuntime = rightRuntime.copy(config = right)
  }

  fun pickZoneForNormalizedPoint(normX: Float, normY: Float): TouchZoneConfig? {
    val candidates =
      listOf(leftRuntime.config, rightRuntime.config)
        .filter { it.enabled && it.bounds.contains(normX, normY) }
        .sortedByDescending { it.zPriority }
    return candidates.firstOrNull()
  }

  fun onDown(
    zoneId: String,
    pointerId: Int,
    posPx: TouchPointPx,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
  ): TouchZoneCommand? {
    val runtime = runtimeFor(zoneId) ?: return null
    if (!runtime.config.enabled || runtime.activePointerId != null) {
      return null
    }
    val anchor =
      when (runtime.config.joystickMode) {
        JoystickMode.FIXED ->
          TouchPointPx(
            x = runtime.config.fixedCenterNormalized.x * viewportWidthPx.coerceAtLeast(1f),
            y = runtime.config.fixedCenterNormalized.y * viewportHeightPx.coerceAtLeast(1f),
          )
        JoystickMode.FLOATING, JoystickMode.HYBRID -> posPx
      }
    val updated =
      runtime.copy(
        activePointerId = pointerId,
        anchorPx = anchor,
        thumbPx = anchor,
        axisX = 0f,
        axisY = if (runtime.config.autoCenterVertical) 0f else runtime.axisY,
      )
    setRuntime(zoneId, updated)
    // First touch (DOWN) anchors neutral origin and emits zero move delta.
    return TouchZoneCommand(
      zoneId = zoneId,
      axes =
        mapOf(
          updated.config.horizontalAxis to 0f,
          updated.config.verticalAxis to updated.axisY,
        ),
      released = false,
    )
  }

  fun onMove(pointerId: Int, posPx: TouchPointPx): TouchZoneCommand? {
    val runtime =
      when {
        leftRuntime.activePointerId == pointerId -> leftRuntime
        rightRuntime.activePointerId == pointerId -> rightRuntime
        else -> return null
      }
    val anchor = runtime.anchorPx ?: return null
    val travel = runtime.config.maxTravelPx.coerceAtLeast(24f)
    val dx = posPx.x - anchor.x
    val dy = anchor.y - posPx.y // screen Y grows downward; upward swipe is +Y
    val dist = hypot(dx, dy)
    val clampScale = if (dist > travel) travel / dist else 1f
    val clampedDx = dx * clampScale
    val clampedDy = dy * clampScale

    val rawNormX = (clampedDx / travel).coerceIn(-1f, 1f)
    val rawNormY = (clampedDy / travel).coerceIn(-1f, 1f)

    val outX =
      shapeStickAxis(
        raw = rawNormX,
        deadzone = runtime.config.deadzone,
        sensitivity = runtime.config.sensitivityX,
        invert = runtime.config.invertX,
        curve = runtime.config.responseCurve,
      )
    val outY =
      shapeStickAxis(
        raw = rawNormY,
        deadzone = runtime.config.deadzone,
        sensitivity = runtime.config.sensitivityY,
        invert = runtime.config.invertY,
        curve = runtime.config.responseCurve,
      )

    val thumb = TouchPointPx(anchor.x + clampedDx, anchor.y - clampedDy)
    val updated =
      runtime.copy(
        thumbPx = thumb,
        axisX = outX,
        axisY = outY,
      )
    setRuntime(runtime.config.id, updated)
    return TouchZoneCommand(
      zoneId = runtime.config.id,
      axes =
        mapOf(
          updated.config.horizontalAxis to outX,
          updated.config.verticalAxis to outY,
        ),
      released = false,
    )
  }

  fun onUpOrCancel(pointerId: Int, holdVerticalIfConfigured: Boolean = false): TouchZoneCommand? {
    val runtime =
      when {
        leftRuntime.activePointerId == pointerId -> leftRuntime
        rightRuntime.activePointerId == pointerId -> rightRuntime
        else -> return null
      }
    val nextY =
      if (!runtime.config.autoCenterVertical && holdVerticalIfConfigured) runtime.axisY else 0f
    val updated =
      runtime.copy(
        activePointerId = null,
        anchorPx = null,
        thumbPx = null,
        axisX = 0f,
        axisY = nextY,
      )
    setRuntime(runtime.config.id, updated)
    return TouchZoneCommand(
      zoneId = runtime.config.id,
      axes =
        mapOf(
          updated.config.horizontalAxis to 0f,
          updated.config.verticalAxis to nextY,
        ),
      released = true,
    )
  }

  fun resetAll(neutralizeThrottle: Boolean = true): List<TouchZoneCommand> {
    val out = mutableListOf<TouchZoneCommand>()
    listOf(leftRuntime, rightRuntime).forEach { runtime ->
      val nextY = if (neutralizeThrottle || runtime.config.autoCenterVertical) 0f else runtime.axisY
      val updated =
        runtime.copy(
          activePointerId = null,
          anchorPx = null,
          thumbPx = null,
          axisX = 0f,
          axisY = nextY,
        )
      setRuntime(runtime.config.id, updated)
      out +=
        TouchZoneCommand(
          zoneId = runtime.config.id,
          axes =
            mapOf(
              updated.config.horizontalAxis to 0f,
              updated.config.verticalAxis to nextY,
            ),
          released = true,
        )
    }
    return out
  }

  private fun runtimeFor(zoneId: String): TouchZoneRuntime? =
    when (zoneId) {
      leftRuntime.config.id -> leftRuntime
      rightRuntime.config.id -> rightRuntime
      else -> null
    }

  private fun setRuntime(zoneId: String, runtime: TouchZoneRuntime) {
    when (zoneId) {
      leftRuntime.config.id -> leftRuntime = runtime
      rightRuntime.config.id -> rightRuntime = runtime
    }
  }

  companion object {
    fun shapeStickAxis(
      raw: Float,
      deadzone: Float,
      sensitivity: Float,
      invert: Boolean,
      curve: GravityResponseCurve,
    ): Float {
      val dz = deadzone.coerceIn(0f, 0.35f)
      val mag = abs(raw)
      if (mag <= dz) return 0f
      val rescaled = ((mag - dz) / (1f - dz)).coerceIn(0f, 1f)
      val shaped =
        when (curve) {
          GravityResponseCurve.LINEAR -> rescaled
          GravityResponseCurve.PROGRESSIVE_EXPO -> 0.65f * rescaled + 0.35f * rescaled.pow(3)
          GravityResponseCurve.PRECISION_CUBIC -> rescaled.pow(3)
        }
      val signed = (if (invert) -sign(raw) else sign(raw)) * shaped * sensitivity.coerceIn(0.25f, 1.5f)
      return signed.coerceIn(-1f, 1f)
    }

    fun defaultLeftZone(enabled: Boolean = true): TouchZoneConfig =
      TouchZoneConfig(
        id = "left",
        enabled = enabled,
        bounds = NormalizedBounds(0f, 0.14f, 0.49f, 0.90f),
        joystickMode = JoystickMode.FLOATING,
        horizontalAxis = FlightAxis.YAW,
        verticalAxis = FlightAxis.THROTTLE,
        autoCenterVertical = true,
        fixedCenterNormalized = TouchPointPx(0.16f, 0.78f),
        zPriority = 1,
      )

    fun defaultRightZone(enabled: Boolean = true): TouchZoneConfig =
      TouchZoneConfig(
        id = "right",
        enabled = enabled,
        bounds = NormalizedBounds(0.51f, 0.14f, 1.0f, 0.90f),
        joystickMode = JoystickMode.FLOATING,
        horizontalAxis = FlightAxis.ROLL,
        verticalAxis = FlightAxis.PITCH,
        autoCenterVertical = true,
        fixedCenterNormalized = TouchPointPx(0.84f, 0.78f),
        zPriority = 1,
      )
  }
}
