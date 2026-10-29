package com.betterhv.note

import android.annotation.SuppressLint
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.betterhv.note.toolbar.ToolbarCatalog
import com.betterhv.note.CommandBinding
import com.betterhv.note.HardwareKeyId
import com.betterhv.note.ShortcutScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@SuppressLint("DiscouragedApi")
@RunWith(AndroidJUnit4::class)
class ToolbarObjectificationUiTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val device = UiDevice.getInstance(instrumentation)

  @Test fun catalogToolsDriveToolbarSurfacesAndSelection() {
    val settings = AppSettingsStore(context)
    val oldVisible = settings.visibleToolbarItems
    val oldHidden = settings.toolbarHidden
    settings.visibleToolbarItems = ToolbarCatalog.defaultVisibleItems
    settings.toolbarHidden = false
    val intent = Intent(context, MainActivity::class.java)
      .putExtra(MainActivity.EXTRA_SKIP_TEMPLATE_DIRECTORY_PROMPT, true)
    try {
      ActivityScenario.launch<MainActivity>(intent).use { scenario ->
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        assertNotNull(device.wait(Until.findObject(By.desc("插入图片或文字")), 15_000))

        device.findObject(By.desc("套索")).click()
        device.waitForIdle()
        scenario.onActivity {
          assertEquals(ToolKind.LASSO, it.currentPenViewForTest()!!.currentToolKind())
        }

        device.findObject(By.desc("笔 1")).click()
        device.waitForIdle()
        scenario.onActivity {
          assertEquals(ToolKind.PEN, it.currentPenViewForTest()!!.currentToolKind())
        }

        device.findObject(By.desc("插入图片或文字")).click()
        assertNotNull(device.wait(Until.findObject(By.desc("插入文字")), 5_000))
        device.pressBack()
        device.waitForIdle()

        val pageManager = requireNotNull(
          device.wait(Until.findObject(By.desc("页面管理")), 5_000),
        )
        pageManager.click()
        assertNotNull(device.wait(Until.findObject(By.desc("全部页面")), 5_000))
        assertTrue(
          device.takeScreenshot(
            File(context.getExternalFilesDir(null), "toolbar-page-manager.png"),
          ),
        )
        device.pressBack()
        device.waitForIdle()

        val notebooks = requireNotNull(
          device.wait(Until.findObject(By.desc("笔记本管理")), 5_000),
        )
        notebooks.click()
        assertNotNull(device.wait(Until.findObject(By.text("笔记本")), 10_000))
        assertTrue(
          device.takeScreenshot(
            File(context.getExternalFilesDir(null), "toolbar-notebook-manager.png"),
          ),
        )
        device.pressBack()
        device.waitForIdle()

        val export = requireNotNull(
          device.wait(Until.findObject(By.desc("导出管理")), 5_000),
        )
        export.click()
        assertNotNull(device.wait(Until.findObject(By.desc("新建导出任务")), 10_000))
        assertTrue(
          device.takeScreenshot(
            File(context.getExternalFilesDir(null), "toolbar-export-manager.png"),
          ),
        )
        device.pressBack()
        device.waitForIdle()
      }
    } finally {
      settings.visibleToolbarItems = oldVisible
      settings.toolbarHidden = oldHidden
    }
  }

  @Test fun k1HardwareKeyUsesTheSameToolbarCommand() {
    val settings = AppSettingsStore(context)
    val previous = settings.loadHardwareShortcuts()
      .command(ShortcutScene.EDITOR, HardwareKeyId.K1)
    settings.saveCommandBinding(
      ShortcutScene.EDITOR,
      HardwareKeyId.K1,
      CommandBinding("editor.pages"),
    )
    val intent = Intent(context, MainActivity::class.java)
      .putExtra(MainActivity.EXTRA_SKIP_TEMPLATE_DIRECTORY_PROMPT, true)
    try {
      ActivityScenario.launch<MainActivity>(intent).use {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        assertNotNull(device.wait(Until.findObject(By.desc("页面管理")), 15_000))
        instrumentation.sendKeyDownUpSync(519)
        assertNotNull(device.wait(Until.findObject(By.desc("全部页面")), 5_000))
        device.pressBack()
      }
    } finally {
      settings.saveCommandBinding(ShortcutScene.EDITOR, HardwareKeyId.K1, previous)
    }
  }

  @Test fun sideButtonsOnPagesAndNotebooksUseTheirDerivedCommands() {
    val intent = Intent(context, MainActivity::class.java)
      .putExtra(MainActivity.EXTRA_SKIP_TEMPLATE_DIRECTORY_PROMPT, true)
    ActivityScenario.launch<MainActivity>(intent).use {
      device.wakeUp()
      device.executeShellCommand("wm dismiss-keyguard")
      val pages = requireNotNull(
        device.wait(Until.findObject(By.desc("页面管理")), 15_000),
      )
      val pageCenter = pages.visibleCenter
      pages.click()
      assertNotNull(device.wait(Until.findObject(By.desc("全部页面")), 5_000))
      val initial = requireNotNull(waitForPageProgress())

      sendStylusClick(pageCenter.x, pageCenter.y, PenButtonTracker.SIDE_KEY_1_MASK)
      val afterAdd = requireNotNull(waitForPageProgress { it.second == initial.second + 1 })
      if (afterAdd.first > 1) {
        sendStylusClick(pageCenter.x, pageCenter.y, PenButtonTracker.SIDE_KEY_2_MASK)
        requireNotNull(waitForPageProgress { it.first == afterAdd.first - 1 })
        sendStylusClick(pageCenter.x, pageCenter.y, PenButtonTracker.SIDE_KEY_3_MASK)
        requireNotNull(waitForPageProgress { it.first == afterAdd.first })
      } else {
        sendStylusClick(pageCenter.x, pageCenter.y, PenButtonTracker.SIDE_KEY_3_MASK)
        requireNotNull(waitForPageProgress { it.first == afterAdd.first + 1 })
        sendStylusClick(pageCenter.x, pageCenter.y, PenButtonTracker.SIDE_KEY_2_MASK)
        requireNotNull(waitForPageProgress { it.first == afterAdd.first })
      }

      device.pressBack()
      val notebooks = requireNotNull(
        device.wait(Until.findObject(By.desc("笔记本管理")), 5_000),
      )
      val notebookCenter = notebooks.visibleCenter
      sendStylusClick(notebookCenter.x, notebookCenter.y, PenButtonTracker.SIDE_KEY_2_MASK)
      assertNotNull(device.wait(Until.findObject(By.text("命名笔记本")), 5_000))
      device.pressBack()
    }
  }

  @Test fun side1StylusClickOnPenButtonOpensPenSettings() {
    val intent = Intent(context, MainActivity::class.java)
      .putExtra(MainActivity.EXTRA_SKIP_TEMPLATE_DIRECTORY_PROMPT, true)
    ActivityScenario.launch<MainActivity>(intent).use {
      device.wakeUp()
      device.executeShellCommand("wm dismiss-keyguard")
      val pen = requireNotNull(
        device.wait(Until.findObject(By.desc("笔 1")), 15_000),
      )
      val center = pen.visibleCenter
      sendStylusClick(center.x, center.y, PenButtonTracker.SIDE_KEY_1_MASK)
      assertNotNull(device.wait(Until.findObject(By.text("笔设置")), 5_000))
      device.pressBack()
    }
  }

  @Test fun menuAndPenSettingsOpenFromObjectifiedFlyouts() {
    val intent = Intent(context, MainActivity::class.java)
      .putExtra(MainActivity.EXTRA_SKIP_TEMPLATE_DIRECTORY_PROMPT, true)
    ActivityScenario.launch<MainActivity>(intent).use {
      device.wakeUp()
      device.executeShellCommand("wm dismiss-keyguard")
      assertNotNull(device.wait(Until.findObject(By.desc("插入图片或文字")), 15_000))

      device.findObject(By.desc("Settings: tap to open; Side1 opens quick panel")).click()
      assertNotNull(device.wait(Until.findObject(By.desc("应用设置")), 5_000))
      assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "toolbar-menu.png")))
      device.pressBack()
      device.waitForIdle()

      val pen = requireNotNull(
        device.wait(Until.findObject(By.desc("笔 1")), 5_000),
      )
      val center = pen.visibleCenter
      device.click(center.x, center.y)
      SystemClock.sleep(100)
      device.click(center.x, center.y)
      assertNotNull(device.wait(Until.findObject(By.text("笔设置")), 5_000))
      assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "toolbar-pen-settings.png")))
      device.pressBack()
    }
  }

  private fun waitForPageProgress(
    predicate: (Pair<Int, Int>) -> Boolean = { true },
  ): Pair<Int, Int>? {
    repeat(50) {
      val title = device.findObject(By.textContains("页面 "))?.text
      val match = title?.let { Regex("页面 (\\d+)/(\\d+)").find(it) }
      if (match != null) {
        val progress = match.groupValues[1].toInt() to match.groupValues[2].toInt()
        if (predicate(progress)) return progress
      }
      SystemClock.sleep(100)
    }
    return null
  }

  private fun sendStylusClick(x: Int, y: Int, buttonState: Int) {
    val properties = MotionEvent.PointerProperties().apply {
      id = 0
      toolType = MotionEvent.TOOL_TYPE_STYLUS
    }
    val coordinates = MotionEvent.PointerCoords().apply {
      this.x = x.toFloat()
      this.y = y.toFloat()
      pressure = 1f
    }
    val now = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(
      now, now, MotionEvent.ACTION_DOWN, 1, arrayOf(properties), arrayOf(coordinates),
      0, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0,
    )
    val up = MotionEvent.obtain(
      now, now + 40, MotionEvent.ACTION_UP, 1, arrayOf(properties), arrayOf(coordinates),
      0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0,
    )
    try {
      instrumentation.sendPointerSync(down)
      instrumentation.sendPointerSync(up)
    } finally {
      down.recycle()
      up.recycle()
    }
  }
}
