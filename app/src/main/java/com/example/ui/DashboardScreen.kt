package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autopilot.core.common.VerificationStatus
import com.autopilot.designsystem.AutopilotButton
import com.autopilot.designsystem.AutopilotButtonTone
import com.autopilot.designsystem.AutopilotCard
import com.autopilot.designsystem.LocalResolvedTheme
import com.autopilot.designsystem.SectionHeader
import com.autopilot.designsystem.StatusBadge
import com.autopilot.designsystem.StatusTone
import com.autopilot.designsystem.TelemetryPill
import com.autopilot.drone.protocolapi.ProtocolRegistryEntry
import com.autopilot.flight.control.FlightState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
  viewModel: AutopilotViewModel,
  onNavigateToFlight: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val context = LocalContext.current
  val wifiLink by viewModel.wifiLink.collectAsStateWithLifecycle()
  val selectedEntry by viewModel.selectedEntry.collectAsStateWithLifecycle()
  val hostIp by viewModel.customHostIp.collectAsStateWithLifecycle()
  val flightState by viewModel.flightState.collectAsStateWithLifecycle()

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

  var permissionsGranted by remember {
    mutableStateOf(
      requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
      }
    )
  }

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
      permissionsGranted = result.values.all { it }
      viewModel.refreshWifi()
    }

  LazyColumn(
    modifier = modifier.fillMaxSize().testTag("dashboard_list"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    item {
      Column(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          SectionHeader(
            title = "AUTOPILOT Flight Console",
            subtitle = "Native C++20 protocol engine · TurboDrone compatibility registry",
            modifier = Modifier.weight(1f),
          )
          StatusBadge(
            label = flightState.label,
            tone =
              when (flightState) {
                is FlightState.Ready,
                is FlightState.Connected,
                is FlightState.Flying -> StatusTone.NOMINAL
                is FlightState.Emergency,
                is FlightState.Fault,
                is FlightState.ConnectionLost -> StatusTone.EMERGENCY
                is FlightState.Armed,
                is FlightState.TakingOff,
                is FlightState.Landing -> StatusTone.WARNING
                else -> StatusTone.MUTED
              },
          )
        }
      }
    }

    // Connectivity & Permissions Card
    item {
      AutopilotCard(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Icon(
            Icons.Default.Wifi,
            contentDescription = "Wi-Fi link status",
            tint = theme.colors.accent,
            modifier = Modifier.size(22.dp),
          )
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Aircraft Wi-Fi Link & API ${Build.VERSION.SDK_INT} Routing",
              style = MaterialTheme.typography.titleMedium,
              color = theme.colors.textPrimary,
            )
            Text(
              text = wifiLink.apiStrategy,
              style = MaterialTheme.typography.labelSmall,
              color = theme.colors.textSecondary,
            )
          }
          StatusBadge(
            label = if (wifiLink.isWifiConnected) "WIFI ACTIVE" else "NO AP",
            tone = if (wifiLink.isWifiConnected) StatusTone.NOMINAL else StatusTone.WARNING,
          )
        }

        Spacer(Modifier.height(10.dp))
        Text(
          text = wifiLink.statusMessage,
          style = MaterialTheme.typography.bodySmall,
          color = theme.colors.textSecondary,
        )

        Spacer(Modifier.height(10.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          TelemetryPill(
            label = "SSID",
            value = wifiLink.ssid ?: "Unavailable",
            modifier = Modifier.weight(1f),
          )
          TelemetryPill(
            label = "RSSI",
            value = wifiLink.rssiDbm?.let { "$it dBm" } ?: "N/A",
            modifier = Modifier.weight(1f),
          )
          TelemetryPill(
            label = "Net Handle",
            value = wifiLink.boundNetworkHandle?.toString() ?: "Unbound",
            modifier = Modifier.weight(1f),
          )
        }

        Spacer(Modifier.height(12.dp))
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (!permissionsGranted) {
            AutopilotButton(
              text = "Grant Wi-Fi Permissions",
              onClick = { permissionLauncher.launch(requiredPermissions.toTypedArray()) },
              icon = Icons.Default.Security,
              tone = AutopilotButtonTone.WARNING,
              tag = "grant_wifi_permissions_button",
            )
          }
          AutopilotButton(
            text = "Bind Drone AP",
            onClick = { viewModel.bindWifiApForSelectedProtocol() },
            icon = Icons.Default.Wifi,
            tone = AutopilotButtonTone.SECONDARY,
            tag = "bind_drone_ap_button",
          )
          AutopilotButton(
            text = "Wi-Fi Settings",
            onClick = { context.startActivity(viewModel.wifiController.createOpenWifiSettingsIntent()) },
            icon = Icons.Default.Settings,
            tone = AutopilotButtonTone.SECONDARY,
            tag = "open_wifi_settings_button",
          )
        }
      }
    }

    // Selected Protocol Launch Card
    item {
      val desc = selectedEntry.descriptor
      val caps = selectedEntry.capabilities
      AutopilotCard(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
        elevated = true,
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = desc.displayName,
              style = MaterialTheme.typography.titleLarge,
              color = theme.colors.textPrimary,
            )
            Text(
              text = "${desc.companionAppFamily} · ${desc.controlRateHz} Hz · ${desc.videoTransport}",
              style = MaterialTheme.typography.bodySmall,
              color = theme.colors.textSecondary,
            )
          }
          StatusBadge(
            label = desc.verificationStatus.name,
            tone =
              when (desc.verificationStatus) {
                VerificationStatus.PHYSICALLY_VERIFIED -> StatusTone.NOMINAL
                VerificationStatus.IMPLEMENTED,
                VerificationStatus.TESTED -> StatusTone.INFO
                VerificationStatus.EXPERIMENTAL -> StatusTone.WARNING
                else -> StatusTone.MUTED
              },
          )
        }

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
          value = hostIp,
          onValueChange = { viewModel.updateHostIp(it) },
          label = { Text("Aircraft Gateway IP") },
          supportingText = {
            Text(
              "Control :${desc.controlPort} · Video :${desc.videoPort}" +
                (desc.heartbeatPort?.let { " · Heartbeat :$it" } ?: "")
            )
          },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("host_ip_input"),
        )

        Spacer(Modifier.height(8.dp))
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          StatusBadge(
            label = if (caps.supportsControlledLanding) "Controlled Land" else "No Land Cmd",
            tone = if (caps.supportsControlledLanding) StatusTone.NOMINAL else StatusTone.MUTED,
          )
          StatusBadge(
            label = if (caps.supportsEmergencyMotorStop) "E-Stop" else "No E-Stop",
            tone = if (caps.supportsEmergencyMotorStop) StatusTone.WARNING else StatusTone.MUTED,
          )
          StatusBadge(
            label = if (caps.reportsBatteryPercent) "Battery Telemetry" else "Battery: N/A",
            tone = if (caps.reportsBatteryPercent) StatusTone.NOMINAL else StatusTone.MUTED,
          )
          StatusBadge(
            label = if (caps.reportsGps) "GPS" else "No GPS / No RTH",
            tone = StatusTone.MUTED,
          )
        }

        Spacer(Modifier.height(10.dp))
        desc.knownLimitations.forEach { limit ->
          Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(vertical = 2.dp),
          ) {
            Icon(
              Icons.Outlined.Info,
              contentDescription = null,
              tint = theme.colors.textSecondary,
              modifier = Modifier.size(15.dp).padding(top = 2.dp),
            )
            Text(
              text = limit,
              style = MaterialTheme.typography.bodySmall,
              color = theme.colors.textSecondary,
            )
          }
        }

        Spacer(Modifier.height(14.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          AutopilotButton(
            text = if (flightState == FlightState.Disconnected) "Connect & Open HUD" else "Open Flight HUD",
            onClick = {
              if (flightState == FlightState.Disconnected) {
                viewModel.connectSelectedProtocol(useIsolatedSyntheticHarness = false)
              }
              onNavigateToFlight()
            },
            icon = Icons.Default.FlightTakeoff,
            modifier = Modifier.weight(1f),
            tag = "connect_protocol_button",
          )
          if (flightState != FlightState.Disconnected) {
            AutopilotButton(
              text = "Disconnect",
              onClick = { viewModel.disconnect() },
              tone = AutopilotButtonTone.SECONDARY,
              tag = "disconnect_button",
            )
          }
        }
      }
    }

    // Protocol Compatibility Registry Section
    item {
      SectionHeader(
        title = "TurboDrone Protocol Registry (6 Families)",
        subtitle = "Select an adapter; SSID hints are advisory only and never guarantee compatibility",
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
      )
    }

    items(viewModel.productionProtocols, key = { it.descriptor.id }) { entry ->
      ProtocolRegistryCard(
        entry = entry,
        selected = selectedEntry.descriptor.id == entry.descriptor.id,
        onSelect = { viewModel.selectProtocol(entry.descriptor.id) },
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
      )
    }

    // Strictly Isolated Synthetic Test Harness
    item {
      AutopilotCard(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            Icons.Default.Science,
            contentDescription = "Isolated test harness",
            tint = theme.colors.statusWarning,
          )
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Isolated Loopback Test Harness",
              style = MaterialTheme.typography.titleMedium,
              color = theme.colors.textPrimary,
            )
            Text(
              text = "Offline verification of C++20 framing, HUD, and LiteRT/optical flow",
              style = MaterialTheme.typography.bodySmall,
              color = theme.colors.textSecondary,
            )
          }
          StatusBadge(label = "SYNTHETIC ONLY", tone = StatusTone.WARNING)
        }
        Spacer(Modifier.height(8.dp))
        Row(
          verticalAlignment = Alignment.Top,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = theme.colors.statusWarning,
            modifier = Modifier.size(16.dp),
          )
          Text(
            text =
              "Strictly isolated from hardware protocols. Transmits zero Wi-Fi/radio frames and cannot be used to fly physical aircraft.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.colors.textSecondary,
          )
        }
        Spacer(Modifier.height(12.dp))
        AutopilotButton(
          text = "Launch Isolated Test Harness",
          onClick = {
            viewModel.connectSelectedProtocol(useIsolatedSyntheticHarness = true)
            onNavigateToFlight()
          },
          icon = Icons.Default.Science,
          tone = AutopilotButtonTone.WARNING,
          modifier = Modifier.fillMaxWidth(),
          tag = "launch_test_harness_button",
        )
      }
    }
  }
}

