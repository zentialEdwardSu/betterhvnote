package com.betterhv.note.sender.desktop

import com.betterhv.transfer.core.ContentKind
import java.io.File
import java.sql.DriverManager
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopDatabaseMigrationTest {
  @Test fun readsExistingVersionOneQueueAndInboxDatabases() {
    val root = createTempDirectory("notelink-v1-").toFile()
    try {
      val paths = DesktopPaths(root)
      val queueId = UUID.randomUUID()
      val artifactId = UUID.randomUUID()
      val hash = ByteArray(32) { it.toByte() }
      createQueueV1(paths.queueDatabase, queueId, hash)
      createInboxV1(paths.inboxDatabase, artifactId, hash, File(paths.inbox, "$artifactId.pdf"))

      DesktopQueueRepository(paths).use { queue ->
        val item = queue.items().single()
        assertEquals(queueId, item.id)
        assertEquals("legacy.png", item.displayName)
        assertContentEquals(hash, item.sha256)
      }
      DesktopInboxRepository(paths).use { inbox ->
        val item = inbox.items().single()
        assertEquals(artifactId, item.artifactId)
        assertEquals("legacy.pdf", item.displayName)
        assertContentEquals(hash, item.sha256)
      }
    } finally {
      root.deleteRecursively()
    }
  }

  @Test fun queueCountsAndLeasesAreIsolatedByDestination() {
    val root = createTempDirectory("notelink-multi-queue-").toFile()
    try {
      val paths = DesktopPaths(root)
      DesktopQueueRepository(paths).use { queue ->
        queue.enqueueText("for a", "note-a")
        queue.enqueueText("for b", "note-b")
        queue.enqueueText("legacy unassigned", null)

        assertEquals(2, queue.counts("note-a").texts)
        assertEquals(2, queue.counts("note-b").texts)
        val first = requireNotNull(queue.leaseNext(ContentKind.TEXT, "note-a"))
        assertEquals("note-a", first.item.destinationDeviceId)
        first.commit()
        val second = requireNotNull(queue.leaseNext(ContentKind.TEXT, "note-a"))
        assertEquals("note-a", second.item.destinationDeviceId)
        second.release()
        val forB = requireNotNull(queue.leaseNext(ContentKind.TEXT, "note-b"))
        assertEquals("note-b", forB.item.destinationDeviceId)
        forB.commit()
        assertNull(queue.leaseNext(ContentKind.TEXT, "note-b"))
      }
    } finally {
      root.deleteRecursively()
    }
  }

  private fun createQueueV1(file: File, id: UUID, hash: ByteArray) {
    DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
      connection.createStatement().use {
        it.execute(
          """CREATE TABLE queue_items(
                id TEXT PRIMARY KEY, destination_device_id TEXT, kind TEXT NOT NULL,
                mime_type TEXT NOT NULL, byte_length INTEGER NOT NULL, sha256 BLOB NOT NULL,
                created_at INTEGER NOT NULL, position INTEGER NOT NULL, state TEXT NOT NULL,
                display_name TEXT, lease_expires_at INTEGER, failure_reason TEXT,
                payload_path TEXT, text_content TEXT)"""
        )
      }
      connection.prepareStatement("INSERT INTO queue_items VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use {
        it.setString(1, id.toString());
        it.setString(2, null);
        it.setString(3, "IMAGE")
        it.setString(4, "image/png");
        it.setLong(5, 42);
        it.setBytes(6, hash);
        it.setLong(7, 10)
        it.setLong(8, 0);
        it.setString(9, "PENDING");
        it.setString(10, "legacy.png")
        it.setObject(11, null);
        it.setObject(12, null);
        it.setString(13, "outbox/$id.bin");
        it.setObject(14, null)
        it.executeUpdate()
      }
    }
  }

  private fun createInboxV1(file: File, id: UUID, hash: ByteArray, payload: File) {
    DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
      connection.createStatement().use {
        it.execute(
          """CREATE TABLE inbox_exports(
                artifact_id TEXT PRIMARY KEY, source_device_id TEXT NOT NULL, display_name TEXT NOT NULL,
                mime_type TEXT NOT NULL, byte_length INTEGER NOT NULL, sha256 BLOB NOT NULL,
                file_path TEXT NOT NULL, state TEXT NOT NULL, created_at INTEGER NOT NULL,
                received_at INTEGER, error TEXT)"""
        )
      }
      connection.prepareStatement("INSERT INTO inbox_exports VALUES(?,?,?,?,?,?,?,?,?,?,?)").use {
        it.setString(1, id.toString());
        it.setString(2, "note");
        it.setString(3, "legacy.pdf")
        it.setString(
          4,
          "application/pdf",
        );
        it.setLong(5, 42);
        it.setBytes(6, hash);
        it.setString(7, payload.absolutePath)
        it.setString(8, "COMPLETE");
        it.setLong(9, 10);
        it.setLong(10, 11);
        it.setObject(11, null)
        it.executeUpdate()
      }
    }
  }
}
