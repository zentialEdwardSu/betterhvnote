package com.betterhv.note.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.AtomicFile
import com.betterhv.note.PenSettingsStore
import com.betterhv.note.storage.MigrationManager
import com.betterhv.note.storage.SQLiteStore
import com.betterhv.note.template.TemplateManifestCodec
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class PreparedBackup(val directory: File, val manifest: BackupManifest)

/** Used only after the editor/export sessions have closed, or before they open on startup. */
class BackupManager(private val context: Context) {
  private val root = File(context.filesDir, "backups").also { it.mkdirs() }
  private val rollback = File(root, "rollback")
  private val journal = AtomicFile(File(root, "restore.pending"))
  private val gson = Gson()
  private val preferenceNames = listOf("app_settings", PenSettingsStore.PREFERENCES_NAME)

  fun needsRecovery(): Boolean = journal.baseFile.exists() || File(journal.baseFile.path + ".bak").exists()

  fun hasRollback(): Boolean = File(rollback, "settings.json").isFile &&
    File(rollback, "documents/default.inknote").isFile

  fun export(uri: Uri, progress: (Int, Int) -> Unit) {
    val snapshot = File(root, "export-staging")
    try {
      snapshot(snapshot, portable = true, progress)
      val count = validateDatabase(snapshot, clearArtifacts = true)
      val manifest = BackupArchive.manifest(snapshot, MigrationManager.VERSION, count)
      val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法打开备份文件")
      BackupArchive.write(snapshot, manifest, output, progress)
    } finally {
      snapshot.deleteRecursively()
    }
  }

  fun prepare(uri: Uri, progress: (Int, Int) -> Unit): PreparedBackup {
    val staging = freshStaging()
    try {
      val input = context.contentResolver.openInputStream(uri) ?: error("无法读取备份文件")
      val manifest = BackupArchive.read(input, staging, progress)
      require(manifest.schemaVersion <= MigrationManager.VERSION) { "备份来自更新版本，请先升级应用" }
      val version = SQLiteDatabase.openDatabase(File(staging, "documents/default.inknote").path, null,
        SQLiteDatabase.OPEN_READONLY).use { it.version }
      require(version == manifest.schemaVersion) { "数据库版本与备份清单不一致" }
      validateSettings(staging)
      val count = validateDatabase(staging, clearArtifacts = true)
      require(count == manifest.notebookCount) { "笔记数量与备份清单不一致" }
      return PreparedBackup(staging, manifest)
    } catch (error: Throwable) {
      staging.deleteRecursively()
      throw error
    }
  }

  fun prepareRollback(): PreparedBackup {
    require(hasRollback()) { "没有恢复前副本" }
    val staging = freshStaging()
    try {
      rollback.copyRecursively(staging, overwrite = true)
      validateSettings(staging)
      val count = validateDatabase(staging, clearArtifacts = true)
      return PreparedBackup(staging, BackupArchive.manifest(staging, MigrationManager.VERSION, count))
    } catch (error: Throwable) {
      staging.deleteRecursively()
      throw error
    }
  }

  /** The durable journal remains until data, settings and validation have all committed. */
  @Suppress("UsableSpace")
  fun restore(prepared: PreparedBackup, progress: (Int, Int) -> Unit) {
    require(prepared.directory.canonicalFile.parentFile == root.canonicalFile)
    validateSettings(prepared.directory)
    validateDatabase(prepared.directory, clearArtifacts = true)
    val currentBytes = listOf("documents", "templates").sumOf { name ->
      File(context.filesDir, name).walkTopDown().filter(File::isFile).sumOf(File::length)
    }
    val incomingBytes = prepared.directory.walkTopDown().filter(File::isFile).sumOf(File::length)
    require(root.usableSpace > currentBytes + incomingBytes + 32L * 1024 * 1024) {
      "空间不足以同时保存恢复前副本与安装文件，当前数据未修改"
    }
    val previous = File(root, "rollback-new")
    snapshot(previous, portable = false, progress)
    validateDatabase(previous, clearArtifacts = false)
    val obsolete = File(root, "rollback-old")
    obsolete.deleteRecursively()
    if (rollback.exists()) Files.move(rollback.toPath(), obsolete.toPath())
    Files.move(previous.toPath(), rollback.toPath())
    val stream = journal.startWrite()
    try { stream.write("restore-v1".toByteArray()); journal.finishWrite(stream) } catch (error: Throwable) {
      journal.failWrite(stream); throw error
    }
    try {
      install(prepared.directory)
      validateLiveDatabase()
      journal.delete()
      obsolete.deleteRecursively()
    } catch (error: Throwable) {
      // Do not clear the journal when rollback fails: startup must retry before opening the editor.
      recoverInterruptedRestore()
      throw error
    } finally {
      prepared.directory.deleteRecursively()
    }
  }

