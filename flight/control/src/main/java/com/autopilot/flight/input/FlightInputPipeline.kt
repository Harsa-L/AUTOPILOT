package com.autopilot.flight.input

import com.autopilot.drone.protocolapi.DroneCapabilities
import com.autopilot.drone.protocolapi.ProtocolDescriptor
import kotlin.math.abs
import kotlin.math.sign

enum class FlightAxis {
  ROLL,
  PITCH,
  THROTTLE,
  YAW,
}

enum class InputSourceId {
  NONE,
  FIXED_LEFT_STICK,
  FIXED_RIGHT_STICK,
  ATC_LEFT,
  ATC_RIGHT,
  GRAVITY,
  GAMEPAD,
  KEYBOARD,
  TRAJECTORY,
  SPINNER,
  VISION_SERVO,
}

enum class StickInputProfile(val label: String) {
  FIXED_STICKS("Fixed Pads"),
  ATC_DUAL_TOUCH("Floating ATC"),
  ATC_HYBRID_GRAVITY("Hybrid Gravity"),
  MANUAL_LOCKED("Manual Lock"),
}

/**
 * Honest distinction between input neutral, protocol neutral, and actual aircraft behavior.
 */
enum class NeutralSemantic {
  INPUT_NEUTRAL_DESCENDS,
  PROTOCOL_NEUTRAL_NO_ALT_HOLD,
  OPEN_LOOP_HOVER_ESTIMATE,
  BARO_ALTITUDE_HOLD,
}

enum class DeviceReleasePolicy {
  ZERO_ALL_AXES,
  SPRING_HORIZONTAL_ZERO_THROTTLE,
  SPRING_HORIZONTAL_HOLD_THROTTLE,
}

data class AxisOwnershipConfig(
  val owners: Map<FlightAxis, InputSourceId> = defaultDualTouch(),
) {
  fun ownerFor(axis: FlightAxis): InputSourceId = owners[axis] ?: InputSourceId.NONE

  fun axesOwnedBy(source: InputSourceId): Set<FlightAxis> =
    owners.filterValues { it == source }.keys

  fun withOverrides(vararg pairs: Pair<FlightAxis, InputSourceId>): AxisOwnershipConfig {
    val next = owners.toMutableMap()
    pairs.forEach { (axis, src) -> next[axis] = src }
    return AxisOwnershipConfig(next)
  }

  companion object {
    fun defaultFixedSticks(): Map<FlightAxis, InputSourceId> =
      mapOf(
        FlightAxis.THROTTLE to InputSourceId.FIXED_LEFT_STICK,
        FlightAxis.YAW to InputSourceId.FIXED_LEFT_STICK,
        FlightAxis.ROLL to InputSourceId.FIXED_RIGHT_STICK,
        FlightAxis.PITCH to InputSourceId.FIXED_RIGHT_STICK,
      )

    fun defaultDualTouch(): Map<FlightAxis, InputSourceId> =
      mapOf(
        FlightAxis.THROTTLE to InputSourceId.ATC_LEFT,
        FlightAxis.YAW to InputSourceId.ATC_LEFT,
        FlightAxis.ROLL to InputSourceId.ATC_RIGHT,
        FlightAxis.PITCH to InputSourceId.ATC_RIGHT,
      )

    fun defaultHybridGravity(): Map<FlightAxis, InputSourceId> =
      mapOf(
        FlightAxis.THROTTLE to InputSourceId.ATC_LEFT,
        FlightAxis.YAW to InputSourceId.ATC_LEFT,
        FlightAxis.ROLL to InputSourceId.GRAVITY,
        FlightAxis.PITCH to InputSourceId.GRAVITY,
      )
  }
}

data class SourceAxisValue(
  val value: Float = 0f,
  val active: Boolean = false,
  val updatedAtMs: Long = 0L,
  val staleTimeoutMs: Long? = null,
)

