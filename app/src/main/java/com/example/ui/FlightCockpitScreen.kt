package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autopilot.designsystem.AutopilotButton
import com.autopilot.designsystem.AutopilotButtonTone
import com.autopilot.designsystem.LocalResolvedTheme
import com.autopilot.designsystem.VirtualStickPad
import com.autopilot.drone.protocolapi.DroneCapabilities
import com.autopilot.drone.protocolapi.FlipDirection
import com.autopilot.flight.control.FlightState
import com.autopilot.video.MvFilterPreset
import com.autopilot.video.VideoCaptureState
import com.autopilot.video.VideoStreamState
import com.autopilot.vision.TrackedTarget
import com.autopilot.vision.VisionFrameResult
import com.example.input.SpinnerMode
import kotlinx.coroutines.delay

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
  val captureState by viewModel.captureState.collectAsStateWithLifecycle()
  val visionEnabled by viewModel.visionOverlayEnabled.collectAsStateWithLifecycle()
  val targetSelectionEnabled by viewModel.targetSelectionEnabled.collectAsStateWithLifecycle()
  val visionResult by viewModel.visionResult.collectAsStateWithLifecycle()
  val selectedTargetId by viewModel.selectedTargetId.collectAsStateWithLifecycle()
  val activeTargetTask by viewModel.activeTargetTask.collectAsStateWithLifecycle()
  val targetActionConfig by viewModel.targetActionConfig.collectAsStateWithLifecycle()
  val selectedEntry by viewModel.selectedEntry.collectAsStateWithLifecycle()
  val speedTier by viewModel.speedTier.collectAsStateWithLifecycle()
  val fastFly by viewModel.fastFly.collectAsStateWithLifecycle()
  val headlessMode by viewModel.headlessMode.collectAsStateWithLifecycle()
  val sensitivity by viewModel.sensitivity.collectAsStateWithLifecycle()
  val automationState by viewModel.automationState.collectAsStateWithLifecycle()
  val userPrefs by viewModel.userPrefs.collectAsStateWithLifecycle()

  var showEmergencyDialog by remember { mutableStateOf(false) }
  var showAircraftSheet by remember { mutableStateOf(false) }
  var showFlightModesSheet by remember { mutableStateOf(false) }
  var showTrimDialog by remember { mutableStateOf(false) }
  var targetModalSubject by remember { mutableStateOf<TrackedTarget?>(null) }
  var viewportSize by remember { mutableStateOf(IntSize.Zero) }
  var drawingPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

  val caps = selectedEntry.capabilities
  val isConnected = flightState != FlightState.Disconnected
  val activeFrame = videoState as? VideoStreamState.ActiveFrame

  LaunchedEffect(captureState.lastNotice) {
    if (captureState.lastNotice != null) {
      delay(2600L)
      viewModel.clearCaptureNotice()
    }
  }

  LaunchedEffect(automationState.trajectoryDrawMode) {
    if (!automationState.trajectoryDrawMode) {
      drawingPoints = emptyList()
    }
  }

  // 100% Full-Screen Video Stream Root with <=30% Translucent Distraction-Free HUD Overlay
  Box(
    modifier = modifier.fillMaxSize().background(Color.Black),
  ) {
    // Layer 1: 100% Full-Screen Drone Camera Stream, Touch-Target & Trajectory Draw Layer
    Box(
      modifier =
        Modifier.fillMaxSize()
          .onSizeChanged { viewportSize = it }
          .pointerInput(
            automationState.trajectoryDrawMode,
            viewportSize,
          ) {
            if (!automationState.trajectoryDrawMode) return@pointerInput
            detectDragGestures(
              onDragStart = { start ->
                val w = viewportSize.width.coerceAtLeast(1).toFloat()
                val h = viewportSize.height.coerceAtLeast(1).toFloat()
                val pt = Offset((start.x / w).coerceIn(0f, 1f), (start.y / h).coerceIn(0f, 1f))
                drawingPoints = listOf(pt)
                viewModel.updatePendingTrajectory(drawingPoints)
              },
              onDrag = { change, _ ->
                change.consume()
                val w = viewportSize.width.coerceAtLeast(1).toFloat()
                val h = viewportSize.height.coerceAtLeast(1).toFloat()
                val pt =
                  Offset(
                    (change.position.x / w).coerceIn(0f, 1f),
                    (change.position.y / h).coerceIn(0f, 1f),
                  )
                drawingPoints = drawingPoints + pt
                viewModel.updatePendingTrajectory(drawingPoints)
              },
            )
          }
          .pointerInput(
            targetSelectionEnabled,
            automationState.trajectoryDrawMode,
            viewportSize,
            activeFrame?.bitmap?.width,
            activeFrame?.bitmap?.height,
          ) {
            if (!targetSelectionEnabled || automationState.trajectoryDrawMode) return@pointerInput
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
            contentScale = ContentScale.Crop,
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
            detail = "Tap Connect for ${selectedEntry.descriptor.displayName.substringBefore(" (")}",
            modifier = Modifier.align(Alignment.Center),
          )
        }
      }

      if ((visionEnabled || targetSelectionEnabled) && visionResult != null) {
        VisionHudOverlay(
          result = visionResult!!,
          frameWidth = activeFrame?.bitmap?.width ?: 0,
          frameHeight = activeFrame?.bitmap?.height ?: 0,
          selectedTargetId = selectedTargetId,
          activeTask = activeTargetTask,
          modifier = Modifier.fillMaxSize(),
        )
      }

      if (automationState.trajectoryDrawMode || automationState.pendingTrajectoryPoints.isNotEmpty()) {
        val pts = automationState.pendingTrajectoryPoints
        Canvas(modifier = Modifier.fillMaxSize().testTag("trajectory_draw_canvas")) {
          if (pts.size >= 2) {
            val path =
              Path().apply {
                moveTo(pts.first().x * size.width, pts.first().y * size.height)
                for (i in 1 until pts.size) {
                  lineTo(pts[i].x * size.width, pts[i].y * size.height)
                }
              }
            drawPath(
              path = path,
              color = theme.colors.accent,
              style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawCircle(
              color = theme.colors.accent,
              radius = 10f,
              center = Offset(pts.first().x * size.width, pts.first().y * size.height),
            )
            drawCircle(
              color = Color.White,
              radius = 9f,
              center = Offset(pts.last().x * size.width, pts.last().y * size.height),
            )
          }
        }
      }
    }

    // Layer 2: Safe-Area Bounded Translucent Flight HUD Overlay (<=30% area, >=70% clear video, zero scrolling)
    BoxWithConstraints(
      modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
      val isWideLayout = maxWidth >= 560.dp
      val isShortHeight = maxHeight < 480.dp

      val stickSize =
        if (isWideLayout) {
          if (isShortHeight) {
            (maxHeight * 0.30f).coerceIn(88.dp, 114.dp)
          } else {
            minOf(maxHeight * 0.24f, maxWidth * 0.18f).coerceIn(112.dp, 148.dp)
          }
        } else {
          minOf(maxHeight * 0.16f, (maxWidth - 40.dp) * 0.36f).coerceIn(88.dp, 132.dp)
        }

      // Top Minimal Translucent HUD Row (No Title Bar)
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .align(Alignment.TopCenter)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp).testTag("hud_top_left"),
          horizontalArrangement = Arrangement.spacedBy(5.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          HudAircraftPill(
            aircraftName = selectedEntry.descriptor.displayName.substringBefore(" ("),
            flightState = flightState,
            onClick = { showAircraftSheet = true },
            modifier = Modifier.weight(1f, fill = false),
          )

          HudConnectPill(
            isConnected = isConnected,
            onClick = {
              if (isConnected) viewModel.disconnect()
              else viewModel.connectSelectedProtocol()
            },
          )
        }

        HudTopIconCluster(
          hasActiveVideo = activeFrame != null,
          captureState = captureState,
          targetSelectionEnabled = targetSelectionEnabled,
          visionEnabled = visionEnabled,
          onCaptureSnapshot = { viewModel.captureSnapshot() },
          onToggleRecord = { viewModel.toggleVideoRecording() },
          onToggleTargetSelect = { viewModel.toggleTargetSelection(!targetSelectionEnabled) },
          onOpenVision = onOpenVision,
          onOpenSettings = onOpenSettings,
          modifier = Modifier.testTag("hud_top_right"),
        )
      }

      // Transient Micro-Banner directly below top cluster (only when active target, capture notice, or alert exists)
      val topStatusBannerModifier =
        Modifier.align(Alignment.TopCenter)
          .padding(top = 52.dp, start = 12.dp, end = 12.dp)
          .widthIn(max = 380.dp)

      if (automationState.trajectoryDrawMode || automationState.pendingTrajectoryPoints.isNotEmpty()) {
        val hasPath = automationState.pendingTrajectoryPoints.size >= 2
        val canFlyPath = isConnected && (flightState == FlightState.Armed || flightState == FlightState.Flying)
        HudTranslucentPill(modifier = topStatusBannerModifier, highlighted = true) {
          Row(
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = if (hasPath) "Trajectory Ready" else "Draw path on video",
              style = MaterialTheme.typography.labelSmall,
              color = Color.White,
              maxLines = 1,
              modifier = Modifier.weight(1f, fill = false),
            )
            if (hasPath) {
              TextButton(
                onClick = { viewModel.executeTrajectory() },
                enabled = canFlyPath,
                modifier = Modifier.testTag("trajectory_run_button"),
              ) {
                Text("Fly", style = MaterialTheme.typography.labelSmall, color = theme.colors.accent)
              }
            }
            TextButton(
              onClick = { viewModel.clearTrajectory() },
              modifier = Modifier.testTag("trajectory_clear_button"),
            ) {
              Text("Clear", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
          }
        }
      } else if (automationState.activeSequenceTitle != null) {
        HudTranslucentPill(modifier = topStatusBannerModifier, highlighted = true) {
          Row(
            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text(
              text = "${automationState.activeSequenceTitle} · ${automationState.activeStepLabel ?: ""} (${automationState.stepIndex}/${automationState.totalSteps})",
              style = MaterialTheme.typography.labelSmall,
              color = theme.colors.accent,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false),
            )
            IconButton(
              onClick = { viewModel.stopAllAutomation() },
              modifier = Modifier.size(30.dp).minimumInteractiveComponentSize().testTag("stop_automation_button"),
            ) {
              Icon(
                Icons.Default.Close,
                contentDescription = "Stop flight automation",
                tint = Color.White,
                modifier = Modifier.size(14.dp),
              )
            }
          }
        }
      } else if (automationState.spinnerMode != SpinnerMode.NONE || automationState.gravityActive) {
        val statusText =
          if (automationState.spinnerMode != SpinnerMode.NONE) "${automationState.spinnerMode.label} Active"
          else "Gravity Tilt Active"
        HudTranslucentPill(modifier = topStatusBannerModifier, highlighted = true) {
          Row(
            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text(
              text = statusText,
              style = MaterialTheme.typography.labelSmall,
              color = theme.colors.accent,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false),
            )
            IconButton(
              onClick = { viewModel.stopAllAutomation() },
              modifier = Modifier.size(30.dp).minimumInteractiveComponentSize().testTag("stop_automation_button"),
            ) {
              Icon(
                Icons.Default.Close,
                contentDescription = "Stop flight mode",
                tint = Color.White,
                modifier = Modifier.size(14.dp),
              )
            }
          }
        }
      } else if (activeTargetTask != null) {
        val task = activeTargetTask!!
        HudTranslucentPill(modifier = topStatusBannerModifier) {
          Row(
            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text(
              text = "${task.action.title} · #${task.targetId} ${task.targetLabel}",
              style = MaterialTheme.typography.labelSmall,
              color = theme.colors.accent,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false),
            )
            IconButton(
              onClick = { viewModel.stopTargetAction() },
              modifier = Modifier.size(30.dp).minimumInteractiveComponentSize().testTag("stop_target_action_button"),
            ) {
              Icon(
                Icons.Default.Close,
                contentDescription = "Stop target action",
                tint = Color.White,
                modifier = Modifier.size(14.dp),
              )
            }
          }
        }
      } else if (captureState.isRecording) {
        HudTranslucentPill(
          modifier = topStatusBannerModifier,
          borderColor = theme.colors.statusEmergency.copy(alpha = 0.7f),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Box(
              modifier =
                Modifier.size(8.dp).clip(CircleShape).background(theme.colors.statusEmergency)
            )
            val secs = captureState.recordingDurationSec
            val formatted = "%02d:%02d".format(secs / 60L, secs % 60L)
            Text(
              text = "REC $formatted",
              style = MaterialTheme.typography.labelSmall,
              color = Color.White,
              maxLines = 1,
            )
          }
        }
      } else if (captureState.lastNotice != null) {
        HudTranslucentPill(modifier = topStatusBannerModifier) {
          Text(
            text = captureState.lastNotice!!,
            style = MaterialTheme.typography.labelSmall,
            color = theme.colors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
          )
        }
      } else if (targetSelectionEnabled && selectedTargetId != null) {
        val target = visionResult?.trackedTargets?.firstOrNull { it.trackId == selectedTargetId }
        if (target != null) {
          HudTranslucentPill(
            modifier = topStatusBannerModifier.clickable { targetModalSubject = target },
          ) {
            Text(
              text = "#${target.trackId} ${target.label} · Long-press for actions",
              style = MaterialTheme.typography.labelSmall,
              color = theme.colors.accent,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
          }
        }
      } else if (safetyNotice != null) {
        HudTranslucentPill(modifier = topStatusBannerModifier) {
          Text(
            text = safetyNotice!!,
            style = MaterialTheme.typography.labelSmall,
            color = theme.colors.statusWarning,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
          )
        }
      }

      // Bottom Translucent HUD Controls (All visible at once, zero scrolling, no opaque cockpit box)
      if (isWideLayout) {
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .align(Alignment.BottomCenter)
              .padding(horizontal = 10.dp, vertical = 6.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.Bottom,
        ) {
          VirtualStickPad(
            label = "THR / YAW",
            horizontalAxisLabel = "Yaw",
            verticalAxisLabel = "Throttle",
            autoCenterY = false,
            onAxesChanged = { x, y -> viewModel.onLeftStickChanged(yaw = x, throttle = y) },
            onTouchActiveChanged = { viewModel.onManualStickTouchChanged(it) },
            size = stickSize,
            hudOverlay = true,
            tag = "left_stick_pad",
          )

          Box(
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            contentAlignment = Alignment.BottomCenter,
          ) {
            Column(
              modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
              HudCapabilityModifiersRow(
                caps = caps,
                flightState = flightState,
                speedTier = speedTier,
                fastFly = fastFly,
                headlessMode = headlessMode,
                isConnected = isConnected,
                trimActive = sensitivity.trimRoll != 0f || sensitivity.trimPitch != 0f,
                modesActive = automationState.hasActiveAutomation || automationState.trajectoryDrawMode,
                onCycleSpeedTier = { viewModel.setSpeedTier((speedTier + 1) % 3) },
                onToggleHeadless = { viewModel.setHeadlessMode(!headlessMode) },
                onFlip360 = { dir -> viewModel.commandFlip360(dir.wireId) },
                onTiltCamera = { viewModel.tiltCamera(it) },
                onCalibrateGyro = { viewModel.commandCalibrateGyro() },
                onOpenTrim = { showTrimDialog = true },
                onOpenFlightModes = { showFlightModesSheet = true },
                modifier = Modifier.fillMaxWidth().testTag("hud_modifiers_row"),
              )

              HudPrimaryActionsRow(
                flightState = flightState,
                caps = caps,
                isConnected = isConnected,
                onArmOrReset = {
                  if (flightState == FlightState.Emergency) viewModel.disarmAircraft()
                  else viewModel.armAircraft()
                },
                onTakeoff = { viewModel.commandTakeoff() },
                onLand = { viewModel.commandControlledLand() },
                onStop = { viewModel.commandMotorStop() },
                onEmergencyClick = { showEmergencyDialog = true },
                modifier = Modifier.fillMaxWidth().testTag("hud_actions_row"),
              )
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
            hudOverlay = true,
            tag = "right_stick_pad",
          )
        }
      } else {
        // Portrait Compact Overlay: Modifiers + Primary Flight Actions + Dual Translucent Sticks
        Column(
          modifier =
            Modifier.fillMaxWidth()
              .align(Alignment.BottomCenter)
              .padding(horizontal = 8.dp, vertical = 6.dp),
          verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
          HudCapabilityModifiersRow(
            caps = caps,
            flightState = flightState,
            speedTier = speedTier,
            fastFly = fastFly,
            headlessMode = headlessMode,
            isConnected = isConnected,
            trimActive = sensitivity.trimRoll != 0f || sensitivity.trimPitch != 0f,
            modesActive = automationState.hasActiveAutomation || automationState.trajectoryDrawMode,
            onCycleSpeedTier = { viewModel.setSpeedTier((speedTier + 1) % 3) },
            onToggleHeadless = { viewModel.setHeadlessMode(!headlessMode) },
            onFlip360 = { dir -> viewModel.commandFlip360(dir.wireId) },
            onTiltCamera = { viewModel.tiltCamera(it) },
            onCalibrateGyro = { viewModel.commandCalibrateGyro() },
            onOpenTrim = { showTrimDialog = true },
            onOpenFlightModes = { showFlightModesSheet = true },
            modifier = Modifier.fillMaxWidth().testTag("hud_modifiers_row"),
          )

          HudPrimaryActionsRow(
            flightState = flightState,
            caps = caps,
            isConnected = isConnected,
            onArmOrReset = {
              if (flightState == FlightState.Emergency) viewModel.disarmAircraft()
              else viewModel.armAircraft()
            },
            onTakeoff = { viewModel.commandTakeoff() },
            onLand = { viewModel.commandControlledLand() },
            onStop = { viewModel.commandMotorStop() },
            onEmergencyClick = { showEmergencyDialog = true },
            modifier = Modifier.fillMaxWidth().testTag("hud_actions_row"),
          )

          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
          ) {
            VirtualStickPad(
              label = "THR / YAW",
              horizontalAxisLabel = "Yaw",
              verticalAxisLabel = "Throttle",
              autoCenterY = false,
              onAxesChanged = { x, y -> viewModel.onLeftStickChanged(yaw = x, throttle = y) },
              onTouchActiveChanged = { viewModel.onManualStickTouchChanged(it) },
              size = stickSize,
              hudOverlay = true,
              tag = "left_stick_pad",
            )

            VirtualStickPad(
              label = "ROLL / PITCH",
              horizontalAxisLabel = "Roll",
              verticalAxisLabel = "Pitch",
              autoCenterY = true,
              onAxesChanged = { x, y -> viewModel.onRightStickChanged(roll = x, pitch = y) },
              onTouchActiveChanged = { viewModel.onManualStickTouchChanged(it) },
              size = stickSize,
              hudOverlay = true,
              tag = "right_stick_pad",
            )
          }
        }
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

  // Quick Flight Modes & KY UFO Automations Sheet
  if (showFlightModesSheet) {
    val canEngageFlightAutomation =
      isConnected && (flightState == FlightState.Armed || flightState == FlightState.Flying)
    ModalBottomSheet(
      onDismissRequest = { showFlightModesSheet = false },
      containerColor = theme.colors.surface,
      contentColor = theme.colors.textPrimary,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = "Flight Modes & Filters",
          style = MaterialTheme.typography.titleMedium,
          color = theme.colors.textPrimary,
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
          ) {
            Icon(Icons.Default.ScreenRotation, contentDescription = null, tint = theme.colors.accent, modifier = Modifier.size(20.dp))
            Column {
              Text("Gravity Tilt Steering", style = MaterialTheme.typography.bodyMedium, color = theme.colors.textPrimary)
              Text("Tilt phone to pitch & roll around grip angle", style = MaterialTheme.typography.bodySmall, color = theme.colors.textSecondary)
            }
          }
          Switch(
            checked = automationState.gravityActive,
            enabled = isConnected && viewModel.gravityHandler.isSensorAvailable,
            onCheckedChange = {
              viewModel.toggleGravityControl(it)
              if (it) showFlightModesSheet = false
            },
            modifier = Modifier.testTag("mode_gravity_switch"),
          )
        }

        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FilterChip(
            selected = automationState.trajectoryDrawMode,
            onClick = {
              viewModel.setTrajectoryDrawMode(true)
              showFlightModesSheet = false
            },
            leadingIcon = { Icon(Icons.Default.Gesture, contentDescription = null, modifier = Modifier.size(16.dp)) },
            label = { Text("Trajectory Draw") },
            modifier = Modifier.testTag("mode_trajectory_button"),
          )
          FilterChip(
            selected = automationState.spinnerMode == SpinnerMode.ROTATION,
            enabled = canEngageFlightAutomation || automationState.spinnerMode == SpinnerMode.ROTATION,
            onClick = {
              viewModel.toggleSpinner(SpinnerMode.ROTATION)
              showFlightModesSheet = false
            },
            leadingIcon = { Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.size(16.dp)) },
            label = { Text("Rot Spin") },
            modifier = Modifier.testTag("mode_rot_spinner_chip"),
          )
          FilterChip(
            selected = automationState.spinnerMode == SpinnerMode.LATERAL,
            enabled = canEngageFlightAutomation || automationState.spinnerMode == SpinnerMode.LATERAL,
            onClick = {
              viewModel.toggleSpinner(SpinnerMode.LATERAL)
              showFlightModesSheet = false
            },
            label = { Text("Lat Spin") },
            modifier = Modifier.testTag("mode_lat_spinner_chip"),
          )
        }

        Text(
          text = "MV Visual Filter",
          style = MaterialTheme.typography.labelMedium,
          color = theme.colors.textSecondary,
        )
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          MvFilterPreset.entries.forEach { preset ->
            FilterChip(
              selected = userPrefs.mvFilterPreset == preset,
              onClick = {
                viewModel.updateVideoTransformSettings(
                  rotationDeg = userPrefs.videoRotationDeg,
                  mirrorHorizontal = userPrefs.videoMirrorHorizontal,
                  mvFilterPreset = preset,
                  firmwareSdTriggerEnabled = userPrefs.firmwareSdTriggerEnabled,
                )
              },
              label = { Text(preset.label) },
              modifier = Modifier.testTag("hud_mv_${preset.name.lowercase()}"),
            )
          }
        }

        Text(
          text = "Timed Flight Macros",
          style = MaterialTheme.typography.labelMedium,
          color = theme.colors.textSecondary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          viewModel.builtInMacros.forEach { preset ->
            Surface(
              modifier =
                Modifier.fillMaxWidth()
                  .testTag("macro_${preset.id}")
                  .clickable(enabled = canEngageFlightAutomation) {
                    viewModel.startMacro(preset)
                    showFlightModesSheet = false
                  },
              shape = RoundedCornerShape(10.dp),
              color = theme.colors.canvas,
              border = BorderStroke(1.dp, theme.colors.borderSubtle),
            ) {
              Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(
                  text = preset.title,
                  style = MaterialTheme.typography.bodyMedium,
                  color = if (canEngageFlightAutomation) theme.colors.textPrimary else theme.colors.textSecondary,
                )
                Text(
                  text = preset.subtitle,
                  style = MaterialTheme.typography.bodySmall,
                  color = theme.colors.textSecondary,
                )
              }
            }
          }
        }
      }
    }
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = "Select Aircraft",
          style = MaterialTheme.typography.titleMedium,
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
            shape = RoundedCornerShape(10.dp),
            color = if (isCurrent) theme.colors.surfaceElevated else theme.colors.surface,
            border =
              BorderStroke(
                width = if (isCurrent) 1.5.dp else 1.dp,
                color = if (isCurrent) theme.colors.accent else theme.colors.borderSubtle,
              ),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = desc.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
              Text(
                text = desc.knownModels.firstOrNull().orEmpty(),
                style = MaterialTheme.typography.labelSmall,
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

@Composable
private fun HudTranslucentPill(
  modifier: Modifier = Modifier,
  highlighted: Boolean = false,
  fillColor: Color? = null,
  borderColor: Color? = null,
  content: @Composable () -> Unit,
) {
  val theme = LocalResolvedTheme.current
  val bg =
    fillColor
      ?: if (highlighted) theme.colors.accent.copy(alpha = 0.28f)
      else Color.Black.copy(alpha = 0.36f)
  val stroke =
    borderColor
      ?: if (highlighted) theme.colors.accent.copy(alpha = 0.72f)
      else Color.White.copy(alpha = 0.18f)

  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(10.dp),
    color = bg,
    border = BorderStroke(0.75.dp, stroke),
  ) {
    content()
  }
}

