package com.betterhv.transfer.core.v4.bench

import java.util.Random

/**
 * Deterministic in-memory link for benchmarks and fault tests. A record is delivered after
 * [rttMillis] and dropped with [lossRate]; [mtu] is asserted rather than enforced so a benchmark
 * fails visibly when fragmentation regresses.
 */
class FakeLink(
  private val rttMillis: Long = 20,
  private val lossRate: Double = 0.0,
  private val mtu: Int = 247,
  seed: Long = 1,
) {
  private val random = Random(seed)
  var roundTrips: Int = 0
    private set
  var droppedRecords: Int = 0
    private set

  init {
    require(lossRate in 0.0..1.0)
    require(mtu >= 64)
  }

  fun exchange(records: List<ByteArray>): List<ByteArray> {
    roundTrips++
    if (rttMillis > 0) Thread.sleep(rttMillis)
    return records.filter { record ->
      require(record.size <= mtu) { "Record ${record.size} exceeds fake MTU $mtu" }
      val keep = random.nextDouble() >= lossRate
      if (!keep) droppedRecords++
      keep
    }
  }

  fun reset() {
    roundTrips = 0
    droppedRecords = 0
  }
}
