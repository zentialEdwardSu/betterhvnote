package com.betterhv.transfer.core.v4

import com.betterhv.transfer.proto.v4.CapabilitySet
import com.betterhv.transfer.proto.v4.ClientHello
import com.betterhv.transfer.proto.v4.ContentKind
import com.betterhv.transfer.proto.v4.FetchRequest
import com.betterhv.transfer.proto.v4.Frame
import com.betterhv.transfer.proto.v4.P2pGroup
import com.betterhv.transfer.proto.v4.P2pState
import com.betterhv.transfer.proto.v4.ServerHello
import com.betterhv.transfer.proto.v4.SessionTicket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaCompatibilityTest {
  private val deviceId = "9f1c2f5e-0000-4000-8000-000000000001"

  private fun hello(): ClientHello = ClientHello.newBuilder()
    .setMajor(4)
    .setMinorMin(0)
    .setMinorMax(0)
    .setDeviceId(deviceId)
    .setClientTimeMs(1_700_000_000_000L)
    .setCapabilities(
      CapabilitySet.newBuilder()
        .setTransports(1 or 2)
        .setMaxStreams(8)
        .addKinds(ContentKind.CONTENT_IMAGE)
        .setFeatures(1 or 8)
        .setP2P(
          P2pGroup.newBuilder()
            .setSsid("DIRECT-BH-abcdef")
            .setPort(39817)
            .setState(P2pState.P2P_STATE_READY),
        ),
    )
    .setRequest(
      com.betterhv.transfer.proto.v4.Request.newBuilder()
        .setFetch(FetchRequest.newBuilder().setKind(ContentKind.CONTENT_IMAGE).setMaxItems(2)),
    )
    .setListenerTicket(
      SessionTicket.newBuilder()
        .setVersion(1)
        .setListenerDeviceId(deviceId)
        .setPeerDeviceId("peer")
        .setExpiresAtMs(1_700_000_300_000L)
        .setListenerPort(39817),
    )
    .build()

  @Test
  fun frameRoundTripPreservesTheOneofAndNestedMessages() {
    val frame = Frame.newBuilder().setClientHello(hello()).build()
    val decoded = Frame.parseFrom(frame.toByteArray())
    assertEquals(Frame.BodyCase.CLIENT_HELLO, decoded.bodyCase)
    assertEquals(4, decoded.clientHello.major)
    assertEquals(8, decoded.clientHello.capabilities.maxStreams)
    assertEquals(P2pState.P2P_STATE_READY, decoded.clientHello.capabilities.getP2P().state)
    assertEquals(ContentKind.CONTENT_IMAGE, decoded.clientHello.request.fetch.kind)
    assertEquals(2, decoded.clientHello.request.fetch.maxItems)
    assertEquals(39817, decoded.clientHello.listenerTicket.listenerPort)
  }

  @Test
  fun serverHelloRoundTripPreservesRepeatedOffers() {
    val frame = Frame.newBuilder()
      .setServerHello(
        ServerHello.newBuilder()
          .setMajor(4)
          .setMinor(0)
          .setSessionId(com.google.protobuf.ByteString.copyFrom(ByteArray(8) { it.toByte() }))
          .setKeepaliveMs(5_000)
          .addOffers(
            com.betterhv.transfer.proto.v4.Offer.newBuilder()
              .setItemId(com.google.protobuf.ByteString.copyFrom(ByteArray(16) { 3 }))
              .setKind(ContentKind.CONTENT_PDF)
              .setByteLength(12_345)
              .setDisplayName("page.pdf"),
          ),
      )
      .build()
    val decoded = Frame.parseFrom(frame.toByteArray())
    assertEquals(1, decoded.serverHello.offersCount)
    assertEquals("page.pdf", decoded.serverHello.offersList.single().displayName)
  }

  @Test
  fun unknownFieldsSurviveAReadWriteCycle() {
    val known = Frame.newBuilder().setClientHello(hello()).build().toByteArray()
    // Field 100, varint wire type, value 42: a field only a future schema knows about.
    val unknown = byteArrayOf(0xA0.toByte(), 0x06, 42)
    val decoded = Frame.parseFrom(known + unknown)
    assertEquals(4, decoded.clientHello.major)
    val reencoded = decoded.toByteArray()
    assertTrue(
      "Unknown field must survive a round trip",
      reencoded.toList().windowed(unknown.size).any { it == unknown.toList() },
    )
  }

  @Test
  fun appendingFieldsKeepsExistingSemantics() {
    val base = hello()
    val extended = base.toBuilder()
      .setResumeToken(com.google.protobuf.ByteString.copyFromUtf8("resume"))
      .build()
    val decoded = ClientHello.parseFrom(extended.toByteArray())
    assertEquals(base.deviceId, decoded.deviceId)
    assertArrayEquals(base.toByteArray(), base.toBuilder().build().toByteArray())
  }

  @Test
  fun enumZeroIsAlwaysUnspecified() {
    assertEquals(0, ContentKind.CONTENT_KIND_UNSPECIFIED.number)
    assertEquals(0, P2pState.P2P_STATE_UNSPECIFIED.number)
    assertEquals(0, com.betterhv.transfer.proto.v4.ErrorCode.ERROR_CODE_UNSPECIFIED.number)
  }
}
