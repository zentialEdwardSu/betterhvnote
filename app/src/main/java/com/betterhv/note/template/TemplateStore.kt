package com.betterhv.note.template

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.betterhv.note.AppSettingsStore
import com.betterhv.note.doc.DEFAULT_TEMPLATE_ID
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class TemplateStore private constructor(private val context: Context) : TemplateResolver {
  private val settings = AppSettingsStore(context)
  private val root = File(context.filesDir, "templates").also(File::mkdirs)
  private val builtinRoot = File(root, "builtin").also(File::mkdirs)
  private val cacheRoot = File(root, "cache").also(File::mkdirs)
  private val lastManifest = File(root, "last-manifest.json")
  val renderer = TemplateRenderer()
  private var generation = 0L
  private var catalog = TemplateCatalogSnapshot(emptyList(), settings.templateDirectoryUri, emptyList(), generation)

  init {
    refresh()
  }

  @Synchronized fun snapshot(): TemplateCatalogSnapshot = catalog

  /** True only while the configured SAF tree still has persisted read/write access. */
  fun hasUsableDirectoryPermission(): Boolean {
    val uri = settings.templateDirectoryUri?.let(Uri::parse) ?: return false
    val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
      ?: return false
    if (!permission.isReadPermission || !permission.isWritePermission) return false
    val tree = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
    return tree?.isDirectory == true && tree.canRead() && tree.canWrite()
  }

  @Synchronized
  override fun resolve(templateId: String?): TemplateDefinition? = catalog.find(templateId ?: DEFAULT_TEMPLATE_ID)

  @Synchronized
  override fun visualFingerprint(templateId: String?): String {
    val requested = templateId ?: return "none"
    val resolved = catalog.templates.firstOrNull { it.id == requested }
    return if (resolved != null) {
      resolved.fingerprint
    } else {
      "missing:$requested:${catalog.find(DEFAULT_TEMPLATE_ID)?.fingerprint.orEmpty()}"
    }
  }

  @Synchronized
  fun connect(uri: Uri): TemplateCatalogSnapshot {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    context.contentResolver.takePersistableUriPermission(uri, flags)
    settings.templateDirectoryUri = uri.toString()
    seedBuiltins(uri)
    return refresh()
  }

  @Synchronized
  fun refresh(): TemplateCatalogSnapshot {
    val errors = ArrayList<String>()
    val builtins = loadBuiltins(errors)
    val uri = settings.templateDirectoryUri?.let(Uri::parse)
    val external = if (uri != null) {
      val tree = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
      if (tree?.isDirectory == true && tree.canRead()) {
        val manifestText = tree.findFile(MANIFEST_NAME)?.let(::readDocument)
        if (manifestText == null) {
          errors += "模板目录缺少 $MANIFEST_NAME"
          loadLastManifest(errors)
        } else {
          val parsed = TemplateManifestCodec.parse(manifestText)
          if (parsed.entries.isEmpty() && parsed.errors.isNotEmpty()) {
            errors += parsed.errors
            errors += "manifest.json 无效，正在使用最后有效版本"
            loadLastManifest(errors)
          } else {
            val loaded = loadManifest(manifestText, tree, errors)
            runCatching { lastManifest.writeText(manifestText) }
            loaded
          }
        }
      } else {
        errors += "模板目录暂时不可访问，正在使用最后可用缓存"
        loadLastManifest(errors)
      }
    } else {
      emptyList()
    }
    val merged = LinkedHashMap<String, TemplateDefinition>()
    builtins.forEach { merged[it.id] = it }
    // APK-owned ids are reserved. This also prevents old files previously
    // seeded into the public directory from shadowing updated bundled SVGs.
    external.filterNot { it.id.startsWith(BUILTIN_ID_PREFIX) }.forEach { merged[it.id] = it }
    val nextTemplates = merged.values.toList()
    val signature = nextTemplates.joinToString("|") {
      "${it.id}:${it.name}:${it.description}:${it.fingerprint}:${it.availability}:${it.error}"
    } + errors.joinToString("|") + uri
    val oldSignature = catalog.templates.joinToString("|") {
      "${it.id}:${it.name}:${it.description}:${it.fingerprint}:${it.availability}:${it.error}"
    } + catalog.errors.joinToString("|") + catalog.directoryUri
    if (signature != oldSignature) generation++
    catalog = TemplateCatalogSnapshot(nextTemplates, uri?.toString(), errors, generation)
    return catalog
  }

  private fun loadBuiltins(errors: MutableList<String>): List<TemplateDefinition> {
    val blank = TemplateDefinition(
      entry = TemplateManifestEntry(
        id = DEFAULT_TEMPLATE_ID,
        name = "Blank",
        description = "纯白纸",
        file = "",
        width = 1,
        height = 1,
      ),
      assetFile = null,
      fingerprint = "builtin.blank:white-v1",
      availability = TemplateAvailability.AVAILABLE,
    )
    val text = runCatching { context.assets.open(ASSET_MANIFEST).bufferedReader().use { it.readText() } }
      .getOrElse {
        errors += "无法读取内置模板清单：${it.message}"
        return listOf(blank)
      }
    val parsed = TemplateManifestCodec.parse(text)
    errors += parsed.errors
    return listOf(blank) + parsed.entries.mapNotNull { entry ->
      val target = File(builtinRoot, entry.file)
      val staged = File(builtinRoot, ".${entry.file}.new")
      runCatching {
        context.assets.open("templates/${entry.file}").use { input ->
          FileOutputStream(staged).use(input::copyTo)
        }
        if (!target.isFile || sha256(target) != sha256(staged)) {
          Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
      }.onFailure { errors += "无法更新内置模板 ${entry.name}：${it.message}" }
        .also { staged.delete() }
      target.takeIf(File::isFile)?.let {
        runCatching {
          validateAsset(it, entry)
          TemplateDefinition(entry, it, sha256(it), TemplateAvailability.AVAILABLE)
        }.getOrElse { failure ->
          errors += "内置模板 ${entry.name} 无效：${failure.message}"
          null
        }
      }
    }
  }

  private fun loadLastManifest(errors: MutableList<String>): List<TemplateDefinition> {
    if (!lastManifest.isFile) return emptyList()
    return loadManifest(lastManifest.readText(), null, errors)
  }

  private fun loadManifest(text: String, tree: DocumentFile?, errors: MutableList<String>): List<TemplateDefinition> {
    val parsed = TemplateManifestCodec.parse(text)
    errors += parsed.errors
    return parsed.entries.mapNotNull { entry ->
      val current = tree?.findFile(entry.file)
      val available = current?.takeIf { it.isFile && it.canRead() }?.let { document ->
        runCatching { mirrorAndValidate(document, entry) }.onFailure {
          errors += "${entry.name}：${it.message}"
        }.getOrNull()
      }
      if (available != null) return@mapNotNull available
      val cached = findCached(entry.id)
      if (cached != null) {
        runCatching { validateAsset(cached, entry) }.onFailure {
          errors += "${entry.name} 的缓存已损坏：${it.message}"
        }.getOrNull()?.let {
          TemplateDefinition(
            entry,
            cached,
            sha256(cached),
            TemplateAvailability.CACHED,
            "外部来源不可用，正在使用最后可用版本",
          )
        }
      } else {
        TemplateDefinition(
          entry,
          null,
          "unavailable:${entry.id}",
          TemplateAvailability.INVALID,
          "模板文件缺失或损坏",
        )
      }
    }
  }

  private fun mirrorAndValidate(document: DocumentFile, entry: TemplateManifestEntry): TemplateDefinition {
    val extension = entry.file.substringAfterLast('.').lowercase()
    val temporary = File(cacheRoot, ".${entry.id}-${System.nanoTime()}.$extension")
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    try {
      context.contentResolver.openInputStream(document.uri).use { input ->
        requireNotNull(input) { "无法打开 ${entry.file}" }
        FileOutputStream(temporary).use { output ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_TEMPLATE_BYTES) { "模板文件超过 32 MiB" }
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
          }
          output.fd.sync()
        }
      }
      require(total > 0) { "模板文件为空" }
      validateAsset(temporary, entry)
      val fingerprint = digest.digest().toHex()
      val target = File(cacheRoot, "${entry.id}-$fingerprint.$extension")
      if (!target.isFile) {
        Files.move(
          temporary.toPath(),
          target.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
        )
      }
      cacheRoot.listFiles { file ->
        file.name.startsWith("${entry.id}-") && file != target
      }?.forEach(File::delete)
      return TemplateDefinition(entry, target, fingerprint, TemplateAvailability.AVAILABLE)
    } finally {
      temporary.delete()
    }
  }

  private fun validateAsset(file: File, entry: TemplateManifestEntry) {
    val actual = if (file.extension.equals("png", true)) {
      val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.absolutePath, options)
      require(options.outWidth > 0 && options.outHeight > 0) { "PNG 无法解码" }
      options.outWidth.toDouble() to options.outHeight.toDouble()
    } else {
      val head = file.inputStream().bufferedReader().use { it.readText().take(MAX_SVG_TEXT) }
      val match = VIEW_BOX.find(head) ?: error("SVG 缺少有效 viewBox")
      val values = match.groupValues[1].trim().split(Regex("[ ,]+"))
      require(values.size == 4) { "SVG viewBox 无效" }
      val width = values[2].toDoubleOrNull() ?: error("SVG viewBox 无效")
      val height = values[3].toDoubleOrNull() ?: error("SVG viewBox 无效")
      require(width > 0 && height > 0) { "SVG viewBox 尺寸无效" }
      width to height
    }
    val declared = entry.width.toDouble() / entry.height
    val detected = actual.first / actual.second
    require(kotlin.math.abs(declared / detected - 1.0) <= TemplateDefinition.ASPECT_RATIO_TOLERANCE) {
      "文件宽高比与 manifest 不一致"
    }
  }

  private fun findCached(id: String): File? = cacheRoot.listFiles { file ->
    file.isFile && file.name.startsWith("$id-") &&
      (file.extension.equals("svg", true) || file.extension.equals("png", true))
  }?.maxByOrNull(File::lastModified)

  private fun seedBuiltins(uri: Uri) {
    val tree = DocumentFile.fromTreeUri(context, uri) ?: error("无法打开模板目录")
    require(tree.isDirectory && tree.canWrite()) { "模板目录不可写" }
    val builtinText = context.assets.open(ASSET_MANIFEST).bufferedReader().use { it.readText() }
    val builtinJson = JsonParser.parseString(builtinText).asJsonObject
    val manifestDoc = tree.findFile(MANIFEST_NAME)
    val targetJson = if (manifestDoc == null) {
      builtinJson
    } else {
      val existingText = readDocument(manifestDoc) ?: error("无法读取现有 manifest.json")
      val existing = JsonParser.parseString(existingText).asJsonObject
      require(existing.get("schemaVersion")?.asInt == 1) { "现有 manifest.json 版本不受支持" }
      val array = existing.getAsJsonArray("templates") ?: error("现有 manifest.json 缺少 templates")
      val ids = array.mapNotNull { runCatching { it.asJsonObject.get("id").asString }.getOrNull() }.toSet()
      builtinJson.getAsJsonArray("templates").forEach { item ->
        if (item.asJsonObject.get("id").asString !in ids) array.add(item.deepCopy())
      }
      existing
    }
    TemplateManifestCodec.parse(targetJson.toString()).errors.takeIf { it.isNotEmpty() }?.let {
      error(it.joinToString("；"))
    }
    builtinJson.getAsJsonArray("templates").forEach { item ->
      val name = item.asJsonObject.get("file").asString
      if (tree.findFile(name) == null) {
        val document = tree.createFile(mimeFor(name), name) ?: error("无法创建 $name")
        context.assets.open("templates/$name").use { input ->
          context.contentResolver.openOutputStream(document.uri, "w").use { output ->
            requireNotNull(output) { "无法写入 $name" }
            input.copyTo(output)
          }
        }
      }
    }
    val target = manifestDoc ?: tree.createFile("application/json", MANIFEST_NAME)
      ?: error("无法创建 $MANIFEST_NAME")
    requireNotNull(context.contentResolver.openOutputStream(target.uri, "wt")) {
      "无法写入 $MANIFEST_NAME"
    }.bufferedWriter().use {
      it.write(targetJson.toString())
    }
  }

  private fun readDocument(document: DocumentFile): String? = runCatching {
    context.contentResolver.openInputStream(document.uri)?.bufferedReader()?.use { it.readText() }
  }.getOrNull()

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().toHex()
  }

  private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
  private fun mimeFor(name: String) = if (name.endsWith(".svg", true)) "image/svg+xml" else "image/png"

  companion object {
    private const val MANIFEST_NAME = "manifest.json"
    private const val ASSET_MANIFEST = "templates/manifest.json"
    private const val BUILTIN_ID_PREFIX = "builtin."
    private const val MAX_TEMPLATE_BYTES = 32L * 1024L * 1024L
    private const val MAX_SVG_TEXT = 4 * 1024 * 1024
    private val VIEW_BOX = Regex("""viewBox\s*=\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)

    @Volatile private var instance: TemplateStore? = null
    fun get(context: Context): TemplateStore = instance ?: synchronized(this) {
      instance ?: TemplateStore(context.applicationContext).also { instance = it }
    }
  }
}
