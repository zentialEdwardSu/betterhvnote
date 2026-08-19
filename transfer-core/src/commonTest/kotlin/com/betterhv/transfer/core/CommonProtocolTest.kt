package com.betterhv.transfer.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CommonProtocolTest {
    @Test fun transferIdKeepsUuidWireBytes() {
        val value = "00112233-4455-6677-8899-aabbccddeeff"
        val id = TransferId.parse(value)
        assertEquals(value, id.toString())
        assertContentEquals(
            byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, -0x78, -0x67, -0x56, -0x45, -0x34, -0x23, -0x12, -0x01),
            id.toByteArray()
        )
    }

    @Test fun identityRoundTripsAndPreservesCounts() {
        val encoded = NoteLinkIdentityCodec.encode(DeviceId("00112233-4455-6677-8899-aabbccddeeff"), "工作电脑", 300, -1)
        val decoded = NoteLinkIdentityCodec.decode(encoded)
        assertEquals(255, decoded.imageCount)
        assertEquals(0, decoded.textCount)
        assertEquals("工作电脑", decoded.deviceName)
    }

    @Test fun advertisementGoldenBytesRemainCompatibleAcrossPlatforms() {
        val encoded = NoteLinkAdvertisementCodec.encode(
            byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50), "NoteLink", 2, 3
        )

        assertContentEquals(
            byteArrayOf(
                1, 2, 3, 0x10, 0x20, 0x30, 0x40, 0x50,
                0x4e, 0x6f, 0x74, 0x65, 0x4c, 0x69, 0x6e, 0x6b
            ),
            encoded
        )
        val decoded = NoteLinkAdvertisementCodec.decode(encoded)
        assertEquals(NoteLinkIdentityCodec.PROTOCOL_VERSION, decoded.protocolVersion)
        assertEquals(2, decoded.imageCount)
        assertEquals(3, decoded.textCount)
        assertContentEquals(byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50), decoded.identityHash)
        assertEquals("NoteLink", decoded.deviceName)
    }

    @Test fun advertisementBoundsAndVersionAreValidated() {
        val hash = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = NoteLinkAdvertisementCodec.encode(hash, "1234567890abcdefghijkl", 300, -1)
        val decoded = NoteLinkAdvertisementCodec.decode(encoded)

        assertEquals(255, decoded.imageCount)
        assertEquals(0, decoded.textCount)
        assertEquals("1234567890abcdef", decoded.deviceName)
        assertFailsWith<IllegalArgumentException> {
            NoteLinkAdvertisementCodec.encode(byteArrayOf(1, 2, 3, 4), "NoteLink", 0, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            NoteLinkAdvertisementCodec.decode(encoded.copyOf().also { it[0] = 2 })
        }
    }

    @Test fun multipleOnlineClientsRequireChoiceAndPreferLastUsed() {
        val first = DeviceId("first")
        val second = DeviceId("second")
        val result = selectTransferClient(listOf(first, second), setOf(first, second), second)
        val choice = assertIs<ClientSelection.Choose>(result)
        assertEquals(second, choice.preselected)
    }

    @Test fun exactlyOneOnlineClientSkipsChoice() {
        val first = DeviceId("first")
        val second = DeviceId("second")
        assertEquals(ClientSelection.Selected(second), selectTransferClient(listOf(first, second), setOf(second), first))
    }
}
