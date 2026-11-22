package com.betterhv.note.sender.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleInstanceGuardTest {
  @Test
  fun secondInstanceSignalsTheFirst() {
    val root = createTempDirectory("notelink-single-instance-").toFile()
    val first = SingleInstanceGuard(root)
    try {
      assertTrue(first.tryAcquire())
      val shown = CountDownLatch(1)
      first.startListening { shown.countDown() }

      val second = SingleInstanceGuard(root)
      assertFalse(second.tryAcquire())
      assertTrue(second.signalExisting())
      assertTrue(shown.await(5, TimeUnit.SECONDS))
    } finally {
      first.close()
      root.deleteRecursively()
    }
  }

  @Test
  fun lockIsReleasedOnClose() {
    val root = createTempDirectory("notelink-single-instance-").toFile()
    val first = SingleInstanceGuard(root)
    assertTrue(first.tryAcquire())
    first.close()

    val second = SingleInstanceGuard(root)
    try {
      assertTrue(second.tryAcquire())
    } finally {
      second.close()
      root.deleteRecursively()
    }
  }

  @Test
  fun secondaryGuardClosePreservesActiveInstancePortFile() {
    val root = createTempDirectory("notelink-single-instance-").toFile()
    val first = SingleInstanceGuard(root)
    try {
      assertTrue(first.tryAcquire())
      val shown = CountDownLatch(2)
      first.startListening { shown.countDown() }

      val second = SingleInstanceGuard(root)
      assertFalse(second.tryAcquire())
      assertTrue(second.signalExisting())
      second.close()

      assertTrue(root.resolve("instance.json").isFile)

      val third = SingleInstanceGuard(root)
      assertFalse(third.tryAcquire())
      assertTrue(third.signalExisting())
      third.close()

      assertTrue(shown.await(5, TimeUnit.SECONDS))
    } finally {
      first.close()
      root.deleteRecursively()
    }
  }

  @Test
  fun stalePortFileDoesNotAcquireOrSignal() {
    val root = createTempDirectory("notelink-single-instance-").toFile()
    root.resolve("instance.json").writeText("port=1\n")
    val guard = SingleInstanceGuard(root)
    try {
      assertFalse(guard.signalExisting())
      assertTrue(guard.tryAcquire())
    } finally {
      guard.close()
      root.deleteRecursively()
    }
  }
}
