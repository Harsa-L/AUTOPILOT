package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlightLand
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autopilot.designsystem.AutopilotButton
import com.autopilot.designsystem.AutopilotButtonTone
import com.autopilot.designsystem.GlassHudPanel
import com.autopilot.designsystem.LocalResolvedTheme
import com.autopilot.designsystem.StatusBadge
import com.autopilot.designsystem.StatusTone
import com.autopilot.designsystem.TelemetryPill
import com.autopilot.designsystem.VirtualStickPad
import com.autopilot.flight.control.FlightState
import com.autopilot.video.VideoStreamState
import com.autopilot.vision.DetectorAvailability
import com.autopilot.vision.VisionFrameResult

@Composable
fun FlightCockpitScreen(
  viewModel: AutopilotViewModel,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val flightState by viewModel.flightState.collectAsStateWithLifecycle()
  val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
  val safetyNotice by viewModel.safetyNotice.collectAsStateWithLifecycle()
  val videoState by viewModel.videoState.collectAsStateWithLifecycle()
  val visionEnabled by viewModel.visionOverlayEnabled.collectAsStateWithLifecycle()
  val visionResult by viewModel.visionResult.collectAsStateWithLifecycle()
  val detectorAvailability by viewModel.detectorAvailability.collectAsStateWithLifecycle()
  val selectedEntry by viewModel.selectedEntry.collectAsStateWithLifecycle()

  var showEmergencyDialog by remember { mutableStateOf(false) }

  val isSynthetic = selectedEntry.descriptor.isSyntheticHarness || (telemetry?.isSyntheticHarness == true)
  val caps = selectedEntry.capabilities

  BoxWithConstraints(
    modifier = modifier.fillMaxSize().background(theme.colors.canvas),
  ) {
    val isLandscape = maxWidth > maxHeight
    val stickSize = if (isLandscape) 144.dp else 152.dp

    Column(modifier = Modifier.fillMaxSize()) {
      // Explicit Synthetic Harness Warning Strip
      if (isSynthetic) {
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .background(theme.colors.statusWarning.copy(alpha = 0.22f))
              .padding(horizontal = 12.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            Icons.Default.Warning,
            contentDescription = "Isolated test harness warning",
            tint = theme.colors.statusWarning,
            modifier = Modifier.size(16.dp),
          )
          Text(
            text = "ISOLATED TEST HARNESS ACTIVE — NO REAL AIRCRAFT CONNECTED",
            style = MaterialTheme.typography.labelSmall,
            color = theme.colors.statusWarning,
            fontWeight = FontWeight.Bold,
          )
        }
      }

      // Main Video + HUD Stage
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .weight(1f)
            .background(theme.colors.canvas),
      ) {
        when (val video = videoState) {
          is VideoStreamState.ActiveFrame -> {
            Image(
              bitmap = video.bitmap.asImageBitmap(),
              contentDescription = "Live aircraft camera frame",
              contentScale = ContentScale.Fit,
              modifier = Modifier.fillMaxSize(),
            )
          }
          is VideoStreamState.WaitingForKeyframe -> {
            EmptyVideoViewport(
              title = "Waiting for Video Stream",
              detail = video.detail,
              modifier = Modifier.align(Alignment.Center),
            )
          }
          is VideoStreamState.Error -> {
            EmptyVideoViewport(
              title = "Video Stream Error",
              detail = video.message,
              modifier = Modifier.align(Alignment.Center),
            )
          }
          VideoStreamState.Idle -> {
            EmptyVideoViewport(
              title = "No Active Aircraft Stream",
              detail = "Connect to a protocol on the Dashboard or start the Isolated Test Harness.",
              modifier = Modifier.align(Alignment.Center),
            )
          }
        }

        // Vision & Optical Flow Overlay
        if (visionEnabled && visionResult != null) {
          VisionHudOverlay(
            result = visionResult!!,
            modifier = Modifier.fillMaxSize(),
          )
        }

        // Top Minimalist Telemetry Strip
        Row(
          modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(10.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.Top,
        ) {
          GlassHudPanel {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              StatusBadge(
                label = flightState.label,
                tone =
                  when (flightState) {
                    is FlightState.Ready,
                    is FlightState.Flying -> StatusTone.NOMINAL
                    is FlightState.Emergency,
                    is FlightState.Fault,
                    is FlightState.ConnectionLost -> StatusTone.EMERGENCY
                    is FlightState.Armed,
                    is FlightState.TakingOff,
                    is FlightState.Landing -> StatusTone.WARNING
                    else -> StatusTone.MUTED
                  },
              )
              Text(
                text = selectedEntry.descriptor.id.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = theme.colors.textPrimary,
              )
            }
          }

          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TelemetryPill(
              label = "TX/RX",
              value =
                telemetry?.let { "${it.packetsSent}/${it.packetsReceived}" } ?: "0/0",
            )
            TelemetryPill(
              label = "VIDEO",
              value =
                (videoState as? VideoStreamState.ActiveFrame)?.metrics?.let {
                  "${"%.0f".format(it.fps)} FPS"
                } ?: "NO SIG",
            )
            TelemetryPill(
              label = "BAT",
              value = telemetry?.batteryPercent?.let { "$it%" } ?: "N/A",
            )
            GlassHudPanel {
              Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                  onClick = { viewModel.toggleVisionOverlay(!visionEnabled) },
                  modifier = Modifier.size(48.dp).testTag("toggle_vision_overlay_button"),
                ) {
                  Icon(
                    Icons.Default.RemoveRedEye,
                    contentDescription = "Toggle computer vision HUD",
                    tint = if (visionEnabled) theme.colors.accent else theme.colors.textSecondary,
                  )
                }
                IconButton(
                  onClick = {
                    viewModel.disconnect()
                    onNavigateBack()
                  },
                  modifier = Modifier.size(48.dp).testTag("close_cockpit_button"),
                ) {
                  Icon(
                    Icons.Default.Close,
                    contentDescription = "Disconnect and return to dashboard",
                    tint = theme.colors.textPrimary,
                  )
                }
              }
            }
          }
        }

        // Vision Engine Diagnostic Badge (Bottom-Top-Left of video stage)
        if (visionEnabled) {
          GlassHudPanel(modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            val detLabel =
              when (val avail = detectorAvailability) {
                is DetectorAvailability.Ready ->
                  "YOLO26n · ${avail.acceleratorLabel} · ${"%.1f".format(visionResult?.inferenceTimeMs ?: 0f)}ms"
                is DetectorAvailability.ModelNotProvisioned ->
                  "C++20 Optical Flow Active · Import yolo26n.tflite for ML boxes"
                is DetectorAvailability.Unavailable -> avail.reason
              }
            Text(
              text = detLabel,
              style = MaterialTheme.typography.labelSmall,
              color = theme.colors.textPrimary,
            )
          }
        }

        // Safety Notice Banner
        if (safetyNotice != null) {
          GlassHudPanel(modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)) {
            Text(
              text = safetyNotice!!,
              style = MaterialTheme.typography.labelSmall,
              color = theme.colors.statusWarning,
            )
          }
        }
      }

      // Ergonomic Dual-Stick & Safety Action Deck
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
              AutopilotButton(
                text = "Takeoff",
                onClick = { viewModel.commandTakeoff() },
                enabled = caps.supportsTakeoff,
                icon = Icons.Default.FlightTakeoff,
                tag = "takeoff_button",
              )
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

            AutopilotButton(
              text = "Land",
              onClick = { viewModel.commandControlledLand() },
              enabled = caps.supportsControlledLanding && flightState != FlightState.Disconnected,
              icon = Icons.Default.FlightLand,
              tone = AutopilotButtonTone.SECONDARY,
              tag = "controlled_land_button",
            )
          }

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AutopilotButton(
              text = "Motor Stop",
              onClick = { viewModel.commandMotorStop() },
              enabled = caps.supportsMotorStop && flightState != FlightState.Disconnected,
              icon = Icons.Default.PowerSettingsNew,
              tone = AutopilotButtonTone.WARNING,
              tag = "motor_stop_button",
            )

            AutopilotButton(
              text = "E-STOP",
              onClick = { showEmergencyDialog = true },
              enabled = caps.supportsEmergencyMotorStop && flightState != FlightState.Disconnected,
              icon = Icons.Default.Warning,
              tone = AutopilotButtonTone.EMERGENCY,
              tag = "emergency_stop_button",
            )
          }
        }

        VirtualStickPad(
          label = "ROLL / PITCH",
          horizontalAxisLabel = "Roll",
          verticalAxisLabel = "Pitch",
          autoCenterY = true,
          onAxesChanged = { x, y -> viewModel.onRightStickChanged(roll = x, pitch = y) },
          size = stickSize,
          tag = "right_stick_pad",
        )
      }
    }
  }

  if (showEmergencyDialog) {
    AlertDialog(
      onDismissRequest = { showEmergencyDialog = false },
      title = { Text("Confirm Emergency Motor Cutoff") },
      text = {
        Text(
          "Emergency Stop cuts motor drive immediately and is NOT a controlled landing. Aircraft will drop from current altitude."
        )
      },
      confirmButton = {
        AutopilotButton(
          text = "CUT MOTORS NOW",
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
private fun EmptyVideoViewport(
  title: String,
  detail: String,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  Column(
    modifier = modifier.padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(
      Icons.Default.VideocamOff,
      contentDescription = null,
      tint = theme.colors.textSecondary,
      modifier = Modifier.size(40.dp),
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
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val flowColor = theme.colors.accent.copy(alpha = 0.78f)
  val boxColor = theme.colors.statusNominal

  Box(modifier = modifier) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      // C++20 Lucas-Kanade Optical Flow Grid Vectors
      result.flowVectors.forEach { vec ->
        if (vec.confidence > 0.05f) {
          val start = Offset(vec.normX * size.width, vec.normY * size.height)
          val end =
            Offset(
              (vec.normX + vec.dxNorm * 8f) * size.width,
              (vec.normY + vec.dyNorm * 8f) * size.height,
            )
          drawCircle(color = flowColor, radius = 3f, center = start)
          drawLine(color = flowColor, start = start, end = end, strokeWidth = 2f)
        }
      }

      // YOLO26n + C++20 Tracked Targets
      result.trackedTargets.forEach { target ->
        val l = target.boxNormalized.left * size.width
        val t = target.boxNormalized.top * size.height
        val w = (target.boxNormalized.right - target.boxNormalized.left) * size.width
        val h = (target.boxNormalized.bottom - target.boxNormalized.top) * size.height
        drawRect(
          color = boxColor,
          topLeft = Offset(l, t),
          size = Size(w, h),
          style = Stroke(width = 3f),
        )
      }
    }

    // Track labels with calibrated/uncertainty distance metadata
    if (result.trackedTargets.isNotEmpty()) {
      Column(
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        result.trackedTargets.take(3).forEach { target ->
          val distStr =
            if (target.estimatedDistanceMeters != null && target.uncertaintyMeters != null) {
              val calTag = if (target.isDistanceCalibrated) "CAL" else "UNCAL"
              "${"%.1f".format(target.estimatedDistanceMeters)}±${"%.1f".format(target.uncertaintyMeters)}m ($calTag)"
            } else {
              "Dist N/A"
            }
          GlassHudPanel {
            Text(
              text = "#${target.trackId} ${target.label} ${(target.confidence * 100).toInt()}% · $distStr",
              style = MaterialTheme.typography.labelSmall,
              color = theme.colors.statusNominal,
            )
          }
        }
      }
    }
  }
}
