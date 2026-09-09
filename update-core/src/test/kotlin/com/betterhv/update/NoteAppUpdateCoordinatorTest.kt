package com.betterhv.update

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class NoteAppUpdateCoordinatorTest {
  @Test
  fun `reuses verified cache and keeps repeated operation idempotent`() = runBlocking {
    val root = createTempDirectory("note-update-test").toFile()
    val cache = File(root, "cache").also(File::mkdirs)
    val bytes = "official note apk".encodeToByteArray()
    val fixture = fixture("2.0.0", bytes)
    File(cache, fixture.apk.name).writeBytes(bytes)
    val enqueueCount = AtomicInteger()
    val enqueuedPeer = AtomicReference<String>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val coordinator = NoteAppUpdateCoordinator(
      scope,
      cache,
      fixture.resolver,
      enqueue = { _, _, peer ->
        enqueueCount.incrementAndGet()
        enqueuedPeer.set(peer)
        UUID.randomUUID()
      },
    )
    try {
      val operationId = UUID.randomUUID()
      coordinator.begin(operationId, "tablet-a", "1.0.0")
      val ready = withTimeout(5_000) {
        coordinator.state.filterNotNull().first { it is NoteAppUpdateTaskState.Ready }
      }

      assertEquals("2.0.0", assertIs<NoteAppUpdateTaskState.Ready>(ready).version)
      assertIs<NoteAppUpdateTaskState.Ready>(coordinator.begin(operationId, "tablet-a", "1.0.0"))
      assertEquals(1, enqueueCount.get())
      assertEquals("tablet-a", enqueuedPeer.get())
    } finally {
      coordinator.close()
      scope.cancel()
      root.deleteRecursively()
    }
  }

  @Test
  fun `local import requires official checksum and caches arbitrary filename`() {
    val root = createTempDirectory("note-import-test").toFile()
    val official = "official note apk".encodeToByteArray()
    val fixture = fixture("2.0.0", official)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val coordinator = NoteAppUpdateCoordinator(scope, File(root, "cache"), fixture.resolver) {
        _, _, _ -> UUID.randomUUID()
    }
    try {
      val invalid = File(root, "renamed.apk").apply { writeText("tampered") }
      assertFailsWith<IllegalArgumentException> { coordinator.importLatest(invalid) }

      invalid.writeBytes(official)
      val imported = coordinator.importLatest(invalid)

      assertEquals("2.0.0", imported.version.display)
      assertTrue(File(root, "cache/${fixture.apk.name}").isFile)
    } finally {
      coordinator.close()
      scope.cancel()
      root.deleteRecursively()
    }
  }

  private fun fixture(version: String, bytes: ByteArray): Fixture {
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
    val tag = "note-v$version"
    val apkName = "BetterHvNote-$version-android-arm64.apk"
    val base = "https://github.com/zentialEdwardSu/betterhvnote/releases/download/$tag"
    val apk = ReleaseAsset(apkName, "$base/$apkName", bytes.size.toLong())
    val release = ReleaseRecord(
      tag,
      "BetterHvNote $version",
      "https://github.com/zentialEdwardSu/betterhvnote/releases/tag/$tag",
      draft = false,
      preRelease = false,
      assets = listOf(apk, ReleaseAsset("SHA256SUMS.txt", "$base/SHA256SUMS.txt", 100)),
    )
    val manifest = hash.joinToString("") { "%02x".format(it) } + "  $apkName"
    val resolver = NotePackageResolver(ReleaseSource { listOf(release) }, ReleaseAssetTextLoader { manifest })
    return Fixture(apk, resolver)
  }

  private data class Fixture(val apk: ReleaseAsset, val resolver: NotePackageResolver)
}
