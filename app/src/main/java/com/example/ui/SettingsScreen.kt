package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autopilot.designsystem.AutopilotButton
import com.autopilot.designsystem.AutopilotButtonTone
import com.autopilot.designsystem.AutopilotCard
import com.autopilot.designsystem.LocalResolvedTheme
import com.autopilot.designsystem.StatusBadge
import com.autopilot.designsystem.StatusTone
import com.autopilot.designsystem.ThemeAppearanceMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  viewModel: AutopilotViewModel,
  onNavigateBack: () -> Unit,
  onOpenAircraft: () -> Unit,
  onOpenFlightControls: () -> Unit,
  onOpenAppearance: () -> Unit,
  onOpenLogs: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val selectedEntry by viewModel.selectedEntry.collectAsStateWithLifecycle()
  val sensitivity by viewModel.sensitivity.collectAsStateWithLifecycle()
  val themeState by viewModel.themeState.collectAsStateWithLifecycle()
  val sessions by viewModel.flightSessions.collectAsStateWithLifecycle()

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = theme.colors.canvas,
    topBar = {
      SettingsTopBar(title = "Settings", onBack = onNavigateBack, backTag = "settings_back_button")
    },
  ) { innerPadding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      item {
        SettingsNavRow(
          icon = Icons.Default.Flight,
          title = "Aircraft & Link",
          subtitle = selectedEntry.descriptor.displayName,
          onClick = onOpenAircraft,
          tag = "settings_row_aircraft",
          modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        )
      }
      item {
        SettingsNavRow(
          icon = Icons.Default.Tune,
          title = "Flight Controls & Trim",
          subtitle =
            "Deadzone ${(sensitivity.deadzone * 100).toInt()}% · Expo ${(sensitivity.expo * 100).toInt()}% · Cap ${(sensitivity.maxThrottleCap * 100).toInt()}%",
          onClick = onOpenFlightControls,
          tag = "settings_row_controls",
          modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        )
      }
      item {
        SettingsNavRow(
          icon = Icons.Default.Palette,
          title = "Appearance & Theme",
          subtitle = "${theme.themeName} · ${themeState.appearanceMode.name.lowercase().replaceFirstChar { it.uppercase() }}",
          onClick = onOpenAppearance,
          tag = "settings_row_appearance",
          modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        )
      }
      item {
        SettingsNavRow(
          icon = Icons.Default.History,
          title = "Flight History",
          subtitle = "${sessions.size} recorded sessions",
          onClick = onOpenLogs,
          tag = "settings_row_logs",
          modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsAircraftScreen(
  viewModel: AutopilotViewModel,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val context = LocalContext.current
  val selectedEntry by viewModel.selectedEntry.collectAsStateWithLifecycle()
  val hostIp by viewModel.customHostIp.collectAsStateWithLifecycle()
  val wifiLink by viewModel.wifiLink.collectAsStateWithLifecycle()

  val requiredPermissions = remember {
    buildList {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        add(Manifest.permission.POST_NOTIFICATIONS)
      } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
      }
    }
  }

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
      if (granted.values.all { it }) {
        viewModel.bindWifiApForSelectedProtocol()
      } else {
        Toast.makeText(context, "Wi-Fi permission is needed to bind directly to the aircraft AP.", Toast.LENGTH_SHORT).show()
      }
    }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = theme.colors.canvas,
    topBar = {
      SettingsTopBar(title = "Aircraft & Link", onBack = onNavigateBack, backTag = "settings_sub_back")
    },
  ) { innerPadding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      item {
        AutopilotCard(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(), elevated = true) {
          Text(
            text = "Connection Gateway",
            style = MaterialTheme.typography.titleMedium,
            color = theme.colors.textPrimary,
          )
          Spacer(Modifier.height(8.dp))
          OutlinedTextField(
            value = hostIp,
            onValueChange = { viewModel.updateHostIp(it) },
            label = { Text("Aircraft IP Address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("host_ip_input"),
          )
          Spacer(Modifier.height(10.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            AutopilotButton(
              text = "Bind Aircraft Wi-Fi",
              onClick = {
                val allGranted =
                  requiredPermissions.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                  }
                if (allGranted) viewModel.bindWifiApForSelectedProtocol()
                else permissionLauncher.launch(requiredPermissions.toTypedArray())
              },
              icon = Icons.Default.Wifi,
              tone = AutopilotButtonTone.SECONDARY,
              modifier = Modifier.weight(1f),
              tag = "bind_drone_ap_button",
            )
            AutopilotButton(
              text = "System Wi-Fi",
              onClick = {
                context.startActivity(viewModel.wifiController.createOpenWifiSettingsIntent())
              },
              tone = AutopilotButtonTone.SECONDARY,
              tag = "open_wifi_settings_button",
            )
          }
          wifiLink.ssid?.let { ssid ->
            Spacer(Modifier.height(8.dp))
            Text(
              text = "Connected network: $ssid",
              style = MaterialTheme.typography.bodySmall,
              color = theme.colors.textSecondary,
            )
          }
        }
      }

      item {
        val caps = selectedEntry.capabilities
        AutopilotCard(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
          Text(
            text = "Supported Aircraft Controls",
            style = MaterialTheme.typography.titleMedium,
            color = theme.colors.textPrimary,
          )
          Spacer(Modifier.height(8.dp))
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            if (caps.supportsTakeoff) StatusBadge("Takeoff", StatusTone.NOMINAL)
            if (caps.supportsControlledLanding) StatusBadge("Controlled Land", StatusTone.NOMINAL)
            if (caps.supportsSpeedTiers) StatusBadge("Speed Tiers", StatusTone.NOMINAL)
            if (caps.supportsHeadlessMode) StatusBadge("Headless Mode", StatusTone.NOMINAL)
            if (caps.supportsGyroCalibration) StatusBadge("Gyro Cal", StatusTone.NOMINAL)
            if (caps.supportsCameraTilt) StatusBadge("Camera Tilt", StatusTone.NOMINAL)
            if (caps.supportsEmergencyMotorStop) StatusBadge("E-Stop", StatusTone.WARNING)
          }
        }
      }

      item {
        Text(
          text = "Compatible Aircraft Profiles",
          style = MaterialTheme.typography.titleMedium,
          color = theme.colors.textPrimary,
          modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        )
      }

      items(viewModel.productionProtocols, key = { it.descriptor.id }) { entry ->
        val desc = entry.descriptor
        val selected = desc.id == selectedEntry.descriptor.id
        Surface(
          modifier =
            Modifier.widthIn(max = 720.dp)
              .fillMaxWidth()
              .testTag("protocol_card_${desc.id}")
              .clickable { viewModel.selectProtocol(desc.id) },
          shape = RoundedCornerShape(theme.components.cardRadius),
          color = if (selected) theme.colors.surfaceElevated else theme.colors.surface,
          border =
            BorderStroke(
              width = if (selected) 1.5.dp else 1.dp,
              color = if (selected) theme.colors.accent else theme.colors.borderSubtle,
            ),
        ) {
          Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = desc.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = theme.colors.textPrimary,
              )
              Text(
                text = desc.knownModels.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = theme.colors.textSecondary,
              )
            }
            if (selected) {
              Icon(
                Icons.Default.Check,
                contentDescription = "Selected aircraft",
                tint = theme.colors.accent,
              )
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsFlightControlsScreen(
  viewModel: AutopilotViewModel,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val sensitivity by viewModel.sensitivity.collectAsStateWithLifecycle()

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = theme.colors.canvas,
    topBar = {
      SettingsTopBar(title = "Flight Controls & Trim", onBack = onNavigateBack, backTag = "settings_sub_back")
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
          Text(
            text = "Stick Response",
            style = MaterialTheme.typography.titleMedium,
            color = theme.colors.textPrimary,
          )
          Spacer(Modifier.height(10.dp))

          TuningSlider(
            label = "Stick Deadzone",
            valueLabel = "${(sensitivity.deadzone * 100).toInt()}%",
            value = sensitivity.deadzone,
            range = 0f..0.20f,
            onValueChange = {
              viewModel.updateSensitivity(it, sensitivity.expo, sensitivity.maxThrottleCap)
            },
          )
          TuningSlider(
            label = "Expo Curve",
            valueLabel = "${(sensitivity.expo * 100).toInt()}%",
            value = sensitivity.expo,
            range = 0f..0.80f,
            onValueChange = {
              viewModel.updateSensitivity(sensitivity.deadzone, it, sensitivity.maxThrottleCap)
            },
          )
          TuningSlider(
            label = "Max Throttle Authority",
            valueLabel = "${(sensitivity.maxThrottleCap * 100).toInt()}%",
            value = sensitivity.maxThrottleCap,
            range = 0.30f..1.0f,
            onValueChange = {
              viewModel.updateSensitivity(sensitivity.deadzone, sensitivity.expo, it)
            },
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
            Text(
              text = "Attitude Trim",
              style = MaterialTheme.typography.titleMedium,
              color = theme.colors.textPrimary,
            )
            AutopilotButton(
              text = "Reset Trim",
              onClick = { viewModel.updateTrim(0f, 0f) },
              tone = AutopilotButtonTone.SECONDARY,
              tag = "reset_trim_button",
            )
          }
          Spacer(Modifier.height(10.dp))
          TuningSlider(
            label = "Roll Trim",
            valueLabel = "${"%.2f".format(sensitivity.trimRoll)}",
            value = sensitivity.trimRoll,
            range = -0.25f..0.25f,
            onValueChange = { viewModel.updateTrim(it, sensitivity.trimPitch) },
          )
          TuningSlider(
            label = "Pitch Trim",
            valueLabel = "${"%.2f".format(sensitivity.trimPitch)}",
            value = sensitivity.trimPitch,
            range = -0.25f..0.25f,
            onValueChange = { viewModel.updateTrim(sensitivity.trimRoll, it) },
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsAppearanceScreen(
  viewModel: AutopilotViewModel,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val themeState by viewModel.themeState.collectAsStateWithLifecycle()

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = theme.colors.canvas,
    topBar = {
      SettingsTopBar(title = "Appearance & Theme", onBack = onNavigateBack, backTag = "settings_sub_back")
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
          Text(
            text = "Appearance Mode",
            style = MaterialTheme.typography.titleMedium,
            color = theme.colors.textPrimary,
          )
          Spacer(Modifier.height(10.dp))
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeAppearanceMode.entries.forEach { mode ->
              FilterChip(
                selected = themeState.appearanceMode == mode,
                onClick = { viewModel.setAppearanceMode(mode) },
                label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                leadingIcon =
                  if (themeState.appearanceMode == mode) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                  } else null,
                modifier = Modifier.testTag("appearance_chip_${mode.name.lowercase()}"),
              )
            }
          }
          Spacer(Modifier.height(12.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Reduce HUD Blur",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.colors.textPrimary,
              )
              Text(
                text = "Minimizes GPU compositing during extended flights",
                style = MaterialTheme.typography.bodySmall,
                color = theme.colors.textSecondary,
              )
            }
            Switch(
              checked = themeState.contextualEffects.thermalDegradeBlur,
              onCheckedChange = { viewModel.setThermalDegradeBlur(it) },
              modifier = Modifier.testTag("thermal_degrade_switch"),
            )
          }
        }
      }

      item {
        AutopilotCard(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
          Text(
            text = "Theme Presets",
            style = MaterialTheme.typography.titleMedium,
            color = theme.colors.textPrimary,
          )
          Spacer(Modifier.height(10.dp))
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            themeState.availableThemes.forEach { preset ->
              val selected = themeState.activeThemeId == preset.id
              Surface(
                modifier =
                  Modifier.fillMaxWidth()
                    .testTag("theme_preset_${preset.id}")
                    .clickable { viewModel.selectTheme(preset.id) },
                shape = RoundedCornerShape(12.dp),
                color = if (selected) theme.colors.surfaceElevated else theme.colors.canvas,
                border =
                  BorderStroke(
                    width = if (selected) 1.5.dp else 1.dp,
                    color = if (selected) theme.colors.accent else theme.colors.borderSubtle,
                  ),
              ) {
                Row(
                  modifier = Modifier.padding(14.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = theme.colors.textPrimary,
                  )
                  if (selected) {
                    Icon(
                      Icons.Default.Check,
                      contentDescription = "Active theme",
                      tint = theme.colors.accent,
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsLogsScreen(
  viewModel: AutopilotViewModel,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val context = LocalContext.current
  val sessions by viewModel.flightSessions.collectAsStateWithLifecycle()
  val timeFmt = remember { SimpleDateFormat("MMM d · HH:mm", Locale.US) }

  val exportLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
      if (uri != null) {
        viewModel.exportTelemetryToUri(uri) { ok ->
          Toast.makeText(
            context,
            if (ok) "Flight history exported" else "Export failed",
            Toast.LENGTH_SHORT,
          ).show()
        }
      }
    }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = theme.colors.canvas,
    topBar = {
      SettingsTopBar(title = "Flight History", onBack = onNavigateBack, backTag = "settings_sub_back")
    },
  ) { innerPadding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      item {
        Row(
          modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          AutopilotButton(
            text = "Export JSON",
            onClick = { exportLauncher.launch("autopilot-flight-history.json") },
            icon = Icons.Default.Download,
            tone = AutopilotButtonTone.SECONDARY,
            modifier = Modifier.weight(1f),
            tag = "export_logs_button",
          )
          AutopilotButton(
            text = "Clear",
            onClick = { viewModel.clearHistory() },
            icon = Icons.Default.DeleteOutline,
            tone = AutopilotButtonTone.SECONDARY,
            tag = "clear_logs_button",
          )
        }
      }

      if (sessions.isEmpty()) {
        item {
          AutopilotCard(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
            Text(
              text = "No flight sessions recorded yet.",
              style = MaterialTheme.typography.bodyMedium,
              color = theme.colors.textSecondary,
            )
          }
        }
      } else {
        items(sessions, key = { it.id }) { session ->
          AutopilotCard(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
            Text(
              text = session.protocolName,
              style = MaterialTheme.typography.titleMedium,
              color = theme.colors.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
              text = "${timeFmt.format(Date(session.startedAtMs))} · ${session.terminationReason}",
              style = MaterialTheme.typography.bodySmall,
              color = theme.colors.textSecondary,
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(
  title: String,
  onBack: () -> Unit,
  backTag: String,
) {
  val theme = LocalResolvedTheme.current
  TopAppBar(
    title = { Text(title) },
    navigationIcon = {
      IconButton(
        onClick = onBack,
        modifier = Modifier.size(48.dp).testTag(backTag),
      ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
      }
    },
    colors =
      TopAppBarDefaults.topAppBarColors(
        containerColor = theme.colors.surface,
        titleContentColor = theme.colors.textPrimary,
        navigationIconContentColor = theme.colors.textPrimary,
      ),
  )
}

@Composable
private fun SettingsNavRow(
  icon: ImageVector,
  title: String,
  subtitle: String,
  onClick: () -> Unit,
  tag: String,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  Surface(
    modifier = modifier.testTag(tag).clickable(onClick = onClick),
    shape = RoundedCornerShape(theme.components.cardRadius),
    color = theme.colors.surface,
    border = BorderStroke(1.dp, theme.colors.borderSubtle),
  ) {
    Row(
      modifier = Modifier.padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = theme.colors.accent,
        modifier = Modifier.size(22.dp),
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          color = theme.colors.textPrimary,
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = theme.colors.textSecondary,
        )
      }
      Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = theme.colors.textSecondary,
      )
    }
  }
}

@Composable
private fun TuningSlider(
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
      Text(label, style = MaterialTheme.typography.bodySmall, color = theme.colors.textSecondary)
      Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = theme.colors.textPrimary)
    }
    Slider(value = value, onValueChange = onValueChange, valueRange = range)
  }
}
