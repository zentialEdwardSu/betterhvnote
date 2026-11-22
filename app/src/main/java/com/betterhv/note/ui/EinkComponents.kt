package com.betterhv.note.ui

import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.betterhv.note.DockEdge
import com.betterhv.note.hardware.PenButtonTracker
import com.betterhv.note.hardware.PenSideButton
import com.betterhv.note.StylusClickResolver

@Composable
fun EinkSurface(
  modifier: Modifier = Modifier,
  color: Color = EinkColors.Chrome,
  bordered: Boolean = true,
  content: @Composable () -> Unit,
) {
  Surface(
    modifier = modifier,
    shape = RectangleShape,
    color = color,
    contentColor = EinkColors.Ink,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    border = if (bordered) BorderStroke(EinkDimens.Hairline, EinkColors.Ink) else null,
    content = content,
  )
}

@Composable
fun EinkFlyoutSurface(
  title: String,
  dockEdge: DockEdge,
  modifier: Modifier = Modifier,
  compactHeaderWidth: Dp? = null,
  wrapContentWidth: Boolean = true,
  content: @Composable () -> Unit,
) {
  val resolvedHeaderWidth = compactHeaderWidth.takeIf {
    dockEdge == DockEdge.TOP || dockEdge == DockEdge.BOTTOM
  }
  EinkSurface(modifier = modifier, color = EinkColors.Chrome) {
    Column(if (wrapContentWidth) Modifier.width(IntrinsicSize.Max) else Modifier.fillMaxWidth()) {
      Row(Modifier.fillMaxWidth().height(EinkDimens.PanelHeader)) {
        Box(
          Modifier
            .then(
              if (resolvedHeaderWidth == null) Modifier.fillMaxWidth()
              else Modifier.width(resolvedHeaderWidth),
            )
            .height(EinkDimens.PanelHeader)
            .background(EinkColors.Chrome)
            .padding(horizontal = EinkDimens.SpaceMd),
          contentAlignment = Alignment.CenterStart,
        ) {
          Text(
            text = title,
            color = EinkColors.Ink,
            style = EinkType.PanelTitle,
            maxLines = 1,
          )
        }
      }
      Box(Modifier.background(EinkColors.Chrome)) { content() }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun EinkToolButton(
  icon: Painter,
  contentDescription: String,
  enabled: Boolean,
  selected: Boolean,
  onFunctionClick: (() -> Unit)? = null,
  onSide2Click: (() -> Unit)? = null,
  onSide3Click: (() -> Unit)? = null,
  onDoubleClick: (() -> Unit)? = null,
  onClick: () -> Unit,
) {
  val stylusClick = androidx.compose.runtime.remember { StylusClickResolver() }
  val background = if (selected) EinkColors.Ink else Color.Transparent
  val tint = when {
    !enabled -> EinkColors.Disabled
    selected -> EinkColors.Paper
    else -> EinkColors.Ink
  }
  val resolvedClick = {
    when (stylusClick.consume()) {
      PenSideButton.SIDE_1 -> (onFunctionClick ?: onClick).invoke()
      PenSideButton.SIDE_2 -> (onSide2Click ?: onClick).invoke()
      PenSideButton.SIDE_3 -> (onSide3Click ?: onClick).invoke()
      PenSideButton.NONE -> onClick()
    }
  }
  val gestures = if (onDoubleClick != null) {
    Modifier.combinedClickable(
      enabled = enabled,
      onClick = resolvedClick,
      onDoubleClick = onDoubleClick,
    )
  } else {
    Modifier.clickable(enabled = enabled, onClick = resolvedClick)
  }
  val functionKeyDetector = if (
    onFunctionClick != null || onSide2Click != null || onSide3Click != null
  ) {
    @Suppress("DEPRECATION")
    Modifier.pointerInteropFilter { event ->
      if (event.actionMasked == MotionEvent.ACTION_DOWN) {
        stylusClick.observe(event)
        PenButtonTracker.observeMotion(event, "pen-icon")
      }
      false
    }
  } else {
    Modifier
  }
  Box(
    modifier = Modifier
      .size(EinkDimens.Touch)
      .background(background, RectangleShape)
      .then(functionKeyDetector)
      .then(gestures),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = contentDescription, tint = tint)
  }
}

@Composable
fun EinkChoiceCell(
  modifier: Modifier = Modifier,
  selected: Boolean,
  enabled: Boolean = true,
  contentDescription: String? = null,
  onClick: () -> Unit,
  content: @Composable BoxScope.() -> Unit,
) {
  val background = if (selected) EinkColors.Ink else EinkColors.Paper
  val contentColor = when {
    !enabled -> EinkColors.Disabled
    selected -> EinkColors.Paper
    else -> EinkColors.Ink
  }
  Box(
    modifier = modifier
      .background(background, RectangleShape)
      .border(EinkDimens.Hairline, EinkColors.Ink, RectangleShape)
      .clickable(enabled = enabled, onClick = onClick)
      .then(
        if (contentDescription != null) {
          Modifier.semantics { this.contentDescription = contentDescription }
        } else {
          Modifier
        },
      ),
    contentAlignment = Alignment.Center,
  ) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
      content()
    }
  }
}

