package com.example.ui

import kotlinx.serialization.Serializable

@Serializable
sealed interface AutopilotRoute {
  @Serializable data object Dashboard : AutopilotRoute

  @Serializable data object Flight : AutopilotRoute

  @Serializable data object Diagnostics : AutopilotRoute

  @Serializable data object VisionSettings : AutopilotRoute

  @Serializable data object ThemeStudio : AutopilotRoute
}
