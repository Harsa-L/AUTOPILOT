package com.example.input

import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class FlightMacroStep(
  val label: String,
  val roll: Float = 0f,
  val pitch: Float = 0f,
  val throttle: Float = 0f,
  val yaw: Float = 0f,
  val durationMs: Long = 800L,
)

data class FlightMacroPreset(
  val id: String,
  val title: String,
  val subtitle: String,
  val steps: List<FlightMacroStep>,
)

enum class SpinnerMode(val label: String) {
  NONE("Off"),
  ROTATION("Rot Spin"),
  LATERAL("Lat Spin"),
}

data class AutomationState(
  val gravityActive: Boolean = false,
  val spinnerMode: SpinnerMode = SpinnerMode.NONE,
  val trajectoryDrawMode: Boolean = false,
  val pendingTrajectoryPoints: List<Offset> = emptyList(),
  val activeSequenceTitle: String? = null,
  val activeStepLabel: String? = null,
  val stepIndex: Int = 0,
  val totalSteps: Int = 0,
  val remainingMs: Long = 0L,
) {
  val hasActiveAutomation: Boolean
    get() =
      gravityActive ||
        spinnerMode != SpinnerMode.NONE ||
        activeSequenceTitle != null
}

/**
 * Coordinates phone-side KY UFO / TurboDrone flight automations:
 * - Screen-drawn Trajectory Path sampling & sequential execution
 * - Continuous Rotation (Yaw) & Lateral (Roll + Yaw Orbit) Spinners
 * - Safe Timed Flight Maneuver Macros
 * Always yields immediately to manual touch, gamepad, keyboard, or emergency input.
 */
