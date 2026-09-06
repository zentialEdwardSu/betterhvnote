package com.betterhv.note

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

internal enum class EinkModalPosition { TOP, CENTER }

/** In-window modal surface with no dim layer or transition animation. */
@Composable
internal fun EinkModalOverlay(
  onDismissRequest: () -> Unit,
  position: EinkModalPosition = EinkModalPosition.CENTER,
  dismissOnOutside: Boolean = true,
  content: @Composable BoxScope.() -> Unit,
) {
  BackHandler(onBack = onDismissRequest)
  val backdropInteractions = remember { MutableInteractionSource() }
  val contentInteractions = remember { MutableInteractionSource() }
  Box(
    modifier = Modifier
      .fillMaxSize()
      .zIndex(1000f)
      .background(Color.Transparent)
      .clickable(
        interactionSource = backdropInteractions,
        indication = null,
        onClick = { if (dismissOnOutside) onDismissRequest() },
      ),
  ) {
    val alignment = if (position == EinkModalPosition.TOP) Alignment.TopCenter else Alignment.Center
    val verticalPadding = if (position == EinkModalPosition.TOP) 32.dp else 24.dp
    Surface(
      modifier = Modifier
        .align(alignment)
        .padding(horizontal = 24.dp, vertical = verticalPadding)
        .widthIn(max = 560.dp)
        .fillMaxWidth()
        .border(2.dp, Color.Black, RectangleShape)
        .clickable(
          interactionSource = contentInteractions,
          indication = null,
          onClick = {},
        ),
      shape = RectangleShape,
      color = Color.White,
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
    ) {
      Box(content = content)
    }
  }
}

@Composable
internal fun EinkDialogAction(
  label: String,
  enabled: Boolean = true,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  Box(
    modifier = modifier
      .border(1.dp, if (enabled) Color.Black else Color.Gray, RectangleShape)
      .background(if (enabled) Color.White else Color(0xFFE8E8E8), RectangleShape)
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 22.dp, vertical = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(label, color = if (enabled) Color.Black else Color.Gray)
  }
}

/** Shared Note-style source/target chooser used by PDF and NoteLink flows. */
@Composable
internal fun EinkChoiceOverlay(
  title: String,
  description: String? = null,
  choices: List<Pair<String, () -> Unit>>,
  onDismissRequest: () -> Unit,
) {
  EinkModalOverlay(
    onDismissRequest = onDismissRequest,
    position = EinkModalPosition.TOP,
  ) {
    Column(
      Modifier.fillMaxWidth().padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(title, color = Color.Black)
      description?.let { Text(it, color = Color.DarkGray) }
      choices.forEach { (label, action) ->
        EinkDialogAction(label, modifier = Modifier.fillMaxWidth(), onClick = action)
      }
      EinkDialogAction(noteText("取消", "Cancel"), modifier = Modifier.fillMaxWidth(), onClick = onDismissRequest)
    }
  }
}
