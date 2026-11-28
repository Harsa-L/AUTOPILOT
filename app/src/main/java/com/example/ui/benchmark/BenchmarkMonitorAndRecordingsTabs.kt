package com.example.ui.benchmark

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
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
import com.autopilot.designsystem.TelemetryPill
import com.autopilot.visionbenchmark.MetricsSample
import com.autopilot.visionbenchmark.RecordedFileInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BenchmarkMonitorTab(vm: VisionBenchmarkViewModel) {
  val theme = LocalResolvedTheme.current
  val m by vm.metrics.collectAsStateWithLifecycle()

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      AutopilotCard(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "Real-Time FPS Streams",
          style = MaterialTheme.typography.titleSmall,
          color = theme.colors.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          TelemetryPill("Source", "${"%.1f".format(m.sourceFps)} fps", Modifier.weight(1f), StatusTone.INFO)
          TelemetryPill("Degraded", "${"%.1f".format(m.degradedFps)} fps", Modifier.weight(1f), StatusTone.WARNING)
          TelemetryPill("Infer", "${"%.1f".format(m.inferenceFps)} fps", Modifier.weight(1f), StatusTone.NOMINAL)
        }
        Spacer(Modifier.height(10.dp))
        FpsLineChart(
          samples = m.historySamples,
          modifier = Modifier.fillMaxWidth().height(120.dp),
        )
      }
    }

    item {
      AutopilotCard(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "Latency Percentiles & Stage Durations",
          style = MaterialTheme.typography.titleSmall,
          color = theme.colors.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          TelemetryPill("Avg", "${"%.1f".format(m.avgLatencyMs)} ms", Modifier.weight(1f), StatusTone.MUTED)
          TelemetryPill("P50", "${"%.1f".format(m.p50LatencyMs)} ms", Modifier.weight(1f), StatusTone.INFO)
          TelemetryPill("P95", "${"%.1f".format(m.p95LatencyMs)} ms", Modifier.weight(1f), StatusTone.WARNING)
          TelemetryPill("P99", "${"%.1f".format(m.p99LatencyMs)} ms", Modifier.weight(1f), StatusTone.EMERGENCY)
        }
        Spacer(Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          TelemetryPill("Degrade", "${"%.1f".format(m.transformAndDegradeMs)} ms", Modifier.weight(1f))
          TelemetryPill("Preproc", "${"%.1f".format(m.preprocessMs)} ms", Modifier.weight(1f))
          TelemetryPill("LiteRT", "${"%.1f".format(m.inferenceMs)} ms", Modifier.weight(1f))
          TelemetryPill("Track/Flow", "${"%.1f".format(m.trackingFlowMs)} ms", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        LatencyLineChart(
          samples = m.historySamples,
          modifier = Modifier.fillMaxWidth().height(110.dp),
        )
      }
    }

    item {
      AutopilotCard(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "Link Reliability & Pipeline Health",
          style = MaterialTheme.typography.titleSmall,
          color = theme.colors.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          TelemetryPill("Drops", "${m.droppedFramesCount}", Modifier.weight(1f), StatusTone.WARNING)
          TelemetryPill("Dups", "${m.duplicatedFramesCount}", Modifier.weight(1f))
          TelemetryPill("Freezes", "${m.frozenFramesCount}", Modifier.weight(1f))
          TelemetryPill("Blackout", "${m.blackoutFramesCount}", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          TelemetryPill("Jitter", "${"%.1f".format(m.frameJitterMs)} ms", Modifier.weight(1f))
          TelemetryPill("Queue", "${m.queueDepth}", Modifier.weight(1f))
          TelemetryPill("Min/Max", "${"%.0f".format(m.minLatencyMs)}/${"%.0f".format(m.maxLatencyMs)}ms", Modifier.weight(1f))
        }
      }
    }

    item {
      AutopilotCard(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "Device Resource Telemetry",
          style = MaterialTheme.typography.titleSmall,
          color = theme.colors.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          TelemetryPill("Process CPU", "${"%.1f".format(m.cpuUsagePercent)}%", Modifier.weight(1f), StatusTone.INFO)
          TelemetryPill("Memory", "${"%.0f".format(m.memoryUsageMb)} MB", Modifier.weight(1f), StatusTone.MUTED)
          TelemetryPill("Thermal", m.thermalStatusLabel, Modifier.weight(1f), StatusTone.WARNING)
        }
        Spacer(Modifier.height(6.dp))
        Text(
          text = "${m.gpuMetricsNote} · Codec: ${m.codecStatusLabel}",
          style = MaterialTheme.typography.labelSmall,
          color = theme.colors.textSecondary,
        )
      }
    }
  }
}

