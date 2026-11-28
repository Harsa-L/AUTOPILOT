package com.example.ui.benchmark

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autopilot.designsystem.AutopilotButton
import com.autopilot.designsystem.AutopilotButtonTone
import com.autopilot.designsystem.AutopilotCard
import com.autopilot.designsystem.LocalResolvedTheme
import com.autopilot.designsystem.StatusBadge
import com.autopilot.designsystem.StatusTone
import com.autopilot.visionbenchmark.BenchmarkLifecycleState

private val benchmarkTabTitles =
  listOf("Live", "Degrade", "CV & Models", "Monitor", "Recordings")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionBenchmarkScreen(
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
  vm: VisionBenchmarkViewModel = viewModel(),
) {
  val theme = LocalResolvedTheme.current
  val context = LocalContext.current

  var hasCameraPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      hasCameraPermission = granted
      if (granted) {
        vm.initializeStudio()
      }
    }

  LaunchedEffect(Unit) {
    vm.initializeStudio()
  }

  DisposableEffect(Unit) {
    onDispose {
      vm.stopBenchmark()
    }
  }

  val lifecycleState by vm.lifecycleState.collectAsStateWithLifecycle()
  val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
  val metrics by vm.metrics.collectAsStateWithLifecycle()
  var selectedTab by remember { mutableIntStateOf(0) }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = theme.colors.canvas,
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = "Vision Benchmark Studio",
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = "Phone Camera Offline CV Harness (Flight Isolated)",
              style = MaterialTheme.typography.labelSmall,
              color = theme.colors.textSecondary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        },
        navigationIcon = {
          IconButton(
            onClick = {
              vm.stopBenchmark()
              onNavigateBack()
            },
            modifier = Modifier.size(48.dp).testTag("benchmark_back_button"),
          ) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Exit Vision Benchmark Studio",
            )
          }
        },
        actions = {
          val (badgeLabel, badgeTone) =
            when (lifecycleState) {
              BenchmarkLifecycleState.Inactive -> "INACTIVE" to StatusTone.MUTED
              BenchmarkLifecycleState.Initializing -> "INIT" to StatusTone.INFO
              BenchmarkLifecycleState.Ready -> "READY" to StatusTone.INFO
              BenchmarkLifecycleState.Running -> "RUNNING" to StatusTone.NOMINAL
              BenchmarkLifecycleState.Paused -> "PAUSED" to StatusTone.WARNING
              BenchmarkLifecycleState.Stopping -> "STOPPING" to StatusTone.WARNING
              is BenchmarkLifecycleState.Completed -> "SAVED" to StatusTone.NOMINAL
              is BenchmarkLifecycleState.Failed -> "ERROR" to StatusTone.EMERGENCY
            }
          StatusBadge(
            label = badgeLabel,
            tone = badgeTone,
            modifier = Modifier.padding(end = 12.dp),
          )
        },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = theme.colors.surface,
            titleContentColor = theme.colors.textPrimary,
            navigationIconContentColor = theme.colors.textPrimary,
          ),
      )
    },
  ) { innerPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      // Transport & lifecycle bar
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val isRunning = lifecycleState == BenchmarkLifecycleState.Running
        val isPaused = lifecycleState == BenchmarkLifecycleState.Paused
        if (!hasCameraPermission) {
          AutopilotButton(
            text = "Grant Camera Access",
            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            icon = Icons.Default.CameraAlt,
            tone = AutopilotButtonTone.WARNING,
            compact = true,
            tag = "benchmark_grant_camera_button",
          )
        } else if (!isRunning && !isPaused) {
          AutopilotButton(
            text = "Start Benchmark",
            onClick = { vm.startBenchmark() },
            icon = Icons.Default.PlayArrow,
            tone = AutopilotButtonTone.PRIMARY,
            compact = true,
            tag = "benchmark_start_button",
          )
        } else {
          if (isRunning) {
            AutopilotButton(
              text = "Pause",
              onClick = { vm.pauseBenchmark() },
              icon = Icons.Default.Pause,
              tone = AutopilotButtonTone.SECONDARY,
              compact = true,
              tag = "benchmark_pause_button",
            )
          } else {
            AutopilotButton(
              text = "Resume",
              onClick = { vm.resumeBenchmark() },
              icon = Icons.Default.PlayArrow,
              tone = AutopilotButtonTone.PRIMARY,
              compact = true,
              tag = "benchmark_resume_button",
            )
          }
          AutopilotButton(
            text = "Stop",
            onClick = { vm.stopBenchmark() },
            icon = Icons.Default.Stop,
            tone = AutopilotButtonTone.EMERGENCY,
            compact = true,
            tag = "benchmark_stop_button",
          )
        }

        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = activeProfile.name,
            style = MaterialTheme.typography.labelMedium,
            color = theme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          val errMsg = (lifecycleState as? BenchmarkLifecycleState.Failed)?.reason
          Text(
            text =
              errMsg
                ?: "${"%.1f".format(metrics.inferenceFps)} infer FPS · ${"%.1f".format(metrics.avgLatencyMs)} ms avg",
            style = MaterialTheme.typography.labelSmall,
            color = if (errMsg != null) theme.colors.statusEmergency else theme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      if (!hasCameraPermission && selectedTab == 0) {
        AutopilotCard(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
          Text(
            text = "Camera Permission Required for Live Capture",
            style = MaterialTheme.typography.titleSmall,
            color = theme.colors.textPrimary,
          )
          Spacer(Modifier.height(4.dp))
          Text(
            text =
              "Grant Camera access to feed your Android phone's camera through the degradation engine into AUTOPILOT's LiteRT YOLO pipeline. Saved MP4 model comparisons remain usable without camera permission.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.colors.textSecondary,
          )
        }
      }

      ScrollableTabRow(
        selectedTabIndex = selectedTab,
        containerColor = theme.colors.surface,
        contentColor = theme.colors.accent,
        edgePadding = 8.dp,
      ) {
        benchmarkTabTitles.forEachIndexed { idx, title ->
          Tab(
            selected = selectedTab == idx,
            onClick = { selectedTab = idx },
            modifier = Modifier.testTag("benchmark_tab_$idx"),
            text = {
              Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
              )
            },
          )
        }
      }

      Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        when (selectedTab) {
          0 -> BenchmarkLiveTab(vm = vm)
          1 -> BenchmarkDegradeTab(vm = vm)
          2 -> BenchmarkModelsTab(vm = vm)
          3 -> BenchmarkMonitorTab(vm = vm)
          4 -> BenchmarkRecordingsTab(vm = vm)
        }
      }
    }
  }
}
