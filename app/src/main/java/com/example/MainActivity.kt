package com.example

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.autopilot.designsystem.AutopilotFontSet
import com.autopilot.designsystem.AutopilotTheme
import com.example.input.GamepadInputHandler
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

  private val viewModel: AutopilotViewModel by viewModels()
  private lateinit var gamepadHandler: GamepadInputHandler

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    gamepadHandler =
      GamepadInputHandler(
        context = this,
        onAxesUpdate = { viewModel.onGamepadAxesChanged(it) },
        onAction = { viewModel.onGamepadAction(it) },
        onStatusChanged = { viewModel.onGamepadStatusChanged(it) },
      )
    gamepadHandler.startListening()

    setContent {
      val themeState by viewModel.themeState.collectAsStateWithLifecycle()
      val prefs by viewModel.userPrefs.collectAsStateWithLifecycle()

      LaunchedEffect(prefs.gamepadEnabled, prefs.gamepadThrottleHold) {
        gamepadHandler.enabled = prefs.gamepadEnabled
        gamepadHandler.throttleHoldMode = prefs.gamepadThrottleHold
      }

      val fonts = remember {
        AutopilotFontSet(
          display = FontFamily(Font(R.font.space_grotesk, FontWeight.Normal)),
          body = FontFamily(Font(R.font.inter, FontWeight.Normal)),
          mono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal)),
        )
      }

      AutopilotTheme(runtimeState = themeState, fonts = fonts) {
        AutopilotAppShell(viewModel = viewModel)
      }
    }
  }

  override fun onGenericMotionEvent(event: MotionEvent): Boolean {
    if (::gamepadHandler.isInitialized && gamepadHandler.handleMotionEvent(event)) {
      return true
    }
    return super.onGenericMotionEvent(event)
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    if (::gamepadHandler.isInitialized && gamepadHandler.handleKeyDown(keyCode, event)) {
      return true
    }
    return super.onKeyDown(keyCode, event)
  }

  override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
    if (::gamepadHandler.isInitialized && gamepadHandler.handleKeyUp(keyCode, event)) {
      return true
    }
    return super.onKeyUp(keyCode, event)
  }

  override fun onDestroy() {
    if (::gamepadHandler.isInitialized) {
      gamepadHandler.stopListening()
    }
    super.onDestroy()
  }
}

@Composable
fun AutopilotAppShell(viewModel: AutopilotViewModel) {
  val navController = rememberNavController()

  Box(modifier = Modifier.fillMaxSize()) {
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
