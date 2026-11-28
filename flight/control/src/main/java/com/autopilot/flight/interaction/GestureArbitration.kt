package com.autopilot.flight.interaction

import com.autopilot.flight.input.FlightAxis
import com.autopilot.flight.input.InputSourceId

enum class CockpitInteractionMode(val label: String) {
  PILOTING("Piloting"),
  TRAJECTORY("Trajectory Draw"),
  HUD_EDITING("HUD Edit"),
}

enum class CockpitTool {
  NONE,
  TARGET_SELECT,
  TRAJECTORY_DRAW,
  HUD_EDITOR,
}

enum class TouchOwner {
  NONE,
  ATC_LEFT,
  ATC_RIGHT,
  TRAJECTORY_DRAW,
  HUD_EDITOR,
  VISION_TARGET,
}

data class InteractionModeState(
  val mode: CockpitInteractionMode = CockpitInteractionMode.PILOTING,
  val activeTool: CockpitTool = CockpitTool.NONE,
  val activePointerOwners: Map<Int, TouchOwner> = emptyMap(),
  val suspendedInputSources: Set<InputSourceId> = emptySet(),
  val requiresFreshPointerDown: Set<TouchOwner> = emptySet(),
  val previousModeBeforeEdit: CockpitInteractionMode = CockpitInteractionMode.PILOTING,
  val gravityWasActiveBeforeSuspend: Boolean = false,
  val safetyStatus: String = "Piloting active",
)

sealed interface PointerRouteDecision {
  data class Capture(val owner: TouchOwner) : PointerRouteDecision

  data object IgnoreExistingHeld : PointerRouteDecision

  data object IgnoreBusyOwner : PointerRouteDecision

  data object IgnoreModeMismatch : PointerRouteDecision
}

/**
 * Tracks active pointer IDs so each finger has at most one deterministic owner
 * and never gets reassigned mid-gesture across mode transitions or zone boundaries.
 */
class GestureOwnershipManager {
  private val pointerOwners = mutableMapOf<Int, TouchOwner>()
  private val blockedUntilReleased = mutableMapOf<Int, TouchOwner>()

  fun snapshot(): Map<Int, TouchOwner> = pointerOwners.toMap()

  fun ownerOf(pointerId: Int): TouchOwner = pointerOwners[pointerId] ?: TouchOwner.NONE

  fun hasPointerForOwner(owner: TouchOwner): Boolean =
    pointerOwners.values.any { it == owner }

  fun isBlockedUntilRelease(pointerId: Int, targetOwner: TouchOwner): Boolean =
    blockedUntilReleased.containsKey(pointerId) || pointerOwners.containsKey(pointerId)

  fun claim(pointerId: Int, owner: TouchOwner): Boolean {
    if (pointerId < 0 || owner == TouchOwner.NONE) return false
    if (pointerOwners.containsKey(pointerId)) return false
    if (blockedUntilReleased.containsKey(pointerId)) return false
    pointerOwners[pointerId] = owner
    return true
  }

  fun release(pointerId: Int): TouchOwner {
    blockedUntilReleased.remove(pointerId)
    return pointerOwners.remove(pointerId) ?: TouchOwner.NONE
  }

  /**
   * Deterministic transition cleanup: existing held fingers are invalidated so they cannot
   * immediately trigger an action under a newly activated tool or restored piloting mode.
   */
  fun invalidateAllOnModeTransition(): Set<TouchOwner> {
    val evicted = pointerOwners.values.toSet()
    pointerOwners.forEach { (pid, owner) ->
      blockedUntilReleased[pid] = owner
    }
    pointerOwners.clear()
    return evicted
  }

  fun clearAll() {
    pointerOwners.clear()
    blockedUntilReleased.clear()
  }
}

/**
 * Decides tool priority and pointer admissibility for the current [InteractionModeState].
 */
