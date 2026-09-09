package com.betterhv.note

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import com.betterhv.update.AppVersion
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

sealed interface NoteAppUpdateUiState {
  data object Idle : NoteAppUpdateUiState
  data object Resolving : NoteAppUpdateUiState
  data class Downloading(val version: String, val downloaded: Long, val total: Long) : NoteAppUpdateUiState
  data class Transferring(val version: String) : NoteAppUpdateUiState
  data class Ready(val version: String) : NoteAppUpdateUiState
  data class UpToDate(val latestVersion: String?) : NoteAppUpdateUiState
  data class Failed(val message: String) : NoteAppUpdateUiState
}

data class PendingNotePackage(val file: File, val versionName: String, val versionCode: Long)

class NoteAppInstaller(private val context: Context) {
  private val appContext = context.applicationContext
  private val updates = File(appContext.filesDir, "updates").also(File::mkdirs)
  private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  fun pending(): PendingNotePackage? {
    val path = preferences.getString(KEY_PATH, null) ?: return null
    val file = File(path).canonicalFile
    val safe = file.toPath().startsWith(updates.canonicalFile.toPath()) && file.isFile
    if (!safe) {
      clear(); return null
    }
    val version = preferences.getString(KEY_VERSION, null) ?: run { clear(); return null }
    val code = preferences.getLong(KEY_VERSION_CODE, -1L)
    if (code <= BuildConfig.VERSION_CODE.toLong()) {
      clear(); return null
    }
    return PendingNotePackage(file, version, code)
  }

  fun validateAndStage(source: File, offeredVersion: String): PendingNotePackage {
    require(source.isFile && source.length() in 1..com.betterhv.transfer.core.TransferLimits.MAX_APP_PACKAGE_BYTES) {
      "Received Note APK has an invalid size"
    }
    val manager = appContext.packageManager
    val archive = requireNotNull(packageArchiveInfo(manager, source)) { "Received file is not an Android package" }
    require(archive.packageName == appContext.packageName) { "APK package name is not ${appContext.packageName}" }
    require(archive.longVersionCode > BuildConfig.VERSION_CODE.toLong()) { "APK version is not newer than this app" }
    require(archive.versionName == offeredVersion && AppVersion.parse(offeredVersion) != null) {
      "APK version does not match the offered release"
    }
    val installed = installedPackageInfo(manager)
    require(signerDigests(archive) == signerDigests(installed)) {
      "APK signing certificate does not match the installed app"
    }

    val temporary = File(updates, "BetterHvNote-$offeredVersion.apk.part")
    val target = File(updates, "BetterHvNote-$offeredVersion.apk")
    source.copyTo(temporary, overwrite = true)
    Files.move(
      temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
    )
    source.delete()
    updates.listFiles()?.filter { it.isFile && it.extension == "apk" && it != target }?.forEach(File::delete)
    preferences.edit(commit = true) {
      putString(KEY_PATH, target.absolutePath)
      putString(KEY_VERSION, offeredVersion)
      putLong(KEY_VERSION_CODE, archive.longVersionCode)
    }
    return PendingNotePackage(target, offeredVersion, archive.longVersionCode)
  }

  /** Returns false when the user must first grant the per-source install permission. */
  fun launch(packageInfo: PendingNotePackage = requireNotNull(pending())): Boolean {
    if (!appContext.packageManager.canRequestPackageInstalls()) {
      context.startActivity(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${appContext.packageName}".toUri())
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
      return false
    }
    val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", packageInfo.file)
    context.startActivity(
      Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, com.betterhv.update.NotePackageResolver.NOTE_PACKAGE_MIME)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
      },
    )
    return true
  }

  fun clear() {
    preferences.getString(KEY_PATH, null)?.let { runCatching { File(it).delete() } }
    updates.listFiles()?.filter { it.name.endsWith(".part") }?.forEach(File::delete)
    preferences.edit(commit = true) { clear() }
  }

  private fun packageArchiveInfo(manager: PackageManager, file: File): PackageInfo? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      manager.getPackageArchiveInfo(
        file.absolutePath,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
      )
    } else {
      @Suppress("DEPRECATION")
      manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
    }

  private fun installedPackageInfo(manager: PackageManager): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      manager.getPackageInfo(
        appContext.packageName,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
      )
    } else {
      @Suppress("DEPRECATION")
      manager.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }

  private fun signerDigests(info: PackageInfo): List<String> {
    val signatures = requireNotNull(info.signingInfo) { "APK has no signing information" }.apkContentsSigners
    return signatures.map { signature ->
      MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
    }.sorted()
  }

  companion object {
    private const val PREFERENCES = "note_app_update"
    private const val KEY_PATH = "path"
    private const val KEY_VERSION = "version"
    private const val KEY_VERSION_CODE = "version_code"
  }
}
