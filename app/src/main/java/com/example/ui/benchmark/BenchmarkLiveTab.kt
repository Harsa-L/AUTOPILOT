package com.example.ui.benchmark

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autopilot.designsystem.AutopilotCard
import com.autopilot.designsystem.LocalResolvedTheme
import com.autopilot.designsystem.StatusTone
import com.autopilot.designsystem.TelemetryPill
import com.autopilot.vision.DetectorAvailability
import com.autopilot.vision.VisionFrameResult

private enum class LivePreviewLayout(val label: String) {
  SIDE_BY_SIDE("Side by Side"),
  SPLIT_COMPARE("Split Wipe"),
  DEGRADED_CV("Degraded + CV"),
  ORIGINAL_ONLY("Original"),
}

@Composable
fun BenchmarkLiveTab(vm: VisionBenchmarkViewModel) {
  val theme = LocalResolvedTheme.current
  val origBitmap by vm.originalPreview.collectAsStateWithLifecycle()
  val degBitmap by vm.degradedPreview.collectAsStateWithLifecycle()
  val visionResult by vm.latestVisionResult.collectAsStateWithLifecycle()
  val availability by vm.detectorAvailability.collectAsStateWithLifecycle()
  val metrics by vm.metrics.collectAsStateWithLifecycle()
  val manualConfig by vm.manualConfig.collectAsStateWithLifecycle()

  var layoutMode by remember { mutableStateOf(LivePreviewLayout.SIDE_BY_SIDE) }
  var splitFraction by remember { mutableFloatStateOf(0.5f) }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        TelemetryPill(
          label = "Source FPS",
          value = "${"%.1f".format(metrics.sourceFps)}",
          modifier = Modifier.weight(1f),
          tone = StatusTone.INFO,
        )
        TelemetryPill(
          label = "Degraded FPS",
          value = "${"%.1f".format(metrics.degradedFps)}",
          modifier = Modifier.weight(1f),
          tone = StatusTone.MUTED,
        )
        TelemetryPill(
          label = "Infer FPS",
          value = "${"%.1f".format(metrics.inferenceFps)}",
          modifier = Modifier.weight(1f),
          tone = if (metrics.inferenceFps > 0f) StatusTone.NOMINAL else StatusTone.WARNING,
        )
        TelemetryPill(
          label = "Drops",
          value = "${metrics.droppedFramesCount}",
          modifier = Modifier.weight(1f),
          tone = if (metrics.droppedFramesCount > 0L) StatusTone.WARNING else StatusTone.MUTED,
        )
      }
    }

    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        LivePreviewLayout.entries.forEach { mode ->
          FilterChip(
            selected = layoutMode == mode,
            onClick = { layoutMode = mode },
            label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.testTag("live_mode_${mode.name}"),
          )
        }
      }
    }

    item {
      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val isWide = maxWidth >= 640.dp
        when (layoutMode) {
          LivePreviewLayout.SIDE_BY_SIDE -> {
            if (isWide) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                FeedCard(
                  title = "Original Camera Feed",
                  subtitle =
                    if (origBitmap != null) "${origBitmap!!.width}×${origBitmap!!.height}" else "Waiting",
                  bitmap = origBitmap,
                  visionResult = null,
                  modifier = Modifier.weight(1f),
                )
                FeedCard(
                  title = "Degraded + LiteRT Overlay",
                  subtitle =
                    if (degBitmap != null) {
                      "${degBitmap!!.width}×${degBitmap!!.height} · ${metrics.activeStageLabel}"
                    } else {
                      manualConfig.resolutionPreset.label
                    },
                  bitmap = degBitmap,
                  visionResult = visionResult,
                  modifier = Modifier.weight(1f),
                )
              }
            } else {
              Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FeedCard(
                  title = "Degraded + LiteRT Overlay",
                  subtitle =
                    if (degBitmap != null) {
                      "${degBitmap!!.width}×${degBitmap!!.height} · ${metrics.activeStageLabel}"
                    } else {
                      manualConfig.resolutionPreset.label
                    },
                  bitmap = degBitmap,
                  visionResult = visionResult,
                  modifier = Modifier.fillMaxWidth(),
                )
                FeedCard(
                  title = "Original Camera Feed",
                  subtitle =
                    if (origBitmap != null) "${origBitmap!!.width}×${origBitmap!!.height}" else "Waiting",
                  bitmap = origBitmap,
                  visionResult = null,
                  modifier = Modifier.fillMaxWidth(),
                )
              }
            }
          }

          LivePreviewLayout.SPLIT_COMPARE -> {
            AutopilotCard(modifier = Modifier.fillMaxWidth()) {
              Text(
                text = "Split Wipe Comparison (Left: Original | Right: Degraded + CV)",
                style = MaterialTheme.typography.labelMedium,
                color = theme.colors.textPrimary,
              )
              Spacer(Modifier.height(8.dp))
              Box(
                modifier =
                  Modifier.fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
              ) {
                if (degBitmap != null) {
                  AnnotatedFeedSurface(
                    bitmap = degBitmap!!,
                    visionResult = visionResult,
                    modifier = Modifier.fillMaxSize(),
                  )
                }
                if (origBitmap != null) {
                  Box(
                    modifier =
                      Modifier.fillMaxSize()
                        .clipToBounds(),
                  ) {
                    Image(
                      bitmap = origBitmap!!.asImageBitmap(),
                      contentDescription = "Original split preview",
                      contentScale = ContentScale.Fit,
                      modifier =
                        Modifier.fillMaxWidth(splitFraction.coerceIn(0.05f, 0.95f))
                          .fillMaxSize()
                          .align(Alignment.CenterStart),
                    )
                  }
                }
              }
              Spacer(Modifier.height(6.dp))
              Slider(
                value = splitFraction,
                onValueChange = { splitFraction = it },
                valueRange = 0.05f..0.95f,
              )
            }
          }

          LivePreviewLayout.DEGRADED_CV -> {
            FeedCard(
              title = "Degraded + LiteRT Overlay",
              subtitle =
                "${metrics.activeStageLabel} · ${metrics.codecStatusLabel}",
              bitmap = degBitmap,
              visionResult = visionResult,
              modifier = Modifier.fillMaxWidth(),
            )
          }

          LivePreviewLayout.ORIGINAL_ONLY -> {
            FeedCard(
              title = "Original Phone Camera Feed",
              subtitle =
                if (origBitmap != null) "${origBitmap!!.width}×${origBitmap!!.height}" else "Waiting",
              bitmap = origBitmap,
              visionResult = null,
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }
    }

    item {
      AutopilotCard(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "Pipeline & Detector Status",
          style = MaterialTheme.typography.titleSmall,
          color = theme.colors.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
          text =
            when (val a = availability) {
              is DetectorAvailability.Ready ->
                "Model: ${a.modelLabel} (${a.acceleratorLabel}) · Detections: ${metrics.detectionCount} · Avg Conf: ${(metrics.avgConfidence * 100).toInt()}%"
              is DetectorAvailability.ModelNotProvisioned ->
                "No .tflite YOLO model imported yet. Go to CV & Models tab to import YOLO26n/26s/11n."
              is DetectorAvailability.Unavailable -> "Detector unavailable: ${a.reason}"
            },
          style = MaterialTheme.typography.bodySmall,
          color = theme.colors.textSecondary,
        )
      }
    }
  }
}

