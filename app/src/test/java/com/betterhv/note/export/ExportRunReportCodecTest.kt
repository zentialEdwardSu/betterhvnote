package com.betterhv.note.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class ExportRunReportCodecTest {
  private val taskId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

  @Test fun validReportRoundTrips() {
    val report = ExportRunReport(
      taskId = taskId,
      mode = ExportRenderMode.FAST,
      result = ExportRunResult.SUCCESS,
      pageCount = 30,
      cacheHits = 28,
      cacheMisses = 2,
      rasterScale = 2f,
      startedAt = 1_700_000_000_000L,
      finishedAt = 1_700_000_012_345L,
      renderMs = 8_123,
      mergeGraftMs = 120,
      mergeSaveMs = 3_400,
      verifyMs = 45,
      finalizeMs = 12,
      outputBytes = 12_345_678,
      failedStage = null,
      error = null,
    )

    assertEquals(report, ExportRunReportCodec.decode(ExportRunReportCodec.encode(report)))
  }

  @Test fun failureReportKeepsFailedStageAndSanitizesError() {
    val report = ExportRunReport(
      taskId = taskId,
      mode = ExportRenderMode.VECTOR,
      result = ExportRunResult.FAILED,
      pageCount = 3,
      cacheHits = 1,
      cacheMisses = 2,
      rasterScale = 1f,
      startedAt = 10,
      finishedAt = 20,
      renderMs = 1,
      mergeGraftMs = 2,
      mergeSaveMs = 3,
      verifyMs = 4,
      finalizeMs = 5,
      outputBytes = 0,
      failedStage = ExportProgress.Stage.WRITING,
      error = "first line\nsecond line",
    )

    val decoded = requireNotNull(ExportRunReportCodec.decode(ExportRunReportCodec.encode(report)))
    assertEquals(ExportProgress.Stage.WRITING, decoded.failedStage)
    assertEquals("first line second line", decoded.error)
    assertEquals(ExportRunResult.FAILED, decoded.result)
  }

  @Test fun unknownKeysAreIgnored() {
    val encoded = ExportRunReportCodec.encode(report()) + "future=value\n"
    assertEquals(report(), ExportRunReportCodec.decode(encoded))
  }

  @Test fun malformedReportsDecodeToNull() {
    listOf(
      "",
      "v=1\n",
      "not a key value file",
      ExportRunReportCodec.encode(report()).replace("v=1", "v=2"),
      ExportRunReportCodec.encode(report()).replace("mode=FAST", "mode=UNKNOWN"),
      ExportRunReportCodec.encode(report()).replace("task=" + taskId, "task=not-a-uuid"),
      ExportRunReportCodec.encode(report()).replace("render=100", "render=-1"),
      ExportRunReportCodec.encode(report()).replace("scale=1.5", "scale=0"),
      ExportRunReportCodec.encode(report()).lineSequence().filterNot { it.startsWith("bytes=") }
        .joinToString("\n"),
    ).forEach { input ->
      assertNull("Expected null for: $input", ExportRunReportCodec.decode(input))
    }
  }

  private fun report() = ExportRunReport(
    taskId = taskId,
    mode = ExportRenderMode.FAST,
    result = ExportRunResult.SUCCESS,
    pageCount = 2,
    cacheHits = 1,
    cacheMisses = 1,
    rasterScale = 1.5f,
    startedAt = 100,
    finishedAt = 200,
    renderMs = 100,
    mergeGraftMs = 10,
    mergeSaveMs = 20,
    verifyMs = 30,
    finalizeMs = 40,
    outputBytes = 500,
  )
}
