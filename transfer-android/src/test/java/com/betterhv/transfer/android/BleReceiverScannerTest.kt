package com.betterhv.transfer.android

import org.junit.Assert.assertEquals
import org.junit.Test

class BleReceiverScannerTest {
    @Test fun serviceOnlyUpdateDoesNotEraseManufacturerIdentity() {
        val rich = DiscoveredSender("AA:BB", "0123456789", "Office PC", 2, 3)
        val serviceOnly = DiscoveredSender("AA:BB", "", "NoteLink", 0, 0)

        assertEquals(rich, mergeDiscoveredSender(rich, serviceOnly))
    }

    @Test fun manufacturerUpdateReplacesServiceOnlyFallback() {
        val serviceOnly = DiscoveredSender("AA:BB", "", "NoteLink", 0, 0)
        val rich = DiscoveredSender("AA:BB", "0123456789", "Office PC", 2, 3)

        assertEquals(rich, mergeDiscoveredSender(serviceOnly, rich))
    }
}
