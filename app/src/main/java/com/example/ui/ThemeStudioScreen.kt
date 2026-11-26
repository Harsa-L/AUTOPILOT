package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autopilot.designsystem.AutopilotButton
import com.autopilot.designsystem.AutopilotCard
import com.autopilot.designsystem.BuiltInThemeDocuments
import com.autopilot.designsystem.LocalResolvedTheme
import com.autopilot.designsystem.SectionHeader
import com.autopilot.designsystem.StatusBadge
import com.autopilot.designsystem.StatusTone
import com.autopilot.designsystem.ThemeAppearanceMode
import com.autopilot.designsystem.ThemeSchemaValidator

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeStudioScreen(
  viewModel: AutopilotViewModel,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val themeState by viewModel.themeState.collectAsStateWithLifecycle()
  val validationFeedback by viewModel.themeValidationFeedback.collectAsStateWithLifecycle()

  var jsonEditor by remember {
    mutableStateOf(
      ThemeSchemaValidator.encode(
        BuiltInThemeDocuments.cockpitDark.copy(
          id = "custom_avionics_amber",
          name = "Custom Avionics Amber",
          tokens =
            BuiltInThemeDocuments.cockpitDark.tokens +
              mapOf("color.accent" to com.autopilot.designsystem.W3cTokenLeaf("#F59E0B", "color")),
        )
      )
    )
  }

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    item {
      SectionHeader(
        title = "Design Runtime & Theme Studio",
        subtitle = "W3C DTCG 5-layer cascade · Live token resolution & Room persistence",
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
      )
    }

    // Appearance & Thermal Policy Card
    item {
      AutopilotCard(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(), elevated = true) {
        Text(
          text = "Appearance & Contextual Cascade",
          style = MaterialTheme.typography.titleMedium,
          color = theme.colors.textPrimary,
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ThemeAppearanceMode.entries.forEach { mode ->
            FilterChip(
              selected = themeState.appearanceMode == mode,
              onClick = { viewModel.setAppearanceMode(mode) },
              label = { Text(mode.name) },
              leadingIcon =
                if (themeState.appearanceMode == mode) {
                  { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
              modifier = Modifier.testTag("appearance_chip_${mode.name.lowercase()}"),
            )
          }
        }
        Spacer(Modifier.height(10.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Thermal Degradation Mode (Disable HUD Blur)",
              style = MaterialTheme.typography.bodyMedium,
              color = theme.colors.textPrimary,
            )
            Text(
              text = "Reduces GPU fill cost during extended flight & YOLO26n inference",
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

    // Available Theme Presets
    item {
      AutopilotCard(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
        Text(
          text = "Active Theme Presets",
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
                  width = if (selected) 1.5.dp else theme.primitives.borderHairline,
                  color = if (selected) theme.colors.accent else theme.colors.borderSubtle,
                ),
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = theme.colors.textPrimary,
                  )
                  Text(
                    text = "id=${preset.id} · appearance=${preset.appearance}",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.colors.textSecondary,
                  )
                }
                if (selected) {
                  StatusBadge(label = "ACTIVE", tone = StatusTone.NOMINAL)
                }
              }
            }
          }
        }
      }
    }

    // Resolved Semantic Swatch Inspection
    item {
      AutopilotCard(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
        Text(
          text = "Resolved Runtime Token Preview (${theme.themeName})",
          style = MaterialTheme.typography.titleMedium,
          color = theme.colors.textPrimary,
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          TokenSwatch("Canvas", theme.colors.canvas)
          TokenSwatch("Surface", theme.colors.surface)
          TokenSwatch("Accent", theme.colors.accent)
          TokenSwatch("Nominal", theme.colors.statusNominal)
          TokenSwatch("Warning", theme.colors.statusWarning)
          TokenSwatch("Emergency", theme.colors.statusEmergency)
        }
      }
    }

    // W3C DTCG JSON Compiler & Validator
    item {
      AutopilotCard(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
        Text(
          text = "W3C DTCG JSON Theme Compiler",
          style = MaterialTheme.typography.titleMedium,
          color = theme.colors.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
          value = jsonEditor,
          onValueChange = { jsonEditor = it },
          label = { Text("Theme Token Document (JSON)") },
          minLines = 8,
          maxLines = 14,
          textStyle = MaterialTheme.typography.labelSmall,
          modifier = Modifier.fillMaxWidth().testTag("theme_json_editor"),
        )
        if (validationFeedback != null) {
          Spacer(Modifier.height(8.dp))
          val fb = validationFeedback!!
          StatusBadge(
            label = if (fb.isValid) "VALIDATED & APPLIED" else "VALIDATION FAILED",
            tone = if (fb.isValid) StatusTone.NOMINAL else StatusTone.EMERGENCY,
          )
          fb.errors.forEach { err ->
            Text(
              text = "• $err",
              style = MaterialTheme.typography.bodySmall,
              color = theme.colors.statusEmergency,
            )
          }
        }
        Spacer(Modifier.height(10.dp))
        AutopilotButton(
          text = "Compile, Validate & Apply Theme",
          onClick = { viewModel.validateAndSaveCustomTheme(jsonEditor) },
          icon = Icons.Default.Palette,
          modifier = Modifier.fillMaxWidth(),
          tag = "apply_custom_theme_button",
        )
      }
    }
  }
}

@Composable
private fun TokenSwatch(label: String, color: androidx.compose.ui.graphics.Color) {
  val theme = LocalResolvedTheme.current
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(
      modifier =
        Modifier.size(18.dp)
          .clip(CircleShape)
          .background(color)
    )
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = theme.colors.textSecondary,
    )
  }
}