@Composable
private fun FeedCard(
  title: String,
  subtitle: String,
  bitmap: Bitmap?,
  visionResult: VisionFrameResult?,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  AutopilotCard(modifier = modifier) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = theme.colors.textPrimary,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.labelSmall,
        color = theme.colors.textSecondary,
      )
    }
    Spacer(Modifier.height(8.dp))
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .aspectRatio(4f / 3f)
          .clip(RoundedCornerShape(8.dp))
          .background(Color.Black),
      contentAlignment = Alignment.Center,
    ) {
      if (bitmap != null && !bitmap.isRecycled) {
        AnnotatedFeedSurface(
          bitmap = bitmap,
          visionResult = visionResult,
          modifier = Modifier.fillMaxSize(),
        )
      } else {
        Text(
          text = "Press Start Benchmark to capture live frames",
          style = MaterialTheme.typography.bodySmall,
          color = Color.LightGray,
        )
      }
    }
  }
}

@Composable
private fun AnnotatedFeedSurface(
  bitmap: Bitmap,
  visionResult: VisionFrameResult?,
  modifier: Modifier = Modifier,
) {
  val imgBitmap = remember(bitmap) { bitmap.asImageBitmap() }
  val labelPaint = remember {
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.WHITE
      textSize = 26f
      typeface = Typeface.MONOSPACE
    }
  }
  val labelBgPaint = remember {
    Paint().apply {
      color = android.graphics.Color.argb(185, 10, 16, 28)
      style = Paint.Style.FILL
    }
  }

  Box(modifier = modifier) {
    Image(
      bitmap = imgBitmap,
      contentDescription = "Benchmark video frame",
      contentScale = ContentScale.Fit,
      modifier = Modifier.fillMaxSize(),
    )
    if (visionResult != null) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val bw = bitmap.width.toFloat().coerceAtLeast(1f)
        val bh = bitmap.height.toFloat().coerceAtLeast(1f)
        val scale = minOf(size.width / bw, size.height / bh)
        val drawnW = bw * scale
        val drawnH = bh * scale
        val offX = (size.width - drawnW) / 2f
        val offY = (size.height - drawnH) / 2f

        for (fv in visionResult.flowVectors) {
          if (fv.confidence < 0.1f) continue
          val sx = offX + fv.normX * drawnW
          val sy = offY + fv.normY * drawnH
          drawLine(
            color = Color(0xFF69F0AE),
            start = Offset(sx, sy),
            end = Offset(sx + fv.dxNorm * drawnW * 2.5f, sy + fv.dyNorm * drawnH * 2.5f),
            strokeWidth = 2f,
          )
        }

        for (t in visionResult.trackedTargets) {
          val left = offX + t.boxNormalized.left * drawnW
          val top = offY + t.boxNormalized.top * drawnH
          val right = offX + t.boxNormalized.right * drawnW
          val bottom = offY + t.boxNormalized.bottom * drawnH
          drawRect(
            color = Color(0xFF00E5FF),
            topLeft = Offset(left, top),
            size = Size((right - left).coerceAtLeast(2f), (bottom - top).coerceAtLeast(2f)),
            style = Stroke(width = 3f),
          )
          val caption = "#${t.trackId} ${t.label} ${(t.confidence * 100).toInt()}%"
          val tw = labelPaint.measureText(caption)
          val textTop = (top - 30f).coerceAtLeast(0f)
          drawContext.canvas.nativeCanvas.drawRect(
            left,
            textTop,
            (left + tw + 12f).coerceAtMost(size.width),
            textTop + 28f,
            labelBgPaint,
          )
          drawContext.canvas.nativeCanvas.drawText(caption, left + 6f, textTop + 21f, labelPaint)
        }
      }
    }
  }
}
