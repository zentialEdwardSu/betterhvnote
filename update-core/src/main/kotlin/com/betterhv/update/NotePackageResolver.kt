package com.betterhv.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

sealed interface NotePackageResolution {
  data class UpToDate(val latestVersion: AppVersion?) : NotePackageResolution
  data class Available(val descriptor: NotePackageDescriptor) : NotePackageResolution
}

fun interface ReleaseAssetTextLoader {
  fun load(url: String): String
}

class NotePackageResolver(
  private val source: ReleaseSource = GitHubReleaseSource(),
  private val textLoader: ReleaseAssetTextLoader = ReleaseAssetTextLoader { SecureReleaseDownloader().readText(it) },
) {
  fun resolve(currentVersion: String): NotePackageResolution {
    val current = AppVersion.parse(currentVersion)
      ?: throw IllegalArgumentException("Unrecognized current version: $currentVersion")
    val latest = source.loadReleases().asSequence()
      .filterNot { it.draft || it.preRelease }
      .filter { UpdateChecker.isTrustedReleaseUrl(it.pageUrl) }
      .mapNotNull { release ->
        val raw = release.tagName.takeIf { it.startsWith(UpdateProduct.NOTE.tagPrefix) }
          ?.removePrefix(UpdateProduct.NOTE.tagPrefix) ?: return@mapNotNull null
        AppVersion.parse(raw)?.let { it to release }
      }
      .maxByOrNull { it.first }
      ?: return NotePackageResolution.UpToDate(null)
    if (latest.first <= current) return NotePackageResolution.UpToDate(latest.first)

    val version = latest.first.display
    val expectedName = "BetterHvNote-$version-android-arm64.apk"
    val apk = latest.second.assets.singleOrNull { it.name == expectedName }
      ?: throw IOException("Release ${latest.second.tagName} does not contain $expectedName")
    val sums = latest.second.assets.singleOrNull { it.name == CHECKSUM_FILE }
      ?: throw IOException("Release ${latest.second.tagName} does not contain $CHECKSUM_FILE")
    requireTrustedAsset(apk, latest.second.tagName)
    requireTrustedAsset(sums, latest.second.tagName)
    require(apk.byteLength in 1..MAX_NOTE_PACKAGE_BYTES) { "Note APK has an invalid size" }
    val expected = parseSha256Manifest(textLoader.load(sums.downloadUrl))[expectedName]
      ?: throw IOException("$CHECKSUM_FILE does not contain $expectedName")
    return NotePackageResolution.Available(
      NotePackageDescriptor(latest.first, latest.second.tagName, latest.second.title, apk, sums, expected),
    )
  }

  companion object {
    const val NOTE_PACKAGE_MIME = "application/vnd.android.package-archive"
    const val MAX_NOTE_PACKAGE_BYTES = 512L * 1024L * 1024L
    private const val CHECKSUM_FILE = "SHA256SUMS.txt"

    fun parseSha256Manifest(value: String): Map<String, ByteArray> = buildMap {
      value.lineSequence().forEach { line ->
        val match = CHECKSUM_LINE.matchEntire(line.trim()) ?: return@forEach
        put(match.groupValues[2], match.groupValues[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray())
      }
    }

    fun requireTrustedAsset(asset: ReleaseAsset, tagName: String) {
      val uri = runCatching { URI(asset.downloadUrl) }.getOrNull()
        ?: throw IllegalArgumentException("Invalid release asset URL")
      require(uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true)) {
        "Release asset is not hosted on GitHub"
      }
      require(uri.path == "/zentialEdwardSu/betterhvnote/releases/download/$tagName/${asset.name}") {
        "Release asset URL does not match its release"
      }
    }

    private val CHECKSUM_LINE = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$")
  }
}

class SecureReleaseDownloader {
  fun readText(url: String): String {
    val output = ByteArrayOutputStream()
    open(url).use { input ->
      val buffer = ByteArray(8192)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= MAX_MANIFEST_BYTES) { "Checksum manifest is too large" }
        output.write(buffer, 0, count)
      }
    }
    return output.toByteArray().decodeToString()
  }

  fun download(
    url: String,
    destination: File,
    expectedLength: Long,
    expectedSha256: ByteArray,
    progress: (Long, Long) -> Unit = { _, _ -> },
  ) {
    require(expectedLength in 1..NotePackageResolver.MAX_NOTE_PACKAGE_BYTES)
    destination.parentFile?.mkdirs()
    var received = 0L
    try {
      open(url).use { input ->
        FileOutputStream(destination).buffered().use { output ->
          val digest = MessageDigest.getInstance("SHA-256")
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            received += count
            require(received <= NotePackageResolver.MAX_NOTE_PACKAGE_BYTES) { "Note APK is too large" }
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
            progress(received, expectedLength)
          }
          require(received == expectedLength) { "Note APK length does not match the release" }
          require(digest.digest().contentEquals(expectedSha256)) { "Note APK checksum does not match the release" }
        }
      }
    } catch (error: Throwable) {
      destination.delete()
      throw error
    }
  }

  fun verify(file: File, descriptor: NotePackageDescriptor): Boolean = file.isFile &&
    file.length() == descriptor.apk.byteLength && sha256(file).contentEquals(descriptor.expectedSha256)

  private fun open(rawUrl: String): java.io.InputStream {
    var current = URL(rawUrl)
    repeat(MAX_REDIRECTS + 1) {
      requireTrustedDownloadHost(current)
      val connection = current.openConnection() as HttpURLConnection
      connection.instanceFollowRedirects = false
      connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
      connection.readTimeout = READ_TIMEOUT_MILLIS
      connection.setRequestProperty("User-Agent", "BetterHvNote-UpdateDownloader")
      val status = connection.responseCode
      if (status in REDIRECT_CODES) {
        val location = connection.getHeaderField("Location") ?: throw IOException("Redirect has no location")
        connection.disconnect()
        current = URL(current, location)
      } else {
        if (status !in 200..299) {
          connection.disconnect()
          throw IOException("GitHub returned HTTP $status")
        }
        return DisconnectingInputStream(connection)
      }
    }
    throw IOException("Too many release download redirects")
  }

  private fun requireTrustedDownloadHost(url: URL) {
    require(url.protocol == "https" && url.host.lowercase() in TRUSTED_HOSTS) {
      "Untrusted release download URL"
    }
  }

  private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").run {
    file.inputStream().buffered().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        update(buffer, 0, count)
      }
    }
    digest()
  }

  private class DisconnectingInputStream(private val connection: HttpURLConnection) :
    java.io.FilterInputStream(connection.inputStream) {
    override fun close() {
      try {
        super.close()
      } finally {
        connection.disconnect()
      }
    }
  }

  companion object {
    private const val MAX_MANIFEST_BYTES = 256 * 1024
    private const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 120_000
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    private val TRUSTED_HOSTS = setOf(
      "github.com",
      "release-assets.githubusercontent.com",
      "objects.githubusercontent.com",
    )
  }
}
