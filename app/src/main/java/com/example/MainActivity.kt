package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.autopilot.designsystem.AutopilotFontSet
import com.autopilot.designsystem.AutopilotTheme
import com.autopilot.designsystem.LocalResolvedTheme
import com.example.ui.AutopilotRoute
import com.example.ui.AutopilotViewModel
import com.example.ui.DashboardScreen
import com.example.ui.DiagnosticsScreen
import com.example.ui.FlightCockpitScreen
import com.example.ui.ThemeStudioScreen
import com.example.ui.VisionAndSettingsScreen

private data class NavTab(
  val route: AutopilotRoute,
  val label: String,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val tag: String,
)

class MainActivity : ComponentActivity() {
  @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val widthClass = calculateWindowSizeClass(this).widthSizeClass
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
        AutopilotAppShell(
          viewModel = vm,
          useRail = widthClass != WindowWidthSizeClass.Compact,
        )
      }
    }
  }
}

@Composable
fun AutopilotAppShell(
  viewModel: AutopilotViewModel,
  useRail: Boolean,
) {
  val navController = rememberNavController()
  val backStackEntry by navController.currentBackStackEntryAsState()
  val resolvedTheme = LocalResolvedTheme.current

  val tabs = remember {
    listOf(
      NavTab(
        route = AutopilotRoute.Dashboard,
        label = "Console",
        selectedIcon = Icons.Filled.Dashboard,
        unselectedIcon = Icons.Outlined.Dashboard,
        tag = "nav_dashboard",
      ),
      NavTab(
        route = AutopilotRoute.Flight,
        label = "Cockpit",
        selectedIcon = Icons.Filled.Flight,
        unselectedIcon = Icons.Outlined.Flight,
        tag = "nav_flight",
      ),
      NavTab(
        route = AutopilotRoute.Diagnostics,
        label = "Logs",
        selectedIcon = Icons.Filled.Analytics,
        unselectedIcon = Icons.Outlined.Analytics,
        tag = "nav_diagnostics",
      ),
      NavTab(
        route = AutopilotRoute.VisionSettings,
        label = "Vision",
        selectedIcon = Icons.Filled.Tune,
        unselectedIcon = Icons.Outlined.Tune,
        tag = "nav_vision",
      ),
      NavTab(
        route = AutopilotRoute.ThemeStudio,
        label = "Theme",
        selectedIcon = Icons.Filled.Palette,
        unselectedIcon = Icons.Outlined.Palette,
        tag = "nav_theme",
      ),
    )
  }

  val isFlightDestination =
    backStackEntry?.destination?.hasRoute(AutopilotRoute.Flight::class) == true

  fun navigateTo(route: AutopilotRoute) {
    navController.navigate(route) {
      popUpTo(navController.graph.findStartDestination().id) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = resolvedTheme.colors.canvas,
    contentWindowInsets = WindowInsets.safeDrawing,
    bottomBar = {
      if (!useRail && !isFlightDestination) {
        NavigationBar(
          containerColor = resolvedTheme.colors.surface,
          windowInsets = WindowInsets.navigationBars,
        ) {
          tabs.forEach { tab ->
            val selected = backStackEntry?.destination?.hasRoute(tab.route::class) == true
            NavigationBarItem(
              selected = selected,
              onClick = { navigateTo(tab.route) },
              icon = {
                Icon(
                  imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                  contentDescription = tab.label,
                )
              },
              label = { Text(tab.label) },
              modifier = Modifier.testTag(tab.tag),
            )
          }
        }
      }
    },
  ) { innerPadding ->
    Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      if (useRail && !isFlightDestination) {
        NavigationRail(containerColor = resolvedTheme.colors.surface) {
          tabs.forEach { tab ->
            val selected = backStackEntry?.destination?.hasRoute(tab.route::class) == true
            NavigationRailItem(
              selected = selected,
              onClick = { navigateTo(tab.route) },
              icon = {
                Icon(
                  imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                  contentDescription = tab.label,
                )
              },
              label = { Text(tab.label) },
              modifier = Modifier.testTag(tab.tag),
            )
          }
        }
      }

      Box(modifier = Modifier.weight(1f).fillMaxSize()) {
        NavHost(
          navController = navController,
          startDestination = AutopilotRoute.Dashboard,
        ) {
          composable<AutopilotRoute.Dashboard> {
            DashboardScreen(
              viewModel = viewModel,
              onNavigateToFlight = { navigateTo(AutopilotRoute.Flight) },
            )
          }
          composable<AutopilotRoute.Flight> {
            FlightCockpitScreen(
              viewModel = viewModel,
              onNavigateBack = { navigateTo(AutopilotRoute.Dashboard) },
            )
          }
          composable<AutopilotRoute.Diagnostics> {
            DiagnosticsScreen(viewModel = viewModel)
          }
          composable<AutopilotRoute.VisionSettings> {
            VisionAndSettingsScreen(viewModel = viewModel)
          }
          composable<AutopilotRoute.ThemeStudio> {
            ThemeStudioScreen(viewModel = viewModel)
          }
        }
      }
    }
  }
}