  fun recoverInterruptedRestore(): Boolean {
    if (!needsRecovery()) return false
    journal.openRead().use { it.read() }
    check(hasRollback()) { "恢复中断且缺少回滚副本；请保留应用数据以便修复" }
    validateSettings(rollback)
    validateDatabase(rollback, clearArtifacts = false)
    install(rollback)
    validateLiveDatabase()
    journal.delete()
    return true
  }

  fun discard(prepared: PreparedBackup?) {
    prepared?.directory?.takeIf { it.canonicalFile.parentFile == root.canonicalFile && it.name == "import-staging" }
      ?.deleteRecursively()
  }

  private fun freshStaging(): File = File(root, "import-staging").also {
    check(it.deleteRecursively() || !it.exists()); check(it.mkdirs())
  }

  // Keep rollback independent of reclaimable cache space.
  @Suppress("UsableSpace")
  private fun snapshot(destination: File, portable: Boolean, progress: (Int, Int) -> Unit) {
    check(destination.deleteRecursively() || !destination.exists()); check(destination.mkdirs())
    val database = File(context.filesDir, "documents/default.inknote")
    require(database.isFile) { "笔记数据库不存在" }
    SQLiteStore(context, database).use { store ->
      check(store.quickCheck()) { "笔记数据库校验失败" }
      store.checkpoint()
    }
    val files = buildList {
      add(database)
      listOf("documents/assets", "documents/pdfs", "templates/cache").forEach { relative ->
        addAll(File(context.filesDir, relative).walkTopDown().filter { it.isFile && !it.name.startsWith('.') }.toList())
      }
      File(context.filesDir, "templates/last-manifest.json").takeIf(File::isFile)?.let(::add)
    }
    val bytes = files.sumOf(File::length)
    require(destination.usableSpace > bytes + 16L * 1024 * 1024) { "存储空间不足，无法创建完整副本" }
    files.forEachIndexed { index, file ->
      progress(index, files.size)
      val target = BackupArchive.resolve(destination, file.relativeTo(context.filesDir).invariantSeparatorsPath)
      target.parentFile!!.mkdirs()
      file.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
    }
    val settings = JsonObject()
    preferenceNames.forEach { name ->
      val values = JsonObject()
      context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
        if (!(portable && key == "template_directory_uri")) {
          val encoded = JsonObject()
          encoded.addProperty("type", when (value) {
            is Boolean -> "boolean"
            is Int -> "int"
            is Long -> "long"
            is Float -> "float"
            is Set<*> -> "set"
            else -> "string"
          })
          encoded.add("value", gson.toJsonTree(value))
          values.add(key, encoded)
        }
      }
      settings.add(name, values)
    }
    File(destination, "settings.json").outputStream().use {
      it.write(gson.toJson(settings).toByteArray(Charsets.UTF_8)); it.fd.sync()
    }
    progress(files.size, files.size)
  }

  private fun validateSettings(directory: File): JsonObject {
    val file = File(directory, "settings.json")
    require(file.isFile && file.length() <= 4 * 1024 * 1024) { "设置备份无效" }
    val json = gson.fromJson(file.readText(), JsonObject::class.java)
    require(json.keySet() == preferenceNames.toSet()) { "设置备份不完整" }
    preferenceNames.forEach { name -> json.getAsJsonObject(name).entrySet().forEach { (_, element) ->
      val entry = element.asJsonObject
      val value = entry.get("value")
      when (entry.get("type").asString) {
        "boolean" -> require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
        "int" -> require(value.asString.toLong() in Int.MIN_VALUE..Int.MAX_VALUE)
        "long" -> require(value.asString.toLongOrNull() != null)
        "float" -> require(value.asFloat.isFinite())
        "string" -> require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        "set" -> require(value.isJsonArray && value.asJsonArray.all {
          it.isJsonPrimitive && it.asJsonPrimitive.isString
        })
        else -> error("不支持的设置类型")
      }
    } }
    return json
  }

  // KTX edit(commit=true) discards the result; restore must verify the durable commit.
  @Suppress("UseKtx")
  private fun applySettings(directory: File) {
    val settings = validateSettings(directory)
    preferenceNames.forEach { name ->
      val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
      settings.getAsJsonObject(name).entrySet().forEach { (key, element) ->
        val entry = element.asJsonObject
        val value = entry.get("value")
        when (entry.get("type").asString) {
          "boolean" -> editor.putBoolean(key, value.asBoolean)
          "int" -> editor.putInt(key, value.asInt)
          "long" -> editor.putLong(key, value.asLong)
          "float" -> editor.putFloat(key, value.asFloat)
          "set" -> editor.putStringSet(key, value.asJsonArray.map { it.asString }.toSet())
          "string" -> editor.putString(key, value.asString)
        }
      }
      check(editor.commit()) { "无法保存恢复后的设置" }
    }
  }

  private fun validateDatabase(directory: File, clearArtifacts: Boolean): Int {
    val database = File(directory, "documents/default.inknote")
    require(database.isFile)
    SQLiteStore(context, database).use { store ->
      val db = store.writableDatabase // Explicitly migrate the staged copy before it can become live.
      require(store.quickCheck()) { "数据库完整性校验失败" }
      db.rawQuery("PRAGMA foreign_key_check", null).use { require(!it.moveToFirst()) { "数据库引用无效" } }
      db.rawQuery("SELECT asset_path FROM images UNION SELECT asset_path FROM pdf_documents", null).use { cursor ->
        while (cursor.moveToNext()) {
          require(BackupArchive.resolve(directory, "documents/${cursor.getString(0)}").isFile) { "备份缺少图片或 PDF" }
        }
      }
      val manifestFile = File(directory, "templates/last-manifest.json")
      val templates = if (manifestFile.isFile) TemplateManifestCodec.parse(manifestFile.readText()) else null
      require(templates == null || templates.errors.isEmpty()) { "自定义模板清单无效" }
      db.rawQuery("SELECT DISTINCT template_id FROM pages WHERE template_id IS NOT NULL", null).use { cursor ->
        while (cursor.moveToNext()) {
          val id = cursor.getString(0)
          if (!id.startsWith("builtin.")) {
            require(templates?.entries?.any { it.id == id } == true &&
              File(directory, "templates/cache").listFiles().orEmpty().any {
                it.name.startsWith("$id-") && it.isFile
              }) {
              "备份缺少自定义模板：$id"
            }
          }
        }
      }
      if (clearArtifacts) {
        db.execSQL("DELETE FROM export_page_cache")
        db.execSQL("UPDATE export_tasks SET artifact_id=NULL, artifact_path=NULL, artifact_fingerprint=NULL, " +
          "artifact_size=NULL, artifact_sha256=NULL, artifact_created_at=NULL, last_error=NULL")
      }
      val count = db.rawQuery("SELECT COUNT(*) FROM notebooks", null).use { it.moveToFirst(); it.getInt(0) }
      store.checkpoint()
      return count
    }
  }

  private fun validateLiveDatabase() = validateDatabase(context.filesDir, clearArtifacts = true)

  private fun install(source: File) {
    listOf("documents", "templates").forEach { name ->
      val staged = File(root, "install-$name")
      staged.deleteRecursively(); staged.mkdirs()
      val input = File(source, name)
      if (input.isDirectory) check(input.copyRecursively(staged, overwrite = true))
      val live = File(context.filesDir, name)
      check(live.deleteRecursively() || !live.exists()) { "无法替换 $name" }
      Files.move(staged.toPath(), live.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
    applySettings(source)
    listOf(File(context.filesDir, "exports"), File(context.cacheDir, "page-thumbnails")).forEach {
      check(it.deleteRecursively() || !it.exists())
    }
  }
}
