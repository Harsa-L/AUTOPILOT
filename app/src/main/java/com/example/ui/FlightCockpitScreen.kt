package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlightLand
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autopilot.designsystem.AutopilotButton
import com.autopilot.designsystem.AutopilotButtonTone
import com.autopilot.designsystem.GlassHudPanel
import com.autopilot.designsystem.LocalResolvedTheme
import com.autopilot.designsystem.VirtualStickPad
import com.autopilot.flight.control.FlightState
import com.autopilot.video.VideoStreamState
import com.autopilot.vision.TrackedTarget
import com.autopilot.vision.VisionFrameResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightCockpitScreen(
  viewModel: AutopilotViewModel,
  onOpenVision: () -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val flightState by viewModel.flightState.collectAsStateWithLifecycle()
  val safetyNotice by viewModel.safetyNotice.collectAsStateWithLifecycle()
  val videoState by viewModel.videoState.collectAsStateWithLifecycle()
  val visionEnabled by viewModel.visionOverlayEnabled.collectAsStateWithLifecycle()
  val targetSelectionEnabled by viewModel.targetSelectionEnabled.collectAsStateWithLifecycle()
  val visionResult by viewModel.visionResult.collectAsStateWithLifecycle()
  val selectedTargetId by viewModel.selectedTargetId.collectAsStateWithLifecycle()
  val activeTargetTask by viewModel.activeTargetTask.collectAsStateWithLifecycle()
  val targetActionConfig by viewModel.targetActionConfig.collectAsStateWithLifecycle()
  val selectedEntry by viewModel.selectedEntry.collectAsStateWithLifecycle()
  val speedTier by viewModel.speedTier.collectAsStateWithLifecycle()
  val headlessMode by viewModel.headlessMode.collectAsStateWithLifecycle()
  val sensitivity by viewModel.sensitivity.collectAsStateWithLifecycle()

  var showEmergencyDialog by remember { mutableStateOf(false) }
  var showAircraftSheet by remember { mutableStateOf(false) }
  var showTrimDialog by remember { mutableStateOf(false) }
  var targetModalSubject by remember { mutableStateOf<TrackedTarget?>(null) }
  var viewportSize by remember { mutableStateOf(IntSize.Zero) }

  val caps = selectedEntry.capabilities
  val isConnected = flightState != FlightState.Disconnected
  val activeFrame = videoState as? VideoStreamState.ActiveFrame

  BoxWithConstraints(
    modifier = modifier.fillMaxSize().background(theme.colors.canvas),
  ) {
    val isLandscape = maxWidth > maxHeight
    val stickSize = if (isLandscape) 136.dp else 148.dp

    Column(modifier = Modifier.fillMaxSize()) {
      // Main Video & HUD Stage
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .weight(1f)
            .background(theme.colors.canvas)
            .onSizeChanged { viewportSize = it }
            .pointerInput(targetSelectionEnabled, viewportSize, activeFrame?.bitmap?.width, activeFrame?.bitmap?.height) {
              if (!targetSelectionEnabled) return@pointerInput
              detectTapGestures(
                onTap = { tap ->
                  val norm =
                    mapTapToNormalizedFrame(
                      tap = tap,
                      viewport = viewportSize,
                      frameWidth = activeFrame?.bitmap?.width ?: 0,
                      frameHeight = activeFrame?.bitmap?.height ?: 0,
                    )
                  if (norm != null) {
                    viewModel.selectTargetAtNormalizedPoint(norm.x, norm.y)
                  }
                },
                onLongPress = { tap ->
                  val norm =
                    mapTapToNormalizedFrame(
                      tap = tap,
                      viewport = viewportSize,
                      frameWidth = activeFrame?.bitmap?.width ?: 0,
                      frameHeight = activeFrame?.bitmap?.height ?: 0,
                    )
                  val currentTargets = visionResult?.trackedTargets.orEmpty()
                  val hit =
                    if (norm != null) viewModel.selectTargetAtNormalizedPoint(norm.x, norm.y)
                    else null
                  val selected =
                    hit ?: selectedTargetId?.let { id -> currentTargets.firstOrNull { it.trackId == id } }
                  if (selected != null) {
                    targetModalSubject = selected
                  }
                },
              )
            },
      ) {
        when (val video = videoState) {
          is VideoStreamState.ActiveFrame -> {
            Image(
              bitmap = video.bitmap.asImageBitmap(),
              contentDescription = "Live aircraft video feed",
              contentScale = ContentScale.Fit,
              modifier = Modifier.fillMaxSize(),
            )
          }
          is VideoStreamState.WaitingForKeyframe -> {
            EmptyVideoViewport(
              title = "Waiting for Video",
              detail = "Receiving stream from ${selectedEntry.descriptor.displayName}",
              modifier = Modifier.align(Alignment.Center),
            )
          }
          is VideoStreamState.Error -> {
            EmptyVideoViewport(
              title = "Video Unavailable",
              detail = video.message,
              modifier = Modifier.align(Alignment.Center),
            )
          }
          VideoStreamState.Idle -> {
            EmptyVideoViewport(
              title = "No Video Feed",
              detail = "Connect to ${selectedEntry.descriptor.displayName} to begin flight.",
              modifier = Modifier.align(Alignment.Center),
            )
          }
        }

        // Vision Target Reticles
        if ((visionEnabled || targetSelectionEnabled) && visionResult != null) {
          VisionHudOverlay(
            result = visionResult!!,
            selectedTargetId = selectedTargetId,
            activeTask = activeTargetTask,
            modifier = Modifier.fillMaxSize(),
          )
        }

        // Minimal Top Control Bar
        Row(
          modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 10.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
          ) {
            // Aircraft selector + status pill
            Surface(
              modifier =
                Modifier.testTag("aircraft_selector_pill")
                  .clickable { showAircraftSheet = true },
              shape = RoundedCornerShape(12.dp),
              color = theme.colors.surfaceGlass.copy(alpha = theme.components.hudBackdropAlpha),
              border = BorderStroke(1.dp, theme.colors.borderSubtle),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Box(
                  modifier =
                    Modifier.size(8.dp)
                      .clip(CircleShape)
                      .background(flightStateColor(flightState, theme.colors))
                )
                Text(
                  text = selectedEntry.descriptor.displayName.substringBefore(" ("),
                  style = MaterialTheme.typography.titleMedium,
                  color = theme.colors.textPrimary,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  text = "· ${flightStateLabel(flightState)}",
                  style = MaterialTheme.typography.labelSmall,
                  color = theme.colors.textSecondary,
                  maxLines = 1,
                )
                Icon(
                  Icons.Default.ArrowDropDown,
                  contentDescription = "Select aircraft",
                  tint = theme.colors.textSecondary,
                  modifier = Modifier.size(18.dp),
                )
              }
            }

            // Connect / Disconnect pill
            Surface(
              modifier =
                Modifier.testTag("connect_toggle_button")
                  .clickable {
                    if (isConnected) viewModel.disconnect()
                    else viewModel.connectSelectedProtocol()
                  },
              shape = RoundedCornerShape(12.dp),
              color =
                if (isConnected) theme.colors.surfaceGlass.copy(alpha = theme.components.hudBackdropAlpha)
                else theme.colors.accent,
              border =
                if (isConnected) BorderStroke(1.dp, theme.colors.borderSubtle) else null,
            ) {
              Text(
                text = if (isConnected) "Disconnect" else "Connect",
                style = MaterialTheme.typography.labelMedium,
                color = if (isConnected) theme.colors.textPrimary else theme.colors.onAccent,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
              )
            }
          }

          // Right Actions: Target Selection Toggle, Vision Page, Settings Page
          GlassHudPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
              IconButton(
                onClick = { viewModel.toggleTargetSelection(!targetSelectionEnabled) },
                modifier = Modifier.size(48.dp).testTag("target_select_toggle"),
              ) {
                Icon(
                  Icons.Default.CenterFocusWeak,
                  contentDescription = "Toggle target selection on video",
                  tint = if (targetSelectionEnabled) theme.colors.accent else theme.colors.textSecondary,
                )
              }
              IconButton(
                onClick = onOpenVision,
                modifier = Modifier.size(48.dp).testTag("open_vision_button"),
              ) {
                Icon(
                  Icons.Default.RemoveRedEye,
                  contentDescription = "Open Computer Vision",
                  tint = if (visionEnabled) theme.colors.accent else theme.colors.textPrimary,
                )
              }
              IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(48.dp).testTag("open_settings_button"),
              ) {
                Icon(
                  Icons.Default.Settings,
                  contentDescription = "Open Settings",
                  tint = theme.colors.textPrimary,
                )
              }
            }
          }
        }

        // Target Selection Hint or Active Task Pill
        if (activeTargetTask != null) {
          val task = activeTargetTask!!
          GlassHudPanel(modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text = "${task.action.title} · #${task.targetId} ${task.targetLabel}",
                style = MaterialTheme.typography.labelMedium,
                color = theme.colors.accent,
              )
              IconButton(
                onClick = { viewModel.stopTargetAction() },
                modifier = Modifier.size(32.dp).testTag("stop_target_action_button"),
              ) {
                Icon(
                  Icons.Default.Close,
                  contentDescription = "Stop target action",
                  tint = theme.colors.textPrimary,
                  modifier = Modifier.size(16.dp),
                )
              }
            }
          }
        } else if (targetSelectionEnabled && selectedTargetId != null) {
          val target = visionResult?.trackedTargets?.firstOrNull { it.trackId == selectedTargetId }
          if (target != null) {
            GlassHudPanel(
              modifier =
                Modifier.align(Alignment.BottomCenter)
                  .padding(10.dp)
                  .clickable { targetModalSubject = target },
            ) {
              Text(
                text = "#${target.trackId} ${target.label} selected · Long-press target for actions",
                style = MaterialTheme.typography.labelSmall,
                color = theme.colors.accent,
              )
            }
          }
        } else if (safetyNotice != null) {
          GlassHudPanel(modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)) {
            Text(
              text = safetyNotice!!,
              style = MaterialTheme.typography.labelSmall,
              color = theme.colors.statusWarning,
            )
          }
        }
      }

      // Capability-Driven Flight Modifiers Strip (only genuinely supported controls)
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .background(theme.colors.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (caps.supportsSpeedTiers) {
          listOf("Low" to 0, "Med" to 1, "High" to 2).forEach { (label, tier) ->
            FilterChip(
              selected = speedTier == tier,
              onClick = { viewModel.setSpeedTier(tier) },
              label = { Text(label, style = MaterialTheme.typography.labelSmall) },
              colors =
                FilterChipDefaults.filterChipColors(
                  selectedContainerColor = theme.colors.accent.copy(alpha = 0.2f),
                  selectedLabelColor = theme.colors.accent,
                ),
              modifier = Modifier.testTag("speed_tier_$tier"),
            )
          }
        }

        if (caps.supportsHeadlessMode) {
          FilterChip(
            selected = headlessMode,
            onClick = { viewModel.setHeadlessMode(!headlessMode) },
            label = { Text("Headless", style = MaterialTheme.typography.labelSmall) },
            colors =
              FilterChipDefaults.filterChipColors(
                selectedContainerColor = theme.colors.accent.copy(alpha = 0.2f),
                selectedLabelColor = theme.colors.accent,
              ),
            modifier = Modifier.testTag("headless_mode_chip"),
          )
        }

        if (caps.supportsCameraTilt) {
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = theme.colors.surfaceElevated,
            border = BorderStroke(1.dp, theme.colors.borderSubtle),
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              IconButton(
                onClick = { viewModel.tiltCamera(-1) },
                enabled = isConnected,
                modifier = Modifier.size(40.dp).testTag("camera_tilt_down_button"),
              ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Tilt camera down")
              }
              Text(
                text = "Tilt",
                style = MaterialTheme.typography.labelSmall,
                color = theme.colors.textSecondary,
              )
              IconButton(
                onClick = { viewModel.tiltCamera(1) },
                enabled = isConnected,
                modifier = Modifier.size(40.dp).testTag("camera_tilt_up_button"),
              ) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Tilt camera up")
              }
            }
          }
        }

        if (caps.supportsGyroCalibration) {
          FilterChip(
            selected = false,
            onClick = { viewModel.commandCalibrateGyro() },
            enabled = isConnected,
            label = { Text("Cal Gyro", style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.testTag("calibrate_gyro_chip"),
          )
        }

        FilterChip(
          selected = sensitivity.trimRoll != 0f || sensitivity.trimPitch != 0f,
          onClick = { showTrimDialog = true },
          leadingIcon = {
            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
          },
          label = { Text("Trim", style = MaterialTheme.typography.labelSmall) },
          modifier = Modifier.testTag("trim_button"),
        )
      }

      // Ergonomic Dual-Stick & Primary Flight Deck
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .background(theme.colors.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        VirtualStickPad(
          label = "THR / YAW",
          horizontalAxisLabel = "Yaw",
          verticalAxisLabel = "Throttle",
          autoCenterY = false,
          onAxesChanged = { x, y -> viewModel.onLeftStickChanged(yaw = x, throttle = y) },
          onTouchActiveChanged = { viewModel.onManualStickTouchChanged(it) },
          size = stickSize,
          tag = "left_stick_pad",
        )

        Column(
          modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (flightState == FlightState.Armed) {
              if (caps.supportsTakeoff) {
                AutopilotButton(
                  text = "Takeoff",
                  onClick = { viewModel.commandTakeoff() },
                  icon = Icons.Default.FlightTakeoff,
                  tag = "takeoff_button",
                )
              }
            } else {
              AutopilotButton(
                text = if (flightState == FlightState.Emergency) "Reset" else "Arm",
                onClick = {
                  if (flightState == FlightState.Emergency) viewModel.disarmAircraft()
                  else viewModel.armAircraft()
                },
                enabled =
                  flightState == FlightState.Ready ||
                    flightState == FlightState.Connected ||
                    flightState == FlightState.Emergency,
                icon = Icons.Default.Shield,
                tone = AutopilotButtonTone.SECONDARY,
                tag = "arm_button",
              )
            }

            if (caps.supportsControlledLanding) {
              AutopilotButton(
                text = "Land",
                onClick = { viewModel.commandControlledLand() },
                enabled = isConnected,
                icon = Icons.Default.FlightLand,
                tone = AutopilotButtonTone.SECONDARY,
                tag = "controlled_land_button",
              )
            }
          }

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (caps.supportsMotorStop) {
              AutopilotButton(
                text = "Stop",
                onClick = { viewModel.commandMotorStop() },
                enabled = isConnected,
                icon = Icons.Default.PowerSettingsNew,
                tone = AutopilotButtonTone.WARNING,
                tag = "motor_stop_button",
              )
            }

            if (caps.supportsEmergencyMotorStop) {
              AutopilotButton(
                text = "E-STOP",
                onClick = { showEmergencyDialog = true },
                enabled = isConnected,
                icon = Icons.Default.Warning,
                tone = AutopilotButtonTone.EMERGENCY,
                tag = "emergency_stop_button",
              )
            }
          }
        }

        VirtualStickPad(
          label = "ROLL / PITCH",
          horizontalAxisLabel = "Roll",
          verticalAxisLabel = "Pitch",
          autoCenterY = true,
          onAxesChanged = { x, y -> viewModel.onRightStickChanged(roll = x, pitch = y) },
          onTouchActiveChanged = { viewModel.onManualStickTouchChanged(it) },
          size = stickSize,
          tag = "right_stick_pad",
        )
      }
    }
  }

  // Long-press Contextual Target Action Modal
  targetModalSubject?.let { target ->
    TargetActionModal(
      target = target,
      availabilities = viewModel.availableTargetActions(),
      capabilities = caps,
      initialConfig = targetActionConfig,
      activeTask = activeTargetTask,
      onDismiss = { targetModalSubject = null },
      onEngageAction = { action, cfg ->
        viewModel.startTargetAction(target, action, cfg)
        targetModalSubject = null
      },
      onStopActiveAction = {
        viewModel.stopTargetAction()
        targetModalSubject = null
      },
      onDeselectTarget = {
        viewModel.stopTargetAction()
        viewModel.clearSelectedTarget()
        targetModalSubject = null
      },
    )
  }

  // Quick Aircraft Picker Sheet
  if (showAircraftSheet) {
    ModalBottomSheet(
      onDismissRequest = { showAircraftSheet = false },
      containerColor = theme.colors.surface,
      contentColor = theme.colors.textPrimary,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = "Select Aircraft",
          style = MaterialTheme.typography.titleLarge,
          color = theme.colors.textPrimary,
        )
        viewModel.productionProtocols.forEach { entry ->
          val desc = entry.descriptor
          val isCurrent = desc.id == selectedEntry.descriptor.id
          Surface(
            modifier =
              Modifier.fillMaxWidth()
                .testTag("quick_aircraft_${desc.id}")
                .clickable {
                  viewModel.selectProtocol(desc.id)
                  showAircraftSheet = false
                },
            shape = RoundedCornerShape(12.dp),
            color = if (isCurrent) theme.colors.surfaceElevated else theme.colors.surface,
            border =
              BorderStroke(
                width = if (isCurrent) 1.5.dp else 1.dp,
                color = if (isCurrent) theme.colors.accent else theme.colors.borderSubtle,
              ),
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              Text(
                text = desc.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = theme.colors.textPrimary,
              )
              Text(
                text = desc.knownModels.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = theme.colors.textSecondary,
              )
            }
          }
        }
      }
    }
  }

  // Trim Adjustment Dialog
  if (showTrimDialog) {
    AlertDialog(
      onDismissRequest = { showTrimDialog = false },
      title = { Text("Roll & Pitch Trim") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "Roll Trim: ${"%.2f".format(sensitivity.trimRoll)}",
            style = MaterialTheme.typography.bodySmall,
          )
          Slider(
            value = sensitivity.trimRoll,
            onValueChange = { viewModel.updateTrim(it, sensitivity.trimPitch) },
            valueRange = -0.25f..0.25f,
          )
          Text(
            text = "Pitch Trim: ${"%.2f".format(sensitivity.trimPitch)}",
            style = MaterialTheme.typography.bodySmall,
          )
          Slider(
            value = sensitivity.trimPitch,
            onValueChange = { viewModel.updateTrim(sensitivity.trimRoll, it) },
            valueRange = -0.25f..0.25f,
          )
        }
      },
      confirmButton = {
        TextButton(onClick = { showTrimDialog = false }) { Text("Done") }
      },
      dismissButton = {
        TextButton(onClick = { viewModel.updateTrim(0f, 0f) }) { Text("Reset") }
      },
    )
  }

  if (showEmergencyDialog) {
    AlertDialog(
      onDismissRequest = { showEmergencyDialog = false },
      title = { Text("Confirm Emergency Stop") },
      text = {
        Text(
          "Emergency Stop immediately cuts motor drive. Use only when recovery is unsafe."
        )
      },
      confirmButton = {
        AutopilotButton(
          text = "Cut Motors",
          onClick = {
            showEmergencyDialog = false
            viewModel.commandEmergencyStop()
          },
          tone = AutopilotButtonTone.EMERGENCY,
          tag = "confirm_emergency_stop_button",
        )
      },
      dismissButton = {
        TextButton(onClick = { showEmergencyDialog = false }) { Text("Cancel") }
      },
    )
  }
}

