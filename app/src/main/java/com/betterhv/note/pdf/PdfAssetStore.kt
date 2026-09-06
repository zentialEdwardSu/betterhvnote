package com.betterhv.note.pdf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

data class StagedPdf(val relativePath: String, val displayName: String, val byteLength: Long, val sha256: String)

/** Private immutable PDF assets; imports are first staged so partial copies are never referenced. */
class PdfAssetStore(private val context: Context) {
  private val documentsDir = File(context.filesDir, "documents").also { it.mkdirs() }
  private val assetDir = File(documentsDir, "pdfs").also { it.mkdirs() }
  private val incomingDir = File(documentsDir, "pdf-incoming").also { it.mkdirs() }
  private val assetRoot = assetDir.canonicalFile.toPath()
  private val incomingRoot = incomingDir.canonicalFile.toPath()

  fun stage(uri: Uri): StagedPdf {
    val resolver = context.contentResolver
    val declaredSize = resolver.query(
      uri,
      arrayOf(OpenableColumns.SIZE),
      null,
      null,
      null,
    )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    require(declaredSize == null || declaredSize in 1..MAX_BYTES) { "PDF cannot exceed 512 MiB" }
    val displayName = resolver.query(
      uri,
      arrayOf(OpenableColumns.DISPLAY_NAME),
      null,
      null,
      null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
      ?.takeIf(String::isNotBlank) ?: "Imported.pdf"
    val temp = File(incomingDir, "${UUID.randomUUID()}.pdf.tmp")
    return try {
      val digest = MessageDigest.getInstance("SHA-256")
      var total = 0L
      resolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Could not read selected PDF" }
        temp.outputStream().buffered().use { output ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_BYTES) { "PDF cannot exceed 512 MiB" }
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
          }
        }
      }
      require(total > 0L) { "PDF file is empty" }
      StagedPdf(
        relativePath = "pdf-incoming/${temp.name}",
        displayName = sanitizeDisplayName(displayName),
        byteLength = total,
        sha256 = digest.digest().joinToString("") { "%02x".format(it) },
      )
    } catch (t: Throwable) {
      temp.delete()
      throw t
    }
  }

  fun stageFile(source: File, displayName: String): StagedPdf {
    require(source.isFile && source.length() in 1..MAX_BYTES) { "PDF cannot exceed 512 MiB" }
    val temp = File(incomingDir, "${UUID.randomUUID()}.pdf.tmp")
    return try {
      val digest = MessageDigest.getInstance("SHA-256")
      var total = 0L
      FileInputStream(source).use { input ->
        temp.outputStream().buffered().use { output ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_BYTES) { "PDF cannot exceed 512 MiB" }
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
          }
        }
      }
      StagedPdf(
        "pdf-incoming/${temp.name}",
        sanitizeDisplayName(displayName),
        total,
        digest.digest().joinToString("") { "%02x".format(it) },
      )
    } catch (t: Throwable) {
      temp.delete()
      throw t
    }
  }

  fun commit(staged: StagedPdf): StagedPdf {
    if (staged.relativePath.startsWith("pdfs/")) return staged
    val source = resolveIncoming(staged.relativePath) ?: error("Staged PDF is missing")
    val targetName = source.name.removeSuffix(".tmp")
    val target = File(assetDir, targetName)
    check(source.renameTo(target)) { "Could not commit PDF asset" }
    return staged.copy(relativePath = "pdfs/$targetName")
  }

  fun resolve(relativePath: String): File? {
    val file = File(documentsDir, relativePath).canonicalFile
    return file.takeIf { it.toPath().startsWith(assetRoot) && it.isFile }
  }

  fun resolveStaged(relativePath: String): File? = resolveIncoming(relativePath)

  fun discard(pdf: StagedPdf) {
    when {
      pdf.relativePath.startsWith("pdf-incoming/") -> resolveIncoming(pdf.relativePath)?.delete()
      pdf.relativePath.startsWith("pdfs/") -> resolve(pdf.relativePath)?.delete()
    }
  }

  fun cleanupUnreferenced(referencedPaths: Set<String>) {
    incomingDir.listFiles()?.forEach(File::delete)
    assetDir.listFiles()?.forEach { file ->
      if ("pdfs/${file.name}" !in referencedPaths) file.delete()
    }
  }

  private fun resolveIncoming(relativePath: String): File? {
    val file = File(documentsDir, relativePath).canonicalFile
    return file.takeIf { it.toPath().startsWith(incomingRoot) && it.isFile }
  }

  private fun sanitizeDisplayName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(160).ifBlank { "Imported.pdf" }

  companion object {
    const val MAX_BYTES = 512L * 1024L * 1024L
  }
}
