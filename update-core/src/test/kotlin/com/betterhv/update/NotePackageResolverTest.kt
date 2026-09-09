package com.betterhv.update

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

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
