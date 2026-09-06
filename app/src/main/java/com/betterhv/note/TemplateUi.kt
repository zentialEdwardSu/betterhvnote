package com.betterhv.note

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.betterhv.note.template.TemplateAvailability
import com.betterhv.note.template.TemplateCatalogSnapshot
import com.betterhv.note.template.TemplateDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TemplateChooserState(
  val catalog: TemplateCatalogSnapshot,
  val selectedId: String?,
  val pageWidth: Float,
  val pageHeight: Float,
  val preview: (String, Int, Int) -> Bitmap?,
)

data class TemplateChooserActions(
  val onSelect: (String) -> Unit,
  val onConnectDirectory: () -> Unit,
  val onRefresh: () -> Unit,
  val onDismiss: () -> Unit,
)

@Composable
fun TemplateSelectionRow(
  definition: TemplateDefinition?,
  preview: (String, Int, Int) -> Bitmap?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier.fillMaxWidth().height(88.dp).border(1.dp, Color(0xFFB7B7B7), RectangleShape)
      .clickable(onClick = onClick).padding(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    TemplatePreview(definition, preview, Modifier.width(48.dp).height(64.dp))
    Column(Modifier.weight(1f).padding(start = 12.dp)) {
      Text("Template", color = Color(0xFF666666), fontSize = 12.sp)
      Text(definition?.name ?: noteText("不可用", "Unavailable"), fontWeight = FontWeight.Medium, fontSize = 17.sp)
      Text(
        definition?.description.orEmpty(),
        color = Color(0xFF666666),
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
fun TemplateChooserOverlay(state: TemplateChooserState, actions: TemplateChooserActions) {
  EinkModalOverlay(onDismissRequest = actions.onDismiss) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Template", fontSize = 24.sp)
          Text(
            if (state.catalog.directoryUri == null) {
              noteText(
                "正在使用内置模板 · 尚未连接公共目录",
                "Using built-in templates · no shared folder connected",
              )
            } else {
              noteText("公共目录已连接 · 返回应用或点击刷新可同步外部修改", "Shared folder connected · return or refresh to sync changes")
            },
            color = Color(0xFF666666),
            fontSize = 12.sp,
          )
        }
        IconButton(onClick = actions.onConnectDirectory) {
          Icon(Icons.Filled.FolderOpen, contentDescription = noteText("连接或更换模板目录", "Connect or change template folder"))
        }
        IconButton(onClick = actions.onRefresh) {
          Icon(Icons.Filled.Refresh, contentDescription = noteText("刷新模板目录", "Refresh template folder"))
        }
        EinkDialogAction(noteText("完成", "Done"), onClick = actions.onDismiss)
      }
      state.catalog.errors.firstOrNull()?.let {
        Text(it, color = Color(0xFF8B0000), fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
      }
      LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(state.catalog.templates, key = TemplateDefinition::id) { definition ->
          val compatible = definition.isCompatible(state.pageWidth, state.pageHeight)
          val enabled = compatible && definition.availability != TemplateAvailability.INVALID
          Column(
            Modifier.background(Color.White).border(
              if (definition.id == state.selectedId) 3.dp else 1.dp,
              if (definition.id == state.selectedId) Color.Black else Color(0xFFC8C8C8),
              RectangleShape,
            ).clickable(enabled = enabled) { actions.onSelect(definition.id) }.padding(8.dp),
          ) {
            TemplatePreview(definition, state.preview, Modifier.fillMaxWidth().height(160.dp))
            Text(definition.name, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(definition.description, color = Color(0xFF666666), fontSize = 12.sp, maxLines = 2)
            when {
              !compatible -> Text(
                noteText("宽高比与当前页面不一致", "Aspect ratio does not match this page"),
                color = Color(0xFF8B0000),
                fontSize = 11.sp,
              )

              definition.availability == TemplateAvailability.CACHED ->
                Text(
                  noteText("外部来源不可用 · 使用缓存", "External source unavailable · using cache"),
                  color = Color(0xFF8B5A00),
                  fontSize = 11.sp,
                )

              definition.error != null -> Text(definition.error, color = Color(0xFF8B0000), fontSize = 11.sp)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TemplatePreview(
  definition: TemplateDefinition?,
  preview: (String, Int, Int) -> Bitmap?,
  modifier: Modifier,
) {
  var bitmap by remember(definition?.id, definition?.fingerprint) { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(definition?.id, definition?.fingerprint) {
    val target = definition ?: return@LaunchedEffect
    bitmap = withContext(Dispatchers.IO) { preview(target.id, 180, 240) }
  }
  DisposableEffect(definition?.id, definition?.fingerprint) {
    onDispose { bitmap?.takeUnless(Bitmap::isRecycled)?.recycle() }
  }
  Box(modifier.background(Color.White).border(1.dp, Color(0xFFD0D0D0)), contentAlignment = Alignment.Center) {
    bitmap?.let {
      Image(
        it.asImageBitmap(),
        contentDescription = definition?.name,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
      )
    }
  }
}

@Composable
fun DocumentSettingsOverlay(
  template: TemplateDefinition?,
  templateEnabled: Boolean,
  preview: (String, Int, Int) -> Bitmap?,
  onTemplate: () -> Unit,
  onDismiss: () -> Unit,
) {
  EinkModalOverlay(onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(noteText("文档设置", "Document settings"), fontSize = 24.sp, modifier = Modifier.weight(1f))
        EinkDialogAction(noteText("关闭", "Close"), onClick = onDismiss)
      }
      if (templateEnabled) {
        TemplateSelectionRow(template, preview, onTemplate)
      } else {
        Surface(
          Modifier.fillMaxWidth(),
          shape = RectangleShape,
          color = Color(0xFFF2F2F2),
        ) {
          Text(
            noteText("Template\nPDF 页面使用原始文档背景", "Template\nPDF pages use the original document background"),
            Modifier.padding(16.dp),
            color = Color(0xFF666666),
          )
        }
      }
    }
  }
}