@Composable
private fun FpsLineChart(samples: List<MetricsSample>, modifier: Modifier = Modifier) {
  val theme = LocalResolvedTheme.current
  Box(
    modifier =
      modifier
        .clip(RoundedCornerShape(8.dp))
        .background(theme.colors.surfaceElevated)
        .padding(8.dp),
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      if (samples.size < 2) return@Canvas
      val maxY = 35f
      drawSeries(samples.map { it.sourceFps }, maxY, Color(0xFF40C4FF))
      drawSeries(samples.map { it.degradedFps }, maxY, Color(0xFFFFB74D))
      drawSeries(samples.map { it.inferenceFps }, maxY, Color(0xFF69F0AE))
    }
  }
}

@Composable
private fun LatencyLineChart(samples: List<MetricsSample>, modifier: Modifier = Modifier) {
  val theme = LocalResolvedTheme.current
  Box(
    modifier =
      modifier
        .clip(RoundedCornerShape(8.dp))
        .background(theme.colors.surfaceElevated)
        .padding(8.dp),
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      if (samples.size < 2) return@Canvas
      val maxY =
        (samples.maxOfOrNull { maxOf(it.inferenceLatencyMs, it.e2eLatencyMs) } ?: 100f)
          .coerceAtLeast(30f)
      drawSeries(samples.map { it.e2eLatencyMs }, maxY, Color(0xFFFF7043))
      drawSeries(samples.map { it.inferenceLatencyMs }, maxY, Color(0xFF00E5FF))
    }
  }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
  values: List<Float>,
  maxY: Float,
  color: Color,
) {
  if (values.size < 2) return
  val path = Path()
  val stepX = size.width / (values.size - 1).toFloat()
  values.forEachIndexed { idx, v ->
    val x = idx * stepX
    val normY = (v / maxY).coerceIn(0f, 1f)
    val y = size.height * (1f - normY)
    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
  }
  drawPath(path = path, color = color, style = Stroke(width = 3f))
}

