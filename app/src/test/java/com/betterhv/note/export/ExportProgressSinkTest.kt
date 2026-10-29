package com.betterhv.note.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ExportProgressSinkTest {
  private var now = 1_000L
  private val emitted = mutableListOf<ExportProgress>()
  private val sink = ExportProgressSink(emitted::add, clock = { now })

  @Test fun stageAndPageChangesEmitImmediatelyButRepeatedUpdatesAreThrottled() {
    sink.begin(ExportProgress.Stage.RENDERING, total = 10)
    assertEquals(1, emitted.size)

    now += 50
    sink.update(ExportProgress.Stage.RENDERING, 1, 10, pageId())
    assertEquals(2, emitted.size)

    now += 50
    sink.update(ExportProgress.Stage.RENDERING, 1, 10, pageId())
    assertEquals("same page within the throttle window must not emit", 2, emitted.size)

    now += 50
    sink.update(ExportProgress.Stage.RENDERING, 2, 10, pageId())
    assertEquals("a new page emits immediately", 3, emitted.size)

    now += 50
    sink.begin(ExportProgress.Stage.ASSEMBLING, total = 10)
    assertEquals("a stage change emits immediately", 4, emitted.size)
  }

  @Test fun ticksRefreshElapsedAtMostOncePerThrottleWindow() {
    sink.begin(ExportProgress.Stage.WRITING, total = 1)
    val start = emitted.size
    repeat(20) {
      now += 50
      sink.tick()
    }
    // 1000 ms of ticks at a 200 ms throttle emits at most five updates.
    assertTrue("tick count was ${emitted.size - start}", emitted.size - start <= 6)
    assertTrue(emitted.last().elapsedMs > 0)
  }

  @Test fun elapsedAndStageElapsedAreMonotonic() {
    sink.begin(ExportProgress.Stage.PREPARING)
    now += 300
    sink.begin(ExportProgress.Stage.RENDERING, total = 3)
    now += 700
    sink.update(ExportProgress.Stage.RENDERING, 1, 3, pageId(), force = true)

    var previousElapsed = 0L
    emitted.forEach { progress ->
      assertTrue(progress.elapsedMs >= previousElapsed)
      previousElapsed = progress.elapsedMs
      assertTrue(progress.stageElapsedMs <= progress.elapsedMs)
    }
    assertEquals(ExportProgress.Stage.RENDERING, emitted.last().stage)
    assertEquals(1, emitted.last().current)
    assertEquals(3, emitted.last().total)
  }

  private fun pageId() = UUID.fromString("00000000-0000-0000-0000-000000000002")
}
