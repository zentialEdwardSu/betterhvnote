package com.betterhv.note

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.betterhv.transfer.core.ContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EditorFeatureUiTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val device = UiDevice.getInstance(instrumentation)

  @android.annotation.SuppressLint("DiscouragedApi") // Device test must dismiss the ROM keyguard.
  @Test fun toolbarHardwareTailClipboardAndMaintenanceWorkOnDevice() {
    val settings = AppSettingsStore(context)
    val previousBindings = settings.loadHardwareShortcuts()
    val oldVisible = settings.visibleToolbarItems
    val oldHidden = settings.toolbarHidden
    val imageFile = File(context.filesDir, "updates/clipboard-feature-test.png")
    settings.toolbarHidden = false
    settings.visibleToolbarItems = ToolbarItem.defaults
    HardwareKeyId.entries.forEach { key ->
      settings.saveCommandBinding(ShortcutScene.EDITOR, key, CommandBinding("editor.tail_eraser"))
    }
    val intent = Intent(context, MainActivity::class.java)
      .putExtra(MainActivity.EXTRA_SKIP_TEMPLATE_DIRECTORY_PROMPT, true)
    try {
      ActivityScenario.launch<MainActivity>(intent).use { scenario ->
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        assertNotNull(device.wait(Until.findObject(By.desc("插入图片或文字")), 15_000))
        var originalNotebook: UUID? = null
        val created = CompletableFuture<UUID>()
        scenario.onActivity { activity ->
          val pen = requireNotNull(activity.currentPenViewForTest())
          originalNotebook = pen.currentNotebookId()
          pen.createBlankNotebook("Feature test ${UUID.randomUUID()}", onComplete = {
            it.fold(created::complete, created::completeExceptionally)
          })
        }
        val testNotebook = created.get(20, TimeUnit.SECONDS)
        device.waitForIdle()
        try {
          assertNull(device.findObject(By.desc("PDF 导航")))
          var mode = EraserMode.WHOLE_STROKE
          scenario.onActivity { mode = it.currentPenViewForTest()!!.currentEraserMode() }
          val eraser = device.findObject(By.descContains("点击切换"))
          assertNotNull(eraser)
          eraser.click()
          device.waitForIdle()
          scenario.onActivity {
            assertTrue(mode != it.currentPenViewForTest()!!.currentEraserMode())
            mode = it.currentPenViewForTest()!!.currentEraserMode()
            assertEquals(ToolKind.PEN, it.currentPenViewForTest()!!.currentToolKind())
          }
          assertNull(device.findObject(By.text("笔尾橡皮模式")))
          HardwareKeyId.entries.forEach { key ->
            scenario.onActivity { activity ->
              val now = SystemClock.uptimeMillis()
              activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, 488 + key.number, 0))
              activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, 488 + key.number, 1))
              activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, 488 + key.number, 0))
            }
            device.waitForIdle()
            scenario.onActivity {
              assertTrue("K${key.number}", mode != it.currentPenViewForTest()!!.currentEraserMode())
              mode = it.currentPenViewForTest()!!.currentEraserMode()
            }
          }
          scenario.onActivity { activity ->
            val pen = activity.currentPenViewForTest()!!
            val text = pen.placeText("tail-selection", 400f, 600f)
            assertTrue(pen.selectRichObjectAt(400f, 600f))
            val overlay = EraserOverlayView(activity, pen)
            pen.setEraserInputBlocked(true)
            tailEvent(overlay, MotionEvent.ACTION_DOWN, 400f, 600f)
            tailEvent(overlay, MotionEvent.ACTION_UP, 400f, 600f)
            assertTrue(pen.richObjectsForTest().any { it.id == text.id })
            pen.setEraserInputBlocked(false)
            tailEvent(overlay, MotionEvent.ACTION_DOWN, 400f, 600f)
            tailEvent(overlay, MotionEvent.ACTION_MOVE, 600f, 800f)
            tailEvent(overlay, MotionEvent.ACTION_UP, 600f, 800f)
            assertFalse(pen.richObjectsForTest().any { it.id == text.id })
            pen.undo()
            assertTrue(pen.richObjectsForTest().any { it.id == text.id })
            pen.clearRichSelection()
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val oldClip = clipboard.primaryClip
            try {
              clipboard.setPrimaryClip(ClipData.newPlainText("test", "line 1\nline 2"))
              assertEquals(ClipboardContent.Text("line 1\nline 2"), ClipboardSource.read(activity, ContentKind.TEXT))
              imageFile.parentFile!!.mkdirs()
              val bitmap = createBitmap(16, 16)
              imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
              bitmap.recycle()
              val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", imageFile)
              clipboard.setPrimaryClip(ClipData.newUri(activity.contentResolver, "test image", uri))
              val image = ClipboardSource.read(activity, ContentKind.IMAGE) as ClipboardContent.Image
              val imported = pen.importImage(image.uri)
              assertEquals(16, imported.pixelWidth)
              pen.discardImportedImage(imported)
            } finally {
              if (oldClip == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(oldClip)
            }
          }
          device.waitForIdle()
          device.findObject(By.desc("插入图片或文字")).click()
          assertNotNull(device.wait(Until.findObject(By.desc("插入文字")), 5_000))
          device.findObject(By.desc("插入文字")).click()
          assertNotNull(device.wait(Until.findObject(By.text("剪贴板来源")), 5_000))
          device.pressBack()
        } finally {
          val restored = CompletableFuture<UUID>()
          scenario.onActivity { it.currentPenViewForTest()!!.switchNotebook(requireNotNull(originalNotebook)) { result ->
            result.fold(restored::complete, restored::completeExceptionally)
          } }
          restored.get(20, TimeUnit.SECONDS)
          val removed = CompletableFuture<UUID>()
          scenario.onActivity { it.currentPenViewForTest()!!.deleteNotebook(testNotebook) { result ->
            result.fold(removed::complete, removed::completeExceptionally)
          } }
          removed.get(20, TimeUnit.SECONDS)
        }
        // Opening maintenance must close all sessions and be able to rebuild a fresh editor.
        device.waitForIdle()
        requireNotNull(device.wait(Until.findObject(By.desc("Settings: tap to open; Side1 opens quick panel")), 10_000)).click()
        assertNotNull(device.wait(Until.findObject(By.text("应用设置")), 5_000))
        device.findObject(By.text("应用设置")).click()
        repeat(5) {
          if (device.findObject(By.text("备份与恢复")) == null) {
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.8f)
            device.waitForIdle()
          }
        }
        val backup = requireNotNull(device.findObject(By.text("备份与恢复")))
        run {
          backup.click()
          assertNotNull(device.wait(Until.findObject(By.text("导出完整备份")), 20_000))
          device.findObject(By.text("返回笔记")).click()
          assertNotNull(device.wait(Until.findObject(By.desc("插入图片或文字")), 15_000))
        }
      }
    } finally {
      HardwareKeyId.entries.forEach { key ->
        settings.saveCommandBinding(ShortcutScene.EDITOR, key, previousBindings.command(ShortcutScene.EDITOR, key))
      }
      settings.visibleToolbarItems = oldVisible
      settings.toolbarHidden = oldHidden
      imageFile.delete()
    }
  }
  private fun tailEvent(overlay: EraserOverlayView, action: Int, x: Float, y: Float) {
    val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_ERASER }
    val coordinates = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f }
    val now = SystemClock.uptimeMillis()
    val event = MotionEvent.obtain(now, now, action, 1, arrayOf(properties), arrayOf(coordinates),
      0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0)
    try { overlay.onTouchEvent(event) } finally { event.recycle() }
  }
}
