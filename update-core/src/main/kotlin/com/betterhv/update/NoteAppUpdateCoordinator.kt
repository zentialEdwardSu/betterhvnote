package com.betterhv.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
  cleanStalePartsOnInit: Boolean = true,
  private val enqueue: (File, NotePackageDescriptor, String) -> UUID,
) : AutoCloseable {
  private val cache = cacheDirectory.canonicalFile.also(File::mkdirs)
  private val tasks = ConcurrentHashMap<UUID, Task>()
  private val taskSequence = AtomicLong()
  private val mutableState = MutableStateFlow<NoteAppUpdateTaskState?>(null)
  val state: StateFlow<NoteAppUpdateTaskState?> = mutableState.asStateFlow()

  init {
    if (cleanStalePartsOnInit) cleanupStalePartials()
  }

  private fun cleanupStalePartials() {
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
    pruneTerminalTasks()
    val task = Task(peerId, currentVersion, taskSequence.incrementAndGet())
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

  private suspend fun resolveAndPrepare(task: Task) {
    try {
      currentCoroutineContext().ensureActive()
      when (val resolution = resolver.resolve(task.currentVersion)) {
        is NotePackageResolution.UpToDate -> {
          currentCoroutineContext().ensureActive()
          update(task, NoteAppUpdateTaskState.UpToDate(resolution.latestVersion?.display))
        }

        is NotePackageResolution.Available -> {
          currentCoroutineContext().ensureActive()
          val descriptor = resolution.descriptor
          val target = cachedFile(descriptor)
          if (!downloader.verify(target, descriptor)) {
            val temporary = File(cache, "download-${UUID.randomUUID()}.part")
            try {
              currentCoroutineContext().ensureActive()
              update(
                task,
                NoteAppUpdateTaskState.Downloading(
                  descriptor.version.display,
                  0,
                  descriptor.apk.byteLength,
                ),
              )
              downloadCancellable(task, descriptor, temporary)
              currentCoroutineContext().ensureActive()
              installCache(temporary, descriptor)
            } finally {
              temporary.delete()
            }
          }
          currentCoroutineContext().ensureActive()
          val itemId = enqueue(cachedFile(descriptor), descriptor, task.peerId)
          currentCoroutineContext().ensureActive()
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

  private suspend fun downloadCancellable(
    task: Task,
    descriptor: NotePackageDescriptor,
    temporary: File,
  ) = coroutineScope {
    val activeConnection = AtomicReference<HttpURLConnection?>()
    val downloadJob = launch(Dispatchers.IO) {
      downloader.download(
        url = descriptor.apk.downloadUrl,
        destination = temporary,
        expectedLength = descriptor.apk.byteLength,
        expectedSha256 = descriptor.expectedSha256,
        isActive = { isActive },
        onConnection = { conn ->
          activeConnection.set(conn)
          if (!isActive) {
            activeConnection.getAndSet(null)?.let { runCatching { it.disconnect() } }
          }
        },
      ) { done, total ->
        if (!isActive) throw CancellationException("Update download cancelled")
        update(task, NoteAppUpdateTaskState.Downloading(descriptor.version.display, done, total))
      }
    }
    try {
      suspendCancellableCoroutine<Unit> { cont ->
        cont.invokeOnCancellation {
          activeConnection.getAndSet(null)?.let { runCatching { it.disconnect() } }
          downloadJob.cancel()
        }
        downloadJob.invokeOnCompletion { cause ->
          when {
            cause == null -> cont.resume(Unit)
            cont.isActive -> cont.resumeWithException(cause)
          }
        }
      }
    } finally {
      withContext(NonCancellable) { downloadJob.join() }
    }
  }

  private fun cachedFile(descriptor: NotePackageDescriptor) =
    File(cache, "BetterHvNote-${descriptor.version.display}-android-arm64.apk")

  private fun update(task: Task, state: NoteAppUpdateTaskState) {
    task.state = state
    mutableState.value = state
  }

  private fun pruneTerminalTasks() {
    if (tasks.size < MAX_CACHED_TASKS) return
    val terminal = tasks.entries
      .filter { (_, task) ->
        val current = task.state
        current is NoteAppUpdateTaskState.UpToDate ||
          current is NoteAppUpdateTaskState.Ready ||
          current is NoteAppUpdateTaskState.Failed
      }
      .sortedBy { it.value.sequence }
    var excess = tasks.size - MAX_CACHED_TASKS + 1
    for ((id, task) in terminal) {
      if (excess <= 0) break
      if (tasks.remove(id, task)) excess--
    }
  }

  private fun installCache(temporary: File, descriptor: NotePackageDescriptor) {
    val target = cachedFile(descriptor)
    try {
      Files.move(
        temporary.toPath(),
        target.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(
        temporary.toPath(),
        target.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
      )
    }
    cache.listFiles()?.filter { it.isFile && it.extension == "apk" && it != target }?.forEach(File::delete)
  }

  override fun close() {
    tasks.values.forEach { it.job?.cancel() }
    tasks.clear()
  }

  private class Task(val peerId: String, val currentVersion: String, val sequence: Long) {
    @Volatile var state: NoteAppUpdateTaskState = NoteAppUpdateTaskState.Resolving
    @Volatile var job: Job? = null
  }

  companion object {
    internal const val MAX_CACHED_TASKS = 32
  }
}
