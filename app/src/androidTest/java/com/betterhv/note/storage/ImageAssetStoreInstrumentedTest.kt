package com.betterhv.note.storage

import androidx.core.graphics.createBitmap

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageAssetStoreInstrumentedTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(target.cacheDir, "image-store-tests")
    private val context: Context = object : ContextWrapper(target) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
    }

    @Before fun setUp() { root.deleteRecursively(); root.mkdirs() }
    @After fun tearDown() { root.deleteRecursively() }

    @Test fun stagedImageCanBeDiscardedOrAtomicallyCommitted() {
        val source = File(root, "source.png")
        source.parentFile!!.mkdirs()
        source.outputStream().use {
            createBitmap(8, 6).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val store = ImageAssetStore(context)
        val discarded = store.stageFile(source, "image/png")
        assertTrue(File(root, "documents/${discarded.relativePath}").isFile)
        store.discard(discarded)
        assertFalse(File(root, "documents/${discarded.relativePath}").exists())

        val staged = store.stageFile(source, "image/png")
        val committed = store.commit(staged)
        assertTrue(committed.relativePath.startsWith("assets/"))
        assertTrue(store.resolve(committed.relativePath)!!.isFile)
        assertFalse(File(root, "documents/${staged.relativePath}").exists())
    }
}