class GestureArbitrator(
  private val ownershipManager: GestureOwnershipManager,
) {
  fun resolveDown(
    pointerId: Int,
    state: InteractionModeState,
    requestedAtcZone: TouchOwner? = null,
    targetSelectActive: Boolean = false,
  ): PointerRouteDecision {
    if (ownershipManager.ownerOf(pointerId) != TouchOwner.NONE) {
      return PointerRouteDecision.IgnoreExistingHeld
    }

    val desiredOwner =
      when (state.mode) {
        CockpitInteractionMode.HUD_EDITING -> TouchOwner.HUD_EDITOR
        CockpitInteractionMode.TRAJECTORY -> TouchOwner.TRAJECTORY_DRAW
        CockpitInteractionMode.PILOTING ->
          when {
            requestedAtcZone == TouchOwner.ATC_LEFT || requestedAtcZone == TouchOwner.ATC_RIGHT ->
              requestedAtcZone
            targetSelectActive -> TouchOwner.VISION_TARGET
            else -> TouchOwner.NONE
          }
      }

    if (desiredOwner == TouchOwner.NONE) {
      return PointerRouteDecision.IgnoreModeMismatch
    }
    if (ownershipManager.isBlockedUntilRelease(pointerId, desiredOwner)) {
      return PointerRouteDecision.IgnoreExistingHeld
    }
    if (desiredOwner == TouchOwner.ATC_LEFT || desiredOwner == TouchOwner.ATC_RIGHT) {
      if (ownershipManager.hasPointerForOwner(desiredOwner)) {
        return PointerRouteDecision.IgnoreBusyOwner
      }
    }
    return if (ownershipManager.claim(pointerId, desiredOwner)) {
      PointerRouteDecision.Capture(desiredOwner)
    } else {
      PointerRouteDecision.IgnoreBusyOwner
    }
  }

  fun ownerForMove(pointerId: Int, state: InteractionModeState): TouchOwner {
    val owner = ownershipManager.ownerOf(pointerId)
    if (owner == TouchOwner.NONE) return TouchOwner.NONE
    val allowed =
      when (state.mode) {
        CockpitInteractionMode.HUD_EDITING -> owner == TouchOwner.HUD_EDITOR
        CockpitInteractionMode.TRAJECTORY -> owner == TouchOwner.TRAJECTORY_DRAW
        CockpitInteractionMode.PILOTING ->
          owner == TouchOwner.ATC_LEFT ||
            owner == TouchOwner.ATC_RIGHT ||
            owner == TouchOwner.VISION_TARGET
      }
    return if (allowed) owner else TouchOwner.NONE
  }

  fun releasePointer(pointerId: Int): TouchOwner = ownershipManager.release(pointerId)
}

/**
 * Single authoritative mode coordinator preventing conflicting boolean flags across
 * Piloting (ATC/Fixed/Gravity), Trajectory Drawing, and HUD Editing.
 */
