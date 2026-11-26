package com.example.ui

import kotlinx.serialization.Serializable

@Serializable
sealed interface AutopilotRoute {
  @Serializable data object Flight : AutopilotRoute

  @Serializable data object Vision : AutopilotRoute

  @Serializable data object Settings : AutopilotRoute

  @Serializable data object SettingsAircraft : AutopilotRoute

  @Serializable data object SettingsFlightControls : AutopilotRoute

  @Serializable data object SettingsAppearance : AutopilotRoute

  @Serializable data object SettingsLogs : AutopilotRoute
}
