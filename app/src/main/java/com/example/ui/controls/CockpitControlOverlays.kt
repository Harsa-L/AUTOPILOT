package com.example.ui.controls

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.autopilot.designsystem.LocalResolvedTheme
import com.autopilot.flight.gravity.GravityCalibrationSnapshot
import com.autopilot.flight.interaction.CockpitInteractionMode
import com.autopilot.flight.interaction.PointerRouteDecision
import com.autopilot.flight.interaction.TouchOwner
import com.autopilot.flight.touch.JoystickMode
import com.autopilot.flight.touch.TouchPointPx
import com.autopilot.flight.touch.TouchZoneRuntime
import com.example.ui.hud.HudElementConfig
import com.example.ui.hud.HudElementId
import com.example.ui.hud.HudLayoutConfig
import com.example.ui.hud.HudLayoutEngine
import kotlin.math.roundToInt

/**
 * Full-viewport multitouch Adaptive Touch Control (ATC) capture overlay + floating joystick renderer.
 * Tracks pointers strictly by pointerId, displays joysticks only during active touch (or always in FIXED),
 * and yields completely when [interactionMode] != PILOTING.
 */
@Composable
fun TouchZoneOverlay(
  enabled: Boolean,
  interactionMode: CockpitInteractionMode,
  viewportSize: IntSize,
  leftRuntime: TouchZoneRuntime,
  rightRuntime: TouchZoneRuntime,
  onPickZone: (normX: Float, normY: Float) -> TouchOwner?,
  onRouteDown: (pointerId: Int, zone: TouchOwner, posPx: TouchPointPx, w: Float, h: Float) -> Unit,
  onRouteMove: (pointerId: Int, posPx: TouchPointPx) -> Unit,
  onRouteUpOrCancel: (pointerId: Int) -> Unit,
  onCancelAll: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val density = LocalDensity.current

  if (!enabled || interactionMode != CockpitInteractionMode.PILOTING) {
    return
  }

  DisposableEffect(enabled, interactionMode) {
    onDispose { onCancelAll() }
  }

  val w = viewportSize.width.coerceAtLeast(1).toFloat()
  val h = viewportSize.height.coerceAtLeast(1).toFloat()

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .testTag("atc_touch_overlay")
        .pointerInput(enabled, interactionMode, viewportSize) {
          awaitEachGesture {
            while (true) {
              val event = awaitPointerEvent()
              if (event.type == PointerEventType.Exit) continue
              event.changes.forEach { change ->
                val pid = change.id.value.toInt()
                val pos = change.position
                val pt = TouchPointPx(pos.x, pos.y)
                if (change.pressed && !change.previousPressed) {
                  val zoneOwner = onPickZone((pos.x / w).coerceIn(0f, 1f), (pos.y / h).coerceIn(0f, 1f))
                  if (zoneOwner != null && zoneOwner != TouchOwner.NONE) {
                    onRouteDown(pid, zoneOwner, pt, w, h)
                    change.consume()
                  }
                } else if (change.pressed && change.previousPressed) {
                  onRouteMove(pid, pt)
                  if (change.position != change.previousPosition) {
                    change.consume()
                  }
                } else if (!change.pressed && change.previousPressed) {
                  onRouteUpOrCancel(pid)
                  change.consume()
                }
              }
              if (event.changes.none { it.pressed }) {
                break
              }
            }
          }
        },
  ) {
    // Discreet corner guides for enabled ATC touch zones (minimal chrome at rest)
    Canvas(modifier = Modifier.fillMaxSize()) {
      listOf(leftRuntime, rightRuntime).forEach { rt ->
        if (!rt.config.enabled) return@forEach
        val b = rt.config.bounds
        val leftPx = b.xMin * size.width
        val topPx = b.yMin * size.height
        val rectW = (b.xMax - b.xMin) * size.width
        val rectH = (b.yMax - b.yMin) * size.height
        val isHeld = rt.activePointerId != null
        drawRect(
          color =
            if (isHeld) theme.colors.accent.copy(alpha = 0.24f)
            else Color.White.copy(alpha = 0.08f),
          topLeft = Offset(leftPx, topPx),
          size = Size(rectW, rectH),
          style =
            Stroke(
              width = if (isHeld) 2f else 1.25f,
              pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f)),
            ),
        )
      }

      // Draw faint rest anchor guides for FIXED/HYBRID joysticks when released
      listOf(leftRuntime, rightRuntime).forEach { rt ->
        if (rt.showRestAnchor && !rt.isVisible) {
          val restCenter =
            Offset(
              rt.config.fixedCenterNormalized.x * size.width,
              rt.config.fixedCenterNormalized.y * size.height,
            )
          val outerR = with(density) { rt.config.visualRadiusDp.dp.toPx() }
          drawCircle(
            color = theme.colors.accent.copy(alpha = 0.28f),
            radius = outerR * 0.72f,
            center = restCenter,
            style =
              Stroke(
                width = 1.6f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
              ),
          )
        }
      }

      // Draw active floating or hybrid joysticks directly under the finger anchor
      listOf(leftRuntime, rightRuntime).forEach { rt ->
        if (!rt.isVisible) return@forEach
        val anchor =
          rt.anchorPx
            ?: if (rt.config.joystickMode == JoystickMode.FIXED) {
              TouchPointPx(
                rt.config.fixedCenterNormalized.x * size.width,
                rt.config.fixedCenterNormalized.y * size.height,
              )
            } else {
              return@forEach
            }
        val thumb = rt.thumbPx ?: anchor
        val outerR = with(density) { rt.config.visualRadiusDp.dp.toPx() }
        val alpha = rt.config.opacity.coerceIn(0.3f, 1f)
        drawCircle(
          color = Color.Black.copy(alpha = 0.26f * alpha),
          radius = outerR,
          center = Offset(anchor.x, anchor.y),
        )
        drawCircle(
          color = theme.colors.accent.copy(alpha = 0.65f * alpha),
          radius = outerR,
          center = Offset(anchor.x, anchor.y),
          style = Stroke(width = 2.5f),
        )
        drawLine(
          color = Color.White.copy(alpha = 0.24f * alpha),
          start = Offset(anchor.x - outerR * 0.78f, anchor.y),
          end = Offset(anchor.x + outerR * 0.78f, anchor.y),
          strokeWidth = 1.5f,
        )
        drawLine(
          color = Color.White.copy(alpha = 0.24f * alpha),
          start = Offset(anchor.x, anchor.y - outerR * 0.78f),
          end = Offset(anchor.x, anchor.y + outerR * 0.78f),
          strokeWidth = 1.5f,
        )
        drawCircle(
          color = theme.colors.accent.copy(alpha = 0.22f * alpha),
          radius = outerR * 0.42f,
          center = Offset(thumb.x, thumb.y),
        )
        drawCircle(
          color = theme.colors.accent.copy(alpha = alpha),
          radius = outerR * 0.26f,
          center = Offset(thumb.x, thumb.y),
        )
      }
    }
  }
}

