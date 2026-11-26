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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(
  viewModel: AutopilotViewModel,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val context = LocalContext.current
  val selectedEntry by viewModel.selectedEntry.collectAsStateWithLifecycle()
  val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
  val sessions by viewModel.flightSessions.collectAsStateWithLifecycle()
  val logs by viewModel.liveLogs.collectAsStateWithLifecycle()

  var testRoll by remember { mutableFloatStateOf(0f) }
  var testThrottle by remember { mutableFloatStateOf(0f) }

  val exportLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
      if (uri != null) {
        viewModel.exportTelemetryToUri(uri) { ok ->
          Toast.makeText(
            context,
            if (ok) "Exported telemetry & logs JSON" else "Export failed",
            Toast.LENGTH_SHORT,
          ).show()
        }
      }
    }

  val previewHex =
    remember(selectedEntry, testRoll, testThrottle) {
      viewModel.previewPacketHex(
        descriptor = selectedEntry.descriptor,
        roll = testRoll,
        pitch = 0f,
        throttle = testThrottle,
        yaw = 0f,
      )
    }

  val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    item {
      Row(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        SectionHeader(
          title = "Diagnostics & Packet Inspector",
          subtitle = "Live C++20 binary framing, Room session logs & SAF JSON export",
          modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          AutopilotButton(
            text = "Export",
            onClick = { exportLauncher.launch("autopilot-telemetry.json") },
            icon = Icons.Default.Download,
            tone = AutopilotButtonTone.SECONDARY,
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
    }

    // Binary Packet Framing Inspector
    item {
      AutopilotCard(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(), elevated = true) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Protocol Frame Inspector (${selectedEntry.descriptor.displayName})",
            style = MaterialTheme.typography.titleMedium,
            color = theme.colors.textPrimary,
          )
          StatusBadge(label = selectedEntry.descriptor.id.uppercase(), tone = StatusTone.INFO)
        }
        Spacer(Modifier.height(8.dp))
        TelemetryPill(
          label = "Encoded Frame Hex (C++20 / Native Serializer)",
          value = previewHex,
          modifier = Modifier.fillMaxWidth(),
        )
        telemetry?.lastCommandHex?.takeIf { it.isNotBlank() }?.let { liveHex ->
          Spacer(Modifier.height(6.dp))
          TelemetryPill(
            label = "Last Live Session TX Frame",
            value = liveHex,
            tone = StatusTone.NOMINAL,
            modifier = Modifier.fillMaxWidth(),
          )
        }
        Spacer(Modifier.height(10.dp))
        Text(
          text = "Test Roll: ${"%.2f".format(testRoll)} · Throttle: ${"%.2f".format(testThrottle)}",
          style = MaterialTheme.typography.labelSmall,
          color = theme.colors.textSecondary,
        )
        Slider(value = testRoll, onValueChange = { testRoll = it }, valueRange = -1f..1f)
        Slider(value = testThrottle, onValueChange = { testThrottle = it }, valueRange = -1f..1f)
      }
    }

    // Room Flight Sessions
    item {
      SectionHeader(
        title = "Recorded Flight Sessions (${sessions.size})",
        subtitle = "Persisted locally in Room; synthetic test harness sessions are tagged",
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
      )
    }

    if (sessions.isEmpty()) {
      item {
        AutopilotCard(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
          Text(
            text = "No flight sessions recorded yet. Connect an aircraft or run the Isolated Test Harness to inspect TX/RX telemetry.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.colors.textSecondary,
          )
        }
      }
    } else {
      items(sessions, key = { it.id }) { session ->
        AutopilotCard(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = session.protocolName,
                style = MaterialTheme.typography.titleMedium,
                color = theme.colors.textPrimary,
              )
              Text(
                text = "${timeFmt.format(Date(session.startedAtMs))} · ${session.hostIp} · ${session.terminationReason}",
                style = MaterialTheme.typography.bodySmall,
                color = theme.colors.textSecondary,
              )
            }
            StatusBadge(
              label = if (session.isSyntheticHarness) "TEST HARNESS" else "HARDWARE",
              tone = if (session.isSyntheticHarness) StatusTone.WARNING else StatusTone.INFO,
            )
          }
          Spacer(Modifier.height(8.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            TelemetryPill("TX", session.packetsSent.toString(), Modifier.weight(1f))
            TelemetryPill("RX", session.packetsReceived.toString(), Modifier.weight(1f))
            TelemetryPill("Drops", session.droppedPackets.toString(), Modifier.weight(1f))
            TelemetryPill("Frames", session.framesDecoded.toString(), Modifier.weight(1f))
          }
        }
      }
    }

    // Live Subsystem Logs
    item {
      SectionHeader(
        title = "Live Subsystem Log Buffer (${logs.size})",
        subtitle = "Real-time events from JNI, connectivity, video, and vision threads",
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
      )
    }

    items(logs.take(25), key = { it.id }) { entry ->
      AutopilotCard(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "${timeFmt.format(Date(entry.timestampMs))} · ${entry.tag.name}",
            style = MaterialTheme.typography.labelSmall,
            color = theme.colors.accent,
          )
          StatusBadge(
            label = entry.level.name,
            tone =
              when (entry.level.name) {
                "ERROR" -> StatusTone.EMERGENCY
                "WARN" -> StatusTone.WARNING
                else -> StatusTone.MUTED
              },
          )
        }
        Spacer(Modifier.height(4.dp))
        Text(
          text = entry.message,
          style = MaterialTheme.typography.bodySmall,
          color = theme.colors.textPrimary,
        )
      }
    }
  }
}