class InteractionModeController(
  private val onSuspendSources: (Set<InputSourceId>) -> Unit = {},
  private val onResumePiloting: (restoreGravity: Boolean, progressiveMs: Long) -> Unit = { _, _ -> },
  private val onNeutralizeAxes: (Set<FlightAxis>) -> Unit = {},
) {
  val ownershipManager = GestureOwnershipManager()
  val arbitrator = GestureArbitrator(ownershipManager)

  var state: InteractionModeState = InteractionModeState()
    private set

  fun enterTrajectoryMode(gravityCurrentlyActive: Boolean): InteractionModeState {
    if (state.mode == CockpitInteractionMode.TRAJECTORY) return state
    val evicted = ownershipManager.invalidateAllOnModeTransition()
    val suspended = setOf(InputSourceId.ATC_LEFT, InputSourceId.ATC_RIGHT, InputSourceId.FIXED_LEFT_STICK, InputSourceId.FIXED_RIGHT_STICK, InputSourceId.GRAVITY)
    onSuspendSources(suspended)
    onNeutralizeAxes(setOf(FlightAxis.ROLL, FlightAxis.PITCH, FlightAxis.YAW))
    state =
      InteractionModeState(
        mode = CockpitInteractionMode.TRAJECTORY,
        activeTool = CockpitTool.TRAJECTORY_DRAW,
        activePointerOwners = ownershipManager.snapshot(),
        suspendedInputSources = suspended,
        requiresFreshPointerDown = evicted + setOf(TouchOwner.ATC_LEFT, TouchOwner.ATC_RIGHT),
        previousModeBeforeEdit = state.mode,
        gravityWasActiveBeforeSuspend = gravityCurrentlyActive || state.gravityWasActiveBeforeSuspend,
        safetyStatus = "Trajectory drawing active — touch sticks & Gravity suspended",
      )
    return state
  }

  fun enterHudEditingMode(gravityCurrentlyActive: Boolean): InteractionModeState {
    if (state.mode == CockpitInteractionMode.HUD_EDITING) return state
    val evicted = ownershipManager.invalidateAllOnModeTransition()
    val suspended =
      setOf(
        InputSourceId.ATC_LEFT,
        InputSourceId.ATC_RIGHT,
        InputSourceId.FIXED_LEFT_STICK,
        InputSourceId.FIXED_RIGHT_STICK,
        InputSourceId.GRAVITY,
        InputSourceId.TRAJECTORY,
      )
    onSuspendSources(suspended)
    onNeutralizeAxes(setOf(FlightAxis.ROLL, FlightAxis.PITCH, FlightAxis.THROTTLE, FlightAxis.YAW))
    state =
      InteractionModeState(
        mode = CockpitInteractionMode.HUD_EDITING,
        activeTool = CockpitTool.HUD_EDITOR,
        activePointerOwners = ownershipManager.snapshot(),
        suspendedInputSources = suspended,
        requiresFreshPointerDown = evicted + setOf(TouchOwner.ATC_LEFT, TouchOwner.ATC_RIGHT, TouchOwner.TRAJECTORY_DRAW),
        previousModeBeforeEdit = state.mode,
        gravityWasActiveBeforeSuspend = gravityCurrentlyActive || state.gravityWasActiveBeforeSuspend,
        safetyStatus = "HUD editing active — flight inputs suspended",
      )
    return state
  }

  fun returnToPiloting(
    targetSelectActive: Boolean = false,
    restoreGravity: Boolean = false,
  ): InteractionModeState {
    val evicted = ownershipManager.invalidateAllOnModeTransition()
    val shouldRestoreGravity = restoreGravity && state.gravityWasActiveBeforeSuspend
    state =
      InteractionModeState(
        mode = CockpitInteractionMode.PILOTING,
        activeTool = if (targetSelectActive) CockpitTool.TARGET_SELECT else CockpitTool.NONE,
        activePointerOwners = ownershipManager.snapshot(),
        suspendedInputSources = emptySet(),
        requiresFreshPointerDown = evicted + setOf(TouchOwner.ATC_LEFT, TouchOwner.ATC_RIGHT),
        previousModeBeforeEdit = CockpitInteractionMode.PILOTING,
        gravityWasActiveBeforeSuspend = false,
        safetyStatus =
          if (shouldRestoreGravity) "Piloting resumed — progressive Gravity ramp active"
          else "Piloting active (fresh contact required)",
      )
    onResumePiloting(shouldRestoreGravity, if (shouldRestoreGravity) 450L else 0L)
    return state
  }

  fun handleInterrupt(reason: String): InteractionModeState {
    val evicted = ownershipManager.invalidateAllOnModeTransition()
    onNeutralizeAxes(setOf(FlightAxis.ROLL, FlightAxis.PITCH, FlightAxis.THROTTLE, FlightAxis.YAW))
    state =
      state.copy(
        activePointerOwners = ownershipManager.snapshot(),
        requiresFreshPointerDown = evicted + setOf(TouchOwner.ATC_LEFT, TouchOwner.ATC_RIGHT),
        safetyStatus = "Inputs neutralized: $reason",
      )
    return state
  }

  fun syncPointersFromManager() {
    state = state.copy(activePointerOwners = ownershipManager.snapshot())
  }
}

/**
 * Pure entry point routing DOWN/MOVE/UP/CANCEL pointer events cleanly without drone protocol coupling.
 */
class TouchInteractionRouter(
  private val modeController: InteractionModeController,
) {
  fun onPointerDown(
    pointerId: Int,
    requestedAtcZone: TouchOwner? = null,
    targetSelectActive: Boolean = false,
  ): PointerRouteDecision {
    val decision =
      modeController.arbitrator.resolveDown(
        pointerId = pointerId,
        state = modeController.state,
        requestedAtcZone = requestedAtcZone,
        targetSelectActive = targetSelectActive,
      )
    modeController.syncPointersFromManager()
    return decision
  }

  fun onPointerMove(pointerId: Int): TouchOwner =
    modeController.arbitrator.ownerForMove(pointerId, modeController.state)

  fun onPointerUpOrCancel(pointerId: Int): TouchOwner {
    val released = modeController.arbitrator.releasePointer(pointerId)
    modeController.syncPointersFromManager()
    return released
  }
}
