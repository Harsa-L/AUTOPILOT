package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.autopilot.designsystem.AutopilotFontSet
import com.autopilot.designsystem.AutopilotTheme
import com.example.ui.AutopilotRoute
import com.example.ui.AutopilotViewModel
import com.example.ui.FlightCockpitScreen
import com.example.ui.SettingsAircraftScreen
import com.example.ui.SettingsAppearanceScreen
import com.example.ui.SettingsFlightControlsScreen
import com.example.ui.SettingsLogsScreen
import com.example.ui.SettingsScreen
import com.example.ui.VisionScreen

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val vm: AutopilotViewModel = viewModel()
      val themeState by vm.themeState.collectAsStateWithLifecycle()

      val fonts = remember {
        AutopilotFontSet(
          display = FontFamily(Font(R.font.space_grotesk, FontWeight.Normal)),
          body = FontFamily(Font(R.font.inter, FontWeight.Normal)),
          mono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal)),
        )
      }

      AutopilotTheme(runtimeState = themeState, fonts = fonts) {
        AutopilotAppShell(viewModel = vm)
      }
    }
  }
}

@Composable
fun AutopilotAppShell(viewModel: AutopilotViewModel) {
  val navController = rememberNavController()

  Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
    NavHost(
      navController = navController,
      startDestination = AutopilotRoute.Flight,
    ) {
      composable<AutopilotRoute.Flight> {
        FlightCockpitScreen(
          viewModel = viewModel,
          onOpenVision = {
            navController.navigate(AutopilotRoute.Vision) { launchSingleTop = true }
          },
          onOpenSettings = {
            navController.navigate(AutopilotRoute.Settings) { launchSingleTop = true }
          },
        )
      }
      composable<AutopilotRoute.Vision> {
        VisionScreen(
          viewModel = viewModel,
          onNavigateBack = { navController.popBackStack() },
        )
      }
      composable<AutopilotRoute.Settings> {
        SettingsScreen(
          viewModel = viewModel,
          onNavigateBack = { navController.popBackStack() },
          onOpenAircraft = {
            navController.navigate(AutopilotRoute.SettingsAircraft) { launchSingleTop = true }
          },
          onOpenFlightControls = {
            navController.navigate(AutopilotRoute.SettingsFlightControls) { launchSingleTop = true }
          },
          onOpenAppearance = {
            navController.navigate(AutopilotRoute.SettingsAppearance) { launchSingleTop = true }
          },
          onOpenLogs = {
            navController.navigate(AutopilotRoute.SettingsLogs) { launchSingleTop = true }
          },
        )
      }
      composable<AutopilotRoute.SettingsAircraft> {
        SettingsAircraftScreen(
          viewModel = viewModel,
          onNavigateBack = { navController.popBackStack() },
        )
      }
      composable<AutopilotRoute.SettingsFlightControls> {
        SettingsFlightControlsScreen(
          viewModel = viewModel,
          onNavigateBack = { navController.popBackStack() },
        )
      }
      composable<AutopilotRoute.SettingsAppearance> {
        SettingsAppearanceScreen(
          viewModel = viewModel,
          onNavigateBack = { navController.popBackStack() },
        )
      }
      composable<AutopilotRoute.SettingsLogs> {
        SettingsLogsScreen(
          viewModel = viewModel,
          onNavigateBack = { navController.popBackStack() },
        )
      }
    }
  }
}
