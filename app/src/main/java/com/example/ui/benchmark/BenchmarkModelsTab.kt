package com.example.ui.benchmark

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autopilot.designsystem.AutopilotButton
import com.autopilot.designsystem.AutopilotButtonTone
import com.autopilot.designsystem.AutopilotCard
import com.autopilot.designsystem.LocalResolvedTheme
import com.autopilot.designsystem.StatusBadge
import com.autopilot.designsystem.StatusTone
import com.autopilot.vision.DetectorAvailability

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BenchmarkModelsTab(vm: VisionBenchmarkViewModel) {
  val theme = LocalResolvedTheme.current
  val context = LocalContext.current

  val models by vm.availableModels.collectAsStateWithLifecycle()
  val selectedModel by vm.selectedModel.collectAsStateWithLifecycle()
  val preferGpu by vm.preferGpu.collectAsStateWithLifecycle()
  val confidenceThreshold by vm.confidenceThreshold.collectAsStateWithLifecycle()
  val cvEnabled by vm.cvEnabled.collectAsStateWithLifecycle()
  val availability by vm.detectorAvailability.collectAsStateWithLifecycle()
  val sessions by vm.savedSessions.collectAsStateWithLifecycle()
  val comparisonResults by vm.comparisonResults.collectAsStateWithLifecycle()
  val isComparing by vm.isComparingModels.collectAsStateWithLifecycle()
  val comparisonProgress by vm.comparisonProgressText.collectAsStateWithLifecycle()

  val replayableSessions = remember(sessions) {
    sessions.filter { sess -> sess.recordedFiles.any { it.integrityPassed } }
  }
  var selectedSessionId by remember(replayableSessions) {
    mutableStateOf(replayableSessions.firstOrNull()?.sessionId)
  }

  val modelPicker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        vm.importModelUri(uri) { ok ->
          Toast.makeText(
            context,
            if (ok) "Model imported into shared Vision registry" else "Invalid .tflite file",
            Toast.LENGTH_SHORT,
          ).show()
        }
      }
    }

  val exportReportLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
      if (uri != null) {
        vm.exportComparisonJson(uri) { res ->
          Toast.makeText(
            context,
            if (res.isSuccess) "Comparison JSON exported" else "Export failed",
            Toast.LENGTH_SHORT,
          ).show()
        }
      }
    }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      AutopilotCard(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Shared LiteRT YOLO Model Registry",
            style = MaterialTheme.typography.titleSmall,
            color = theme.colors.textPrimary,
          )
          StatusBadge(
            label =
              when (availability) {
                is DetectorAvailability.Ready -> "READY"
                is DetectorAvailability.ModelNotProvisioned -> "NO WEIGHTS"
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
        Spacer(Modifier.height(6.dp))
        if (models.isEmpty()) {
          Text(
            text =
              "No .tflite YOLO models found on device. Import YOLO26n, YOLO26s, YOLO11n, or any compatible YOLO .tflite model — it is shared safely with AUTOPILOT's production Vision pipeline without copying weights.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.colors.textSecondary,
          )
        } else {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            models.forEach { spec ->
              FilterChip(
                selected = selectedModel?.id == spec.id,
                onClick = { vm.selectModel(spec) },
                label = {
                  Text(
                    "${spec.displayName} (${spec.fileSizeBytes / 1024} KB)",
                    style = MaterialTheme.typography.labelSmall,
                  )
                },
                modifier = Modifier.testTag("model_chip_${spec.id}"),
              )
            }
          }
        }
        Spacer(Modifier.height(8.dp))
        AutopilotButton(
          text = "Import .tflite Model",
          onClick = { modelPicker.launch(arrayOf("*/*")) },
          icon = Icons.Default.UploadFile,
          tone = AutopilotButtonTone.SECONDARY,
          modifier = Modifier.fillMaxWidth(),
          tag = "benchmark_import_model_button",
        )
      }
    }

    item {
      AutopilotCard(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "Production CV Execution Settings",
          style = MaterialTheme.typography.titleSmall,
          color = theme.colors.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Run YOLO + Tracking + Optical Flow",
            style = MaterialTheme.typography.bodySmall,
            color = theme.colors.textPrimary,
          )
          Switch(checked = cvEnabled, onCheckedChange = { vm.setCvEnabled(it) })
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Prefer GPU Delegate (Fallback to CPU)",
            style = MaterialTheme.typography.bodySmall,
            color = theme.colors.textPrimary,
          )
          Switch(checked = preferGpu, onCheckedChange = { vm.setPreferGpu(it) })
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = "Confidence Threshold",
            style = MaterialTheme.typography.bodySmall,
            color = theme.colors.textSecondary,
          )
          Text(
            text = "${(confidenceThreshold * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = theme.colors.textPrimary,
          )
        }
        Slider(
          value = confidenceThreshold,
          onValueChange = { vm.setConfidenceThreshold(it) },
          valueRange = 0.15f..0.85f,
        )
      }
    }

    item {
      AutopilotCard(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "Controlled Model Comparison (Same Recorded Degraded Video)",
          style = MaterialTheme.typography.titleSmall,
          color = theme.colors.textPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
          text =
            "Select a recorded session to evaluate every discovered model deterministically on the exact same degraded frames.",
          style = MaterialTheme.typography.bodySmall,
          color = theme.colors.textSecondary,
        )
        Spacer(Modifier.height(8.dp))
        if (replayableSessions.isEmpty()) {
          Text(
            text =
              "No recorded MP4 sessions available yet. Enable 'Record Degraded MP4' in Recordings, run a short capture, and stop to compare models.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.colors.statusWarning,
          )
        } else {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            replayableSessions.take(6).forEach { sess ->
              FilterChip(
                selected = selectedSessionId == sess.sessionId,
                onClick = { selectedSessionId = sess.sessionId },
                label = {
                  Text(
                    "${sess.profileName} (${sess.sessionId.takeLast(6)})",
                    style = MaterialTheme.typography.labelSmall,
                  )
                },
              )
            }
          }
          Spacer(Modifier.height(8.dp))
          val chosen = replayableSessions.firstOrNull { it.sessionId == selectedSessionId }
          AutopilotButton(
            text =
              if (isComparing) "Running Comparison..." else "Run Controlled Comparison (${models.size} models)",
            onClick = { if (chosen != null) vm.runControlledModelComparison(chosen, models) },
            enabled = !isComparing && models.isNotEmpty() && chosen != null,
            icon = Icons.Default.CompareArrows,
            tone = AutopilotButtonTone.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
            tag = "run_model_comparison_button",
          )
        }

        if (isComparing) {
          Spacer(Modifier.height(8.dp))
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (comparisonProgress != null) {
          Spacer(Modifier.height(6.dp))
          Text(
            text = comparisonProgress!!,
            style = MaterialTheme.typography.labelSmall,
            color = theme.colors.textSecondary,
          )
        }

        if (comparisonResults.isNotEmpty()) {
          Spacer(Modifier.height(10.dp))
          HorizontalDivider()
          Spacer(Modifier.height(8.dp))
          comparisonResults.forEach { entry ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(
                  text = entry.modelName,
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.SemiBold,
                  color = theme.colors.textPrimary,
                )
                Text(
                  text = "${"%.1f".format(entry.achievedFps)} FPS · ${"%.1f".format(entry.avgInferenceMs)} ms avg",
                  style = MaterialTheme.typography.labelMedium,
                  color = theme.colors.accent,
                )
              }
              Text(
                text =
                  "Frames: ${entry.framesProcessed} · Det/fr: ${"%.2f".format(entry.avgDetectionsPerFrame)} · Conf: ${(entry.avgConfidence * 100).toInt()}% · P50: ${"%.1f".format(entry.p50InferenceMs)}ms · P95: ${"%.1f".format(entry.p95InferenceMs)}ms · ${entry.statusLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = theme.colors.textSecondary,
              )
            }
            HorizontalDivider()
          }
          Spacer(Modifier.height(8.dp))
          AutopilotButton(
            text = "Export Comparison JSON",
            onClick = { exportReportLauncher.launch("vision_model_comparison.json") },
            icon = Icons.Default.Download,
            tone = AutopilotButtonTone.SECONDARY,
            modifier = Modifier.fillMaxWidth(),
            tag = "export_comparison_json_button",
          )
        }
      }
    }
  }
}
