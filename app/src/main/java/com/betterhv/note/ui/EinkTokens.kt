package com.betterhv.note.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Single source of visual constants for the toolbar surfaces. Keep this
 * monochrome: content previews (pen colours, page/notebook thumbnails) are the
 * only allowed non-gray values.
 */
object EinkColors {
  val Paper = Color.White
  val Chrome = Color(0xFFF2F2F2)
  val ChromeAlt = Color(0xFFE8E8E8)
  val Ink = Color.Black
  val Muted = Color(0xFF666666)
  val Disabled = Color(0xFF9A9A9A)
  val DisabledFill = Color(0xFFE8E8E8)
  val Transparent = Color.Transparent
}

object EinkDimens {
  val Hairline = 1.dp
  val BorderEmphasis = 2.dp
  val Touch = 44.dp
  val Row = 44.dp
  val Icon = 24.dp
  val PanelHeader = 36.dp
  val ScreenHeader = 64.dp
  val SpaceXs = 4.dp
  val SpaceSm = 8.dp
  val SpaceMd = 12.dp
  val SpaceLg = 16.dp
  val SpaceXl = 24.dp
  val ToolbarGap = 2.dp
}

object EinkType {
  val ScreenTitle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
  val SectionTitle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
  val PanelTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
  val Body = TextStyle(fontSize = 16.sp)
  val Label = TextStyle(fontSize = 14.sp)
  val Caption = TextStyle(fontSize = 12.sp)
  val Action = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
}
