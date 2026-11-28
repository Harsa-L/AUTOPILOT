package com.example.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autopilot.designsystem.AutopilotButton
import com.autopilot.designsystem.AutopilotButtonTone
import com.autopilot.designsystem.AutopilotCard
import com.autopilot.designsystem.LocalResolvedTheme
import com.autopilot.designsystem.StatusBadge
import com.autopilot.designsystem.StatusTone
import com.autopilot.vision.DetectorAvailability

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionScreen(
  viewModel: AutopilotViewModel,
  onNavigateBack: () -> Unit,
  onOpenBenchmarkStudio: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val context = LocalContext.current
  val visionEnabled by viewModel.visionOverlayEnabled.collectAsStateWithLifecycle()
  val targetSelectionEnabled by viewModel.targetSelectionEnabled.collectAsStateWithLifecycle()
  val hoverAssistEnabled by viewModel.hoverAssistEnabled.collectAsStateWithLifecycle()
  val visionResult by viewModel.visionResult.collectAsStateWithLifecycle()
  val availability by viewModel.detectorAvailability.collectAsStateWithLifecycle()
  val prefs by viewModel.userPrefs.collectAsStateWithLifecycle()
  val selectedEntry by viewModel.selectedEntry.collectAsStateWithLifecycle()
  val caps = selectedEntry.capabilities

  val modelPickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        viewModel.importYoloModelUri(uri) { ok ->
          Toast.makeText(
            context,
            if (ok) "Detection model loaded" else "Unable to load selected .tflite file",
            Toast.LENGTH_SHORT,
          ).show()
        }
      }
    }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = theme.colors.canvas,
    topBar = {
      TopAppBar(
        title = { Text("Computer Vision") },
        navigationIcon = {
          IconButton(
            onClick = onNavigateBack,
            modifier = Modifier.size(48.dp).testTag("vision_back_button"),
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Flight")
          }
        },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = theme.colors.surface,
            titleContentColor = theme.colors.textPrimary,
            navigationIconContentColor = theme.colors.textPrimary,
          ),
      )
    },
  ) { innerPadding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      item {
        AutopilotCard(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(), elevated = true) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Object Detection & Tracking",
                style = MaterialTheme.typography.titleMedium,
                color = theme.colors.textPrimary,
              )
              Text(
                text =
                  when (val a = availability) {
                    is DetectorAvailability.Ready -> "Model active (${a.acceleratorLabel})"
                    is DetectorAvailability.ModelNotProvisioned ->
                      "Import a .tflite detection model to enable object bounding boxes"
                    is DetectorAvailability.Unavailable -> a.reason
                  },
                style = MaterialTheme.typography.bodySmall,
                color = theme.colors.textSecondary,
              )
            }
            StatusBadge(
              label =
                when (availability) {
                  is DetectorAvailability.Ready -> "READY"
                  is DetectorAvailability.ModelNotProvisioned -> "NO MODEL"
                  is DetectorAvailability.Unavailable -> "OFFLINE"
                },
              tone =
                when (availability) {
                  is DetectorAvailability.Ready -> StatusTone.NOMINAL
                  is DetectorAvailability.ModelNotProvisioned -> StatusTone.WARNING
                  is DetectorAvailability.Unavailable -> StatusTone.EMERGENCY
                },
            )
          }

          Spacer(Modifier.height(14.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Enable Vision Overlay",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.colors.textPrimary,
              )
              Text(
                text = "Display detected targets and motion vectors on the flight feed",
                style = MaterialTheme.typography.bodySmall,
                color = theme.colors.textSecondary,
              )
            }
            Switch(
              checked = visionEnabled,
              onCheckedChange = { viewModel.toggleVisionOverlay(it) },
              modifier = Modifier.testTag("vision_overlay_switch"),
            )
          }

          Spacer(Modifier.height(10.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Interactive Target Selection",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.colors.textPrimary,
              )
              Text(
                text = "Tap an object on the video feed to select; long-press for flight actions",
                style = MaterialTheme.typography.bodySmall,
                color = theme.colors.textSecondary,
              )
            }
            Switch(
              checked = targetSelectionEnabled,
              onCheckedChange = { viewModel.toggleTargetSelection(it) },
              modifier = Modifier.testTag("vision_target_select_switch"),
            )
          }

          Spacer(Modifier.height(10.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
              Text(
                text = "Optical-Flow Hover Assist (Exp)",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.colors.textPrimary,
              )
              val flowCount = visionResult?.validFlowCount ?: 0
              Text(
                text =
                  if (hoverAssistEnabled) {
                    "Active while flying (±20% lat / ±15% yaw · 600ms manual holdoff · $flowCount vec)"
                  } else {
                    "Visual PD drift damping using Lucas-Kanade optical flow (yields immediately to pilot)"
                  },
                style = MaterialTheme.typography.bodySmall,
                color = theme.colors.textSecondary,
              )
            }
            Switch(
              checked = hoverAssistEnabled,
              enabled = !caps.isVideoOnly,
              onCheckedChange = { viewModel.toggleHoverAssist(it) },
              modifier = Modifier.testTag("vision_hover_assist_switch"),
            )
          }

          Spacer(Modifier.height(12.dp))
          AutopilotButton(
            text = "Import Detection Model (.tflite)",
            onClick = { modelPickerLauncher.launch(arrayOf("*/*")) },
            icon = Icons.Default.UploadFile,
            tone = AutopilotButtonTone.SECONDARY,
            modifier = Modifier.fillMaxWidth(),
            tag = "import_yolo_model_button",
          )
        }
      }

      item {
        AutopilotCard(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
              Text(
                text = "Vision Benchmark Studio",
                style = MaterialTheme.typography.titleMedium,
                color = theme.colors.textPrimary,
              )
              Text(
                text =
                  "Test LiteRT YOLO models offline using your phone camera with configurable video degradation, telemetry, and triple MP4 capture. Completely isolated from flight control.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.colors.textSecondary,
              )
            }
            StatusBadge(label = "ISOLATED", tone = StatusTone.INFO)
          }
          Spacer(Modifier.height(12.dp))
          AutopilotButton(
            text = "Open Vision Benchmark Studio",
            onClick = onOpenBenchmarkStudio,
            icon = Icons.Default.Science,
            tone = AutopilotButtonTone.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
            tag = "open_vision_benchmark_button",
          )
        }
      }

      item {
        AutopilotCard(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
          Text(
            text = "Tracking & Standoff Defaults",
            style = MaterialTheme.typography.titleMedium,
            color = theme.colors.textPrimary,
          )
          Spacer(Modifier.height(10.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = "Minimum Tracking Distance",
              style = MaterialTheme.typography.bodySmall,
              color = theme.colors.textSecondary,
            )
            Text(
              text = "${"%.1f".format(prefs.visionDefaultMinDistanceM)} m",
              style = MaterialTheme.typography.labelMedium,
              color = theme.colors.textPrimary,
            )
          }
          Slider(
            value = prefs.visionDefaultMinDistanceM,
            onValueChange = {
              viewModel.updateVisionTrackingDefaults(it, prefs.visionTrackingAuthority)
            },
            valueRange = 1.0f..8.0f,
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = "Tracking Stick Authority",
              style = MaterialTheme.typography.bodySmall,
              color = theme.colors.textSecondary,
            )
            Text(
              text = "${(prefs.visionTrackingAuthority * 100).toInt()}%",
              style = MaterialTheme.typography.labelMedium,
              color = theme.colors.textPrimary,
            )
          }
          Slider(
            value = prefs.visionTrackingAuthority,
            onValueChange = {
              viewModel.updateVisionTrackingDefaults(prefs.visionDefaultMinDistanceM, it)
            },
            valueRange = 0.15f..0.60f,
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = "Detection Confidence",
              style = MaterialTheme.typography.bodySmall,
              color = theme.colors.textSecondary,
            )
            Text(
              text = "${(prefs.visionConfidenceThreshold * 100).toInt()}%",
              style = MaterialTheme.typography.labelMedium,
              color = theme.colors.textPrimary,
            )
          }
          Slider(
            value = prefs.visionConfidenceThreshold,
            onValueChange = { viewModel.updateVisionConfidence(it) },
            valueRange = 0.20f..0.85f,
          )
        }
      }

      item {
        AutopilotCard(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
          Text(
            text = "Camera Distance Calibration",
            style = MaterialTheme.typography.titleMedium,
            color = theme.colors.textPrimary,
          )
          Spacer(Modifier.height(6.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = "Lens Focal Length",
              style = MaterialTheme.typography.bodySmall,
              color = theme.colors.textSecondary,
            )
            Text(
              text = "${prefs.calibrationFocalLengthPx.toInt()} px",
              style = MaterialTheme.typography.labelMedium,
              color = theme.colors.textPrimary,
            )
          }
          Slider(
            value = prefs.calibrationFocalLengthPx,
            onValueChange = { viewModel.updateVisionCalibration(it, prefs.calibrationVerified) },
            valueRange = 200f..1600f,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "Calibrated Intrinsics",
              style = MaterialTheme.typography.bodyMedium,
              color = theme.colors.textPrimary,
            )
            Switch(
              checked = prefs.calibrationVerified,
              onCheckedChange = {
                viewModel.updateVisionCalibration(prefs.calibrationFocalLengthPx, it)
              },
              modifier = Modifier.testTag("calibration_verified_switch"),
            )
          }
        }
      }

      item {
        AutopilotCard(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
          Text(
            text = "Supported Vision Actions (${selectedEntry.descriptor.displayName})",
            style = MaterialTheme.typography.titleMedium,
            color = theme.colors.textPrimary,
          )
          Spacer(Modifier.height(8.dp))
          CapabilityRow("Visual Target Lock", caps.supportsVideoStream)
          CapabilityRow("Horizontal Yaw Centering & Follow", caps.supportsVideoStream)
          CapabilityRow("Distance Maintenance (Pitch)", caps.supportsVideoStream)
          CapabilityRow("Vertical Camera Tilt Tracking", caps.supportsCameraTilt)
          CapabilityRow("Autonomous Position / Altitude Hold", caps.supportsAltitudeHold || caps.reportsGps)
        }
      }
    }
  }
}

@Composable
private fun CapabilityRow(label: String, supported: Boolean) {
  val theme = LocalResolvedTheme.current
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = theme.colors.textPrimary,
      modifier = Modifier.weight(1f).padding(end = 12.dp),
    )
    Text(
      text = if (supported) "Supported" else "Unsupported",
      style = MaterialTheme.typography.labelSmall,
      color = if (supported) theme.colors.accent else theme.colors.textSecondary,
    )
  }
}
