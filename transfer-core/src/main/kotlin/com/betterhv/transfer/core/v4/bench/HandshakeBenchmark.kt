package com.betterhv.transfer.core.v4.bench

import com.betterhv.transfer.core.v4.crypto.SessionKeys
import com.betterhv.transfer.core.v4.session.ClientHandshake
import com.betterhv.transfer.core.v4.session.PairingKey
import com.betterhv.transfer.core.v4.session.PairingKeyStore
import com.betterhv.transfer.core.v4.session.ServerHandshake
import com.betterhv.transfer.proto.v4.CapabilitySet
import com.betterhv.transfer.proto.v4.ContentKind
import com.betterhv.transfer.proto.v4.FetchRequest
import com.betterhv.transfer.proto.v4.Offer
import com.betterhv.transfer.proto.v4.Request
import com.google.protobuf.ByteString
import java.util.concurrent.atomic.AtomicInteger

data class BenchmarkReport(
  val label: String,
  val samples: Int,
  val p50Millis: Long,
  val p95Millis: Long,
  val p99Millis: Long,
  val roundTripsPerHandshake: Int,
  val droppedRecords: Int,
) {
  fun toJson(): String =
    """{"label":"$label","samples":$samples,"p50":$p50Millis,"p95":$p95Millis,"p99":$p99Millis,""" +
      """"roundTripsPerHandshake":$roundTripsPerHandshake,"droppedRecords":$droppedRecords}"""
}

/**
 * Cold/warm handshake benchmark over [FakeLink]. One iteration must complete in exactly one
 * client→server→client exchange; the p95 budget is the same 1 s target as the device benchmark.
 */
object HandshakeBenchmark {
  private val capabilities = CapabilitySet.newBuilder()
    .setTransports(1)
    .setMaxStreams(8)
    .addKinds(ContentKind.CONTENT_PDF)
    .build()
  private val request = Request.newBuilder()
    .setFetch(FetchRequest.newBuilder().setKind(ContentKind.CONTENT_PDF).setMaxItems(1))
    .build()
  private val offer = Offer.newBuilder()
    .setItemId(ByteString.copyFrom(ByteArray(16) { 9 }))
    .setKind(ContentKind.CONTENT_PDF)
    .setByteLength(1_024)
    .build()

  fun run(
    label: String,
    iterations: Int = 30,
    rttMillis: Long = 20,
    lossRate: Double = 0.0,
    maxFragmentBody: Int = 200,
    clock: () -> Long = System::nanoTime,
  ): BenchmarkReport {
    require(iterations > 0)
    val psk = ByteArray(SessionKeys.KEY_BYTES) { (it * 29 + 11).toByte() }
    val keyId = SessionKeys.keyId(psk)
    val random = AtomicInteger(1)
    val samples = mutableListOf<Long>()
    var roundTrips = 0
    var dropped = 0
    repeat(iterations) { iteration ->
      val link = FakeLink(rttMillis, lossRate, mtu = 512, seed = iteration + 1L)
      val now = 1_700_000_000_000L + iteration * 1_000L
      val server = ServerHandshake(
        pairings = PairingKeyStore { if (it == keyId) PairingKey("tablet", psk) else null },
        capabilities = capabilities,
        offers = { listOf(offer) },
        maxFragmentBody = maxFragmentBody,
        clock = { now },
        randomBytes = { size -> ByteArray(size) { random.getAndIncrement().toByte() } },
      )
      val client = ClientHandshake(
        pairingKey = psk,
        deviceId = "tablet",
        capabilities = capabilities,
        request = request,
        maxFragmentBody = maxFragmentBody,
        clock = { now },
        randomBytes = { size -> ByteArray(size) { random.getAndIncrement().toByte() } },
      )
      val started = clock()
      val hello = client.start()
      val serverInput = link.exchange(hello)
      require(serverInput.isNotEmpty()) { "Hello was dropped with loss=$lossRate" }
      val (reply, _) = server.accept(serverInput)
      val clientInput = link.exchange(reply)
      require(clientInput.isNotEmpty()) { "ServerHello was dropped with loss=$lossRate" }
      client.complete(clientInput)
      samples += (clock() - started) / 1_000_000
      roundTrips += link.roundTrips
      dropped += link.droppedRecords
    }
    val sorted = samples.sorted()
    return BenchmarkReport(
      label = label,
      samples = sorted.size,
      p50Millis = percentile(sorted, 50),
      p95Millis = percentile(sorted, 95),
      p99Millis = percentile(sorted, 99),
      roundTripsPerHandshake = if (iterations == 0) 0 else roundTrips / (iterations * 2),
      droppedRecords = dropped,
    )
  }

  private fun percentile(sorted: List<Long>, percent: Int): Long {
    val rank = ((percent * sorted.size + 99) / 100).coerceAtLeast(1)
    return sorted[(rank - 1).coerceIn(0, sorted.size - 1)]
  }
}
