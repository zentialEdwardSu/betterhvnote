package com.betterhv.transfer.core.v4

import com.betterhv.transfer.proto.v4.CapabilitySet
import com.betterhv.transfer.proto.v4.ClientHello
import com.betterhv.transfer.proto.v4.ContentKind
import com.betterhv.transfer.proto.v4.FetchRequest
import com.betterhv.transfer.proto.v4.Frame
import com.betterhv.transfer.proto.v4.Offer
import com.betterhv.transfer.proto.v4.Request
import com.betterhv.transfer.proto.v4.StreamData
import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.HexFormat

/**
 * Frozen v4.0 wire samples. A change here means the wire format changed; that is a protocol
 * break and must be accompanied by a version bump and a migration note.
 */
class GoldenFrameTest {
  private val hex = HexFormat.of()

  private val helloHex =
    "0a290804220d7461626c65742d676f6c64656e2880d095ffbc313207080328084201013a060a0408011002"
  private val offerHex =
    "0a10070707070707070707070707070707071003" +
      "2080082a20090909090909090909090909090909" +
      "0909090909090909090909090909090909320870" +
      "6167652e706466"
  private val streamHex = "3a060a0401020304"

  private fun hello(): ClientHello = ClientHello.newBuilder()
    .setMajor(4)
    .setMinorMin(0)
    .setMinorMax(0)
    .setDeviceId("tablet-golden")
    .setClientTimeMs(1_700_000_000_000L)
    .setCapabilities(
      CapabilitySet.newBuilder()
        .setTransports(1 or 2)
        .setMaxStreams(8)
        .addKinds(ContentKind.CONTENT_IMAGE),
    )
    .setRequest(
      Request.newBuilder().setFetch(
        FetchRequest.newBuilder().setKind(ContentKind.CONTENT_IMAGE).setMaxItems(2),
      ),
    )
    .build()

  @Test
  fun clientHelloMatchesTheFrozenSample() {
    val frame = Frame.newBuilder().setClientHello(hello()).build()
    assertEquals(helloHex, hex.formatHex(frame.toByteArray()))
    val decoded = Frame.parseFrom(hex.parseHex(helloHex))
    assertEquals(Frame.BodyCase.CLIENT_HELLO, decoded.bodyCase)
    assertEquals("tablet-golden", decoded.clientHello.deviceId)
    assertEquals(1_700_000_000_000L, decoded.clientHello.clientTimeMs)
  }

  @Test
  fun offerMatchesTheFrozenSample() {
    val offer = Offer.newBuilder()
      .setItemId(ByteString.copyFrom(ByteArray(16) { 7 }))
      .setKind(ContentKind.CONTENT_PDF)
      .setByteLength(1_024)
      .setSha256(ByteString.copyFrom(ByteArray(32) { 9 }))
      .setDisplayName("page.pdf")
      .build()
    assertEquals(offerHex, hex.formatHex(offer.toByteArray()))
    assertEquals("page.pdf", Offer.parseFrom(hex.parseHex(offerHex)).displayName)
  }

  @Test
  fun streamDataMatchesTheFrozenSample() {
    val frame = Frame.newBuilder()
      .setStreamData(
        StreamData.newBuilder().setData(ByteString.copyFrom(byteArrayOf(1, 2, 3, 4))).setOffset(0),
      )
      .build()
    assertEquals(streamHex, hex.formatHex(frame.toByteArray()))
    assertEquals(4, Frame.parseFrom(hex.parseHex(streamHex)).streamData.data.size())
  }
}
