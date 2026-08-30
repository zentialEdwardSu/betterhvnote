package com.betterhv.note

import android.graphics.Bitmap
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.betterhv.note.doc.ImageObject
import com.betterhv.note.doc.TextFontFamily
import com.betterhv.note.doc.TextObject
import com.betterhv.note.storage.ImportedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RichObjectUiTest {
    @get:Rule
    val rule = ActivityScenarioRule<MainActivity>(
        Intent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MainActivity::class.java
        ).putExtra(MainActivity.EXTRA_SKIP_TEMPLATE_DIRECTORY_PROMPT, true)
    )

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun wakeAndShowActivity() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        rule.scenario.onActivity { activity ->
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.setTurnScreenOn(true)
            activity.setShowWhenLocked(true)
        }
        device.waitForIdle()
    }

    @Test
    fun bundledTemplatesAreCompatibleWithTheN10ProEditorPage() {
        rule.scenario.onActivity { activity ->
            val penView = requireNotNull(activity.currentPenViewForTest())
            val (pageWidth, pageHeight) = penView.currentPageSize()
            listOf("builtin.blank", "builtin.single-lines", "builtin.dotted").forEach { id ->
                val definition = requireNotNull(penView.templateCatalog().find(id))
                assertTrue(
                    "$id should match editor page ${pageWidth}x$pageHeight",
                    definition.isCompatible(pageWidth, pageHeight)
                )
            }
        }
    }

    @Test
    fun insertTextThenEditFontAndSizeFromFloatingToolbar() {
        device.wait(Until.findObject(By.desc("插入图片或文字")), UI_TIMEOUT)!!.click()
        device.wait(Until.findObject(By.desc("插入文字")), UI_TIMEOUT)!!.click()
        device.wait(Until.findObject(By.text("手动输入")), UI_TIMEOUT)!!.click()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        device.waitForIdle()
        device.click(900, 1300)
        requireNotNull(device.wait(Until.findObject(By.text("插入文字")), UI_TIMEOUT)) {
            "文字输入对话框未显示"
        }
        device.findObject(By.text("文字内容")).click()
        device.executeShellCommand("input text automatedtext")
        device.pressBack()
        device.waitForIdle()
        requireNotNull(device.wait(Until.findObject(By.text("确定")), UI_TIMEOUT)) {
            "文字对话框确定按钮未显示"
        }.click()

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(device.wait(Until.gone(By.text("插入文字")), UI_TIMEOUT))

        rule.scenario.onActivity { activity ->
            val pen = activity.currentPenViewForTest()!!
            val inserted = pen.richObjectsForTest()
                .filterIsInstance<TextObject>()
                .last()
            assertTrue(inserted.text.isNotBlank())
            val center = inserted.pageBounds
            assertTrue(
                pen.selectRichObjectAt(
                    (center.left + center.right) / 2f,
                    (center.top + center.bottom) / 2f
                )
            )
            pen.updateTextObject(
                inserted.id,
                text = "automatedtext",
                fontFamily = TextFontFamily.SERIF,
                fontSize = 32f
            )
            val text = pen.selectedRichObject() as TextObject
            assertEquals("automatedtext", text.text)
            assertEquals(TextFontFamily.SERIF, text.fontFamily)
            assertEquals(32f, text.fontSize)

            val initialBounds = text.pageBounds
            assertTrue(pen.beginRichObjectGesture(initialBounds.right, initialBounds.bottom))
            pen.updateRichObjectGesture(initialBounds.right + 80f, initialBounds.bottom + 50f)
            pen.endRichObjectGesture(cancelled = false)
            val resized = pen.selectedRichObject() as TextObject
            assertNotEquals(text.transform, resized.transform)
            assertTrue(resized.pageBounds.width > initialBounds.width)
            assertTrue(resized.pageBounds.height > initialBounds.height)
        }
    }

    @Test
    fun imageCanMoveScaleRotateAndUndoWithoutUsingProductionData() {
        lateinit var image: ImageObject
        rule.scenario.onActivity { activity ->
            val pen = activity.currentPenViewForTest()!!
            val asset = File(activity.filesDir, "documents/assets/ui-test.png")
            asset.parentFile!!.mkdirs()
            asset.outputStream().use {
                Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image = pen.placeImage(
                ImportedImage("assets/ui-test.png", "image/png", 80, 60), 500f, 700f
            )
            val before = image.transform
            assertTrue(pen.beginRichObjectGesture(500f, 700f))
            pen.updateRichObjectGesture(580f, 760f)
            pen.endRichObjectGesture(cancelled = false)
            val moved = pen.selectedRichObject() as ImageObject
            assertNotEquals(before, moved.transform)
            pen.undo()
            assertEquals(before, (pen.selectedRichObject() as ImageObject).transform)

            val initialBounds = (pen.selectedRichObject() as ImageObject).pageBounds
            assertTrue(pen.beginRichObjectGesture(initialBounds.right, initialBounds.bottom))
            pen.updateRichObjectGesture(initialBounds.right + 80f, initialBounds.bottom + 60f)
            pen.endRichObjectGesture(cancelled = false)
            val scaledTransform = (pen.selectedRichObject() as ImageObject).transform
            assertNotEquals(before, scaledTransform)

            val scaledBounds = (pen.selectedRichObject() as ImageObject).pageBounds
            val centerX = (scaledBounds.left + scaledBounds.right) / 2f
            val centerY = (scaledBounds.top + scaledBounds.bottom) / 2f
            assertTrue(pen.beginRichObjectGesture(centerX, scaledBounds.top - 56f))
            pen.updateRichObjectGesture(scaledBounds.right + 56f, centerY)
            pen.endRichObjectGesture(cancelled = false)
            val rotatedTransform = (pen.selectedRichObject() as ImageObject).transform
            assertNotEquals(scaledTransform, rotatedTransform)
            pen.undo()
            assertEquals(scaledTransform, (pen.selectedRichObject() as ImageObject).transform)
            pen.redo()
            assertEquals(rotatedTransform, (pen.selectedRichObject() as ImageObject).transform)

            pen.selectRichObjectAt(500f, 700f)
            val selected = pen.selectedRichObject() as ImageObject
            assertEquals(image.id, selected.id)
        }
    }

    @Test
    fun side1OnMenuOpensQuickPanelWithoutDirectlyEnablingDebug() {
        val menu = requireNotNull(
            device.wait(
                Until.findObject(By.desc("Settings: tap to open; Side1 opens quick panel")),
                UI_TIMEOUT
            )
        ) { "菜单按钮未显示" }

        sendSide1Click(menu.visibleCenter.x.toFloat(), menu.visibleCenter.y.toFloat())
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        requireNotNull(device.wait(Until.findObject(By.text("开启 Debug")), UI_TIMEOUT)) {
            "Side1 未打开快捷面板"
        }
        assertNull(device.findObject(By.text("Flush")))
    }

    private fun sendSide1Click(x: Float, y: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_STYLUS
            }
        )
        val coordinates = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            }
        )
        fun event(action: Int, eventTime: Long, buttons: Int): MotionEvent = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            properties,
            coordinates,
            0,
            buttons,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_STYLUS,
            0
        )

        event(MotionEvent.ACTION_DOWN, downTime, PenButtonTracker.SIDE_KEY_1_MASK).also {
            instrumentation.sendPointerSync(it)
            it.recycle()
        }
        event(MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), 0).also {
            instrumentation.sendPointerSync(it)
            it.recycle()
        }
    }

    companion object {
        private const val UI_TIMEOUT = 15_000L
    }
}