private fun mapTapToNormalizedFrame(
  tap: Offset,
  viewport: IntSize,
  frameWidth: Int,
  frameHeight: Int,
): Offset? {
  if (viewport.width <= 0 || viewport.height <= 0) return null
  if (frameWidth <= 0 || frameHeight <= 0) {
    return Offset(
      x = (tap.x / viewport.width).coerceIn(0f, 1f),
      y = (tap.y / viewport.height).coerceIn(0f, 1f),
    )
  }
  val viewAspect = viewport.width.toFloat() / viewport.height.toFloat()
  val frameAspect = frameWidth.toFloat() / frameHeight.toFloat()
  val renderedW: Float
  val renderedH: Float
  val offsetX: Float
  val offsetY: Float
  if (frameAspect > viewAspect) {
    renderedW = viewport.width.toFloat()
    renderedH = renderedW / frameAspect
    offsetX = 0f
    offsetY = (viewport.height - renderedH) / 2f
  } else {
    renderedH = viewport.height.toFloat()
    renderedW = renderedH * frameAspect
    offsetX = (viewport.width - renderedW) / 2f
    offsetY = 0f
  }
  val nx = (tap.x - offsetX) / renderedW
  val ny = (tap.y - offsetY) / renderedH
  if (nx !in 0f..1f || ny !in 0f..1f) return null
  return Offset(nx, ny)
}