class FlightAutomationCoordinator(
  private val scope: CoroutineScope,
  private val onCommandAxes: suspend (roll: Float, pitch: Float, throttle: Float, yaw: Float) -> Unit,
) {
  private val _state = MutableStateFlow(AutomationState())
  val state: StateFlow<AutomationState> = _state.asStateFlow()

  private var runnerJob: Job? = null
  private var spinnerJob: Job? = null

  @Volatile
  var spinnerRate: Float = 0.42f

  val builtInMacros: List<FlightMacroPreset> =
    listOf(
      FlightMacroPreset(
        id = "yaw_scan_360",
        title = "360° Yaw Panorama",
        subtitle = "Steady slow hover spin for 3.6 s video sweep",
        steps =
          listOf(
            FlightMacroStep("Hover stabilize", durationMs = 400L),
            FlightMacroStep("Yaw scan", yaw = 0.38f, durationMs = 3600L),
            FlightMacroStep("Neutralize", durationMs = 300L),
          ),
      ),
      FlightMacroPreset(
        id = "square_box_nudge",
        title = "Square Box Patrol",
        subtitle = "Compact Fwd -> Right -> Back -> Left maneuver",
        steps =
          listOf(
            FlightMacroStep("Forward leg", pitch = 0.28f, durationMs = 1000L),
            FlightMacroStep("Right leg", roll = 0.28f, durationMs = 1000L),
            FlightMacroStep("Back leg", pitch = -0.28f, durationMs = 1000L),
            FlightMacroStep("Left leg", roll = -0.28f, durationMs = 1000L),
            FlightMacroStep("Hold neutral", durationMs = 300L),
          ),
      ),
      FlightMacroPreset(
        id = "fwd_back_bounce",
        title = "Forward & Return",
        subtitle = "1.2 s forward push, pause, 1.2 s return",
        steps =
          listOf(
            FlightMacroStep("Forward push", pitch = 0.30f, durationMs = 1200L),
            FlightMacroStep("Pause hover", durationMs = 600L),
            FlightMacroStep("Return back", pitch = -0.30f, durationMs = 1200L),
          ),
      ),
    )

  fun setGravityActive(active: Boolean) {
    if (active) {
      stopSpinner()
      stopSequence()
    }
    _state.value =
      _state.value.copy(
        gravityActive = active,
        trajectoryDrawMode = if (active) false else _state.value.trajectoryDrawMode,
      )
  }

  fun toggleSpinner(mode: SpinnerMode) {
    val next = if (_state.value.spinnerMode == mode) SpinnerMode.NONE else mode
    if (next == SpinnerMode.NONE) {
      stopSpinner()
      return
    }
    stopSequence()
    _state.value =
      _state.value.copy(
        gravityActive = false,
        trajectoryDrawMode = false,
        spinnerMode = next,
      )
    spinnerJob?.cancel()
    spinnerJob =
      scope.launch {
        while (isActive && _state.value.spinnerMode == next) {
          val rate = spinnerRate.coerceIn(0.18f, 0.75f)
          when (next) {
            SpinnerMode.ROTATION -> onCommandAxes(0f, 0f, 0f, rate)
            SpinnerMode.LATERAL -> onCommandAxes(rate, 0f, 0f, rate * 0.45f)
            SpinnerMode.NONE -> Unit
          }
          delay(40L)
        }
      }
  }

  fun setTrajectoryDrawMode(enabled: Boolean) {
    if (enabled) {
      stopSpinner()
      stopSequence()
    }
    _state.value =
      _state.value.copy(
        trajectoryDrawMode = enabled,
        gravityActive = if (enabled) false else _state.value.gravityActive,
      )
  }

  fun setPendingTrajectoryPoints(points: List<Offset>) {
    _state.value = _state.value.copy(pendingTrajectoryPoints = points)
  }

  fun clearTrajectory() {
    _state.value =
      _state.value.copy(
        pendingTrajectoryPoints = emptyList(),
        trajectoryDrawMode = false,
      )
  }

  fun startMacro(preset: FlightMacroPreset) {
    startSequence(title = preset.title, steps = preset.steps)
  }

  fun executePendingTrajectory(): Boolean {
    val pts = _state.value.pendingTrajectoryPoints
    val steps = buildTrajectorySteps(pts)
    if (steps.isEmpty()) return false
    _state.value = _state.value.copy(trajectoryDrawMode = false, pendingTrajectoryPoints = emptyList())
    startSequence(title = "Trajectory Flight", steps = steps)
    return true
  }

  fun startSequence(title: String, steps: List<FlightMacroStep>) {
    if (steps.isEmpty()) return
    stopAll(keepTrajectoryPreview = false)
    runnerJob =
      scope.launch {
        try {
          steps.forEachIndexed { idx, step ->
            val startMs = System.currentTimeMillis()
            while (isActive) {
              val elapsed = System.currentTimeMillis() - startMs
              val left = (step.durationMs - elapsed).coerceAtLeast(0L)
              _state.value =
                _state.value.copy(
                  activeSequenceTitle = title,
                  activeStepLabel = step.label,
                  stepIndex = idx + 1,
                  totalSteps = steps.size,
                  remainingMs = left,
                )
              onCommandAxes(step.roll, step.pitch, step.throttle, step.yaw)
              if (left <= 0L) break
              delay(40L)
            }
          }
        } finally {
          onCommandAxes(0f, 0f, 0f, 0f)
          _state.value =
            _state.value.copy(
              activeSequenceTitle = null,
              activeStepLabel = null,
              stepIndex = 0,
              totalSteps = 0,
              remainingMs = 0L,
            )
        }
      }
  }

  fun stopSpinner() {
    spinnerJob?.cancel()
    spinnerJob = null
    if (_state.value.spinnerMode != SpinnerMode.NONE) {
      _state.value = _state.value.copy(spinnerMode = SpinnerMode.NONE)
      scope.launch { onCommandAxes(0f, 0f, 0f, 0f) }
    }
  }

  fun stopSequence() {
    runnerJob?.cancel()
    runnerJob = null
    if (_state.value.activeSequenceTitle != null) {
      _state.value =
        _state.value.copy(
          activeSequenceTitle = null,
          activeStepLabel = null,
          stepIndex = 0,
          totalSteps = 0,
          remainingMs = 0L,
        )
    }
  }

  fun stopAll(keepTrajectoryPreview: Boolean = false) {
    val hadAutomation = _state.value.hasActiveAutomation
    spinnerJob?.cancel()
    spinnerJob = null
    runnerJob?.cancel()
    runnerJob = null
    _state.value =
      AutomationState(
        pendingTrajectoryPoints = if (keepTrajectoryPreview) _state.value.pendingTrajectoryPoints else emptyList(),
      )
    if (hadAutomation) {
      scope.launch { onCommandAxes(0f, 0f, 0f, 0f) }
    }
  }

  companion object {
    /**
     * Converts normalized [0..1] touch-canvas path points into bounded
     * (roll = +dx, pitch = -dy) timed flight segments.
     */
    fun buildTrajectorySteps(
      points: List<Offset>,
      maxAuthority: Float = 0.35f,
      maxSegments: Int = 12,
    ): List<FlightMacroStep> {
      if (points.size < 2) return emptyList()
      // Resample down to maxSegments + 1 points
      val sampleCount = minOf(maxSegments + 1, points.size)
      val sampled =
        List(sampleCount) { i ->
          val srcIdx =
            if (sampleCount == 1) 0
            else ((i.toFloat() / (sampleCount - 1)) * (points.size - 1)).toInt()
              .coerceIn(0, points.lastIndex)
          points[srcIdx]
        }

      val steps = mutableListOf<FlightMacroStep>()
      for (i in 0 until sampled.lastIndex) {
        val a = sampled[i]
        val b = sampled[i + 1]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist < 0.025f) continue
        val roll = ((dx / dist) * maxAuthority).coerceIn(-maxAuthority, maxAuthority)
        val pitch = ((-dy / dist) * maxAuthority).coerceIn(-maxAuthority, maxAuthority)
        val durationMs = (dist * 4500f).toLong().coerceIn(240L, 1800L)
        steps.add(
          FlightMacroStep(
            label = "Leg ${steps.size + 1}",
            roll = roll,
            pitch = pitch,
            durationMs = durationMs,
          )
        )
      }
      return steps
    }
  }
}
