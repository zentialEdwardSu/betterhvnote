package com.betterhv.note.backup

import com.google.gson.Gson
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupEntry(val path: String, val size: Long, val sha256: String)
data class BackupManifest(
  val formatVersion: Int = 1,
  val schemaVersion: Int,
  val createdAt: Long,
  val notebookCount: Int,
  val files: List<BackupEntry>,
)

/** Framework-free archive boundary. Every payload is declared and validated before installation. */
object BackupArchive {
  private const val MANIFEST = "manifest.json"
  private const val MANIFEST_LIMIT = 4 * 1024 * 1024
  private const val MAX_ENTRIES = 100_000
  private const val MAX_BYTES = 64L * 1024 * 1024 * 1024
  private val gson = Gson()

  fun payloadPath(path: String): Boolean {
    if (path.isEmpty() || path.startsWith('/') || '\\' in path || ':' in path || '\u0000' in path ||
      path.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
    return path == "documents/default.inknote" || path == "settings.json" || path == "templates/last-manifest.json" ||
      listOf("documents/assets/", "documents/pdfs/", "templates/cache/").any { path.startsWith(it) }
  }

  fun resolve(root: File, path: String): File {
    require(payloadPath(path)) { "Invalid backup path: $path" }
    val file = File(root, path).canonicalFile
    require(file.toPath().startsWith(root.canonicalFile.toPath())) { "Backup path escapes its directory" }
    return file
  }

  fun hash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
  }

  fun manifest(root: File, schema: Int, notebooks: Int): BackupManifest = BackupManifest(
    schemaVersion = schema, createdAt = System.currentTimeMillis(), notebookCount = notebooks,
    files = root.walkTopDown().filter(File::isFile).map { file ->
      val path = file.relativeTo(root).invariantSeparatorsPath
      require(payloadPath(path)) { "Unexpected snapshot file: $path" }
      BackupEntry(path, file.length(), hash(file))
    }.sortedBy(BackupEntry::path).toList(),
  )

  fun write(root: File, manifest: BackupManifest, output: OutputStream, progress: (Int, Int) -> Unit = { _, _ -> }) {
    validateManifest(manifest)
    ZipOutputStream(output.buffered()).use { zip ->
      zip.putNextEntry(ZipEntry(MANIFEST))
      zip.write(gson.toJson(manifest).toByteArray(Charsets.UTF_8))
      zip.closeEntry()
      manifest.files.forEachIndexed { index, entry ->
        progress(index, manifest.files.size)
        zip.putNextEntry(ZipEntry(entry.path))
        resolve(root, entry.path).inputStream().use { input ->
          copy(input, zip, entry.size) { progress(index, manifest.files.size) }
        }
        zip.closeEntry()
      }
      progress(manifest.files.size, manifest.files.size)
    }
  }

  // Use actual free bytes: recovery must not depend on reclaiming another app's cache.
  @Suppress("UsableSpace")
  fun read(input: InputStream, root: File, progress: (Int, Int) -> Unit = { _, _ -> }): BackupManifest {
    require(!root.exists() || root.listFiles().orEmpty().isEmpty()) { "Staging directory must be empty" }
    check(root.mkdirs() || root.isDirectory)
    ZipInputStream(input.buffered()).use { zip ->
      require(zip.nextEntry?.name == MANIFEST) { "Backup manifest must be the first entry" }
      val manifestBytes = java.io.ByteArrayOutputStream()
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = zip.read(buffer)
        if (read < 0) break
        require(manifestBytes.size() + read <= MANIFEST_LIMIT) { "Backup manifest is too large" }
        manifestBytes.write(buffer, 0, read)
      }
      val bytes = manifestBytes.toByteArray()
      val manifest = gson.fromJson(String(bytes, Charsets.UTF_8), BackupManifest::class.java)
      validateManifest(manifest)
      val required = manifest.files.sumOf(BackupEntry::size)
      require(root.usableSpace >= required + 16L * 1024 * 1024) { "Not enough space to unpack backup" }
      val pending = manifest.files.associateBy(BackupEntry::path).toMutableMap()
      while (true) {
        val zipEntry = zip.nextEntry ?: break
        val entry = pending.remove(zipEntry.name) ?: error("Unexpected or duplicate archive entry: ${zipEntry.name}")
        require(!zipEntry.isDirectory) { "Directory entries are not supported" }
        val destination = resolve(root, entry.path)
        check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
        destination.outputStream().use { output ->
          copy(zip, output, entry.size) { progress(manifest.files.size - pending.size - 1, manifest.files.size) }
          output.fd.sync()
        }
        require(hash(destination) == entry.sha256) { "Checksum mismatch: ${entry.path}" }
        progress(manifest.files.size - pending.size, manifest.files.size)
      }
      require(pending.isEmpty()) { "Backup has missing files" }
      return manifest
    }
  }

  private fun validateManifest(manifest: BackupManifest) {
    require(manifest.formatVersion == 1) { "Unsupported backup format" }
    require(manifest.schemaVersion > 0 && manifest.createdAt > 0 && manifest.notebookCount >= 0)
    require(manifest.files.size in 2..MAX_ENTRIES)
    require(manifest.files.map(BackupEntry::path).toSet().size == manifest.files.size) { "Duplicate manifest paths" }
    require(manifest.files.any { it.path == "documents/default.inknote" })
    require(manifest.files.any { it.path == "settings.json" })
    var total = 0L
    manifest.files.forEach {
      require(payloadPath(it.path) && it.size in 0..MAX_BYTES && it.sha256.matches(Regex("[0-9a-f]{64}")))
      total = Math.addExact(total, it.size)
      require(total <= MAX_BYTES) { "Backup exceeds supported size" }
    }
  }

  private fun copy(input: InputStream, output: OutputStream, size: Long, checkCancelled: () -> Unit) {
    var total = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
      checkCancelled()
      val read = input.read(buffer)
      if (read < 0) break
      total += read
      require(total <= size) { "Archive entry exceeds declared size" }
      output.write(buffer, 0, read)
    }
    require(total == size) { "Archive entry is truncated" }
  }
}

internal class BackupProgressThrottle(
  private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
  private val minDelta: Float = DEFAULT_MIN_DELTA,
  private val clockMillis: () -> Long = System::currentTimeMillis,
) {
  private var lastEmittedAt = Long.MIN_VALUE
  private var lastEmittedFraction = Float.NaN

  fun shouldEmit(done: Int, total: Int): Boolean {
    val fraction = fraction(done, total)
    val now = clockMillis()
    if (lastEmittedFraction.isNaN()) {
      record(fraction, now)
      return true
    }
    if (fraction == 0f && lastEmittedFraction != 0f) {
      record(fraction, now)
      return true
    }
    if (fraction >= 1f && lastEmittedFraction < 1f) {
      record(fraction, now)
      return true
    }
    val elapsed = now - lastEmittedAt
    val delta = kotlin.math.abs(fraction - lastEmittedFraction)
    if (elapsed >= minIntervalMillis || delta >= minDelta) {
      if (fraction == lastEmittedFraction && (fraction == 0f || fraction >= 1f)) {
        return false
      }
      record(fraction, now)
      return true
    }
    return false
  }

  private fun record(fraction: Float, now: Long) {
    lastEmittedAt = now
    lastEmittedFraction = fraction
  }

  companion object {
    const val DEFAULT_MIN_INTERVAL_MILLIS = 100L
    const val DEFAULT_MIN_DELTA = 0.02f

    fun fraction(done: Int, total: Int): Float =
      if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
  }
}