/**
 * Compact artificial-horizon tilt indicator for Gravity Mode with live calibration status
 * and stability-gated Recenter control.
 */
@Composable
fun GravityIndicator(
  tiltRollNorm: Float,
  tiltPitchNorm: Float,
  calibration: GravityCalibrationSnapshot,
  stale: Boolean,
  presetLabel: String,
  onRecenter: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  Surface(
    modifier = modifier.testTag("gravity_indicator"),
    shape = RoundedCornerShape(12.dp),
    color = Color.Black.copy(alpha = 0.44f),
    border =
      BorderStroke(
        width = 0.85.dp,
        color =
          if (stale) theme.colors.statusWarning
          else theme.colors.accent.copy(alpha = 0.65f),
      ),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Canvas(modifier = Modifier.size(30.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = (minOf(size.width, size.height) / 2f) - 2f
        drawCircle(
          color = Color.White.copy(alpha = 0.25f),
          radius = r,
          center = c,
          style = Stroke(width = 1.4f),
        )
        drawLine(
          color = Color.White.copy(alpha = 0.22f),
          start = Offset(c.x - r * 0.75f, c.y),
          end = Offset(c.x + r * 0.75f, c.y),
          strokeWidth = 1f,
        )
        drawLine(
          color = Color.White.copy(alpha = 0.22f),
          start = Offset(c.x, c.y - r * 0.75f),
          end = Offset(c.x, c.y + r * 0.75f),
          strokeWidth = 1f,
        )
        val dot =
          Offset(
            x = c.x + tiltRollNorm.coerceIn(-1f, 1f) * r * 0.75f,
            y = c.y - tiltPitchNorm.coerceIn(-1f, 1f) * r * 0.75f,
          )
        drawCircle(
          color = if (stale) theme.colors.statusWarning else theme.colors.accent,
          radius = 4.5f,
          center = dot,
        )
      }

      Column {
        Text(
          text =
            when {
              stale -> "GRAVITY STALE"
              !calibration.calibrated -> "CALIBRATING..."
              else -> "GRAVITY · $presetLabel"
            },
          style = MaterialTheme.typography.labelSmall,
          color = if (stale) theme.colors.statusWarning else Color.White,
          maxLines = 1,
        )
        Text(
          text =
            calibration.blockedReason
              ?: if (calibration.calibrated) "Ref locked · Stable" else "Hold steady",
          style = MaterialTheme.typography.labelSmall,
          color =
            if (calibration.blockedReason != null) theme.colors.statusWarning
            else Color.White.copy(alpha = 0.72f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Surface(
        modifier =
          Modifier.testTag("gravity_recenter_button")
            .defaultMinSize(minHeight = 32.dp)
            .clickable(onClick = onRecenter),
        shape = RoundedCornerShape(8.dp),
        color = theme.colors.accent.copy(alpha = 0.24f),
        border = BorderStroke(0.75.dp, theme.colors.accent.copy(alpha = 0.7f)),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
          Icon(
            Icons.Default.CenterFocusStrong,
            contentDescription = "Recenter Gravity neutral tilt",
            tint = theme.colors.accent,
            modifier = Modifier.size(13.dp),
          )
          Text("Zero", style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
      }
    }
  }
}

/**
 * Interactive HUD Editing overlay that suspends all flight sources, renders draggable viewport-normalized
 * handles for HUD elements, enforces the 30% HUD coverage metric, and persists portrait/landscape configs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HudEditorOverlay(
  layoutConfig: HudLayoutConfig,
  selectedElementId: HudElementId,
  viewportSize: IntSize,
  canUndo: Boolean = false,
  canRedo: Boolean = false,
  onSelectElement: (HudElementId) -> Unit,
  onBeginDrag: () -> Unit = {},
  onMoveElement: (HudElementId, dxNorm: Float, dyNorm: Float) -> Unit,
  onScaleElement: (HudElementId, Float) -> Unit,
  onOpacityElement: (HudElementId, Float) -> Unit,
  onToggleVisibility: (HudElementId) -> Unit,
  onToggleLock: (HudElementId) -> Unit = {},
  onUndo: () -> Unit = {},
  onRedo: () -> Unit = {},
  onResetDefaults: () -> Unit,
  onDoneEditing: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val w = viewportSize.width.coerceAtLeast(1).toFloat()
  val h = viewportSize.height.coerceAtLeast(1).toFloat()
  val selected = layoutConfig.element(selectedElementId)
  val coveragePct =
    (HudLayoutEngine.estimatePersistentCoverageFraction(layoutConfig, includeSticks = true) * 100f)
      .roundToInt()

  Box(
    modifier = modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)).testTag("hud_editor_overlay"),
  ) {
    // Draggable handle pills placed at normalized viewport coordinates
    layoutConfig.elements.forEach { el ->
      val isSelected = el.id == selectedElementId
      val cx = (el.normalizedX * w).roundToInt()
      val cy = (el.normalizedY * h).roundToInt()
      Surface(
        modifier =
          Modifier.offset { IntOffset((cx - 95).coerceIn(8, (w - 190).toInt().coerceAtLeast(8)), (cy - 22).coerceIn(8, (h - 52).toInt().coerceAtLeast(8))) }
            .testTag("hud_edit_handle_${el.id.name.lowercase()}")
            .clickable { onSelectElement(el.id) }
            .pointerInput(el.id, el.locked, viewportSize) {
              detectDragGestures(
                onDragStart = {
                  onSelectElement(el.id)
                  if (!el.locked) onBeginDrag()
                },
                onDrag = { change, dragAmount ->
                  change.consume()
                  if (!el.locked) {
                    onMoveElement(el.id, dragAmount.x / w, dragAmount.y / h)
                  }
                },
              )
            },
        shape = RoundedCornerShape(10.dp),
        color =
          if (isSelected) theme.colors.accent.copy(alpha = 0.88f)
          else Color.Black.copy(alpha = 0.68f),
        border =
          BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color =
              when {
                el.locked -> theme.colors.accent.copy(alpha = 0.85f)
                el.visible -> Color.White
                else -> theme.colors.statusWarning
              },
          ),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          if (el.locked) {
            Icon(
              Icons.Default.Lock,
              contentDescription = "Locked",
              tint = if (isSelected) theme.colors.onAccent else theme.colors.accent,
              modifier = Modifier.size(12.dp),
            )
          }
          Text(
            text = "${el.id.displayName}${if (!el.visible) " (Hidden)" else ""}",
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) theme.colors.onAccent else Color.White,
          )
        }
      }
    }

    // Center Inspector Toolbar
    Surface(
      modifier =
        Modifier.align(Alignment.Center)
          .widthIn(max = 410.dp)
          .fillMaxWidth()
          .padding(horizontal = 16.dp),
      shape = RoundedCornerShape(14.dp),
      color = theme.colors.surface.copy(alpha = 0.96f),
      border = BorderStroke(1.dp, theme.colors.accent.copy(alpha = 0.65f)),
    ) {
      Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "HUD Edit · ${if (layoutConfig.landscape) "Landscape" else "Portrait"}",
            style = MaterialTheme.typography.titleSmall,
            color = theme.colors.textPrimary,
          )
          Text(
            text = "Area ${coveragePct}% (target ≤30%)",
            style = MaterialTheme.typography.labelSmall,
            color = if (coveragePct <= 30) theme.colors.statusNominal else theme.colors.statusWarning,
          )
        }
        Text(
          text = "Selected: ${selected.id.displayName} · Scale ${(selected.scale * 100).roundToInt()}% · Alpha ${(selected.opacity * 100).roundToInt()}%${if (selected.locked) " · Locked" else ""}",
          style = MaterialTheme.typography.bodySmall,
          color = theme.colors.textSecondary,
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          IconButton(
            onClick = onUndo,
            enabled = canUndo,
            modifier = Modifier.size(40.dp).testTag("hud_edit_undo_button"),
          ) {
            Icon(
              Icons.AutoMirrored.Filled.Undo,
              contentDescription = "Undo HUD change",
              tint = if (canUndo) theme.colors.textPrimary else theme.colors.textSecondary.copy(alpha = 0.4f),
            )
          }
          IconButton(
            onClick = onRedo,
            enabled = canRedo,
            modifier = Modifier.size(40.dp).testTag("hud_edit_redo_button"),
          ) {
            Icon(
              Icons.AutoMirrored.Filled.Redo,
              contentDescription = "Redo HUD change",
              tint = if (canRedo) theme.colors.textPrimary else theme.colors.textSecondary.copy(alpha = 0.4f),
            )
          }
          TextButton(onClick = { onScaleElement(selected.id, -0.06f) }) { Text("Size −") }
          TextButton(onClick = { onScaleElement(selected.id, +0.06f) }) { Text("Size +") }
          TextButton(onClick = { onOpacityElement(selected.id, -0.10f) }) { Text("Alpha −") }
          TextButton(onClick = { onOpacityElement(selected.id, +0.10f) }) { Text("Alpha +") }
          TextButton(
            onClick = { onToggleLock(selected.id) },
            modifier = Modifier.testTag("hud_edit_lock_button"),
          ) {
            Icon(
              if (selected.locked) Icons.Default.LockOpen else Icons.Default.Lock,
              contentDescription = null,
              modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.size(3.dp))
            Text(if (selected.locked) "Unlock" else "Lock")
          }
          if (!selected.essential) {
            TextButton(onClick = { onToggleVisibility(selected.id) }) {
              Text(if (selected.visible) "Hide" else "Show")
            }
          }
          TextButton(onClick = onResetDefaults) {
            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.size(3.dp))
            Text("Reset")
          }
          Surface(
            modifier = Modifier.testTag("hud_edit_done_button").clickable(onClick = onDoneEditing),
            shape = RoundedCornerShape(8.dp),
            color = theme.colors.accent,
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Icon(Icons.Default.Check, contentDescription = null, tint = theme.colors.onAccent, modifier = Modifier.size(15.dp))
              Text("Done", style = MaterialTheme.typography.labelMedium, color = theme.colors.onAccent)
            }
          }
        }
      }
    }
  }
}
