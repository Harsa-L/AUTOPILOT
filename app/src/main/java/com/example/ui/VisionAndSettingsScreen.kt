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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.autopilot.designsystem.SectionHeader
import com.autopilot.designsystem.StatusBadge
import com.autopilot.designsystem.StatusTone
import com.autopilot.designsystem.TelemetryPill
import com.autopilot.vision.DetectorAvailability

@Composable
fun VisionAndSettingsScreen(
  viewModel: AutopilotViewModel,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val context = LocalContext.current
  val sensitivity by viewModel.sensitivity.collectAsStateWithLifecycle()
  val visionEnabled by viewModel.visionOverlayEnabled.collectAsStateWithLifecycle()
  val availability by viewModel.detectorAvailability.collectAsStateWithLifecycle()
  val prefs by viewModel.userPrefs.collectAsStateWithLifecycle()

  val modelPickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        viewModel.importYoloModelUri(uri) { ok ->
          Toast.makeText(
            context,
            if (ok) "YOLO26n LiteRT model imported and initialized"
            else "Failed to load selected .tflite model",
            Toast.LENGTH_LONG,
          ).show()
        }
      }
    }

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    item {
      SectionHeader(
        title = "Vision Engine & Flight Tuning",
        subtitle = "YOLO26n + LiteRT 2.3 + C++20 Optical Flow & Control Curves",
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
      )
    }

    // Vision Stack Card
    item {
      AutopilotCard(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(), elevated = true) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "LiteRT + YOLO26n & C++20 Vision",
              style = MaterialTheme.typography.titleMedium,
              color = theme.colors.textPrimary,
            )
            Text(
              text =
                when (val a = availability) {
                  is DetectorAvailability.Ready -> "${a.modelLabel} active on ${a.acceleratorLabel}"
                  is DetectorAvailability.ModelNotProvisioned -> a.guidance
                  is DetectorAvailability.Unavailable -> a.reason
                },
              style = MaterialTheme.typography.bodySmall,
              color = theme.colors.textSecondary,
            )
          }
          StatusBadge(
            label =
              when (availability) {
                is DetectorAvailability.Ready -> "ML READY"
                is DetectorAvailability.ModelNotProvisioned -> "FLOW ONLY"
                is DetectorAvailability.Unavailable -> "UNAVAILABLE"
              },
            tone =
              when (availability) {
                is DetectorAvailability.Ready -> StatusTone.NOMINAL
                is DetectorAvailability.ModelNotProvisioned -> StatusTone.WARNING
                is DetectorAvailability.Unavailable -> StatusTone.EMERGENCY
              },
          )
        }

        Spacer(Modifier.height(12.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Enable Real-Time Vision Overlay",
              style = MaterialTheme.typography.bodyMedium,
              color = theme.colors.textPrimary,
            )
            Text(
              text = "Runs on a bounded single-thread background queue (never blocks flight sticks)",
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
        AutopilotButton(
          text = "Import yolo26n.tflite Model",
          onClick = { modelPickerLauncher.launch(arrayOf("*/*")) },
          icon = Icons.Default.UploadFile,
          tone = AutopilotButtonTone.SECONDARY,
          modifier = Modifier.fillMaxWidth(),
          tag = "import_yolo_model_button",
        )
      }
    }

    // Monocular Distance Calibration Card
    item {
      AutopilotCard(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
        Text(
          text = "Monocular Distance Calibration & Uncertainty",
          style = MaterialTheme.typography.titleMedium,
          color = theme.colors.textPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
          text =
            "Monocular depth is an optical projection, not a laser rangefinder. Uncalibrated lenses display ±30% uncertainty; verified intrinsics tighten bounds to ±10%.",
          style = MaterialTheme.typography.bodySmall,
          color = theme.colors.textSecondary,
        )
        Spacer(Modifier.height(10.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          TelemetryPill(
            label = "Focal Length",
            value = "${prefs.calibrationFocalLengthPx.toInt()} px",
            modifier = Modifier.weight(1f),
          )
          TelemetryPill(
            label = "Calibration",
            value = if (prefs.calibrationVerified) "VERIFIED (±10%)" else "UNCALIBRATED (±30%)",
            tone = if (prefs.calibrationVerified) StatusTone.NOMINAL else StatusTone.WARNING,
            modifier = Modifier.weight(1f),
          )
        }
        Spacer(Modifier.height(8.dp))
        Slider(
          value = prefs.calibrationFocalLengthPx,
          onValueChange = {
            viewModel.updateVisionCalibration(it, prefs.calibrationVerified)
          },
          valueRange = 200f..1600f,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Mark Intrinsics Physically Calibrated",
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

    // Flight Stick Sensitivity Card
    item {
      AutopilotCard(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
        Text(
          text = "Flight Control Sensitivity & Caps",
          style = MaterialTheme.typography.titleMedium,
          color = theme.colors.textPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
          text = "Stick Deadzone: ${(sensitivity.deadzone * 100).toInt()}%",
          style = MaterialTheme.typography.labelMedium,
          color = theme.colors.textSecondary,
        )
        Slider(
          value = sensitivity.deadzone,
          onValueChange = {
            viewModel.updateSensitivity(it, sensitivity.expo, sensitivity.maxThrottleCap)
          },
          valueRange = 0f..0.20f,
        )
        Text(
          text = "Expo Curve: ${(sensitivity.expo * 100).toInt()}%",
          style = MaterialTheme.typography.labelMedium,
          color = theme.colors.textSecondary,
        )
        Slider(
          value = sensitivity.expo,
          onValueChange = {
            viewModel.updateSensitivity(sensitivity.deadzone, it, sensitivity.maxThrottleCap)
          },
          valueRange = 0f..0.80f,
        )
        Text(
          text = "Max Throttle Authority: ${(sensitivity.maxThrottleCap * 100).toInt()}%",
          style = MaterialTheme.typography.labelMedium,
          color = theme.colors.textSecondary,
        )
        Slider(
          value = sensitivity.maxThrottleCap,
          onValueChange = {
            viewModel.updateSensitivity(sensitivity.deadzone, sensitivity.expo, it)
          },
          valueRange = 0.30f..1.0f,
        )
      }
    }
  }
}