@Composable
fun BenchmarkRecordingsTab(vm: VisionBenchmarkViewModel) {
  val theme = LocalResolvedTheme.current
  val context = LocalContext.current
  val recSel by vm.recordingSelection.collectAsStateWithLifecycle()
  val sessions by vm.savedSessions.collectAsStateWithLifecycle()

  var pendingExportFile by remember { mutableStateOf<File?>(null) }
  var previewFile by remember { mutableStateOf<RecordedFileInfo?>(null) }

  val exportMp4Launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
      val src = pendingExportFile
      pendingExportFile = null
      if (uri != null && src != null) {
        vm.exportFile(src, uri) { res ->
          Toast.makeText(
            context,
            if (res.isSuccess) "Exported ${src.name}" else "Export failed",
            Toast.LENGTH_SHORT,
          ).show()
        }
      }
    }

  val exportJsonLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
      val src = pendingExportFile
      pendingExportFile = null
      if (uri != null && src != null) {
        vm.exportFile(src, uri) { res ->
          Toast.makeText(
            context,
            if (res.isSuccess) "Exported metadata JSON" else "Export failed",
            Toast.LENGTH_SHORT,
          ).show()
        }
      }
    }

  previewFile?.let { fileInfo ->
    Mp4ScrubberDialog(
      fileInfo = fileInfo,
      onDismiss = { previewFile = null },
    )
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      AutopilotCard(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "Triple Independent MP4 Capture",
          style = MaterialTheme.typography.titleSmall,
          color = theme.colors.textPrimary,
        )
        Text(
          text =
            "Select which video streams to record simultaneously to app-scoped storage during benchmark runs.",
          style = MaterialTheme.typography.bodySmall,
          color = theme.colors.textSecondary,
        )
        Spacer(Modifier.height(6.dp))
        RecordingSwitchRow(
          label = "Record Original Camera MP4",
          checked = recSel.recordOriginal,
          tag = "rec_original_switch",
          onCheckedChange = { v -> vm.updateRecordingSelection { it.copy(recordOriginal = v) } },
        )
        RecordingSwitchRow(
          label = "Record Degraded Feed MP4",
          checked = recSel.recordDegraded,
          tag = "rec_degraded_switch",
          onCheckedChange = { v -> vm.updateRecordingSelection { it.copy(recordDegraded = v) } },
        )
        RecordingSwitchRow(
          label = "Record Annotated CV Overlay MP4",
          checked = recSel.recordAnnotated,
          tag = "rec_annotated_switch",
          onCheckedChange = { v -> vm.updateRecordingSelection { it.copy(recordAnnotated = v) } },
        )
      }
    }

    if (sessions.isEmpty()) {
      item {
        AutopilotCard(modifier = Modifier.fillMaxWidth()) {
          Text(
            text = "No Benchmark Sessions Recorded Yet",
            style = MaterialTheme.typography.titleSmall,
            color = theme.colors.textPrimary,
          )
          Spacer(Modifier.height(4.dp))
          Text(
            text =
              "Start and Stop a benchmark run to save session_metadata.json and any selected MP4 recordings here.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.colors.textSecondary,
          )
        }
      }
    } else {
      items(sessions, key = { it.sessionId }) { session ->
        AutopilotCard(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "${session.profileName} · ${session.modelName}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = theme.colors.textPrimary,
              )
              Text(
                text = "${session.createdAtIso} · ${session.cameraLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = theme.colors.textSecondary,
              )
            }
            StatusBadge(
              label = "${"%.1f".format(session.metricsSummary.inferenceFps)} FPS",
              tone = StatusTone.INFO,
            )
          }
          Spacer(Modifier.height(6.dp))
          Text(
            text =
              "Source: ${session.metricsSummary.sourceFramesCount} fr · Drops: ${session.metricsSummary.droppedFramesCount} · Avg: ${"%.1f".format(session.metricsSummary.avgLatencyMs)}ms · P95: ${"%.1f".format(session.metricsSummary.p95LatencyMs)}ms",
            style = MaterialTheme.typography.bodySmall,
            color = theme.colors.textSecondary,
          )

          session.recordedFiles.forEach { fileInfo ->
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "${fileInfo.role} (${fileInfo.width}×${fileInfo.height})",
                  style = MaterialTheme.typography.labelMedium,
                  color = theme.colors.textPrimary,
                )
                Text(
                  text =
                    "${fileInfo.frameCount} frames · ${fileInfo.fileSizeBytes / 1024} KB · ${if (fileInfo.integrityPassed) "MP4 Verified" else fileInfo.notes}",
                  style = MaterialTheme.typography.labelSmall,
                  color =
                    if (fileInfo.integrityPassed) theme.colors.statusNominal else theme.colors.statusWarning,
                )
              }
              if (fileInfo.integrityPassed) {
                AutopilotButton(
                  text = "Inspect",
                  onClick = { previewFile = fileInfo },
                  icon = Icons.Default.PlayCircle,
                  tone = AutopilotButtonTone.SECONDARY,
                  compact = true,
                )
                Spacer(Modifier.padding(horizontal = 3.dp))
                AutopilotButton(
                  text = "Save",
                  onClick = {
                    pendingExportFile = File(fileInfo.filePath)
                    exportMp4Launcher.launch("${session.sessionId}_${fileInfo.fileName}")
                  },
                  icon = Icons.Default.Download,
                  tone = AutopilotButtonTone.SECONDARY,
                  compact = true,
                )
              }
            }
          }

          Spacer(Modifier.height(10.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            AutopilotButton(
              text = "Export JSON",
              onClick = {
                val metaFile =
                  File(
                    File(File(context.filesDir, "vision_benchmark/sessions"), session.sessionId),
                    "session_metadata.json",
                  )
                pendingExportFile = metaFile
                exportJsonLauncher.launch("${session.sessionId}_metadata.json")
              },
              icon = Icons.Default.Download,
              tone = AutopilotButtonTone.SECONDARY,
              compact = true,
              modifier = Modifier.weight(1f),
            )
            AutopilotButton(
              text = "Delete",
              onClick = { vm.deleteSession(session.sessionId) },
              icon = Icons.Default.Delete,
              tone = AutopilotButtonTone.EMERGENCY,
              compact = true,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun RecordingSwitchRow(
  label: String,
  checked: Boolean,
  tag: String,
  onCheckedChange: (Boolean) -> Unit,
) {
  val theme = LocalResolvedTheme.current
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(text = label, style = MaterialTheme.typography.bodySmall, color = theme.colors.textPrimary)
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      modifier = Modifier.testTag(tag),
    )
  }
}

@Composable
private fun Mp4ScrubberDialog(
  fileInfo: RecordedFileInfo,
  onDismiss: () -> Unit,
) {
  val durationMs = fileInfo.durationMs.coerceAtLeast(1000L)
  var seekMs by remember { mutableLongStateOf(0L) }
  var previewBmp by remember { mutableStateOf<Bitmap?>(null) }

  LaunchedEffect(fileInfo.filePath, seekMs) {
    val bmp =
      withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
          retriever.setDataSource(fileInfo.filePath)
          retriever.getFrameAtTime(seekMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (_: Throwable) {
          null
        } finally {
          runCatching { retriever.release() }
        }
      }
    previewBmp = bmp
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("${fileInfo.role} Frame Inspector") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .aspectRatio(4f / 3f)
              .clip(RoundedCornerShape(8.dp))
              .background(Color.Black),
          contentAlignment = Alignment.Center,
        ) {
          val bmp = previewBmp
          if (bmp != null && !bmp.isRecycled) {
            Image(
              bitmap = bmp.asImageBitmap(),
              contentDescription = "Recorded frame preview",
              contentScale = ContentScale.Fit,
              modifier = Modifier.fillMaxSize(),
            )
          } else {
            Text("Decoding frame...", color = Color.LightGray)
          }
        }
        Text(
          text = "${seekMs} ms / ${durationMs} ms",
          style = MaterialTheme.typography.labelSmall,
        )
        Slider(
          value = seekMs.toFloat(),
          onValueChange = { seekMs = it.toLong() },
          valueRange = 0f..durationMs.toFloat(),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("Close") }
    },
  )
}