data class MixedFlightAxes(
  val roll: Float = 0f,
  val pitch: Float = 0f,
  val throttle: Float = 0f,
  val yaw: Float = 0f,
  val activeOwners: Map<FlightAxis, InputSourceId> = emptyMap(),
  val staleClearedSources: Set<InputSourceId> = emptySet(),
) {
  val isRollPitchPreShaped: Boolean
    get() = isPreShapedSource(activeOwners[FlightAxis.ROLL]) && isPreShapedSource(activeOwners[FlightAxis.PITCH])

  val isThrottleYawPreShaped: Boolean
    get() = isPreShapedSource(activeOwners[FlightAxis.THROTTLE]) && isPreShapedSource(activeOwners[FlightAxis.YAW])

  companion object {
    fun isPreShapedSource(source: InputSourceId?): Boolean =
      source == InputSourceId.ATC_LEFT ||
        source == InputSourceId.ATC_RIGHT ||
        source == InputSourceId.GRAVITY
  }
}

/**
 * Evaluates protocol-specific safety, release semantics, and capability truthfulness.
 */
data class DeviceSafetyEvaluation(
  val isVideoOnly: Boolean,
  val supportsFlightControl: Boolean,
  val supportsTrajectoryExecution: Boolean,
  val releasePolicy: DeviceReleasePolicy,
  val neutralSemantic: NeutralSemantic,
  val releaseWarning: String,
  val trajectoryBlockReason: String?,
)

object DeviceSafetyPolicy {
  fun evaluate(
    descriptor: ProtocolDescriptor,
    capabilities: DroneCapabilities,
    gamepadThrottleHold: Boolean = false,
  ): DeviceSafetyEvaluation {
    val neutralSem =
      if (capabilities.supportsAltitudeHold) NeutralSemantic.BARO_ALTITUDE_HOLD
      else NeutralSemantic.PROTOCOL_NEUTRAL_NO_ALT_HOLD

    val releasePolicy =
      when {
        capabilities.isVideoOnly -> DeviceReleasePolicy.ZERO_ALL_AXES
        gamepadThrottleHold -> DeviceReleasePolicy.SPRING_HORIZONTAL_HOLD_THROTTLE
        else -> DeviceReleasePolicy.SPRING_HORIZONTAL_ZERO_THROTTLE
      }

    val releaseWarning =
      when {
        capabilities.isVideoOnly ->
          "${descriptor.displayName} is video-only; stick/gravity/trajectory outputs are blocked."
        capabilities.supportsAltitudeHold ->
          "Release zeros Pitch/Roll/Yaw; protocol reports altitude hold (verify physically)."
        else ->
          "Input neutral ≠ hover hold: ${descriptor.displayName} lacks verified altitude/GPS hold; zero throttle may descend."
      }

    val trajReason =
      when {
        capabilities.isVideoOnly ->
          "${descriptor.displayName} is video-only and cannot execute flight paths."
        !capabilities.supportsTrajectoryExecution ->
          "${descriptor.displayName} does not confirm autonomous waypoint/trajectory support (preview & manual pulses only)."
        else -> null
      }

    return DeviceSafetyEvaluation(
      isVideoOnly = capabilities.isVideoOnly,
      supportsFlightControl = capabilities.supportsAnyFlightControl,
      supportsTrajectoryExecution = !capabilities.isVideoOnly && capabilities.supportsTrajectoryExecution,
      releasePolicy = releasePolicy,
      neutralSemantic = neutralSem,
      releaseWarning = releaseWarning,
      trajectoryBlockReason = trajReason,
    )
  }
}

/**
 * Manages explicit per-axis ownership so two sources cannot silently fight over the same flight axis.
 */
