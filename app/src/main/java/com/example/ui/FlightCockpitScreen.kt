package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RemoveRedEye
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import com.autopilot.flight.input.StickInputProfile
import com.autopilot.flight.interaction.CockpitInteractionMode
import com.autopilot.flight.touch.TouchPointPx
import com.autopilot.video.MvFilterPreset
import com.autopilot.video.VideoCaptureState
import com.autopilot.video.VideoStreamState
import com.autopilot.vision.TrackedTarget
import com.autopilot.vision.VisionFrameResult
import com.example.input.SpinnerMode
import com.example.ui.controls.GravityIndicator
import com.example.ui.controls.HudEditorOverlay
import com.example.ui.controls.TouchZoneOverlay
import com.example.ui.hud.HudElementConfig
import com.example.ui.hud.HudElementId
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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
  val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
  val activeCameraIndex by viewModel.activeCameraIndex.collectAsStateWithLifecycle()
  val speedTier by viewModel.speedTier.collectAsStateWithLifecycle()
  val fastFly by viewModel.fastFly.collectAsStateWithLifecycle()
  val headlessMode by viewModel.headlessMode.collectAsStateWithLifecycle()
  val fixedHeightMode by viewModel.fixedHeightMode.collectAsStateWithLifecycle()
  val linkRxVerified by viewModel.linkRxVerified.collectAsStateWithLifecycle()
  val hoverAssistEnabled by viewModel.hoverAssistEnabled.collectAsStateWithLifecycle()
  val sensitivity by viewModel.sensitivity.collectAsStateWithLifecycle()
  val automationState by viewModel.automationState.collectAsStateWithLifecycle()
  val userPrefs by viewModel.userPrefs.collectAsStateWithLifecycle()
  val interactionModeState by viewModel.interactionModeState.collectAsStateWithLifecycle()
  val atcLeftRuntime by viewModel.atcLeftRuntime.collectAsStateWithLifecycle()
  val atcRightRuntime by viewModel.atcRightRuntime.collectAsStateWithLifecycle()
  val gravityStatus by viewModel.gravityStatus.collectAsStateWithLifecycle()
  val hudPortraitLayout by viewModel.hudPortraitLayout.collectAsStateWithLifecycle()
  val hudLandscapeLayout by viewModel.hudLandscapeLayout.collectAsStateWithLifecycle()
  val selectedHudElement by viewModel.selectedHudElement.collectAsStateWithLifecycle()
  val hudHistoryFlags by viewModel.hudHistoryFlags.collectAsStateWithLifecycle()
  val deviceSafetyEvaluation by viewModel.deviceSafetyEvaluation.collectAsStateWithLifecycle()

  var showEmergencyDialog by remember { mutableStateOf(false) }
  var showAircraftSheet by remember { mutableStateOf(false) }
  var showFlightModesSheet by remember { mutableStateOf(false) }
  var showTrimDialog by remember { mutableStateOf(false) }
  var targetModalSubject by remember { mutableStateOf<TrackedTarget?>(null) }
  var viewportSize by remember { mutableStateOf(IntSize.Zero) }

  val caps = selectedEntry.capabilities
  val isConnected = flightState != FlightState.Disconnected
  val activeFrame = videoState as? VideoStreamState.ActiveFrame
  val pilotingModeActive = interactionModeState.mode == CockpitInteractionMode.PILOTING
  val atcOverlayEnabled =
    !caps.isVideoOnly &&
      pilotingModeActive &&
      (userPrefs.stickInputProfile == StickInputProfile.ATC_DUAL_TOUCH ||
        userPrefs.stickInputProfile == StickInputProfile.ATC_HYBRID_GRAVITY)
  val showFixedSticks =
    !caps.isVideoOnly && userPrefs.stickInputProfile == StickInputProfile.FIXED_STICKS

  BackHandler(
    enabled =
      interactionModeState.mode != CockpitInteractionMode.PILOTING ||
        automationState.trajectoryDrawMode,
  ) {
    when {
      interactionModeState.mode == CockpitInteractionMode.HUD_EDITING ->
        viewModel.exitHudEditingMode()
      automationState.trajectoryDrawMode ||
        interactionModeState.mode == CockpitInteractionMode.TRAJECTORY ->
        viewModel.setTrajectoryDrawMode(false)
    }
  }

  LaunchedEffect(captureState.lastNotice) {
    if (captureState.lastNotice != null) {
      delay(2600L)
      viewModel.clearCaptureNotice()
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
            interactionModeState.mode,
            viewportSize,
          ) {
            if (!automationState.trajectoryDrawMode ||
              interactionModeState.mode != CockpitInteractionMode.TRAJECTORY
            ) {
              return@pointerInput
            }
            awaitEachGesture {
              val down = awaitFirstDown(requireUnconsumed = false)
              val pid = down.id.value.toInt()
              val w = viewportSize.width.coerceAtLeast(1).toFloat()
              val h = viewportSize.height.coerceAtLeast(1).toFloat()
              val startNorm =
                Offset((down.position.x / w).coerceIn(0f, 1f), (down.position.y / h).coerceIn(0f, 1f))
              if (!viewModel.onTrajectoryPointerDown(pid, startNorm)) {
                return@awaitEachGesture
              }
              down.consume()
              while (true) {
                val event = awaitPointerEvent()
                var activePressed = false
                event.changes.forEach { change ->
                  if (change.id.value.toInt() == pid) {
                    if (change.pressed) {
                      activePressed = true
                      val pt =
                        Offset(
                          (change.position.x / w).coerceIn(0f, 1f),
                          (change.position.y / h).coerceIn(0f, 1f),
                        )
                      if (viewModel.onTrajectoryPointerMove(pid, pt)) {
                        change.consume()
                      }
                    } else {
                      viewModel.onTrajectoryPointerUpOrCancel(pid)
                      change.consume()
                    }
                  }
                }
                if (!activePressed) break
              }
            }
          }
          .pointerInput(
            targetSelectionEnabled,
            automationState.trajectoryDrawMode,
            interactionModeState.mode,
            viewportSize,
            activeFrame?.bitmap?.width,
            activeFrame?.bitmap?.height,
          ) {
            if (!targetSelectionEnabled ||
              automationState.trajectoryDrawMode ||
              interactionModeState.mode != CockpitInteractionMode.PILOTING
            ) {
              return@pointerInput
            }
            awaitEachGesture {
              val down = awaitFirstDown(requireUnconsumed = true)
              val pid = down.id.value.toInt()
              if (!viewModel.onTargetPointerDown(pid)) {
                viewModel.onTargetPointerUpOrCancel(pid)
                return@awaitEachGesture
              }
              val downPos = down.position
              val longPressTimeout = viewConfiguration.longPressTimeoutMillis
              val touchSlop = viewConfiguration.touchSlop
              var movedTooFar = false
              val releasedPos: Offset? =
                withTimeoutOrNull(longPressTimeout) {
                  var liftedAt: Offset? = null
                  while (liftedAt == null) {
                    val event = awaitPointerEvent()
                    val ch = event.changes.firstOrNull { it.id.value.toInt() == pid }
                    if (ch == null || !ch.pressed) {
                      liftedAt = ch?.position ?: downPos
                    } else if ((ch.position - downPos).getDistance() > touchSlop) {
                      movedTooFar = true
                    }
                  }
                  liftedAt
                }
              if (releasedPos == null) {
                if (!movedTooFar) {
                  val norm =
                    mapTapToNormalizedFrame(
                      tap = downPos,
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
                }
                while (true) {
                  val event = awaitPointerEvent()
                  if (event.changes.none { it.id.value.toInt() == pid && it.pressed }) break
                }
              } else if (!movedTooFar) {
                val norm =
                  mapTapToNormalizedFrame(
                    tap = releasedPos,
                    viewport = viewportSize,
                    frameWidth = activeFrame?.bitmap?.width ?: 0,
                    frameHeight = activeFrame?.bitmap?.height ?: 0,
                  )
                if (norm != null) {
                  viewModel.selectTargetAtNormalizedPoint(norm.x, norm.y)
                }
              }
              viewModel.onTargetPointerUpOrCancel(pid)
            }
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
        val cleaned = automationState.cleanedTrajectory
        val pts =
          cleaned?.cleanedPoints?.map { Offset(it.x, it.y) }
            ?: automationState.pendingTrajectoryPoints
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
            cleaned?.waypoints?.forEach { wp ->
              drawCircle(
                color = Color.White.copy(alpha = 0.72f),
                radius = 5f,
                center = Offset(wp.point.x * size.width, wp.point.y * size.height),
              )
            }
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

      TouchZoneOverlay(
        enabled = atcOverlayEnabled,
        interactionMode = interactionModeState.mode,
        viewportSize = viewportSize,
        leftRuntime = atcLeftRuntime,
        rightRuntime = atcRightRuntime,
        onPickZone = { nx, ny -> viewModel.pickAtcZone(nx, ny) },
        onRouteDown = { pid, zone, pos, w, h -> viewModel.onAtcPointerDown(pid, zone, pos, w, h) },
        onRouteMove = { pid, pos -> viewModel.onAtcPointerMove(pid, pos) },
        onRouteUpOrCancel = { pid -> viewModel.onAtcPointerUpOrCancel(pid) },
        onCancelAll = { viewModel.cancelAllAtcPointers() },
        modifier = Modifier.fillMaxSize(),
      )
    }

    // Layer 2: Safe-Area Bounded Translucent Flight HUD Overlay (<=30% area, >=70% clear video, zero scrolling)
    BoxWithConstraints(
      modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
      val density = LocalDensity.current
      val marginPx = with(density) { 6.dp.roundToPx() }
      val isWideLayout = maxWidth >= 560.dp
      val isShortHeight = maxHeight < 480.dp
      val activeHudLayout = if (isWideLayout) hudLandscapeLayout else hudPortraitLayout
      val topLeftHud = activeHudLayout.element(HudElementId.TOP_LEFT_STATUS)
      val topRightHud = activeHudLayout.element(HudElementId.TOP_RIGHT_ACTIONS)
      val gravityHud = activeHudLayout.element(HudElementId.GRAVITY_INDICATOR)
      val modifiersHud = activeHudLayout.element(HudElementId.CAPABILITY_MODIFIERS)
      val primaryHud = activeHudLayout.element(HudElementId.PRIMARY_ACTIONS)
      val leftStickHud = activeHudLayout.element(HudElementId.LEFT_STICK)
      val rightStickHud = activeHudLayout.element(HudElementId.RIGHT_STICK)

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

      // 1. Top-Left Aircraft Status & Connect Cluster (dynamically placed by HudLayoutConfig)
      HudPlacedSlot(config = topLeftHud, marginPx = marginPx) {
        Row(
          modifier =
            Modifier.widthIn(max = if (isWideLayout) 280.dp else 210.dp)
              .graphicsLayer(
                alpha = topLeftHud.opacity,
                scaleX = topLeftHud.scale,
                scaleY = topLeftHud.scale,
              )
              .testTag("hud_top_left"),
          horizontalArrangement = Arrangement.spacedBy(5.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          val shortName = selectedEntry.descriptor.displayName.substringBefore(" (")
          val pillTitle =
            if (!isConnected) {
              shortName
            } else {
              val linkSuffix =
                when {
                  caps.isVideoOnly -> "VID"
                  linkRxVerified -> "RX OK"
                  else -> "NO RX"
                }
              val batSuffix = telemetry?.batteryPercent?.let { " $it%" }.orEmpty()
              "$shortName · $linkSuffix$batSuffix"
            }
          HudAircraftPill(
            aircraftName = pillTitle,
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
      }

      // 2. Top-Right Camera & Tools Cluster
      HudPlacedSlot(config = topRightHud, marginPx = marginPx) {
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
          modifier =
            Modifier.graphicsLayer(
                alpha = topRightHud.opacity,
                scaleX = topRightHud.scale,
                scaleY = topRightHud.scale,
              )
              .testTag("hud_top_right"),
        )
      }

      // 3. Dedicated Gravity Artificial Horizon Indicator (independent of transient banners)
      if (automationState.gravityActive && gravityHud.visible) {
        HudPlacedSlot(config = gravityHud, marginPx = marginPx) {
          GravityIndicator(
            tiltRollNorm = gravityStatus.rollNorm,
            tiltPitchNorm = gravityStatus.pitchNorm,
            calibration = gravityStatus.calibration,
            stale = gravityStatus.stale,
            presetLabel = userPrefs.gravityPreset.label,
            onRecenter = { viewModel.recenterGravity() },
            modifier =
              Modifier.graphicsLayer(
                alpha = gravityHud.opacity,
                scaleX = gravityHud.scale,
                scaleY = gravityHud.scale,
              ),
          )
        }
      }

      // Transient Micro-Banner directly below top cluster (only when active task, capture notice, or alert exists)
      val topStatusBannerModifier =
        Modifier.align(Alignment.TopCenter)
          .padding(
            top = if (automationState.gravityActive && gravityHud.visible) 96.dp else 52.dp,
            start = 12.dp,
            end = 12.dp,
          )
          .widthIn(max = 400.dp)

      if (automationState.trajectoryDrawMode || automationState.pendingTrajectoryPoints.isNotEmpty()) {
        val cleaned = automationState.cleanedTrajectory
        val hasPath = (cleaned?.waypoints?.isNotEmpty() == true) || automationState.pendingTrajectoryPoints.size >= 2
        val protocolSupportsTrajectory = deviceSafetyEvaluation.supportsTrajectoryExecution
        val canFlyPath =
          protocolSupportsTrajectory &&
            isConnected &&
            (flightState == FlightState.Armed || flightState == FlightState.Flying)
        HudTranslucentPill(modifier = topStatusBannerModifier, highlighted = true) {
          Row(
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
              Text(
                text =
                  when {
                    !hasPath -> "Draw path on video"
                    !protocolSupportsTrajectory -> "Preview Only · ${cleaned?.waypoints?.size ?: 0} wp"
                    else -> "Trajectory Ready · ${cleaned?.waypoints?.size ?: 0} wp"
                  },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              if (hasPath && !protocolSupportsTrajectory && deviceSafetyEvaluation.trajectoryBlockReason != null) {
                Text(
                  text = "Closed-loop flight unverified on this protocol",
                  style = MaterialTheme.typography.labelSmall,
                  color = theme.colors.statusWarning,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
            if (hasPath) {
              TextButton(
                onClick = { viewModel.executeTrajectory() },
                enabled = canFlyPath,
                modifier = Modifier.testTag("trajectory_run_button"),
              ) {
                Text(
                  text = if (protocolSupportsTrajectory) "Fly" else "Preview",
                  style = MaterialTheme.typography.labelSmall,
                  color = if (canFlyPath) theme.colors.accent else Color.White.copy(alpha = 0.45f),
                )
              }
            }
            if (automationState.trajectoryDrawMode) {
              TextButton(
                onClick = { viewModel.setTrajectoryDrawMode(false) },
                modifier = Modifier.testTag("trajectory_done_button"),
              ) {
                Text("Done", style = MaterialTheme.typography.labelSmall, color = theme.colors.accent)
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
      } else if (automationState.spinnerMode != SpinnerMode.NONE) {
        val statusText = "${automationState.spinnerMode.label} Active"
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

      // 4. Capability Modifiers Row (dynamically positioned via HudElementId.CAPABILITY_MODIFIERS)
      val centerHudMaxWidth = if (isWideLayout) 360.dp else (maxWidth - 20.dp).coerceAtLeast(240.dp)
      if (modifiersHud.visible) {
        HudPlacedSlot(config = modifiersHud, marginPx = marginPx) {
          HudCapabilityModifiersRow(
            caps = caps,
            flightState = flightState,
            speedTier = speedTier,
            fastFly = fastFly,
            headlessMode = headlessMode,
            fixedHeightMode = fixedHeightMode,
            isConnected = isConnected,
            activeCameraIndex = activeCameraIndex,
            trimActive = sensitivity.trimRoll != 0f || sensitivity.trimPitch != 0f,
            modesActive =
              automationState.hasActiveAutomation ||
                automationState.trajectoryDrawMode ||
                hoverAssistEnabled,
            onCycleSpeedTier = { viewModel.setSpeedTier((speedTier + 1) % 3) },
            onToggleHeadless = { viewModel.setHeadlessMode(!headlessMode) },
            onToggleLight = { viewModel.toggleLight() },
            onToggleFixedHeight = { viewModel.setFixedHeightMode(!fixedHeightMode) },
            onFlip360 = { dir -> viewModel.commandFlip360(dir.wireId) },
            onTiltCamera = { viewModel.tiltCamera(it) },
            onSwitchCamera = { viewModel.switchCamera() },
            onCalibrateGyro = { viewModel.commandCalibrateGyro() },
            onOpenTrim = { showTrimDialog = true },
            onOpenFlightModes = { showFlightModesSheet = true },
            modifier =
              Modifier.widthIn(max = centerHudMaxWidth)
                .graphicsLayer(
                  alpha = modifiersHud.opacity,
                  scaleX = modifiersHud.scale,
                  scaleY = modifiersHud.scale,
                )
                .testTag("hud_modifiers_row"),
          )
        }
      }

      // 5. Primary Actions Row (dynamically positioned via HudElementId.PRIMARY_ACTIONS)
      HudPlacedSlot(config = primaryHud, marginPx = marginPx) {
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
          modifier =
            Modifier.widthIn(max = centerHudMaxWidth)
              .fillMaxWidth()
              .graphicsLayer(
                alpha = primaryHud.opacity,
                scaleX = primaryHud.scale,
                scaleY = primaryHud.scale,
              )
              .testTag("hud_actions_row"),
        )
      }

      // 6 & 7. Fixed Sticks (dynamically positioned via LEFT_STICK / RIGHT_STICK)
      if (showFixedSticks && leftStickHud.visible) {
        HudPlacedSlot(config = leftStickHud, marginPx = marginPx) {
          VirtualStickPad(
            label = "THR / YAW",
            horizontalAxisLabel = "Yaw",
            verticalAxisLabel = "Throttle",
            autoCenterY = false,
            enabled = pilotingModeActive,
            onAxesChanged = { x, y -> viewModel.onLeftStickChanged(yaw = x, throttle = y) },
            onTouchActiveChanged = { viewModel.onManualStickTouchChanged(it) },
            size = stickSize,
            hudOverlay = true,
            tag = "left_stick_pad",
            modifier =
              Modifier.graphicsLayer(
                alpha = leftStickHud.opacity,
                scaleX = leftStickHud.scale,
                scaleY = leftStickHud.scale,
              ),
          )
        }
      }

      if (showFixedSticks && rightStickHud.visible) {
        HudPlacedSlot(config = rightStickHud, marginPx = marginPx) {
          VirtualStickPad(
            label = "ROLL / PITCH",
            horizontalAxisLabel = "Roll",
            verticalAxisLabel = "Pitch",
            autoCenterY = true,
            enabled = pilotingModeActive,
            onAxesChanged = { x, y -> viewModel.onRightStickChanged(roll = x, pitch = y) },
            onTouchActiveChanged = { viewModel.onManualStickTouchChanged(it) },
            size = stickSize,
            hudOverlay = true,
            tag = "right_stick_pad",
            modifier =
              Modifier.graphicsLayer(
                alpha = rightStickHud.opacity,
                scaleX = rightStickHud.scale,
                scaleY = rightStickHud.scale,
              ),
          )
        }
      }

      if (interactionModeState.mode == CockpitInteractionMode.HUD_EDITING) {
        HudEditorOverlay(
          layoutConfig = activeHudLayout,
          selectedElementId = selectedHudElement,
          viewportSize = viewportSize,
          canUndo = hudHistoryFlags.canUndo(isWideLayout),
          canRedo = hudHistoryFlags.canRedo(isWideLayout),
          onSelectElement = { viewModel.selectHudElement(it) },
          onBeginDrag = { viewModel.beginHudDragUndo(isWideLayout) },
          onMoveElement = { id, dx, dy -> viewModel.moveHudElement(isWideLayout, id, dx, dy) },
          onScaleElement = { id, delta -> viewModel.scaleHudElement(isWideLayout, id, delta) },
          onOpacityElement = { id, delta -> viewModel.opacityHudElement(isWideLayout, id, delta) },
          onToggleVisibility = { viewModel.toggleHudElementVisibility(isWideLayout, it) },
          onToggleLock = { viewModel.toggleHudElementLock(isWideLayout, it) },
          onUndo = { viewModel.undoHudLayout(isWideLayout) },
          onRedo = { viewModel.redoHudLayout(isWideLayout) },
          onResetDefaults = { viewModel.resetHudLayout(isWideLayout) },
          onDoneEditing = { viewModel.exitHudEditingMode() },
          modifier = Modifier.fillMaxSize(),
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
            Icon(Icons.Default.CenterFocusWeak, contentDescription = null, tint = theme.colors.accent, modifier = Modifier.size(20.dp))
            Column {
              Text("Optical-Flow Hover Assist (Exp)", style = MaterialTheme.typography.bodyMedium, color = theme.colors.textPrimary)
              Text("PD drift damping (±20% lat) with 600ms manual holdoff", style = MaterialTheme.typography.bodySmall, color = theme.colors.textSecondary)
            }
          }
          Switch(
            checked = hoverAssistEnabled,
            enabled = !caps.isVideoOnly,
            onCheckedChange = { viewModel.toggleHoverAssist(it) },
            modifier = Modifier.testTag("mode_hover_assist_switch"),
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
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null, modifier = Modifier.size(16.dp)) },
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
          FilterChip(
            selected = interactionModeState.mode == CockpitInteractionMode.HUD_EDITING,
            onClick = {
              viewModel.enterHudEditingMode()
              showFlightModesSheet = false
            },
            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp)) },
            label = { Text("Edit HUD") },
            modifier = Modifier.testTag("mode_hud_edit_button"),
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
                  videoRotationAuto = userPrefs.videoRotationAuto,
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
private fun HudPlacedSlot(
  config: HudElementConfig,
  marginPx: Int,
  content: @Composable () -> Unit,
) {
  Layout(content = content) { measurables, constraints ->
    val measurable = measurables.firstOrNull()
    if (measurable == null) {
      layout(constraints.maxWidth, constraints.maxHeight) {}
    } else {
      val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
      val placeable = measurable.measure(childConstraints)
      val xCenter = (config.normalizedX * constraints.maxWidth).roundToInt()
      val yCenter = (config.normalizedY * constraints.maxHeight).roundToInt()
      val x =
        (xCenter - placeable.width / 2).coerceIn(
          marginPx,
          (constraints.maxWidth - placeable.width - marginPx).coerceAtLeast(marginPx),
        )
      val y =
        (yCenter - placeable.height / 2).coerceIn(
          marginPx,
          (constraints.maxHeight - placeable.height - marginPx).coerceAtLeast(marginPx),
        )
      layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.placeRelative(x, y)
      }
    }
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
  fixedHeightMode: Boolean,
  isConnected: Boolean,
  activeCameraIndex: Int,
  trimActive: Boolean,
  modesActive: Boolean,
  onCycleSpeedTier: () -> Unit,
  onToggleHeadless: () -> Unit,
  onToggleLight: () -> Unit,
  onToggleFixedHeight: () -> Unit,
  onFlip360: (FlipDirection) -> Unit,
  onTiltCamera: (Int) -> Unit,
  onSwitchCamera: () -> Unit,
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

    if (caps.supportsLightControl) {
      HudOverlayMiniButton(
        label = "Light",
        enabled = isConnected,
        onClick = onToggleLight,
        icon = Icons.Default.Lightbulb,
        tag = "light_toggle_button",
      )
    }

    if (caps.supportsFixedHeightFlag) {
      HudOverlayMiniButton(
        label = "AltBit",
        enabled = isConnected,
        active = fixedHeightMode,
        onClick = onToggleFixedHeight,
        icon = Icons.Default.Height,
        tag = "alt_hold_button",
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

    if (caps.supportsCameraSwitch) {
      HudOverlayMiniButton(
        label = "Cam $activeCameraIndex",
        enabled = isConnected,
        active = activeCameraIndex == 2,
        onClick = onSwitchCamera,
        tag = "camera_switch_chip",
      )
    }

    if (caps.supportsGyroCalibration) {
      HudOverlayMiniButton(
        label = "Gyro",
        enabled = isConnected,
        onClick = onCalibrateGyro,
        tag = "calibrate_gyro_chip",
      )
    }

    if (!caps.isVideoOnly) {
      HudOverlayMiniButton(
        label = "Trim",
        active = trimActive,
        onClick = onOpenTrim,
        icon = Icons.Default.Tune,
        tag = "trim_button",
        activeColor = theme.colors.accent,
      )
    }

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
  if (caps.isVideoOnly) {
    HudTranslucentPill(
      modifier = modifier,
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier =
          Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("video_only_banner"),
      ) {
        Text(
          text = "Video-Only Profile (Flight Commands Disabled)",
          style = MaterialTheme.typography.labelMedium,
          color = theme.colors.statusWarning,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    return
  }
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
