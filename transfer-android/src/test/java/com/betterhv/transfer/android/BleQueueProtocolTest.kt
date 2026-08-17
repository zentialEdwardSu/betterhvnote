package com.betterhv.transfer.android

import com.betterhv.transfer.core.ExportTransferOffer
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class BleQueueProtocolTest {
    @Test fun phoneHostedWifiCommandsRoundTrip() {
        val itemId = UUID.randomUUID()
        val commands = listOf(
            BleCommand.WifiHost(itemId),
            BleCommand.WifiHostStatus(itemId),
            BleCommand.WifiSendTo(itemId, "192.168.49.23")
        )

        commands.forEach { command ->
            assertEquals(command, BleQueueProtocol.decodeCommand(BleQueueProtocol.encode(command)))
        }
    }

    @Test fun phoneHostedWifiResponsesRoundTrip() {
        val responses = listOf(
            BleResponse.Pending,
            BleResponse.WifiOwnerInfo(
                "12:34:56:78:9a:bc",
                "NoteLink",
                "192.168.49.1",
                "DIRECT-BH-BetterHv",
                "BetterHv-passphrase"
            )
        )

        responses.forEach { response ->
            assertEquals(response, BleQueueProtocol.decodeResponse(BleQueueProtocol.encode(response)))
        }
    }

    @Test fun exportPushCommandsRoundTrip() {
        val artifactId = UUID.randomUUID()
        val offer = ExportTransferOffer(
            artifactId, "notebook_all.pdf", "application/pdf", 42L, ByteArray(32) { it.toByte() }
        )
        val commands = listOf(
            BleCommand.Capabilities,
            BleCommand.PushOffer(offer),
            BleCommand.PushStatus(artifactId),
            BleCommand.PushCancel(artifactId)
        )
        commands.forEach { assertEquals(it, BleQueueProtocol.decodeCommand(BleQueueProtocol.encode(it))) }
    }

    @Test fun exportPushResponsesRoundTrip() {
        val artifactId = UUID.randomUUID()
        val responses = listOf(
            BleResponse.Capabilities(BleQueueProtocol.CAPABILITY_EXPORT_PUSH),
            BleResponse.PushComplete(artifactId),
            BleResponse.AlreadyReceived(artifactId)
        )
        responses.forEach { assertEquals(it, BleQueueProtocol.decodeResponse(BleQueueProtocol.encode(it))) }
    }
}
