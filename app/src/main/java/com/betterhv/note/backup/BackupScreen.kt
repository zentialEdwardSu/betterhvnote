package com.betterhv.note.backup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

object BackupMaintenance {
  val mutex = Mutex()
}

@Composable
fun BackupScreen(onClose: () -> Unit, initialError: String? = null) {
  val context = LocalContext.current
  val manager = remember { BackupManager(context.applicationContext) }
  val scope = rememberCoroutineScope()
  var recoveryRequired by remember { mutableStateOf(initialError != null) }
  var busy by remember { mutableStateOf(false) }
  var committing by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf(initialError ?: "备份包含全部笔记、图片、PDF、自定义模板和设置。") }
  var progress by remember { mutableFloatStateOf(0f) }
  var prepared by remember { mutableStateOf<PreparedBackup?>(null) }
  var hasRollback by remember { mutableStateOf(manager.hasRollback()) }
  var job by remember { mutableStateOf<Job?>(null) }

  fun runOperation(commit: Boolean = false, operation: suspend ((Int, Int) -> Unit) -> Unit) {
    if (busy) return
    busy = true; committing = commit; progress = 0f
    job = scope.launch {
      try {
        val operationContext = currentCoroutineContext()
        withContext(if (commit) Dispatchers.IO + NonCancellable else Dispatchers.IO) {
          BackupMaintenance.mutex.withLock {
            operation { done, total ->
              if (!commit) operationContext.ensureActive()
              progress = if (total == 0) 0f else done.toFloat() / total
            }
          }
        }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        message = "已取消"
        throw cancelled
      } catch (error: Exception) {
        message = error.message ?: "操作失败"
      } finally {
        busy = false; committing = false; hasRollback = manager.hasRollback()
      }
    }
  }

  val exportPicker = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("application/zip"),
  ) { uri ->
    if (uri != null) runOperation { report ->
      message = "正在导出备份…"
      try {
        manager.export(uri, report)
        message = "备份已导出"
      } catch (error: Throwable) {
        runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
        throw error
      }
    }
  }
  val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) runOperation { report ->
      message = "正在校验备份…"
      prepared = manager.prepare(uri, report)
      message = "备份校验通过，请确认恢复"
    }
  }

  BackHandler {
    if (!busy) {
      if (prepared != null) { manager.discard(prepared); prepared = null }
      else if (!recoveryRequired) onClose()
    }
  }
  DisposableEffect(Unit) { onDispose { if (!busy) manager.discard(prepared) } }
  Surface(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("备份与恢复", style = MaterialTheme.typography.headlineMedium)
      Text(message)
      Text("导入将整体替换笔记与设置。NoteLink 配对保留在本机；恢复前会保存一份回滚副本。")
      if (busy) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Text(if (committing) "正在恢复，请等待完成" else "正在处理文件…")
        if (!committing) TextButton(onClick = { job?.cancel() }) { Text("取消") }
      }
      if (prepared == null) {
        Button(onClick = { exportPicker.launch("BetterHvNote-${System.currentTimeMillis()}.zip") },
          enabled = !busy && !recoveryRequired) { Text("导出完整备份") }
        Button(onClick = { importPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
          enabled = !busy && !recoveryRequired) { Text("导入备份") }
        OutlinedButton(onClick = {
          runOperation { prepared = manager.prepareRollback(); message = "恢复前副本已就绪" }
        }, enabled = !busy && hasRollback && !recoveryRequired) { Text("回滚到恢复前副本") }
        if (recoveryRequired) Button(onClick = {
          runOperation(commit = true) {
            manager.recoverInterruptedRestore(); recoveryRequired = false; message = "回滚完成，可以返回笔记"
          }
        }, enabled = !busy) { Text("重试恢复中断回滚") }
        TextButton(onClick = onClose, enabled = !busy && !recoveryRequired) { Text("返回笔记") }
      } else {
        val candidate = requireNotNull(prepared)
        Text("备份时间：${DateFormat.getDateTimeInstance().format(Date(candidate.manifest.createdAt))}")
        Text("笔记本数量：${candidate.manifest.notebookCount}")
        Text("确认后，当前笔记与设置将被此备份整体替换。")
        Button(onClick = {
          runOperation(commit = true) {
            prepared = null
            try { manager.restore(candidate, it) } catch (error: Exception) {
              recoveryRequired = manager.needsRecovery()
              throw error
            } finally { manager.discard(candidate) }
            message = "恢复完成，返回后将重新加载笔记"
          }
        }, enabled = !busy) { Text("确认整体恢复") }
        TextButton(onClick = { manager.discard(candidate); prepared = null }, enabled = !busy) { Text("取消") }
      }
    }
  }
}