class AxisOwnershipManager(
  initialConfig: AxisOwnershipConfig = AxisOwnershipConfig(AxisOwnershipConfig.defaultFixedSticks()),
) {
  var config: AxisOwnershipConfig = initialConfig
    private set

  fun applyProfile(profile: StickInputProfile, gravityEnabled: Boolean) {
    config =
      when {
        gravityEnabled || profile == StickInputProfile.ATC_HYBRID_GRAVITY ->
          AxisOwnershipConfig(AxisOwnershipConfig.defaultHybridGravity())
        profile == StickInputProfile.ATC_DUAL_TOUCH ->
          AxisOwnershipConfig(AxisOwnershipConfig.defaultDualTouch())
        else ->
          AxisOwnershipConfig(AxisOwnershipConfig.defaultFixedSticks())
      }
  }

  fun setConfig(next: AxisOwnershipConfig) {
    config = next
  }

  fun setOverride(axis: FlightAxis, source: InputSourceId) {
    config = config.withOverrides(axis to source)
  }

  fun ownerFor(axis: FlightAxis): InputSourceId = config.ownerFor(axis)

  fun axesFor(source: InputSourceId): Set<FlightAxis> = config.axesOwnedBy(source)
}

/**
 * Prevents stale sensor/network sources from holding a frozen non-zero axis command.
 */
class InputFreshnessGuard(
  private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
  fun isFresh(sample: SourceAxisValue, defaultTimeoutMs: Long? = null): Boolean {
    if (!sample.active) return false
    val timeout = sample.staleTimeoutMs ?: defaultTimeoutMs ?: return true
    if (timeout <= 0L) return true
    val age = nowMs() - sample.updatedAtMs
    return age in 0L..timeout
  }
}

/**
 * Enforces slew-rate limits and progressive resumption envelopes to avoid abrupt command jumps.
 */
class FlightCommandLimiter(
  private val maxDeltaPerStep: Float = 0.25f,
) {
  private var lastOutput = FloatArray(4)

  fun limit(axis: FlightAxis, target: Float, bypassSlew: Boolean = false): Float {
    val clamped = if (target.isFinite()) target.coerceIn(-1f, 1f) else 0f
    val idx = axis.ordinal
    if (bypassSlew) {
      lastOutput[idx] = clamped
      return clamped
    }
    val prev = lastOutput[idx]
    val diff = clamped - prev
    val next =
      if (abs(diff) <= maxDeltaPerStep) clamped
      else prev + sign(diff) * maxDeltaPerStep
    lastOutput[idx] = next
    return next
  }

  fun reset(axis: FlightAxis? = null) {
    if (axis == null) {
      lastOutput.fill(0f)
    } else {
      lastOutput[axis.ordinal] = 0f
    }
  }
}

/**
 * Central axis mixer combining ownership, source suspension, freshness expiration,
 * hardware-input preemption, and progressive resume ramps.
 */
