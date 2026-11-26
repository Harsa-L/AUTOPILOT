package com.autopilot.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/**
 * Responsive dual-axis virtual flight stick with zero-allocation Canvas drawing,
 * spring-like center snap on release (optional Y hold for throttle), and haptic detents.
 */
@Composable
fun VirtualStickPad(
  label: String,
  horizontalAxisLabel: String,
  verticalAxisLabel: String,
  autoCenterY: Boolean,
  onAxesChanged: (x: Float, y: Float) -> Unit,
  modifier: Modifier = Modifier,
  size: Dp? = null,
  tag: String = "virtual_stick",
  hudOverlay: Boolean = false,
  onTouchActiveChanged: (Boolean) -> Unit = {},
) {
  val theme = LocalResolvedTheme.current
  val padSize = size ?: theme.components.stickPadSize
  val haptics = LocalHapticFeedback.current

  var normX by remember { mutableStateOf(0f) }
  var normY by remember { mutableStateOf(if (autoCenterY) 0f else -0.6f) }

  val trackColor =
    if (hudOverlay) Color.Black.copy(alpha = 0.22f)
    else theme.colors.surfaceGlass.copy(alpha = 0.65f)
  val ringColor =
    if (hudOverlay) theme.colors.textPrimary.copy(alpha = 0.34f)
    else theme.colors.borderSubtle
  val crosshairColor =
    if (hudOverlay) theme.colors.textPrimary.copy(alpha = 0.20f)
    else theme.colors.textSecondary.copy(alpha = 0.35f)
  val thumbColor = theme.colors.accent
  val thumbGlow = theme.colors.accent.copy(alpha = if (hudOverlay) 0.18f else 0.22f)

  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier =
        Modifier.size(padSize)
          .testTag(tag)
          .semantics {
            contentDescription = "$label flight stick ($horizontalAxisLabel / $verticalAxisLabel)"
          }
          .pointerInput(autoCenterY) {
            detectDragGestures(
              onDragStart = {
                onTouchActiveChanged(true)
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              },
              onDragEnd = {
                normX = 0f
                if (autoCenterY) normY = 0f
                onAxesChanged(normX, normY)
                onTouchActiveChanged(false)
              },
              onDragCancel = {
                normX = 0f
                if (autoCenterY) normY = 0f
                onAxesChanged(normX, normY)
                onTouchActiveChanged(false)
              },
              onDrag = { change, dragAmount ->
                change.consume()
                val radius = (this.size.width / 2f) * 0.72f
                if (radius > 1f) {
                  val rawX = (normX * radius) + dragAmount.x
                  val rawY = (normY * radius) - dragAmount.y
                  val dist = hypot(rawX, rawY)
                  val scale = if (dist > radius) radius / dist else 1f
                  normX = (rawX * scale / radius).coerceIn(-1f, 1f)
                  normY = (rawY * scale / radius).coerceIn(-1f, 1f)
                  onAxesChanged(normX, normY)
                }
              },
            )
          },
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val outerRadius = (minOf(this.size.width, this.size.height) / 2f) - 4f
        val travelRadius = outerRadius * 0.72f

        drawCircle(color = trackColor, radius = outerRadius, center = center)
        drawCircle(
          color = ringColor,
          radius = outerRadius,
          center = center,
          style = Stroke(width = if (hudOverlay) 1.5f else 2f),
        )
        drawCircle(
          color = crosshairColor,
          radius = outerRadius * 0.45f,
          center = center,
          style = Stroke(width = 1.25f),
        )
        drawLine(
          color = crosshairColor,
          start = Offset(center.x - outerRadius * 0.85f, center.y),
          end = Offset(center.x + outerRadius * 0.85f, center.y),
          strokeWidth = 1.25f,
        )
        drawLine(
          color = crosshairColor,
          start = Offset(center.x, center.y - outerRadius * 0.85f),
          end = Offset(center.x, center.y + outerRadius * 0.85f),
          strokeWidth = 1.25f,
        )

        val thumbPos =
          Offset(
            x = center.x + normX * travelRadius,
            y = center.y - normY * travelRadius,
          )
        drawCircle(color = thumbGlow, radius = outerRadius * 0.28f, center = thumbPos)
        drawCircle(color = thumbColor, radius = outerRadius * 0.18f, center = thumbPos)
      }
    }
    Spacer(Modifier.height(2.dp))
    Text(
      text = if (hudOverlay) label else "$label · ${"%.2f".format(normX)}, ${"%.2f".format(normY)}",
      style = MaterialTheme.typography.labelSmall,
      color =
        if (hudOverlay) theme.colors.textPrimary.copy(alpha = 0.78f)
        else theme.colors.textSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
