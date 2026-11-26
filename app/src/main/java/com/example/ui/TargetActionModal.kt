package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.autopilot.designsystem.AutopilotButton
import com.autopilot.designsystem.LocalResolvedTheme
import com.autopilot.drone.protocolapi.DroneCapabilities
import com.autopilot.vision.TrackedTarget

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TargetActionModal(
  target: TrackedTarget,
  availabilities: List<TargetActionAvailability>,
  capabilities: DroneCapabilities,
  initialConfig: TargetActionConfig,
  activeTask: ActiveTargetTask?,
  onDismiss: () -> Unit,
  onEngageAction: (TargetActionType, TargetActionConfig) -> Unit,
  onStopActiveAction: () -> Unit,
  onDeselectTarget: () -> Unit,
) {
  val theme = LocalResolvedTheme.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  val firstReady =
    availabilities.firstOrNull { it.readyNow }?.action
      ?: availabilities.firstOrNull { it.supportedByDrone }?.action
      ?: TargetActionType.TRACK_TARGET

  var selectedAction by remember(target.trackId) {
    mutableStateOf(activeTask?.takeIf { it.targetId == target.trackId }?.action ?: firstReady)
  }
  var config by remember(target.trackId) { mutableStateOf(initialConfig) }

  val selectedAvailability = availabilities.firstOrNull { it.action == selectedAction }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = theme.colors.surface,
    contentColor = theme.colors.textPrimary,
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp)
          .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "${target.label.replaceFirstChar { it.uppercase() }} #${target.trackId}",
            style = MaterialTheme.typography.titleLarge,
            color = theme.colors.textPrimary,
          )
          val distText =
            target.estimatedDistanceMeters?.let { " · ~${"%.1f".format(it)} m" } ?: ""
          Text(
            text = "Confidence ${(target.confidence * 100).toInt()}%$distText",
            style = MaterialTheme.typography.bodySmall,
            color = theme.colors.textSecondary,
          )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Default.Close, contentDescription = "Close target actions")
        }
      }

      HorizontalDivider(color = theme.colors.borderSubtle)

      Text(
        text = "Target Actions",
        style = MaterialTheme.typography.titleMedium,
        color = theme.colors.textPrimary,
      )

      availabilities.forEach { item ->
        val isSelected = selectedAction == item.action
        val enabled = item.supportedByDrone && item.readyNow
        Surface(
          modifier =
            Modifier.fillMaxWidth()
              .testTag("target_action_${item.action.name.lowercase()}")
              .clickable(enabled = item.supportedByDrone) { selectedAction = item.action },
          shape = RoundedCornerShape(12.dp),
          color =
            when {
              !item.supportedByDrone -> theme.colors.canvas
              isSelected -> theme.colors.surfaceElevated
              else -> theme.colors.surface
            },
          border =
            BorderStroke(
              width = if (isSelected) 1.5.dp else 1.dp,
              color =
                when {
                  !item.supportedByDrone -> theme.colors.borderSubtle.copy(alpha = 0.4f)
                  isSelected -> theme.colors.accent
                  else -> theme.colors.borderSubtle
                },
            ),
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Icon(
              imageVector = iconForAction(item.action),
              contentDescription = null,
              tint =
                when {
                  !item.supportedByDrone -> theme.colors.textSecondary.copy(alpha = 0.45f)
                  isSelected -> theme.colors.accent
                  else -> theme.colors.textSecondary
                },
              modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = item.action.title,
                style = MaterialTheme.typography.titleMedium,
                color =
                  if (item.supportedByDrone) theme.colors.textPrimary
                  else theme.colors.textSecondary.copy(alpha = 0.6f),
              )
              Text(
                text = item.statusReason ?: item.action.summary,
                style = MaterialTheme.typography.bodySmall,
                color =
                  if (enabled || item.statusReason == null) theme.colors.textSecondary
                  else theme.colors.statusWarning,
              )
            }
          }
        }
      }

      // Contextual Advanced Parameters for the chosen action
      if (selectedAvailability?.supportedByDrone == true) {
        HorizontalDivider(color = theme.colors.borderSubtle)
        Text(
          text = "Action Parameters",
          style = MaterialTheme.typography.titleMedium,
          color = theme.colors.textPrimary,
        )

        when (selectedAction) {
          TargetActionType.FOLLOW_TARGET,
          TargetActionType.MAINTAIN_DISTANCE -> {
            ParameterSlider(
              label = "Minimum Tracking Distance",
              valueLabel = "${"%.1f".format(config.minTrackingDistanceMeters)} m",
              value = config.minTrackingDistanceMeters,
              range = 1.0f..8.0f,
              onValueChange = { config = config.copy(minTrackingDistanceMeters = it) },
            )
            ParameterSlider(
              label = "Distance Tolerance",
              valueLabel = "±${"%.2f".format(config.distanceHysteresisMeters)} m",
              value = config.distanceHysteresisMeters,
              range = 0.2f..1.5f,
              onValueChange = { config = config.copy(distanceHysteresisMeters = it) },
            )
            ParameterSlider(
              label = "Max Pitch / Yaw Authority",
              valueLabel = "${(config.maxTrackingAuthority * 100).toInt()}%",
              value = config.maxTrackingAuthority,
              range = 0.15f..0.60f,
              onValueChange = { config = config.copy(maxTrackingAuthority = it) },
            )
            if (selectedAction == TargetActionType.MAINTAIN_DISTANCE) {
              ParameterSwitch(
                label = "Keep Target Centered (Yaw)",
                checked = config.autoYawCenter,
                onCheckedChange = { config = config.copy(autoYawCenter = it) },
              )
            }
            if (capabilities.supportsCameraTilt && selectedAction == TargetActionType.FOLLOW_TARGET) {
              ParameterSwitch(
                label = "Track Vertically with Camera Tilt",
                checked = config.useCameraTiltIfSupported,
                onCheckedChange = { config = config.copy(useCameraTiltIfSupported = it) },
              )
            }
          }

          TargetActionType.CENTER_IN_VIEW -> {
            ParameterSlider(
              label = "Yaw Centering Gain",
              valueLabel = "${"%.2f".format(config.yawCenteringGain)}x",
              value = config.yawCenteringGain,
              range = 0.2f..1.0f,
              onValueChange = { config = config.copy(yawCenteringGain = it) },
            )
            ParameterSlider(
              label = "Center Deadband",
              valueLabel = "${(config.centerDeadbandNorm * 100).toInt()}%",
              value = config.centerDeadbandNorm,
              range = 0.03f..0.15f,
              onValueChange = { config = config.copy(centerDeadbandNorm = it) },
            )
            if (capabilities.supportsCameraTilt) {
              ParameterSwitch(
                label = "Adjust Vertical Camera Tilt",
                checked = config.useCameraTiltIfSupported,
                onCheckedChange = { config = config.copy(useCameraTiltIfSupported = it) },
              )
            }
          }

          TargetActionType.TRACK_TARGET -> {
            ParameterSlider(
              label = "Lost-Target Coast Timeout",
              valueLabel = "${"%.1f".format(config.lostTargetTimeoutSec)} s",
              value = config.lostTargetTimeoutSec,
              range = 0.5f..5.0f,
              onValueChange = { config = config.copy(lostTargetTimeoutSec = it) },
            )
          }

          TargetActionType.HOVER_HOLD -> Unit
        }
      }

      Spacer(Modifier.height(4.dp))
      AutopilotButton(
        text = "Engage ${selectedAction.title}",
        onClick = { onEngageAction(selectedAction, config) },
        enabled = selectedAvailability?.readyNow == true,
        modifier = Modifier.fillMaxWidth(),
        tag = "engage_target_action_button",
      )

      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        if (activeTask != null) {
          TextButton(onClick = onStopActiveAction) {
            Text("Stop Current Action", color = theme.colors.statusWarning)
          }
        }
        TextButton(onClick = onDeselectTarget) {
          Text("Deselect Target", color = theme.colors.textSecondary)
        }
      }
    }
  }
}

@Composable
private fun ParameterSlider(
  label: String,
  valueLabel: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  onValueChange: (Float) -> Unit,
) {
  val theme = LocalResolvedTheme.current
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = theme.colors.textSecondary,
        modifier = Modifier.weight(1f).padding(end = 8.dp),
      )
      Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = theme.colors.textPrimary)
    }
    Slider(value = value, onValueChange = onValueChange, valueRange = range)
  }
}

@Composable
private fun ParameterSwitch(
  label: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  val theme = LocalResolvedTheme.current
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = theme.colors.textPrimary,
      modifier = Modifier.weight(1f).padding(end = 12.dp),
    )
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

private fun iconForAction(action: TargetActionType): ImageVector =
  when (action) {
    TargetActionType.TRACK_TARGET -> Icons.Default.GpsFixed
    TargetActionType.CENTER_IN_VIEW -> Icons.Default.CenterFocusStrong
    TargetActionType.FOLLOW_TARGET -> Icons.Default.NearMe
    TargetActionType.MAINTAIN_DISTANCE -> Icons.Default.Straighten
    TargetActionType.HOVER_HOLD -> Icons.Default.PauseCircleOutline
  }
