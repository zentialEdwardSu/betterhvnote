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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.betterhv.update.UpdateInfo

@Composable
fun UpdateBanner(
    info: UpdateInfo,
    onOpenRelease: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.border(2.dp, Color.DarkGray, RectangleShape),
        color = Color.White,
        shape = RectangleShape,
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(noteText("发现新版本 ${info.latestVersion.display}", "New version ${info.latestVersion.display}"))
                Text(info.releaseTitle, color = Color.DarkGray)
            }
            Button(onClick = onOpenRelease) { Text(noteText("查看 Release", "View release")) }
            OutlinedButton(onClick = onDismiss) { Text(noteText("关闭", "Close")) }
        }
    }
}
