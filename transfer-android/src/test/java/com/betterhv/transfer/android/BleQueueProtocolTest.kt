package com.betterhv.transfer.android

import com.betterhv.transfer.core.BleCommand
import com.betterhv.transfer.core.BleQueueProtocol
import com.betterhv.transfer.core.BleResponse
import com.betterhv.transfer.core.ExportTransferOffer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class BleQueueProtocolTest {
  @Test fun prepareFileTransferRoundTrips() {
    val itemId = UUID.randomUUID()
    val commands = listOf(
      BleCommand.PrepareFileTransfer(
        itemId,
        com.betterhv.transfer.core.TransferMode.LAN,
        com.betterhv.transfer.core.NetworkEndpoint("192.168.49.23"),
        ByteArray(16) { it.toByte() },
      ),
    )

    commands.forEach { command ->
      assertEquals(command, BleQueueProtocol.decodeCommand(BleQueueProtocol.encode(command)))
    }
  }

  @Test fun preparedResponsesRoundTrip() {
    val responses = listOf(
      BleResponse.Pending,
      BleResponse.Prepared(
        UUID.randomUUID(),
        com.betterhv.transfer.core.TransferMode.LAN,
        com.betterhv.transfer.core.NetworkEndpoint("192.168.49.1"),
      ),
    )

    responses.forEach { response ->
      assertEquals(response, BleQueueProtocol.decodeResponse(BleQueueProtocol.encode(response)))
    }
  }

  @Test fun exportPushCommandsRoundTrip() {
    val artifactId = UUID.randomUUID()
    val offer = ExportTransferOffer(
      artifactId,
      "notebook_all.pdf",
      "application/pdf",
      42L,
      ByteArray(32) { it.toByte() },
    )
    val commands = listOf(
      BleCommand.Capabilities(
        com.betterhv.transfer.core.DeviceCapabilities(
          modes = com.betterhv.transfer.core.TransferModes.LAN,
          lanEndpoint = com.betterhv.transfer.core.NetworkEndpoint("192.168.1.10"),
        )
      ),
      BleCommand.PushOffer(offer),
      BleCommand.PushStatus(artifactId),
      BleCommand.PushCancel(artifactId),
    )
    commands.forEach { assertEquals(it, BleQueueProtocol.decodeCommand(BleQueueProtocol.encode(it))) }
  }

  @Test fun exportPushResponsesRoundTrip() {
    val artifactId = UUID.randomUUID()
    val responses = listOf(
      BleResponse.Capabilities(
        com.betterhv.transfer.core.CapabilityNegotiation(
          com.betterhv.transfer.core.DeviceCapabilities(
            modes = com.betterhv.transfer.core.TransferModes.LAN,
            lanEndpoint = com.betterhv.transfer.core.NetworkEndpoint("192.168.1.10"),
            extensions = BleQueueProtocol.CAPABILITY_EXPORT_PUSH,
          ),
          com.betterhv.transfer.core.SsidMatch.UNKNOWN,
        )
      ),
      BleResponse.PushComplete(artifactId),
      BleResponse.AlreadyReceived(artifactId),
    )
    responses.forEach { assertEquals(it, BleQueueProtocol.decodeResponse(BleQueueProtocol.encode(it))) }
  }
}
