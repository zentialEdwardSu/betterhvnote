package com.betterhv.transfer.core.v4.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerAddressBookTest {
  private class MemoryStore : PeerAddressStore {
    var records: List<PeerRecord> = emptyList()
    var saves = 0

    override fun load(): List<PeerRecord> = records

    override fun save(records: List<PeerRecord>) {
      this.records = records
      saves++
    }
  }

  private var now = 1_000_000L
  private val store = MemoryStore()
  private val book = PeerAddressBook(store, clock = { now })

  @Test
  fun successIsPersistedAndReloaded() {
    book.recordSuccess("phone", "AA:BB", PeerProtocol.V4)
    assertEquals("AA:BB", book.addressFor("phone"))
    assertEquals(PeerProtocol.V4, book.protocolFor("phone"))
    assertTrue(book.serviceTableKnown("phone"))
    assertTrue(store.saves > 0)

    val reloaded = PeerAddressBook(store, clock = { now })
    assertEquals("AA:BB", reloaded.addressFor("phone"))
  }

  @Test
  fun expiredOrTooManyFailuresInvalidateTheAddress() {
    book.recordSuccess("phone", "AA:BB", PeerProtocol.V4)
    now += PeerAddressBook.DEFAULT_TTL_MILLIS + 1
    assertNull(book.addressFor("phone"))

    book.recordSuccess("phone", "AA:BB", PeerProtocol.V4)
    book.recordFailure("phone")
    assertEquals("AA:BB", book.addressFor("phone"))
    book.recordFailure("phone")
    assertNull("two failures must drop the address", book.addressFor("phone"))
    assertEquals(PeerProtocol.V4, book.protocolFor("phone"))
  }

  @Test
  fun successResetsTheFailureCount() {
    book.recordSuccess("phone", "AA:BB", PeerProtocol.V3)
    book.recordFailure("phone")
    book.recordSuccess("phone", "CC:DD", PeerProtocol.V4)
    assertEquals("CC:DD", book.addressFor("phone"))
    book.recordFailure("phone")
    assertEquals("CC:DD", book.addressFor("phone"))
  }

  @Test
  fun unknownPeersHaveNoAddressOrProtocol() {
    assertNull(book.addressFor("missing"))
    assertEquals(PeerProtocol.UNKNOWN, book.protocolFor("missing"))
    assertTrue(!book.serviceTableKnown("missing"))
  }

  @Test
  fun forgettingRemovesTheRecord() {
    book.recordSuccess("phone", "AA:BB", PeerProtocol.V4)
    book.forget("phone")
    assertNull(book.addressFor("phone"))
    assertTrue(book.snapshot().isEmpty())
  }
}
