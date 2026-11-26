package com.example.ui

import com.autopilot.drone.protocolapi.DroneCapabilities
import com.autopilot.drone.protocolapi.ProtocolDescriptor
import com.autopilot.flight.control.FlightState
import com.autopilot.vision.TrackedTarget
import kotlin.math.abs

enum class TargetActionType(
  val title: String,
  val summary: String,
) {
  TRACK_TARGET(
    title = "Track Target",
    summary = "Maintain continuous visual lock without commanding flight axes.",
  ),
  CENTER_IN_VIEW(
    title = "Center in Camera View",
    summary = "Yaw aircraft to keep target centered; uses camera tilt if supported.",
  ),
  FOLLOW_TARGET(
    title = "Follow Target",
    summary = "Pursue target with yaw alignment while holding minimum distance.",
  ),
  MAINTAIN_DISTANCE(
    title = "Maintain Minimum Distance",
    summary = "Hold standoff distance forward/back relative to the selected target.",
  ),
  HOVER_HOLD(
    title = "Hover / Maintain Position",
    summary = "Station-keep at current altitude and horizontal position.",
  ),
}

data class TargetActionConfig(
  val minTrackingDistanceMeters: Float = 2.5f,
  val distanceHysteresisMeters: Float = 0.45f,
  val yawCenteringGain: Float = 0.55f,
  val centerDeadbandNorm: Float = 0.06f,
  val maxTrackingAuthority: Float = 0.35f,
  val autoYawCenter: Boolean = true,
  val useCameraTiltIfSupported: Boolean = true,
  val lostTargetTimeoutSec: Float = 2.0f,
)

data class TargetActionAvailability(
  val action: TargetActionType,
  val supportedByDrone: Boolean,
  val readyNow: Boolean,
  val statusReason: String?,
)

data class ActiveTargetTask(
  val targetId: Int,
  val targetLabel: String,
  val action: TargetActionType,
  val config: TargetActionConfig,
  val startedAtMs: Long,
  val lastSeenAtMs: Long,
)

data class VisualServoCommand(
  val roll: Float = 0f,
  val pitch: Float = 0f,
  val throttle: Float = 0f,
  val yaw: Float = 0f,
  val cameraTiltDelta: Int? = null,
)

object TargetActionEvaluator {

  fun evaluateActions(
    descriptor: ProtocolDescriptor,
    capabilities: DroneCapabilities,
    flightState: FlightState,
  ): List<TargetActionAvailability> {
    val hasVideo = capabilities.supportsVideoStream
    val isConnected =
      flightState != FlightState.Disconnected &&
        flightState !is FlightState.Connecting &&
        flightState !is FlightState.Fault &&
        flightState !is FlightState.ConnectionLost
    val isFlying = flightState == FlightState.Flying || flightState == FlightState.TakingOff

    val hoverSupported = capabilities.supportsAltitudeHold || capabilities.reportsGps

    return TargetActionType.entries.map { action ->
      when (action) {
        TargetActionType.TRACK_TARGET ->
          TargetActionAvailability(
            action = action,
            supportedByDrone = hasVideo,
            readyNow = hasVideo && isConnected,
            statusReason =
              when {
                !hasVideo -> "No video stream on ${descriptor.displayName}."
                !isConnected -> "Connect aircraft to track on live feed."
                else -> null
              },
          )

        TargetActionType.CENTER_IN_VIEW ->
          TargetActionAvailability(
            action = action,
            supportedByDrone = hasVideo,
            readyNow = hasVideo && isFlying,
            statusReason =
              when {
                !hasVideo -> "No video stream on ${descriptor.displayName}."
                !isFlying -> "Take off to enable yaw centering."
                capabilities.supportsCameraTilt -> "Yaw + camera tilt supported."
                else -> "Horizontal yaw only (camera tilt unsupported)."
              },
          )

        TargetActionType.FOLLOW_TARGET ->
          TargetActionAvailability(
            action = action,
            supportedByDrone = hasVideo,
            readyNow = hasVideo && isFlying,
            statusReason =
              when {
                !hasVideo -> "No video stream on ${descriptor.displayName}."
                !isFlying -> "Take off to engage Follow Target."
                else -> null
              },
          )

        TargetActionType.MAINTAIN_DISTANCE ->
          TargetActionAvailability(
            action = action,
            supportedByDrone = hasVideo,
            readyNow = hasVideo && isFlying,
            statusReason =
              when {
                !hasVideo -> "No video stream on ${descriptor.displayName}."
                !isFlying -> "Take off to engage distance hold."
                else -> null
              },
          )

        TargetActionType.HOVER_HOLD ->
          TargetActionAvailability(
            action = action,
            supportedByDrone = hoverSupported,
            readyNow = hoverSupported && isFlying,
            statusReason =
              if (!hoverSupported) {
                "Unsupported: ${descriptor.displayName} lacks altitude hold and GPS."
              } else if (!isFlying) {
                "Take off to engage position hold."
              } else {
                null
              },
          )
      }
    }
  }

  fun computeServoCommand(
    task: ActiveTargetTask,
    target: TrackedTarget,
    capabilities: DroneCapabilities,
  ): VisualServoCommand {
    val cfg = task.config
    val maxAuth = cfg.maxTrackingAuthority.coerceIn(0.10f, 0.65f)
    val errX = target.boxNormalized.centerX() - 0.5f
    val errY = target.boxNormalized.centerY() - 0.5f

    val yawOut =
      if (task.action == TargetActionType.CENTER_IN_VIEW ||
        task.action == TargetActionType.FOLLOW_TARGET ||
        (task.action == TargetActionType.MAINTAIN_DISTANCE && cfg.autoYawCenter)
      ) {
        if (abs(errX) > cfg.centerDeadbandNorm) {
          (errX * 2f * cfg.yawCenteringGain).coerceIn(-maxAuth, maxAuth)
        } else {
          0f
        }
      } else {
        0f
      }

    val pitchOut =
      if (task.action == TargetActionType.FOLLOW_TARGET ||
        task.action == TargetActionType.MAINTAIN_DISTANCE
      ) {
        val dist = target.estimatedDistanceMeters
        if (dist != null) {
          val distErr = dist - cfg.minTrackingDistanceMeters
          if (abs(distErr) > cfg.distanceHysteresisMeters) {
            (distErr * 0.25f).coerceIn(-maxAuth, maxAuth)
          } else {
            0f
          }
        } else {
          0f
        }
      } else {
        0f
      }

    val tiltDelta =
      if (capabilities.supportsCameraTilt &&
        cfg.useCameraTiltIfSupported &&
        (task.action == TargetActionType.CENTER_IN_VIEW || task.action == TargetActionType.FOLLOW_TARGET)
      ) {
        when {
          errY < -cfg.centerDeadbandNorm -> 1
          errY > cfg.centerDeadbandNorm -> -1
          else -> 0
        }
      } else {
        null
      }

    return VisualServoCommand(
      roll = 0f,
      pitch = pitchOut,
      throttle = 0f,
      yaw = yawOut,
      cameraTiltDelta = tiltDelta,
    )
  }
}
