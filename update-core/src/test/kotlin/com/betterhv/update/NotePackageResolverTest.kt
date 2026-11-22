package com.betterhv.update

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotePackageResolverTest {
  @Test
  fun `resolves exact latest stable note apk and checksum`() {
    val hash = ByteArray(32) { it.toByte() }
    val release = release("1.2.3", hash)
    val resolver = NotePackageResolver(
      ReleaseSource { listOf(release("9.0.0", hash, prefix = "notelink-v"), release) },
      ReleaseAssetTextLoader { manifest(hash, release.assets.first().name) },
    )

    val descriptor = assertIs<NotePackageResolution.Available>(resolver.resolve("1.0.0")).descriptor

    assertEquals("1.2.3", descriptor.version.display)
    assertEquals("BetterHvNote-1.2.3-android-arm64.apk", descriptor.apk.name)
    assertContentEquals(hash, descriptor.expectedSha256)
  }

  @Test
  fun `reports current version as up to date without loading checksums`() {
    val hash = ByteArray(32)
    val resolver = NotePackageResolver(
      ReleaseSource { listOf(release("1.2.3", hash)) },
      ReleaseAssetTextLoader { error("must not load") },
    )

    assertEquals("1.2.3", assertIs<NotePackageResolution.UpToDate>(resolver.resolve("1.2.3")).latestVersion?.display)
  }

  @Test
  fun `rejects missing checksum entry and untrusted assets`() {
    val hash = ByteArray(32)
    assertFailsWith<IOException> {
      NotePackageResolver(
        ReleaseSource { listOf(release("1.2.3", hash)) },
        ReleaseAssetTextLoader { "${"00".repeat(32)}  another.apk" },
      ).resolve("1.0.0")
    }
    val bad = release("1.2.3", hash).let { value ->
      value.copy(assets = value.assets.map { it.copy(downloadUrl = "https://example.com/${it.name}") })
    }
    assertFailsWith<IllegalArgumentException> {
      NotePackageResolver(ReleaseSource { listOf(bad) }, ReleaseAssetTextLoader { "" }).resolve("1.0.0")
    }
  }

  @Test
  fun `parses lowercase uppercase and star checksum lines`() {
    val first = ByteArray(32) { it.toByte() }
    val second = ByteArray(32) { (255 - it).toByte() }
    val parsed = NotePackageResolver.parseSha256Manifest(
      "${hex(first)}  first.apk\n${hex(second).uppercase()} *second.apk\ninvalid",
    )
    assertContentEquals(first, parsed.getValue("first.apk"))
    assertContentEquals(second, parsed.getValue("second.apk"))
  }

  @Test
  fun `requireTrustedAsset rejects userInfo non-default ports and traversal`() {
    val assetName = "BetterHvNote-1.2.3-android-arm64.apk"
    val tag = "note-v1.2.3"
    val base = "zentialEdwardSu/betterhvnote/releases/download/$tag"
    assertFailsWith<IllegalArgumentException> {
      NotePackageResolver.requireTrustedAsset(
        ReleaseAsset(assetName, "https://user@github.com/$base/$assetName", 10),
        tag,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      NotePackageResolver.requireTrustedAsset(
        ReleaseAsset(assetName, "https://github.com:8443/$base/$assetName", 10),
        tag,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      NotePackageResolver.requireTrustedAsset(
        ReleaseAsset(assetName, "https://github.com/$base/../$tag/$assetName", 10),
        tag,
      )
    }
  }

  @Test
  fun `downloader rejects oversized stream deletes partial file and disconnects`() {
    val root = createTempDirectory("downloader-test").toFile()
    val destination = File(root, "out.part")
    val expectedPayload = "hello".encodeToByteArray()
    val expectedSha = MessageDigest.getInstance("SHA-256").digest(expectedPayload)
    val oversizedPayload = "hello world!".encodeToByteArray()
    var disconnected = false
    val downloader = SecureReleaseDownloader { url ->
      FakeHttpConnection(url, 200, ByteArrayInputStream(oversizedPayload)) { disconnected = true }
    }
    try {
      assertFailsWith<IllegalArgumentException> {
        downloader.download(
          url = "https://github.com/zentialEdwardSu/betterhvnote/releases/download/note-v1.0.0/app.apk",
          destination = destination,
          expectedLength = expectedPayload.size.toLong(),
          expectedSha256 = expectedSha,
        )
      }
      assertFalse(destination.exists())
      assertTrue(disconnected)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `downloader disconnects on open failure`() {
    var disconnected = false
    val downloader = SecureReleaseDownloader { url ->
      object : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() {
          disconnected = true
        }
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = throw IOException("header read failed")
      }
    }
    val sumsUrl = "https://github.com/zentialEdwardSu/betterhvnote/releases/download/note-v1.0.0/SHA256SUMS.txt"
    assertFailsWith<IOException> {
      downloader.readText(sumsUrl)
    }
    assertTrue(disconnected)
  }

  private class FakeHttpConnection(
    url: URL,
    private val code: Int,
    private val body: InputStream,
    private val onDisconnect: () -> Unit,
  ) : HttpURLConnection(url) {
    override fun connect() = Unit
    override fun disconnect() = onDisconnect()
    override fun usingProxy(): Boolean = false
    override fun getResponseCode(): Int = code
    override fun getInputStream(): InputStream = body
  }

  private fun release(
    version: String,
    hash: ByteArray,
    prefix: String = "note-v",
  ): ReleaseRecord {
    val tag = "$prefix$version"
    val apkName = "BetterHvNote-$version-android-arm64.apk"
    val base = "https://github.com/zentialEdwardSu/betterhvnote/releases/download/$tag"
    return ReleaseRecord(
      tag, "BetterHvNote $version",
      "https://github.com/zentialEdwardSu/betterhvnote/releases/tag/$tag",
      draft = false, preRelease = false,
      assets = listOf(
        ReleaseAsset(apkName, "$base/$apkName", 1234),
        ReleaseAsset("SHA256SUMS.txt", "$base/SHA256SUMS.txt", 100),
      ),
    )
  }

  private fun manifest(hash: ByteArray, name: String) = "${hex(hash)}  $name"
  private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it) }
}