class FlightInputMixer(
  private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
  val ownershipManager = AxisOwnershipManager()
  private val freshnessGuard = InputFreshnessGuard(nowMs)

  private val sourceAxes =
    mutableMapOf<InputSourceId, MutableMap<FlightAxis, SourceAxisValue>>()
  private val suspendedSources = mutableSetOf<InputSourceId>()
  private val sourceResumeEnvelope = mutableMapOf<InputSourceId, Pair<Long, Long>>() // startMs to durationMs

  var holdThrottleOnRelease: Boolean = false
  private var lastMixedThrottle: Float = 0f

  fun submit(
    source: InputSourceId,
    axes: Map<FlightAxis, Float>,
    active: Boolean = true,
    staleTimeoutMs: Long? = null,
  ) {
    val ts = nowMs()
    val map = sourceAxes.getOrPut(source) { mutableMapOf() }
    axes.forEach { (axis, rawValue) ->
      val clean = if (rawValue.isFinite()) rawValue.coerceIn(-1f, 1f) else 0f
      map[axis] =
        SourceAxisValue(
          value = if (active) clean else 0f,
          active = active,
          updatedAtMs = ts,
          staleTimeoutMs = staleTimeoutMs,
        )
    }
  }

  fun suspendSource(source: InputSourceId, neutralizeOwnedAxes: Boolean = true) {
    suspendedSources.add(source)
    if (neutralizeOwnedAxes) {
      neutralizeSource(source)
    }
  }

  fun resumeSource(source: InputSourceId, progressiveRampMs: Long = 0L) {
    suspendedSources.remove(source)
    if (progressiveRampMs > 0L) {
      sourceResumeEnvelope[source] = nowMs() to progressiveRampMs
    } else {
      sourceResumeEnvelope.remove(source)
    }
  }

  fun isSuspended(source: InputSourceId): Boolean = suspendedSources.contains(source)

  fun neutralizeSource(source: InputSourceId) {
    val map = sourceAxes[source] ?: return
    val ts = nowMs()
    map.keys.forEach { axis ->
      val preserveThrottle =
        axis == FlightAxis.THROTTLE && holdThrottleOnRelease && !suspendedSources.contains(source)
      if (!preserveThrottle) {
        map[axis] = SourceAxisValue(value = 0f, active = false, updatedAtMs = ts)
      }
    }
  }

  fun neutralizeAll(resetHoldThrottle: Boolean = true) {
    val ts = nowMs()
    sourceAxes.values.forEach { map ->
      map.keys.forEach { axis ->
        map[axis] = SourceAxisValue(value = 0f, active = false, updatedAtMs = ts)
      }
    }
    if (resetHoldThrottle) {
      lastMixedThrottle = 0f
    }
  }

  fun mix(
    activeHardwareOverride: InputSourceId? = null,
    activeAutomationOverride: InputSourceId? = null,
  ): MixedFlightAxes {
    val staleSources = mutableSetOf<InputSourceId>()
    val now = nowMs()

    fun resolveAxis(axis: FlightAxis): Pair<Float, InputSourceId> {
      // Explicit priority: Hardware Gamepad/Keyboard > Automation (Spinner/Vision) > Configured Axis Owner
      val candidateSources =
        buildList {
          if (activeHardwareOverride != null) add(activeHardwareOverride)
          if (activeAutomationOverride != null) add(activeAutomationOverride)
          add(ownershipManager.ownerFor(axis))
        }

      for (src in candidateSources) {
        if (src == InputSourceId.NONE || suspendedSources.contains(src)) continue
        val sample = sourceAxes[src]?.get(axis) ?: continue
        if (!sample.active) continue
        if (!freshnessGuard.isFresh(sample)) {
          staleSources.add(src)
          sourceAxes[src]?.set(axis, SourceAxisValue(0f, active = false, updatedAtMs = now))
          continue
        }
        var value = sample.value
        val env = sourceResumeEnvelope[src]
        if (env != null) {
          val (startMs, durationMs) = env
          val elapsed = (now - startMs).coerceAtLeast(0L)
          if (elapsed >= durationMs) {
            sourceResumeEnvelope.remove(src)
          } else {
            val gain = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            value *= gain
          }
        }
        return value.coerceIn(-1f, 1f) to src
      }

      if (axis == FlightAxis.THROTTLE && holdThrottleOnRelease && suspendedSources.isEmpty()) {
        return lastMixedThrottle to InputSourceId.NONE
      }
      return 0f to InputSourceId.NONE
    }

    val (roll, rollOwner) = resolveAxis(FlightAxis.ROLL)
    val (pitch, pitchOwner) = resolveAxis(FlightAxis.PITCH)
    val (throttle, thrOwner) = resolveAxis(FlightAxis.THROTTLE)
    val (yaw, yawOwner) = resolveAxis(FlightAxis.YAW)
    lastMixedThrottle = throttle

    return MixedFlightAxes(
      roll = roll,
      pitch = pitch,
      throttle = throttle,
      yaw = yaw,
      activeOwners =
        mapOf(
          FlightAxis.ROLL to rollOwner,
          FlightAxis.PITCH to pitchOwner,
          FlightAxis.THROTTLE to thrOwner,
          FlightAxis.YAW to yawOwner,
        ),
      staleClearedSources = staleSources,
    )
  }
}
