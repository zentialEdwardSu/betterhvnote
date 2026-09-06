package com.betterhv.note.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.util.UUID

data class ImportedImage(
  val relativePath: String,
  val mimeType: String,
  val pixelWidth: Int,
  val pixelHeight: Int,
  val exifOrientation: Int = 1,
)

/** Owns imported image bytes and never returns a path outside the private asset root. */
class ImageAssetStore(private val context: Context) {
  private val documentsDir = File(context.filesDir, "documents")
  val assetDir: File = File(documentsDir, "assets").also { it.mkdirs() }
  val incomingDir: File = File(documentsDir, "incoming").also { it.mkdirs() }
  private val rootPath = assetDir.canonicalFile.toPath()

  fun import(uri: Uri): ImportedImage {
    val mime = context.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") }
      ?: "image/*"
    val extension = when (mime) {
      "image/png" -> "png"
      "image/webp" -> "webp"
      "image/gif" -> "gif"
      else -> "jpg"
    }
    val name = "${UUID.randomUUID()}.$extension"
    val temp = File(incomingDir, "$name.tmp")
    try {
      context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Could not read selected image" }
        temp.outputStream().buffered().use(input::copyTo)
      }
      return inspect(temp, if (mime == "image/*") null else mime)
    } catch (t: Throwable) {
      temp.delete()
      throw t
    }
  }

  fun stageFile(source: File, mimeType: String): ImportedImage {
    require(source.isFile) { "Received image does not exist" }
    require(source.length() <= 64L * 1024L * 1024L) { "Image cannot exceed 64 MiB" }
    val temp = File(incomingDir, "${UUID.randomUUID()}.bin.tmp")
    try {
      FileInputStream(source).use { input -> temp.outputStream().buffered().use(input::copyTo) }
      return inspect(temp, mimeType)
    } catch (t: Throwable) {
      temp.delete();
      throw t
    }
  }

  fun importPng(bitmap: Bitmap): ImportedImage {
    require(bitmap.width > 0 && bitmap.height > 0) { "Invalid screenshot dimensions" }
    require(bitmap.width.toLong() * bitmap.height <= 100_000_000L) { "Screenshot cannot exceed 100 million pixels" }
    val temp = File(incomingDir, "${UUID.randomUUID()}.png.tmp")
    try {
      temp.outputStream().buffered().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not save PDF screenshot" }
      }
      return ImportedImage(
        relativePath = "incoming/${temp.name}",
        mimeType = "image/png",
        pixelWidth = bitmap.width,
        pixelHeight = bitmap.height,
      )
    } catch (t: Throwable) {
      temp.delete()
      throw t
    }
  }

  private fun inspect(temp: File, declaredMime: String?): ImportedImage {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(temp.absolutePath, options)
    require(options.outWidth > 0 && options.outHeight > 0) { "Unsupported image format" }
    require(options.outWidth.toLong() * options.outHeight <= 100_000_000L) { "Image cannot exceed 100 million pixels" }
    return ImportedImage(
      relativePath = "incoming/${temp.name}",
      mimeType = declaredMime?.takeIf { it.startsWith("image/") } ?: options.outMimeType ?: "image/*",
      pixelWidth = options.outWidth,
      pixelHeight = options.outHeight,
      exifOrientation = runCatching {
        ExifInterface(temp.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
      }.getOrDefault(1).coerceIn(1, 8),
    )
  }

  /** Moves a staged image into the durable asset root on the same filesystem. */
  fun commit(image: ImportedImage): ImportedImage {
    if (image.relativePath.startsWith("assets/")) return image
    require(image.relativePath.startsWith("incoming/")) { "Invalid staged image path" }
    val staged = resolveIncoming(image.relativePath) ?: error("Staged image is missing")
    val original = staged.name.removeSuffix(".tmp")
    val target = File(assetDir, original)
    check(staged.renameTo(target)) { "Could not commit image asset" }
    return image.copy(relativePath = "assets/$original")
  }

  fun discard(image: ImportedImage) {
    when {
      image.relativePath.startsWith("incoming/") -> resolveIncoming(image.relativePath)?.delete()
      image.relativePath.startsWith("assets/") -> resolve(image.relativePath)?.delete()
    }
  }

  fun resolve(relativePath: String): File? {
    val file = File(documentsDir, relativePath).canonicalFile
    return file.takeIf { it.toPath().startsWith(rootPath) && it.isFile }
  }

  private fun resolveIncoming(relativePath: String): File? {
    val file = File(documentsDir, relativePath).canonicalFile
    val root = incomingDir.canonicalFile.toPath()
    return file.takeIf { it.toPath().startsWith(root) && it.isFile }
  }

  /** Run only at cold start, after undo history from the prior process is gone. */
  fun cleanupUnreferenced(referencedPaths: Set<String>) {
    incomingDir.listFiles()?.forEach(File::delete)
    assetDir.listFiles()?.forEach { file ->
      val relative = "assets/${file.name}"
      if (relative !in referencedPaths || file.name.endsWith(".tmp")) file.delete()
    }
  }
}
