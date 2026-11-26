package com.autopilot.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class AutopilotButtonTone {
  PRIMARY,
  SECONDARY,
  EMERGENCY,
  WARNING,
}

@Composable
fun AutopilotButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  icon: ImageVector? = null,
  tone: AutopilotButtonTone = AutopilotButtonTone.PRIMARY,
  tag: String? = null,
  compact: Boolean = false,
) {
  val theme = LocalResolvedTheme.current
  val taggedModifier = if (tag != null) modifier.testTag(tag) else modifier
  val shape = RoundedCornerShape(theme.components.buttonRadius)
  val horizontalPad = if (compact) 10.dp else 16.dp
  val verticalPad = if (compact) 8.dp else 10.dp
  val textStyle = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium
  val iconSize = if (compact) 16.dp else theme.components.iconSizeSm
  val iconGap = if (compact) 5.dp else 8.dp

  if (tone == AutopilotButtonTone.SECONDARY) {
    OutlinedButton(
      onClick = onClick,
      enabled = enabled,
      shape = shape,
      border = BorderStroke(theme.primitives.borderHairline, theme.colors.borderSubtle),
      contentPadding = PaddingValues(horizontal = horizontalPad, vertical = verticalPad),
      modifier = taggedModifier.defaultMinSize(minHeight = theme.components.buttonHeight),
    ) {
      if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
        Spacer(Modifier.width(iconGap))
      }
      Text(
        text = text,
        style = textStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  } else {
    val container =
      when (tone) {
        AutopilotButtonTone.PRIMARY -> theme.colors.accent
        AutopilotButtonTone.EMERGENCY -> theme.colors.statusEmergency
        AutopilotButtonTone.WARNING -> theme.colors.statusWarning
        AutopilotButtonTone.SECONDARY -> theme.colors.surface
      }
    val content =
      when (tone) {
        AutopilotButtonTone.PRIMARY -> theme.colors.onAccent
        AutopilotButtonTone.EMERGENCY,
        AutopilotButtonTone.WARNING -> Color.White
        AutopilotButtonTone.SECONDARY -> theme.colors.textPrimary
      }
    Button(
      onClick = onClick,
      enabled = enabled,
      shape = shape,
      colors =
        ButtonDefaults.buttonColors(
          containerColor = container,
          contentColor = content,
          disabledContainerColor = theme.colors.surfaceElevated,
          disabledContentColor = theme.colors.textSecondary,
        ),
      contentPadding = PaddingValues(horizontal = horizontalPad, vertical = verticalPad),
      modifier = taggedModifier.defaultMinSize(minHeight = theme.components.buttonHeight),
    ) {
      if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
        Spacer(Modifier.width(iconGap))
      }
      Text(
        text = text,
        style = textStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
fun AutopilotCard(
  modifier: Modifier = Modifier,
  elevated: Boolean = false,
  content: @Composable ColumnScope.() -> Unit,
) {
  val theme = LocalResolvedTheme.current
  val shape = RoundedCornerShape(theme.components.cardRadius)
  Surface(
    modifier = modifier,
    shape = shape,
    color = if (elevated) theme.colors.surfaceElevated else theme.colors.surface,
    border = BorderStroke(theme.primitives.borderHairline, theme.colors.borderSubtle),
    tonalElevation = if (elevated) 2.dp else 0.dp,
  ) {
    Column(modifier = Modifier.padding(theme.components.cardPadding), content = content)
  }
}

@Composable
fun GlassHudPanel(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val theme = LocalResolvedTheme.current
  val shape = RoundedCornerShape(theme.components.hudCornerRadius)
  val glassColor = theme.colors.surfaceGlass.copy(alpha = theme.components.hudBackdropAlpha)
  Column(
    modifier =
      modifier
        .clip(shape)
        .background(glassColor)
        .border(theme.primitives.borderHairline, theme.colors.borderSubtle, shape)
        .padding(horizontal = 12.dp, vertical = 8.dp),
    content = content,
  )
}

enum class StatusTone {
  NOMINAL,
  WARNING,
  EMERGENCY,
  INFO,
  MUTED,
}

@Composable
fun StatusBadge(
  label: String,
  tone: StatusTone,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  val color =
    when (tone) {
      StatusTone.NOMINAL -> theme.colors.statusNominal
      StatusTone.WARNING -> theme.colors.statusWarning
      StatusTone.EMERGENCY -> theme.colors.statusEmergency
      StatusTone.INFO -> theme.colors.statusInfo
      StatusTone.MUTED -> theme.colors.textSecondary
    }
  Row(
    modifier =
      modifier
        .clip(RoundedCornerShape(50))
        .background(color.copy(alpha = 0.14f))
        .border(theme.primitives.borderHairline, color.copy(alpha = 0.45f), RoundedCornerShape(50))
        .padding(horizontal = 10.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = color,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
fun TelemetryPill(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  tone: StatusTone = StatusTone.MUTED,
) {
  val theme = LocalResolvedTheme.current
  val valueColor =
    when (tone) {
      StatusTone.NOMINAL -> theme.colors.statusNominal
      StatusTone.WARNING -> theme.colors.statusWarning
      StatusTone.EMERGENCY -> theme.colors.statusEmergency
      StatusTone.INFO -> theme.colors.statusInfo
      StatusTone.MUTED -> theme.colors.textPrimary
    }
  Column(
    modifier =
      modifier
        .clip(RoundedCornerShape(theme.primitives.radiusSmall))
        .background(theme.colors.surfaceElevated)
        .border(
          theme.primitives.borderHairline,
          theme.colors.borderSubtle,
          RoundedCornerShape(theme.primitives.radiusSmall),
        )
        .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Text(
      text = label.uppercase(),
      style = MaterialTheme.typography.labelSmall,
      color = theme.colors.textSecondary,
      maxLines = 1,
    )
    Spacer(Modifier.height(2.dp))
    Text(
      text = value,
      style = MaterialTheme.typography.labelMedium,
      color = valueColor,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
fun SectionHeader(
  title: String,
  subtitle: String? = null,
  modifier: Modifier = Modifier,
) {
  val theme = LocalResolvedTheme.current
  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge,
      color = theme.colors.textPrimary,
    )
    if (subtitle != null) {
      Spacer(Modifier.height(2.dp))
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = theme.colors.textSecondary,
      )
    }
  }
}
