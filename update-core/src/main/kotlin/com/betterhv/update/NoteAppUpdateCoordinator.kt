package com.betterhv.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface NoteAppUpdateTaskState {
  data object Resolving : NoteAppUpdateTaskState
  data class Downloading(val version: String, val bytesDownloaded: Long, val totalBytes: Long) :
    NoteAppUpdateTaskState
  data class UpToDate(val latestVersion: String?) : NoteAppUpdateTaskState
  data class Ready(
    val version: String,
    val itemId: UUID,
    val displayName: String,
    val byteLength: Long,
    val sha256: ByteArray,
  ) : NoteAppUpdateTaskState
  data class Failed(val message: String, val recoverable: Boolean = true) : NoteAppUpdateTaskState
}

class NoteAppUpdateCoordinator(
  private val scope: CoroutineScope,
  cacheDirectory: File,
  private val resolver: NotePackageResolver = NotePackageResolver(),
  private val downloader: SecureReleaseDownloader = SecureReleaseDownloader(),
  private val enqueue: (File, NotePackageDescriptor, String) -> UUID,
) : AutoCloseable {
  private val cache = cacheDirectory.canonicalFile.also(File::mkdirs)
  private val tasks = ConcurrentHashMap<UUID, Task>()
  private val mutableState = MutableStateFlow<NoteAppUpdateTaskState?>(null)
  val state: StateFlow<NoteAppUpdateTaskState?> = mutableState.asStateFlow()

  init {
    cache.listFiles()?.filter { it.name.endsWith(".part") }?.forEach(File::delete)
  }

  fun begin(operationId: UUID, peerId: String, currentVersion: String): NoteAppUpdateTaskState {
    val existing = tasks[operationId]
    if (existing != null) {
      require(existing.peerId == peerId && existing.currentVersion == currentVersion) {
        "Update operation belongs to another request"
      }
      return existing.state
    }
    val task = Task(peerId, currentVersion)
    val winner = tasks.putIfAbsent(operationId, task)
    if (winner != null) return begin(operationId, peerId, currentVersion)
    mutableState.value = task.state
    task.job = scope.launch { resolveAndPrepare(task) }
    return task.state
  }

  fun status(operationId: UUID, peerId: String): NoteAppUpdateTaskState {
    val task = requireNotNull(tasks[operationId]) { "Update operation does not exist" }
    require(task.peerId == peerId) { "Update operation belongs to another device" }
    return task.state
  }

  fun cancel(operationId: UUID, peerId: String) {
    val task = requireNotNull(tasks[operationId]) { "Update operation does not exist" }
    require(task.peerId == peerId) { "Update operation belongs to another device" }
    tasks.remove(operationId, task)
    task.job?.cancel()
  }

  /** Validates a user-selected file against the current latest stable release and caches it. */
  fun importLatest(source: File): NotePackageDescriptor {
    require(source.isFile) { "Selected APK does not exist" }
    val descriptor = (resolver.resolve("0.0.0") as? NotePackageResolution.Available)?.descriptor
      ?: error("No stable BetterHvNote release is available")
    require(source.length() <= NotePackageResolver.MAX_NOTE_PACKAGE_BYTES) { "Note APK is too large" }
    require(downloader.verify(source, descriptor)) { "Selected APK does not match the official SHA-256" }
    val temporary = File(cache, "import-${UUID.randomUUID()}.part")
    try {
      source.copyTo(temporary, overwrite = true)
      require(downloader.verify(temporary, descriptor)) { "Imported APK changed while copying" }
      installCache(temporary, descriptor)
    } finally {
      temporary.delete()
    }
    return descriptor
  }

  private fun resolveAndPrepare(task: Task) {
    try {
      when (val resolution = resolver.resolve(task.currentVersion)) {
        is NotePackageResolution.UpToDate -> {
          update(task, NoteAppUpdateTaskState.UpToDate(resolution.latestVersion?.display))
        }

        is NotePackageResolution.Available -> {
          val descriptor = resolution.descriptor
          val target = cachedFile(descriptor)
          if (!downloader.verify(target, descriptor)) {
            val temporary = File(cache, "download-${UUID.randomUUID()}.part")
            try {
              update(task, NoteAppUpdateTaskState.Downloading(
                descriptor.version.display,
                0,
                descriptor.apk.byteLength,
              ))
              downloader.download(
                descriptor.apk.downloadUrl,
                temporary,
                descriptor.apk.byteLength,
                descriptor.expectedSha256,
              ) { done, total ->
                update(task, NoteAppUpdateTaskState.Downloading(descriptor.version.display, done, total))
              }
              installCache(temporary, descriptor)
            } finally {
              temporary.delete()
            }
          }
          val itemId = enqueue(cachedFile(descriptor), descriptor, task.peerId)
          update(
            task,
            NoteAppUpdateTaskState.Ready(
              descriptor.version.display,
              itemId,
              descriptor.apk.name,
              descriptor.apk.byteLength,
              descriptor.expectedSha256,
            ),
          )
        }
      }
    } catch (_: CancellationException) {
      // Explicit cancellation removes the operation from the public task map.
    } catch (error: Throwable) {
      update(task, NoteAppUpdateTaskState.Failed(error.message ?: "Could not prepare Note update"))
    }
  }

  private fun cachedFile(descriptor: NotePackageDescriptor) =
    File(cache, "BetterHvNote-${descriptor.version.display}-android-arm64.apk")

  private fun update(task: Task, state: NoteAppUpdateTaskState) {
    task.state = state
    mutableState.value = state
  }

  private fun installCache(temporary: File, descriptor: NotePackageDescriptor) {
    val target = cachedFile(descriptor)
    Files.move(
      temporary.toPath(),
      target.toPath(),
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE,
    )
    cache.listFiles()?.filter { it.isFile && it.extension == "apk" && it != target }?.forEach(File::delete)
  }

  override fun close() {
    tasks.values.forEach { it.job?.cancel() }
    tasks.clear()
  }

  private class Task(val peerId: String, val currentVersion: String) {
    @Volatile var state: NoteAppUpdateTaskState = NoteAppUpdateTaskState.Resolving
    @Volatile var job: Job? = null
  }
}