@Composable
private fun HudAircraftPill(
  aircraftName: String,
  flightState: FlightState,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  HudTranslucentPill(
    modifier =
      modifier
        .testTag("aircraft_selector_pill")
        .defaultMinSize(minHeight = 36.dp)
        .clickable(onClick = onClick),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Box(
        modifier =
          Modifier.size(7.dp)
            .clip(CircleShape)
            .background(flightStateColor(flightState, theme.colors))
      )
      Text(
        text = aircraftName,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      Icon(
        Icons.Default.ArrowDropDown,
        contentDescription = "Select aircraft",
        tint = Color.White.copy(alpha = 0.75f),
        modifier = Modifier.size(15.dp),
      )
    }
  }
}

@Composable
private fun HudConnectPill(
  isConnected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  HudTranslucentPill(
    modifier =
      modifier
        .testTag("connect_toggle_button")
        .defaultMinSize(minHeight = 36.dp)
        .clickable(onClick = onClick),
    fillColor =
      if (isConnected) Color.Black.copy(alpha = 0.38f)
      else theme.colors.accent.copy(alpha = 0.84f),
    borderColor =
      if (isConnected) theme.colors.accent.copy(alpha = 0.65f)
      else theme.colors.accent,
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
      Text(
        text = if (isConnected) "Disconnect" else "Connect",
        style = MaterialTheme.typography.labelMedium,
        color = if (isConnected) Color.White else theme.colors.onAccent,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun HudTopIconCluster(
  hasActiveVideo: Boolean,
  captureState: VideoCaptureState,
  targetSelectionEnabled: Boolean,
  visionEnabled: Boolean,
  onCaptureSnapshot: () -> Unit,
  onToggleRecord: () -> Unit,
  onToggleTargetSelect: () -> Unit,
  onOpenVision: () -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  HudTranslucentPill(modifier = modifier) {
    Row(
      modifier = Modifier.padding(horizontal = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(
        onClick = onCaptureSnapshot,
        enabled = hasActiveVideo,
        modifier = Modifier.size(34.dp).minimumInteractiveComponentSize().testTag("snapshot_button"),
      ) {
        Icon(
          Icons.Default.PhotoCamera,
          contentDescription = "Capture FPV snapshot",
          tint = if (hasActiveVideo) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.35f),
          modifier = Modifier.size(17.dp),
        )
      }
      IconButton(
        onClick = onToggleRecord,
        enabled = hasActiveVideo || captureState.isRecording,
        modifier = Modifier.size(34.dp).minimumInteractiveComponentSize().testTag("record_video_button"),
      ) {
        Icon(
          imageVector = if (captureState.isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
          contentDescription = if (captureState.isRecording) "Stop recording" else "Start MP4 recording",
          tint =
            when {
              captureState.isRecording -> theme.colors.statusEmergency
              hasActiveVideo -> Color.White.copy(alpha = 0.9f)
              else -> Color.White.copy(alpha = 0.35f)
            },
          modifier = Modifier.size(17.dp),
        )
      }
      IconButton(
        onClick = onToggleTargetSelect,
        modifier = Modifier.size(34.dp).minimumInteractiveComponentSize().testTag("target_select_toggle"),
      ) {
        Icon(
          Icons.Default.CenterFocusWeak,
          contentDescription = "Toggle target selection on video",
          tint = if (targetSelectionEnabled) theme.colors.accent else Color.White.copy(alpha = 0.85f),
          modifier = Modifier.size(17.dp),
        )
      }
      IconButton(
        onClick = onOpenVision,
        modifier = Modifier.size(34.dp).minimumInteractiveComponentSize().testTag("open_vision_button"),
      ) {
        Icon(
          Icons.Default.RemoveRedEye,
          contentDescription = "Open Computer Vision",
          tint = if (visionEnabled) theme.colors.accent else Color.White.copy(alpha = 0.85f),
          modifier = Modifier.size(17.dp),
        )
      }
      IconButton(
        onClick = onOpenSettings,
        modifier = Modifier.size(34.dp).minimumInteractiveComponentSize().testTag("open_settings_button"),
      ) {
        Icon(
          Icons.Default.Settings,
          contentDescription = "Open Settings",
          tint = Color.White.copy(alpha = 0.85f),
          modifier = Modifier.size(17.dp),
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun HudCapabilityModifiersRow(
  caps: DroneCapabilities,
  flightState: FlightState,
  speedTier: Int,
  fastFly: Boolean,
  headlessMode: Boolean,
  isConnected: Boolean,
  trimActive: Boolean,
  modesActive: Boolean,
  onCycleSpeedTier: () -> Unit,
  onToggleHeadless: () -> Unit,
  onFlip360: (FlipDirection) -> Unit,
  onTiltCamera: (Int) -> Unit,
  onCalibrateGyro: () -> Unit,
  onOpenTrim: () -> Unit,
  onOpenFlightModes: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val canFlip = isConnected && (flightState == FlightState.Armed || flightState == FlightState.Flying)
  var showFlipDirMenu by remember { mutableStateOf(false) }

  FlowRow(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    if (caps.supportsSpeedTiers) {
      val speedLabel =
        when {
          fastFly || speedTier >= 2 -> if (caps.supportsFastFly) "Fast" else "Spd H"
          speedTier == 1 -> "Spd M"
          else -> "Spd L"
        }
      HudOverlayMiniButton(
        label = speedLabel,
        active = speedTier > 0 || fastFly,
        onClick = onCycleSpeedTier,
        tag = "speed_tier_cycle",
      )
    }

    if (caps.supportsHeadlessMode) {
      HudOverlayMiniButton(
        label = "Hdls",
        active = headlessMode,
        onClick = onToggleHeadless,
        icon = Icons.Default.Explore,
        tag = "headless_mode_chip",
      )
    }

    if (caps.supportsFlip) {
      Box {
        HudTranslucentPill(
          highlighted = false,
          modifier =
            Modifier.testTag("flip_360_chip")
              .defaultMinSize(minHeight = 30.dp)
              .combinedClickable(
                enabled = canFlip,
                onClick = { onFlip360(FlipDirection.DEFAULT) },
                onLongClick = { showFlipDirMenu = true },
              ),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
          ) {
            Icon(
              imageVector = Icons.Default.FlipCameraAndroid,
              contentDescription = null,
              tint = if (canFlip) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.4f),
              modifier = Modifier.size(13.dp),
            )
            Text(
              text = "Flip",
              style = MaterialTheme.typography.labelSmall,
              color = if (canFlip) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.4f),
              maxLines = 1,
            )
          }
        }
        DropdownMenu(
          expanded = showFlipDirMenu,
          onDismissRequest = { showFlipDirMenu = false },
        ) {
          FlipDirection.entries.forEach { dir ->
            DropdownMenuItem(
              text = { Text("${dir.label} 360°") },
              onClick = {
                showFlipDirMenu = false
                onFlip360(dir)
              },
              modifier = Modifier.testTag("flip_dir_${dir.name.lowercase()}"),
            )
          }
        }
      }
    }

    if (caps.supportsCameraTilt) {
      HudTranslucentPill {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(
            onClick = { onTiltCamera(-1) },
            enabled = isConnected,
            modifier = Modifier.size(30.dp).testTag("camera_tilt_down_button"),
          ) {
            Icon(
              Icons.Default.KeyboardArrowDown,
              contentDescription = "Tilt camera down",
              tint = if (isConnected) Color.White else Color.White.copy(alpha = 0.4f),
              modifier = Modifier.size(15.dp),
            )
          }
          Text(
            text = "Tilt",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f),
          )
          IconButton(
            onClick = { onTiltCamera(1) },
            enabled = isConnected,
            modifier = Modifier.size(30.dp).testTag("camera_tilt_up_button"),
          ) {
            Icon(
              Icons.Default.KeyboardArrowUp,
              contentDescription = "Tilt camera up",
              tint = if (isConnected) Color.White else Color.White.copy(alpha = 0.4f),
              modifier = Modifier.size(15.dp),
            )
          }
        }
      }
    }

    if (caps.supportsGyroCalibration) {
      HudOverlayMiniButton(
        label = "Gyro",
        enabled = isConnected,
        onClick = onCalibrateGyro,
        tag = "calibrate_gyro_chip",
      )
    }

    HudOverlayMiniButton(
      label = "Trim",
      active = trimActive,
      onClick = onOpenTrim,
      icon = Icons.Default.Tune,
      tag = "trim_button",
      activeColor = theme.colors.accent,
    )

    HudOverlayMiniButton(
      label = "Mode",
      active = modesActive,
      onClick = onOpenFlightModes,
      tag = "flight_modes_chip",
      activeColor = theme.colors.accent,
    )
  }
}

@Composable
private fun HudOverlayMiniButton(
  label: String,
  onClick: () -> Unit,
  tag: String,
  enabled: Boolean = true,
  active: Boolean = false,
  icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
  activeColor: Color? = null,
) {
  val theme = LocalResolvedTheme.current
  val accent = activeColor ?: theme.colors.accent
  HudTranslucentPill(
    highlighted = active,
    modifier =
      Modifier.testTag(tag)
        .defaultMinSize(minHeight = 30.dp)
        .clickable(enabled = enabled, onClick = onClick),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      if (icon != null) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint =
            when {
              !enabled -> Color.White.copy(alpha = 0.4f)
              active -> accent
              else -> Color.White.copy(alpha = 0.85f)
            },
          modifier = Modifier.size(13.dp),
        )
      }
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color =
          when {
            !enabled -> Color.White.copy(alpha = 0.4f)
            active -> accent
            else -> Color.White.copy(alpha = 0.9f)
          },
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun HudPrimaryActionsRow(
  flightState: FlightState,
  caps: DroneCapabilities,
  isConnected: Boolean,
  onArmOrReset: () -> Unit,
  onTakeoff: () -> Unit,
  onLand: () -> Unit,
  onStop: () -> Unit,
  onEmergencyClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (flightState == FlightState.Armed && caps.supportsTakeoff) {
      HudActionButton(
        text = "Takeoff",
        onClick = onTakeoff,
        fillColor = theme.colors.accent.copy(alpha = 0.85f),
        borderColor = theme.colors.accent,
        textColor = theme.colors.onAccent,
        modifier = Modifier.weight(1f),
        tag = "takeoff_button",
      )
    } else {
      val armEnabled =
        flightState == FlightState.Ready ||
          flightState == FlightState.Connected ||
          flightState == FlightState.Emergency
      val armLabel =
        when {
          flightState == FlightState.Emergency -> "Reset"
          caps.supportsUnlockCommand -> "Unlock"
          else -> "Arm"
        }
      HudActionButton(
        text = armLabel,
        enabled = armEnabled,
        onClick = onArmOrReset,
        fillColor = Color.Black.copy(alpha = 0.38f),
        borderColor = if (armEnabled) theme.colors.accent.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.14f),
        textColor = if (armEnabled) Color.White else Color.White.copy(alpha = 0.4f),
        modifier = Modifier.weight(1f),
        tag = "arm_button",
      )
    }

    if (caps.supportsControlledLanding) {
      HudActionButton(
        text = "Land",
        enabled = isConnected,
        onClick = onLand,
        fillColor = Color.Black.copy(alpha = 0.38f),
        borderColor = Color.White.copy(alpha = if (isConnected) 0.24f else 0.12f),
        textColor = if (isConnected) Color.White else Color.White.copy(alpha = 0.4f),
        modifier = Modifier.weight(1f),
        tag = "controlled_land_button",
      )
    }

    if (caps.supportsMotorStop) {
      HudActionButton(
        text = "Stop",
        enabled = isConnected,
        onClick = onStop,
        fillColor = theme.colors.statusWarning.copy(alpha = if (isConnected) 0.34f else 0.16f),
        borderColor = theme.colors.statusWarning.copy(alpha = if (isConnected) 0.72f else 0.28f),
        textColor = if (isConnected) Color.White else Color.White.copy(alpha = 0.4f),
        modifier = Modifier.weight(1f),
        tag = "motor_stop_button",
      )
    }

    if (caps.supportsEmergencyMotorStop) {
      HudActionButton(
        text = "E-STOP",
        enabled = isConnected,
        onClick = onEmergencyClick,
        fillColor = theme.colors.statusEmergency.copy(alpha = if (isConnected) 0.44f else 0.18f),
        borderColor = theme.colors.statusEmergency.copy(alpha = if (isConnected) 0.82f else 0.30f),
        textColor = if (isConnected) Color.White else Color.White.copy(alpha = 0.4f),
        modifier = Modifier.weight(1f),
        tag = "emergency_stop_button",
      )
    }
  }
}

@Composable
private fun HudActionButton(
  text: String,
  onClick: () -> Unit,
  fillColor: Color,
  borderColor: Color,
  textColor: Color,
  tag: String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  Surface(
    modifier =
      modifier
        .testTag(tag)
        .defaultMinSize(minHeight = 44.dp)
        .clickable(enabled = enabled, onClick = onClick),
    shape = RoundedCornerShape(10.dp),
    color = fillColor,
    border = BorderStroke(0.85.dp, borderColor),
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
    ) {
      Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = textColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

private fun computeCropVideoDrawRect(
  viewportWidth: Float,
  viewportHeight: Float,
  frameWidth: Int,
  frameHeight: Int,
): Rect {
  if (viewportWidth <= 0f || viewportHeight <= 0f) return Rect.Zero
  if (frameWidth <= 0 || frameHeight <= 0) {
    return Rect(0f, 0f, viewportWidth, viewportHeight)
  }
  val viewAspect = viewportWidth / viewportHeight
  val frameAspect = frameWidth.toFloat() / frameHeight.toFloat()
  val renderedW: Float
  val renderedH: Float
  val offsetX: Float
  val offsetY: Float
  if (frameAspect > viewAspect) {
    renderedH = viewportHeight
    renderedW = renderedH * frameAspect
    offsetX = (viewportWidth - renderedW) / 2f
    offsetY = 0f
  } else {
    renderedW = viewportWidth
    renderedH = renderedW / frameAspect
    offsetX = 0f
    offsetY = (viewportHeight - renderedH) / 2f
  }
  return Rect(offsetX, offsetY, offsetX + renderedW, offsetY + renderedH)
}

private fun mapTapToNormalizedFrame(
  tap: Offset,
  viewport: IntSize,
  frameWidth: Int,
  frameHeight: Int,
): Offset? {
  if (viewport.width <= 0 || viewport.height <= 0) return null
  val rect =
    computeCropVideoDrawRect(
      viewportWidth = viewport.width.toFloat(),
      viewportHeight = viewport.height.toFloat(),
      frameWidth = frameWidth,
      frameHeight = frameHeight,
    )
  if (rect.width <= 0f || rect.height <= 0f) return null
  val nx = ((tap.x - rect.left) / rect.width).coerceIn(0f, 1f)
  val ny = ((tap.y - rect.top) / rect.height).coerceIn(0f, 1f)
  return Offset(nx, ny)
}

@Composable
private fun EmptyVideoViewport(
  title: String,
  detail: String,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.padding(horizontal = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Icon(
      Icons.Default.VideocamOff,
      contentDescription = null,
      tint = Color.White.copy(alpha = 0.45f),
      modifier = Modifier.size(30.dp),
    )
    Text(
      text = title,
      style = MaterialTheme.typography.titleSmall,
      color = Color.White.copy(alpha = 0.85f),
      textAlign = TextAlign.Center,
    )
    Text(
      text = detail,
      style = MaterialTheme.typography.bodySmall,
      color = Color.White.copy(alpha = 0.55f),
      textAlign = TextAlign.Center,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun VisionHudOverlay(
  result: VisionFrameResult,
  frameWidth: Int,
  frameHeight: Int,
  selectedTargetId: Int?,
  activeTask: ActiveTargetTask?,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val defaultBoxColor = Color.White.copy(alpha = 0.72f)
  val selectedColor = theme.colors.accent

  Box(modifier = modifier) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val drawRect = computeCropVideoDrawRect(size.width, size.height, frameWidth, frameHeight)
      result.trackedTargets.forEach { target ->
        val isSelected = target.trackId == selectedTargetId
        val l = drawRect.left + target.boxNormalized.left * drawRect.width
        val t = drawRect.top + target.boxNormalized.top * drawRect.height
        val w = (target.boxNormalized.right - target.boxNormalized.left) * drawRect.width
        val h = (target.boxNormalized.bottom - target.boxNormalized.top) * drawRect.height
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
        modifier =
          Modifier.align(Alignment.TopStart)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = 56.dp, start = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        result.trackedTargets.take(3).forEach { target ->
          val isSelected = target.trackId == selectedTargetId
          val distStr = target.estimatedDistanceMeters?.let { " · ${"%.1f".format(it)}m" } ?: ""
          val taskTag =
            if (activeTask?.targetId == target.trackId) " · ${activeTask.action.title}" else ""
          HudTranslucentPill {
            Text(
              text = "#${target.trackId} ${target.label}$distStr$taskTag",
              style = MaterialTheme.typography.labelSmall,
              color = if (isSelected) theme.colors.accent else Color.White,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
    is FlightState.Armed,
    is FlightState.TakingOff,
    is FlightState.Flying,
    is FlightState.Landing -> colors.statusNominal
    is FlightState.Discovering,
    is FlightState.Connecting -> colors.statusWarning
    is FlightState.Emergency,
    is FlightState.ConnectionLost,
    is FlightState.Fault -> colors.statusEmergency
    is FlightState.Disconnected -> colors.textSecondary
  }
