package com.betterhv.note.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Scoped e-ink theme. Do not install this at the application root: settings,
 * NoteLink, the editor canvas and document-editor dialogs deliberately keep
 * their existing style.
 */
@Composable
fun EinkTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = lightColorScheme(
      primary = EinkColors.Ink,
      onPrimary = EinkColors.Paper,
      primaryContainer = EinkColors.ChromeAlt,
      onPrimaryContainer = EinkColors.Ink,
      inversePrimary = EinkColors.Muted,
      secondary = EinkColors.Ink,
      onSecondary = EinkColors.Paper,
      secondaryContainer = EinkColors.ChromeAlt,
      onSecondaryContainer = EinkColors.Ink,
      tertiary = EinkColors.Ink,
      onTertiary = EinkColors.Paper,
      tertiaryContainer = EinkColors.ChromeAlt,
      onTertiaryContainer = EinkColors.Ink,
      background = EinkColors.Paper,
      onBackground = EinkColors.Ink,
      surface = EinkColors.Chrome,
      onSurface = EinkColors.Ink,
      surfaceVariant = EinkColors.ChromeAlt,
      onSurfaceVariant = EinkColors.Muted,
      surfaceTint = EinkColors.Ink,
      inverseSurface = EinkColors.Ink,
      inverseOnSurface = EinkColors.Paper,
      error = EinkColors.Ink,
      onError = EinkColors.Paper,
      errorContainer = EinkColors.ChromeAlt,
      onErrorContainer = EinkColors.Ink,
      outline = EinkColors.Ink,
      outlineVariant = EinkColors.Muted,
      scrim = EinkColors.Ink,
    ),
    typography = Typography(
      titleLarge = EinkType.ScreenTitle,
      titleMedium = EinkType.SectionTitle,
      titleSmall = EinkType.PanelTitle,
      bodyLarge = EinkType.Body,
      bodyMedium = EinkType.Label,
      bodySmall = EinkType.Caption,
      labelLarge = EinkType.Action,
      labelMedium = EinkType.Label,
      labelSmall = EinkType.Caption,
    ),
    shapes = Shapes(
      extraSmall = RoundedCornerShape(0.dp),
      small = RoundedCornerShape(0.dp),
      medium = RoundedCornerShape(0.dp),
      large = RoundedCornerShape(0.dp),
      extraLarge = RoundedCornerShape(0.dp),
    ),
    content = content,
  )
}