@Composable
private fun EmptyVideoViewport(
  title: String,
  detail: String,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  Column(
    modifier = modifier.padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Icon(
      Icons.Default.VideocamOff,
      contentDescription = null,
      tint = theme.colors.textSecondary,
      modifier = Modifier.size(34.dp),
    )
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      color = theme.colors.textPrimary,
    )
    Text(
      text = detail,
      style = MaterialTheme.typography.bodySmall,
      color = theme.colors.textSecondary,
    )
  }
}

@Composable
private fun VisionHudOverlay(
  result: VisionFrameResult,
  selectedTargetId: Int?,
  activeTask: ActiveTargetTask?,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val defaultBoxColor = theme.colors.textSecondary.copy(alpha = 0.72f)
  val selectedColor = theme.colors.accent

  Box(modifier = modifier) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      result.trackedTargets.forEach { target ->
        val isSelected = target.trackId == selectedTargetId
        val l = target.boxNormalized.left * size.width
        val t = target.boxNormalized.top * size.height
        val w = (target.boxNormalized.right - target.boxNormalized.left) * size.width
        val h = (target.boxNormalized.bottom - target.boxNormalized.top) * size.height
        val color = if (isSelected) selectedColor else defaultBoxColor
        drawRect(
          color = color,
          topLeft = Offset(l, t),
          size = Size(w, h),
          style = Stroke(width = if (isSelected) 4f else 2f),
        )
      }
    }

    if (result.trackedTargets.isNotEmpty()) {
      Column(
        modifier = Modifier.align(Alignment.TopStart).padding(top = 64.dp, start = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        result.trackedTargets.take(3).forEach { target ->
          val isSelected = target.trackId == selectedTargetId
          val distStr = target.estimatedDistanceMeters?.let { " · ${"%.1f".format(it)}m" } ?: ""
          val taskTag =
            if (activeTask?.targetId == target.trackId) " · ${activeTask.action.title}" else ""
          GlassHudPanel {
            Text(
              text = "#${target.trackId} ${target.label}$distStr$taskTag",
              style = MaterialTheme.typography.labelSmall,
              color = if (isSelected) theme.colors.accent else theme.colors.textPrimary,
            )
          }
        }
      }
    }
  }
}

private fun flightStateColor(
  state: FlightState,
  colors: com.autopilot.designsystem.SemanticColorTokens,
) =
  when (state) {
    is FlightState.Ready,
    is FlightState.Connected,
    is FlightState.Flying -> colors.accent
    is FlightState.Emergency,
    is FlightState.Fault,
    is FlightState.ConnectionLost -> colors.statusEmergency
    is FlightState.Armed,
    is FlightState.TakingOff,
    is FlightState.Landing -> colors.statusWarning
    else -> colors.textSecondary
  }

private fun flightStateLabel(state: FlightState): String =
  when (state) {
    FlightState.Disconnected -> "Idle"
    FlightState.Discovering -> "Searching"
    FlightState.Connecting -> "Connecting"
    FlightState.Connected -> "Connected"
    FlightState.Ready -> "Ready"
    FlightState.Armed -> "Armed"
    FlightState.TakingOff -> "Takeoff"
    FlightState.Flying -> "Flying"
    FlightState.Landing -> "Landing"
    FlightState.Emergency -> "E-Stop"
    is FlightState.ConnectionLost -> "Link Lost"
    is FlightState.Fault -> "Fault"
  }
