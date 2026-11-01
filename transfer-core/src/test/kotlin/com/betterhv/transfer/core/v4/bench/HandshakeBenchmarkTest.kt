package com.betterhv.transfer.core.v4.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HandshakeBenchmarkTest {
  @Test
  fun coldHandshakeCompletesInOneRoundTripAndMeetsTheBudget() {
    val report = HandshakeBenchmark.run("cold", iterations = 30, rttMillis = 10)
    assertEquals(1, report.roundTripsPerHandshake)
    assertEquals(30, report.samples)
    assertTrue("p50 ${report.p50Millis}", report.p50Millis <= 1_000)
    assertTrue("p95 ${report.p95Millis}", report.p95Millis <= 1_000)
    assertTrue(report.p95Millis >= report.p50Millis)
    assertTrue(report.toJson().contains("\"roundTripsPerHandshake\":1"))
  }

  @Test
  fun warmHandshakeHasTheSameRoundTripCount() {
    val report = HandshakeBenchmark.run("warm", iterations = 30, rttMillis = 2)
    assertEquals(1, report.roundTripsPerHandshake)
    assertTrue(report.p95Millis <= 400)
  }

  @Test
  fun reportCanBeWrittenAsAJsonArtifact() {
    val report = HandshakeBenchmark.run("ci", iterations = 5, rttMillis = 1)
    val directory = File("build/reports/notelink").apply { mkdirs() }
    val file = File(directory, "v4-handshake-benchmark.json")
    file.writeText(report.toJson())
    assertTrue(file.readText().contains("\"samples\":5"))
  }

  @Test
  fun fakeLinkEnforcesTheMtuAndCountsDrops() {
    val link = FakeLink(rttMillis = 0, mtu = 200)
    assertEquals(1, link.exchange(listOf(ByteArray(200))).size)
    var failed = false
    try {
      link.exchange(listOf(ByteArray(201)))
    } catch (expected: IllegalArgumentException) {
      failed = true
    }
    assertTrue(failed)
  }
}