@Composable
private fun ProtocolRegistryCard(
  entry: ProtocolRegistryEntry,
  selected: Boolean,
  onSelect: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val desc = entry.descriptor
  val shape = RoundedCornerShape(theme.components.cardRadius)
  Surface(
    modifier =
      modifier
        .testTag("protocol_card_${desc.id}")
        .clickable(onClick = onSelect),
    shape = shape,
    color = if (selected) theme.colors.surfaceElevated else theme.colors.surface,
    border =
      BorderStroke(
        width = if (selected) 1.5.dp else theme.primitives.borderHairline,
        color = if (selected) theme.colors.accent else theme.colors.borderSubtle,
      ),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = desc.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = theme.colors.textPrimary,
          )
          Text(
            text = "Models: ${desc.knownModels.joinToString(", ")}",
            style = MaterialTheme.typography.bodySmall,
            color = theme.colors.textSecondary,
          )
        }
        StatusBadge(
          label = desc.implementationStatus.name,
          tone =
            if (desc.verificationStatus == VerificationStatus.EXPERIMENTAL) StatusTone.WARNING
            else StatusTone.INFO,
        )
      }
      Spacer(Modifier.height(6.dp))
      Text(
        text = "${desc.defaultHostIp}:${desc.controlPort} · Video: ${desc.videoTransport}",
        style = MaterialTheme.typography.labelSmall,
        color = theme.colors.textSecondary,
      )
    }
  }
}
