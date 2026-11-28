package com.example.ui.benchmark

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autopilot.designsystem.AutopilotButton
import com.autopilot.designsystem.AutopilotButtonTone
import com.autopilot.designsystem.AutopilotCard
import com.autopilot.designsystem.LocalResolvedTheme
import com.autopilot.visionbenchmark.AspectMode
import com.autopilot.visionbenchmark.AutoScenarioMode
import com.autopilot.visionbenchmark.CodecType
import com.autopilot.visionbenchmark.ResolutionPreset

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BenchmarkDegradeTab(vm: VisionBenchmarkViewModel) {
  val theme = LocalResolvedTheme.current
  val cameras by vm.cameras.collectAsStateWithLifecycle()
  val cameraConfig by vm.cameraConfig.collectAsStateWithLifecycle()
  val profiles by vm.profiles.collectAsStateWithLifecycle()
  val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
  val cfg by vm.manualConfig.collectAsStateWithLifecycle()
  val autoCfg by vm.autoConfig.collectAsStateWithLifecycle()
  val supportedCodecs = remember(vm) { vm.supportedCodecs }

  var showSaveDialog by remember { mutableStateOf(false) }
  var profileNameInput by remember { mutableStateOf("") }
  var profileDescInput by remember { mutableStateOf("") }
  var seedInput by remember(autoCfg.seed) { mutableStateOf(autoCfg.seed.toString()) }
  var customWInput by remember(cfg.customWidth) { mutableStateOf(cfg.customWidth.toString()) }
  var customHInput by remember(cfg.customHeight) { mutableStateOf(cfg.customHeight.toString()) }

  if (showSaveDialog) {
    AlertDialog(
      onDismissRequest = { showSaveDialog = false },
      title = { Text("Save Custom Degradation Profile") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = profileNameInput,
            onValueChange = { profileNameInput = it },
            label = { Text("Profile Name") },
            singleLine = true,
          )
          OutlinedTextField(
            value = profileDescInput,
            onValueChange = { profileDescInput = it },
            label = { Text("Description") },
            singleLine = true,
          )
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            vm.saveCustomProfile(profileNameInput, profileDescInput)
            profileNameInput = ""
            profileDescInput = ""
            showSaveDialog = false
          }
        ) {
          Text("Save")
        }
      },
      dismissButton = {
        TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
      },
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
          text = "Phone Camera Source (Camera2)",
          style = MaterialTheme.typography.titleSmall,
          color = theme.colors.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        if (cameras.isEmpty()) {
          Text(
            text = "No Camera2 devices enumerated yet.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.colors.textSecondary,
          )
        } else {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            cameras.forEach { cam ->
              FilterChip(
                selected = cam.cameraId == cameraConfig.cameraId,
                onClick = { vm.selectCamera(cam.cameraId) },
                label = { Text(cam.displayLabel, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag("camera_chip_${cam.cameraId}"),
              )
            }
          }
          val selectedCam = cameras.firstOrNull { it.cameraId == cameraConfig.cameraId }
          if (selectedCam != null) {
            Spacer(Modifier.height(6.dp))
            Text(
              text = "Capture Resolution",
              style = MaterialTheme.typography.labelSmall,
              color = theme.colors.textSecondary,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              selectedCam.supportedResolutions.forEach { res ->
                val activeRes = "${cameraConfig.captureWidth}x${cameraConfig.captureHeight}"
                FilterChip(
                  selected = res == activeRes,
                  onClick = { vm.selectCameraResolution(res) },
                  label = { Text(res, style = MaterialTheme.typography.labelSmall) },
                )
              }
            }
          }
        }
      }
    }

    item {
      AutopilotCard(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "Degradation Profiles",
          style = MaterialTheme.typography.titleSmall,
          color = theme.colors.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          profiles.forEach { prof ->
            FilterChip(
              selected = prof.id == activeProfile.id,
              onClick = { vm.selectProfile(prof) },
              label = { Text(prof.name, style = MaterialTheme.typography.labelSmall) },
              modifier = Modifier.testTag("profile_chip_${prof.id}"),
            )
          }
        }
        Spacer(Modifier.height(6.dp))
        Text(
          text = activeProfile.notes.ifBlank { activeProfile.description },
          style = MaterialTheme.typography.bodySmall,
          color = theme.colors.textSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          AutopilotButton(
            text = "Save Custom",
            onClick = { showSaveDialog = true },
            icon = Icons.Default.Add,
            tone = AutopilotButtonTone.SECONDARY,
            compact = true,
            modifier = Modifier.weight(1f),
            tag = "save_custom_profile_button",
          )
          AutopilotButton(
            text = "Duplicate",
            onClick = { vm.duplicateProfile(activeProfile, "${activeProfile.name} Copy") },
            icon = Icons.Default.ContentCopy,
            tone = AutopilotButtonTone.SECONDARY,
            compact = true,
            modifier = Modifier.weight(1f),
            tag = "duplicate_profile_button",
          )
          if (!activeProfile.isBuiltIn) {
            AutopilotButton(
              text = "Delete",
              onClick = { vm.deleteCustomProfile(activeProfile.id) },
              icon = Icons.Default.Delete,
              tone = AutopilotButtonTone.EMERGENCY,
              compact = true,
              tag = "delete_profile_button",
            )
          }
        }
      }
    }

    item {
      AutopilotCard(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "Automatic Scenario Scheduler",
          style = MaterialTheme.typography.titleSmall,
          color = theme.colors.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          AutoScenarioMode.entries.forEach { mode ->
            FilterChip(
              selected = autoCfg.mode == mode,
              onClick = { vm.updateAutoConfig { it.copy(mode = mode) } },
              label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
              modifier = Modifier.testTag("auto_mode_${mode.name}"),
            )
          }
        }
        if (autoCfg.mode != AutoScenarioMode.MANUAL) {
          Spacer(Modifier.height(8.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedTextField(
              value = seedInput,
              onValueChange = { raw ->
                seedInput = raw.filter { it.isDigit() }
                seedInput.toLongOrNull()?.let { parsed ->
                  vm.updateAutoConfig { it.copy(seed = parsed) }
                }
              },
              label = { Text("Deterministic Seed") },
              singleLine = true,
              modifier = Modifier.weight(1f),
            )
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Cycle: ${autoCfg.cycleDurationSec}s",
                style = MaterialTheme.typography.labelSmall,
                color = theme.colors.textSecondary,
              )
              Slider(
                value = autoCfg.cycleDurationSec.toFloat(),
                onValueChange = { v -> vm.updateAutoConfig { it.copy(cycleDurationSec = v.toInt()) } },
                valueRange = 10f..120f,
              )
            }
          }
          LabeledSlider(
            label = "Stress Intensity",
            valueText = "${(autoCfg.stressIntensity * 100).toInt()}%",
            value = autoCfg.stressIntensity,
            range = 0.2f..1.0f,
            onValueChange = { v -> vm.updateAutoConfig { it.copy(stressIntensity = v) } },
          )
        }
      }
    }

    item {
      AutopilotCard(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "Resolution, Framing & Codec Loopback",
          style = MaterialTheme.typography.titleSmall,
          color = theme.colors.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          ResolutionPreset.entries.forEach { preset ->
            FilterChip(
              selected = cfg.resolutionPreset == preset,
              onClick = { vm.updateManualConfig { it.copy(resolutionPreset = preset) } },
              label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) },
            )
          }
        }
        if (cfg.resolutionPreset == ResolutionPreset.CUSTOM) {
          Spacer(Modifier.height(6.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = customWInput,
              onValueChange = { raw ->
                customWInput = raw.filter { it.isDigit() }
                customWInput.toIntOrNull()?.let { w ->
                  vm.updateManualConfig { it.copy(customWidth = w) }
                }
              },
              label = { Text("Width") },
              singleLine = true,
              modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
              value = customHInput,
              onValueChange = { raw ->
                customHInput = raw.filter { it.isDigit() }
                customHInput.toIntOrNull()?.let { h ->
                  vm.updateManualConfig { it.copy(customHeight = h) }
                }
              },
              label = { Text("Height") },
              singleLine = true,
              modifier = Modifier.weight(1f),
            )
          }
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          AspectMode.entries.forEach { mode ->
            FilterChip(
              selected = cfg.aspectMode == mode,
              onClick = { vm.updateManualConfig { it.copy(aspectMode = mode) } },
              label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
            )
          }
        }
        LabeledSlider(
          label = "Crop / Digital Zoom",
          valueText = "${"%.2f".format(cfg.cropZoom)}x",
          value = cfg.cropZoom,
          range = 1.0f..2.5f,
          onValueChange = { v -> vm.updateManualConfig { it.copy(cropZoom = v) } },
        )
        LabeledSlider(
          label = "Target FPS",
          valueText = "${cfg.targetFps} fps",
          value = cfg.targetFps.toFloat(),
          range = 5f..30f,
          onValueChange = { v -> vm.updateManualConfig { it.copy(targetFps = v.toInt()) } },
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Hardware Codec Loopback (H.264/HEVC)",
            style = MaterialTheme.typography.bodySmall,
            color = theme.colors.textPrimary,
          )
          Switch(
            checked = cfg.codecLoopbackEnabled,
            onCheckedChange = { enabled ->
              vm.updateManualConfig {
                it.copy(
                  codecLoopbackEnabled = enabled,
                  codecType =
                    if (enabled && it.codecType == CodecType.NONE) CodecType.H264_AVC
                    else it.codecType,
                )
              }
            },
          )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          CodecType.entries.forEach { ct ->
            val supported = supportedCodecs.contains(ct)
            FilterChip(
              selected = cfg.codecType == ct,
              enabled = supported || ct == CodecType.NONE,
              onClick = { vm.updateManualConfig { it.copy(codecType = ct) } },
              label = {
                Text(
                  if (supported) ct.label else "${ct.label} (N/A)",
                  style = MaterialTheme.typography.labelSmall,
                )
              },
            )
          }
        }
        LabeledSlider(
          label = "Bitrate",
          valueText = "${cfg.bitrateKbps} kbps",
          value = cfg.bitrateKbps.toFloat(),
          range = 100f..4000f,
          onValueChange = { v -> vm.updateManualConfig { it.copy(bitrateKbps = v.toInt()) } },
        )
        LabeledSlider(
          label = "Compression Macroblocking",
          valueText = "${(cfg.compressionBlockStrength * 100).toInt()}%",
          value = cfg.compressionBlockStrength,
          range = 0f..1f,
          onValueChange = { v -> vm.updateManualConfig { it.copy(compressionBlockStrength = v) } },
        )
      }
    }

    item {
      AutopilotCard(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "Optics, Sensor & Color Degradation (C++20 NDK)",
          style = MaterialTheme.typography.titleSmall,
          color = theme.colors.textPrimary,
        )
        LabeledSlider(
          label = "Gaussian Blur",
          valueText = "${"%.1f".format(cfg.gaussianBlur)} px",
          value = cfg.gaussianBlur,
          range = 0f..4.5f,
          onValueChange = { v -> vm.updateManualConfig { it.copy(gaussianBlur = v) } },
        )
        LabeledSlider(
          label = "Motion Blur",
          valueText = "${"%.1f".format(cfg.motionBlurPx)} px",
          value = cfg.motionBlurPx,
          range = 0f..22f,
          onValueChange = { v -> vm.updateManualConfig { it.copy(motionBlurPx = v) } },
        )
        LabeledSlider(
          label = "Motion Blur Angle",
          valueText = "${cfg.motionBlurAngleDeg.toInt()}°",
          value = cfg.motionBlurAngleDeg,
          range = 0f..180f,
          onValueChange = { v -> vm.updateManualConfig { it.copy(motionBlurAngleDeg = v) } },
        )
        LabeledSlider(
          label = "Sensor Noise",
          valueText = "${(cfg.noiseStrength * 100).toInt()}%",
          value = cfg.noiseStrength,
          range = 0f..0.45f,
          onValueChange = { v -> vm.updateManualConfig { it.copy(noiseStrength = v) } },
        )
        LabeledSlider(
          label = "Pixelation Block Size",
          valueText = "${cfg.pixelationBlock} px",
          value = cfg.pixelationBlock.toFloat(),
          range = 1f..12f,
          onValueChange = { v -> vm.updateManualConfig { it.copy(pixelationBlock = v.toInt()) } },
        )
        LabeledSlider(
          label = "Sharpen",
          valueText = "${"%.2f".format(cfg.sharpenAmount)}",
          value = cfg.sharpenAmount,
          range = 0f..1.5f,
          onValueChange = { v -> vm.updateManualConfig { it.copy(sharpenAmount = v) } },
        )
        LabeledSlider(
          label = "Exposure Bias",
          valueText = "${"%.1f".format(cfg.exposureEv)} EV",
          value = cfg.exposureEv,
          range = -2.5f..2.5f,
          onValueChange = { v -> vm.updateManualConfig { it.copy(exposureEv = v) } },
        )
        LabeledSlider(
          label = "Contrast",
          valueText = "${"%.2f".format(cfg.contrast)}",
          value = cfg.contrast,
          range = 0.4f..1.8f,
          onValueChange = { v -> vm.updateManualConfig { it.copy(contrast = v) } },
        )
        LabeledSlider(
          label = "Saturation",
          valueText = "${"%.2f".format(cfg.saturation)}",
          value = cfg.saturation,
          range = 0f..1.8f,
          onValueChange = { v -> vm.updateManualConfig { it.copy(saturation = v) } },
        )
        LabeledSlider(
          label = "Color Distortion",
          valueText = "${(cfg.colorDistortion * 100).toInt()}%",
          value = cfg.colorDistortion,
          range = 0f..1f,
          onValueChange = { v -> vm.updateManualConfig { it.copy(colorDistortion = v) } },
        )
        LabeledSlider(
          label = "Dynamic Range Bit Depth",
          valueText = "${cfg.dynamicRangeBits} bit",
          value = cfg.dynamicRangeBits.toFloat(),
          range = 2f..8f,
          onValueChange = { v -> vm.updateManualConfig { it.copy(dynamicRangeBits = v.toInt()) } },
        )
        LabeledSlider(
          label = "Chroma Subsampling",
          valueText = "${cfg.chromaSubsample}x",
          value = cfg.chromaSubsample.toFloat(),
          range = 1f..4f,
          onValueChange = { v -> vm.updateManualConfig { it.copy(chromaSubsample = v.toInt()) } },
        )
      }
    }

    item {
      AutopilotCard(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "RF Transmission Link Simulator",
          style = MaterialTheme.typography.titleSmall,
          color = theme.colors.textPrimary,
        )
        val tx = cfg.transmission
        LabeledSlider(
          label = "Packet Loss Rate",
          valueText = "${"%.1f".format(tx.packetLossPct)}%",
          value = tx.packetLossPct,
          range = 0f..50f,
          onValueChange = { v ->
            vm.updateManualConfig { it.copy(transmission = it.transmission.copy(packetLossPct = v)) }
          },
        )
        LabeledSlider(
          label = "Transmission Jitter",
          valueText = "${tx.jitterMs} ms",
          value = tx.jitterMs.toFloat(),
          range = 0f..250f,
          onValueChange = { v ->
            vm.updateManualConfig { it.copy(transmission = it.transmission.copy(jitterMs = v.toInt())) }
          },
        )
        LabeledSlider(
          label = "Freeze Frame Probability",
          valueText = "${"%.1f".format(tx.freezeProbabilityPct)}%",
          value = tx.freezeProbabilityPct,
          range = 0f..25f,
          onValueChange = { v ->
            vm.updateManualConfig {
              it.copy(transmission = it.transmission.copy(freezeProbabilityPct = v))
            }
          },
        )
        LabeledSlider(
          label = "Blackout Burst Probability",
          valueText = "${"%.1f".format(tx.blackoutBurstPct)}%",
          value = tx.blackoutBurstPct,
          range = 0f..15f,
          onValueChange = { v ->
            vm.updateManualConfig {
              it.copy(transmission = it.transmission.copy(blackoutBurstPct = v))
            }
          },
        )
        LabeledSlider(
          label = "Duplicate Frame Probability",
          valueText = "${"%.1f".format(tx.duplicateFramePct)}%",
          value = tx.duplicateFramePct,
          range = 0f..25f,
          onValueChange = { v ->
            vm.updateManualConfig {
              it.copy(transmission = it.transmission.copy(duplicateFramePct = v))
            }
          },
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Variable Quality Oscillation",
            style = MaterialTheme.typography.bodySmall,
            color = theme.colors.textPrimary,
          )
          Switch(
            checked = tx.qualityOscillationEnabled,
            onCheckedChange = { enabled ->
              vm.updateManualConfig {
                it.copy(transmission = it.transmission.copy(qualityOscillationEnabled = enabled))
              }
            },
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Simulated Non-Keyframe Drops (Codec)",
            style = MaterialTheme.typography.bodySmall,
            color = theme.colors.textPrimary,
          )
          Switch(
            checked = tx.keyframeDropEnabled,
            onCheckedChange = { enabled ->
              vm.updateManualConfig {
                it.copy(transmission = it.transmission.copy(keyframeDropEnabled = enabled))
              }
            },
          )
        }
      }
    }
  }
}

@Composable
private fun LabeledSlider(
  label: String,
  valueText: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  onValueChange: (Float) -> Unit,
) {
  val theme = LocalResolvedTheme.current
  Spacer(Modifier.height(4.dp))
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(text = label, style = MaterialTheme.typography.bodySmall, color = theme.colors.textSecondary)
    Text(text = valueText, style = MaterialTheme.typography.labelMedium, color = theme.colors.textPrimary)
  }
  Slider(
    value = value.coerceIn(range.start, range.endInclusive),
    onValueChange = onValueChange,
    valueRange = range,
  )
}
