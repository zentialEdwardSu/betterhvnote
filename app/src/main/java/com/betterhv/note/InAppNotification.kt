package com.betterhv.note

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

enum class InAppNotificationDuration(internal val timeoutMillis: Long?) {
  SHORT(4_000L),
  LONG(8_000L),
  PERSISTENT(null),
}

internal data class InAppNotification(
  val id: Long,
  val title: String,
  val message: String?,
  val actionLabel: String?,
  val duration: InAppNotificationDuration,
  val onAction: (() -> Unit)?,
  val onDismiss: (() -> Unit)?,
)

@Stable
class InAppNotificationState {
  internal var current by mutableStateOf<InAppNotification?>(null)
    private set

  private var nextId = 0L

  fun show(
    title: String,
    message: String? = null,
    duration: InAppNotificationDuration = InAppNotificationDuration.SHORT,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
  ): Long {
    require(title.isNotBlank()) { "Notification title must not be blank" }
    require((actionLabel == null) == (onAction == null)) {
      "actionLabel and onAction must either both be set or both be null"
    }
    val previous = current
    val id = ++nextId
    current = InAppNotification(
      id = id,
      title = title,
      message = message?.takeIf(String::isNotBlank),
      actionLabel = actionLabel,
      duration = duration,
      onAction = onAction,
      onDismiss = onDismiss,
    )
    previous?.onDismiss?.invoke()
    return id
  }

  fun dismiss(id: Long? = null) {
    val notification = current ?: return
    if (id != null && notification.id != id) return
    current = null
    notification.onDismiss?.invoke()
  }

  internal fun performAction(id: Long) {
    val notification = current?.takeIf { it.id == id } ?: return
    current = null
    notification.onDismiss?.invoke()
    notification.onAction?.invoke()
  }
}

@Composable
fun rememberInAppNotificationState(): InAppNotificationState = remember { InAppNotificationState() }

@Composable
fun InAppNotificationHost(
  state: InAppNotificationState,
  modifier: Modifier = Modifier,
) {
  val notification = state.current ?: return
  LaunchedEffect(notification.id) {
    notification.duration.timeoutMillis?.let { timeoutMillis ->
      delay(timeoutMillis)
      state.dismiss(notification.id)
    }
  }
  Surface(
    modifier = modifier.border(2.dp, Color.DarkGray, RectangleShape),
    color = Color.White,
    shape = RectangleShape,
  ) {
    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(Modifier.weight(1f)) {
        Text(notification.title)
        notification.message?.let { Text(it, color = Color.DarkGray) }
      }
      notification.actionLabel?.let { label ->
        Button(onClick = { state.performAction(notification.id) }) { Text(label) }
      }
      OutlinedButton(onClick = { state.dismiss(notification.id) }) {
        Text(noteText("关闭", "Close"))
      }
    }
  }
}
