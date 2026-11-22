package com.betterhv.note

import android.annotation.SuppressLint
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.betterhv.note.settings.AppSettingsStore

@SuppressLint("DiscouragedApi")
@RunWith(AndroidJUnit4::class)
class EinkStyleUiTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val device = UiDevice.getInstance(instrumentation)

  @Test fun settingsPageUsesTheSameEinkChrome() {
    val settings = AppSettingsStore(context)
    settings.toolbarHidden = false
    val intent = Intent(context, MainActivity::class.java)
      .putExtra(MainActivity.EXTRA_SKIP_TEMPLATE_DIRECTORY_PROMPT, true)
    ActivityScenario.launch<MainActivity>(intent).use {
      device.wakeUp()
      device.executeShellCommand("wm dismiss-keyguard")
      assertNotNull(device.wait(Until.findObject(By.desc("Settings: tap to open; Side1 opens quick panel")), 15_000))
      device.findObject(By.desc("Settings: tap to open; Side1 opens quick panel")).click()
      val appSettings = requireNotNull(device.wait(Until.findObject(By.text("应用设置")), 5_000))
      appSettings.click()
      assertNotNull(device.wait(Until.findObject(By.text("设置")), 5_000))
      assertNotNull(device.findObject(By.text("常规")))
      assertNotNull(device.findObject(By.text("工具栏")))
      assertNotNull(device.findObject(By.text("物理按键")))
      assertNotNull(device.findObject(By.text("NoteLink")))
      assertTrue(
        device.takeScreenshot(
          File(context.getExternalFilesDir(null), "eink-settings-general.png"),
        ),
      )
      device.findObject(By.text("工具栏")).click()
      assertNotNull(device.wait(Until.findObject(By.text("工具栏显示")), 5_000))
      assertTrue(
        device.takeScreenshot(
          File(context.getExternalFilesDir(null), "eink-settings-toolbar.png"),
        ),
      )

      device.findObject(By.text("物理按键")).click()
      assertNotNull(device.wait(Until.findObject(By.text("搜索功能")), 5_000))
      assertTrue(
        device.takeScreenshot(
          File(context.getExternalFilesDir(null), "eink-settings-shortcuts.png"),
        ),
      )

      device.findObject(By.text("NoteLink")).click()
      assertNotNull(device.wait(Until.findObject(By.text("NoteLink 客户端")), 5_000))
      assertTrue(
        device.takeScreenshot(
          File(context.getExternalFilesDir(null), "eink-settings-notelink.png"),
        ),
      )

      device.findObject(By.text("常规")).click()
      val backup = requireNotNull(device.wait(Until.findObject(By.text("备份与恢复")), 5_000))
      backup.click()
      assertNotNull(device.wait(Until.findObject(By.text("导出完整备份")), 5_000))
      assertTrue(
        device.takeScreenshot(
          File(context.getExternalFilesDir(null), "eink-settings-backup.png"),
        ),
      )
      device.pressBack()
    }
  }

  @Test fun toolbarAndInsertFlyoutCaptureTheUnifiedStyle() {
    val settings = AppSettingsStore(context)
    settings.toolbarHidden = false
    val intent = Intent(context, MainActivity::class.java)
      .putExtra(MainActivity.EXTRA_SKIP_TEMPLATE_DIRECTORY_PROMPT, true)
    ActivityScenario.launch<MainActivity>(intent).use {
      device.wakeUp()
      device.executeShellCommand("wm dismiss-keyguard")
      assertNotNull(device.wait(Until.findObject(By.desc("插入图片或文字")), 15_000))
      assertTrue(
        device.takeScreenshot(
          File(context.getExternalFilesDir(null), "eink-toolbar.png"),
        ),
      )

      device.findObject(By.desc("插入图片或文字")).click()
      assertNotNull(device.wait(Until.findObject(By.desc("插入文字")), 5_000))
      assertTrue(
        device.takeScreenshot(
          File(context.getExternalFilesDir(null), "eink-insert-flyout.png"),
        ),
      )
      device.pressBack()
    }
  }
}
