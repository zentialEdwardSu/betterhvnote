package com.betterhv.note

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.betterhv.note.doc.PageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import com.betterhv.note.settings.HardwareBindingsPanel
import com.betterhv.note.notebook.PageManagerPanel
import com.betterhv.note.notebook.PageManagerPanelActions
import com.betterhv.note.notebook.PageManagerPanelState

@RunWith(AndroidJUnit4::class)
class PageAndBindingUiTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val device = UiDevice.getInstance(instrumentation)

  @Test fun linkedLanesRotateFilterAndBindingParametersPersist() {
    val source = PageUiInfo(UUID.randomUUID(), 1, true, 0, PageKind.PDF_SOURCE)
    val first = PageUiInfo(UUID.randomUUID(), 2, true, 0, PageKind.LINKED_NOTE, source.id, 1, 1)
    val second = first.copy(id = UUID.randomUUID(), pageNumber = 3, bookmarked = false, linkedOrdinal = 2)
    val pages = mutableStateOf(listOf(source, first, second))
    val edge = mutableStateOf(DockEdge.START)
    val requests = mutableSetOf<UUID>()
    val intent = Intent(context, MainActivity::class.java)
      .putExtra(MainActivity.EXTRA_SKIP_TEMPLATE_DIRECTORY_PROMPT, true)
    ActivityScenario.launch<MainActivity>(intent).use { scenario ->
      assertNotNull(device.wait(Until.findObject(By.desc("插入图片或文字")), 15_000))
      scenario.onActivity { activity ->
        activity.setContent {
          MaterialTheme {
            PageManagerPanel(PageManagerPanelState(Modifier.fillMaxSize(), edge.value, DpSize(150.dp, 180.dp),
              pages.value, source.id), PageManagerPanelActions({ null }, { requests.addAll(it) }, {},
              { it }, { false }, { _, _ -> false }, {}))
          }
        }
      }
      DockEdge.entries.forEach { dock ->
        scenario.onActivity { edge.value = dock }
        assertNotNull(device.wait(Until.findObject(By.text("夹页")), 5_000))
        awaitCondition {
          val a = device.findObject(By.text("1"))?.visibleBounds
          val b = device.findObject(By.text(first.label))?.visibleBounds
          a != null && b != null && if (dock == DockEdge.START || dock == DockEdge.END) {
            a.centerX() < b.centerX()
          } else a.centerY() < b.centerY()
        }
        assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "feature-pages-$dock.png")))
      }
      assertTrue(requests.containsAll(pages.value.map { it.id }))
      device.findObject(By.desc("书签页面")).click()
      assertTrue(device.wait(Until.gone(By.text(second.label)), 5_000))
      assertNotNull(device.findObject(By.text(first.label)))
      device.findObject(By.desc("全部页面")).click()
      scenario.onActivity { pages.value = listOf(source, first) }
      assertTrue(device.wait(Until.gone(By.text("夹页")), 5_000))
      scenario.onActivity { pages.value = listOf(source) }
      assertTrue(device.wait(Until.gone(By.text(first.label)), 5_000))

      val selected = mutableStateOf(HardwareKeyId.K1)
      val bindings = mutableStateOf(HardwareShortcutBindings())
      scenario.onActivity { activity ->
        activity.setContent {
          MaterialTheme { HardwareBindingsPanel(ShortcutScene.EDITOR, selected.value, bindings.value,
            { selected.value = it }, { bindings.value = bindings.value.bindCommand(ShortcutScene.EDITOR, selected.value, it) }) }
        }
      }
      assertNotNull(device.wait(Until.findObject(By.text("K8")), 5_000))
      device.findObject(By.text("K8")).click()
      device.findObject(By.clazz("android.widget.EditText")).text = "pen.type"
      requireNotNull(device.wait(Until.findObject(By.text("笔类型")), 5_000)).click()
      requireNotNull(device.wait(Until.findObject(By.text("当前笔槽")), 5_000))
      device.findObject(By.text("笔 2")).click()
      device.findObject(By.text("铅笔")).click()
      device.findObject(By.text("绑定到 K8")).click()
      awaitCondition { bindings.value.command(ShortcutScene.EDITOR, HardwareKeyId.K8) != null }
      assertEquals(CommandBinding("pen.type", mapOf("slot" to "1", "value" to "pencil")),
        bindings.value.command(ShortcutScene.EDITOR, HardwareKeyId.K8))
      assertNotNull(device.wait(Until.findObject(By.text("笔类型 · 笔 2 · 铅笔")), 5_000))
      assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "feature-bindings.png")))
      device.findObject(By.text("取消绑定")).click()
      awaitCondition { bindings.value.command(ShortcutScene.EDITOR, HardwareKeyId.K8) == null }
      assertNull(bindings.value.command(ShortcutScene.EDITOR, HardwareKeyId.K8))
    }
  }
  private fun awaitCondition(condition: () -> Boolean) {
    val deadline = android.os.SystemClock.uptimeMillis() + 5_000
    while (android.os.SystemClock.uptimeMillis() < deadline) {
      val ready = try { condition() } catch (_: androidx.test.uiautomator.StaleObjectException) { false }
      if (ready) return
      android.os.SystemClock.sleep(50)
    }
    assertTrue(condition())
  }
}