@Composable
fun EinkActionRow(
  label: String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  val color = if (enabled) EinkColors.Ink else EinkColors.Disabled
  Box(
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = EinkDimens.Row)
      .background(EinkColors.Paper)
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = EinkDimens.SpaceMd, vertical = EinkDimens.SpaceSm)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.CenterStart,
  ) {
    Text(label, color = color, style = EinkType.Action)
  }
}

@Composable
fun EinkIconButton(
  icon: ImageVector,
  contentDescription: String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  selected: Boolean = false,
  onClick: () -> Unit,
) {
  val background = if (selected) EinkColors.Ink else Color.Transparent
  val tint = when {
    !enabled -> EinkColors.Disabled
    selected -> EinkColors.Paper
    else -> EinkColors.Ink
  }
  Box(
    modifier = modifier
      .size(EinkDimens.Touch)
      .background(background, RectangleShape)
      .clickable(enabled = enabled, onClick = onClick)
      .semantics { this.contentDescription = contentDescription },
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = contentDescription, tint = tint)
  }
}

@Composable
fun EinkTab(
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable RowScope.() -> Unit,
) {
  val background = if (selected) EinkColors.Ink else EinkColors.Paper
  Row(
    modifier = modifier
      .height(EinkDimens.Touch)
      .background(background, RectangleShape)
      .border(EinkDimens.Hairline, EinkColors.Ink, RectangleShape)
      .clickable(onClick = onClick)
      .padding(horizontal = EinkDimens.SpaceSm),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
  ) {
    val scope = this
    CompositionLocalProvider(
      LocalContentColor provides if (selected) EinkColors.Paper else EinkColors.Ink,
    ) {
      scope.content()
    }
  }
}

@Composable
fun EinkToggle(
  checked: Boolean,
  modifier: Modifier = Modifier,
  contentDescription: String? = null,
) {
  Box(
    modifier = modifier
      .size(EinkDimens.Touch)
      .background(if (checked) EinkColors.Ink else EinkColors.Paper, RectangleShape)
      .border(EinkDimens.Hairline, EinkColors.Ink, RectangleShape)
      .then(
        if (contentDescription != null) {
          Modifier.semantics { this.contentDescription = contentDescription }
        } else {
          Modifier
        },
      ),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      if (checked) "✓" else "—",
      color = if (checked) EinkColors.Paper else EinkColors.Ink,
      style = EinkType.Body,
    )
  }
}

@Composable
fun EinkRadioMark(
  selected: Boolean,
  modifier: Modifier = Modifier,
  contentDescription: String? = null,
) {
  Box(
    modifier = modifier
      .size(24.dp)
      .background(if (selected) EinkColors.Ink else EinkColors.Paper, RectangleShape)
      .border(EinkDimens.Hairline, EinkColors.Ink, RectangleShape)
      .then(
        if (contentDescription != null) {
          Modifier.semantics { this.contentDescription = contentDescription }
        } else {
          Modifier
        },
      ),
    contentAlignment = Alignment.Center,
  ) {
    if (selected) {
      Box(Modifier.size(8.dp).background(EinkColors.Paper, RectangleShape))
    }
  }
}

@Composable
fun EinkScreenHeader(
  title: String,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  actions: @Composable RowScope.() -> Unit = {},
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .height(EinkDimens.ScreenHeader)
      .background(EinkColors.Chrome)
      .border(EinkDimens.Hairline, EinkColors.Ink, RectangleShape)
      .padding(horizontal = EinkDimens.SpaceMd),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(EinkDimens.SpaceSm),
  ) {
    if (onBack != null) {
      EinkIconButton(
        icon = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = "Back",
        onClick = onBack,
      )
    }
    Text(title, modifier = Modifier.weight(1f), color = EinkColors.Ink, style = EinkType.ScreenTitle)
    actions()
  }
}

@Composable
fun EinkStatus(text: String, ok: Boolean, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(EinkDimens.SpaceXs),
  ) {
    Text(if (ok) "✓" else "!", color = EinkColors.Ink, style = EinkType.Label)
    Text(text, color = EinkColors.Muted, style = EinkType.Caption)
  }
}

@Composable
fun EinkProgress(progress: Float?, modifier: Modifier = Modifier) {
  if (progress == null) {
    LinearProgressIndicator(modifier = modifier, color = EinkColors.Ink)
  } else {
    LinearProgressIndicator(
      progress = { progress },
      modifier = modifier,
      color = EinkColors.Ink,
      trackColor = EinkColors.ChromeAlt,
    )
  }
}

@Composable
fun EinkThumbnailFrame(
  selected: Boolean,
  modifier: Modifier = Modifier,
  content: @Composable BoxScope.() -> Unit,
) {
  Box(
    modifier = modifier.border(
      if (selected) EinkDimens.BorderEmphasis else EinkDimens.Hairline,
      EinkColors.Ink,
      RectangleShape,
    ),
    content = content,
  )
}

@Composable
fun EinkDialogSurface(
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  EinkSurface(modifier = modifier, color = EinkColors.Paper) {
    content()
  }
}
