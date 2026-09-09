package com.betterhv.note

import android.net.Uri
import com.betterhv.note.storage.ImportedImage
import com.betterhv.transfer.android.ReceivedLease
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.RemotePayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume

private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
  Result.success(block())
} catch (cancelled: CancellationException) {
  throw cancelled
} catch (error: Throwable) {
  Result.failure(error)
}

sealed interface InsertionState {
  data object Idle : InsertionState
  data class ChoosingSource(val kind: ContentKind) : InsertionState
  data class ChoosingClient(val kind: ContentKind, val clients: List<PhoneTransferClient.AvailableNoteLink>) :
    InsertionState
  data class WaitingForPhone(val kind: ContentKind) : InsertionState
  data class ImageReady(val image: ImportedImage, val lease: ReceivedLease?, val sourceDeviceId: String? = null) :
    InsertionState
  data class TextReady(val initialText: String, val lease: ReceivedLease?, val sourceDeviceId: String? = null) :
    InsertionState
  data class Error(val message: String) : InsertionState
}

internal enum class AutomaticRemoteDecision { CHOOSE_SOURCE, PULL_SINGLE, CHOOSE_CLIENT }

internal fun automaticRemoteDecision(availableCount: Int): AutomaticRemoteDecision = when (availableCount) {
  0 -> AutomaticRemoteDecision.CHOOSE_SOURCE
  1 -> AutomaticRemoteDecision.PULL_SINGLE
  else -> AutomaticRemoteDecision.CHOOSE_CLIENT
}

/** Owns insertion resources so Compose recreation cannot leak a staged file or remote lease. */
class InsertionCoordinator(private val phone: PhoneTransferClient) : AutoCloseable {
  private val mutableState = MutableStateFlow<InsertionState>(InsertionState.Idle)
  private val leaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  val state: StateFlow<InsertionState> = mutableState
  private var penView: PenDrawView? = null
  private var requestGeneration = 0L

  fun attach(view: PenDrawView) {
    penView = view
  }
  fun choose(kind: ContentKind) {
    cancel();
    mutableState.value = InsertionState.ChoosingSource(kind)
  }
  fun manualText() {
    cancel();
    mutableState.value = InsertionState.TextReady("", null)
  }

  /** Skips the source sheet only when the paired phone advertises matching content. */
  suspend fun remoteIfAvailableOrChoose(kind: ContentKind) {
    cancel()
    val generation = requestGeneration
    mutableState.value = InsertionState.WaitingForPhone(kind)
    val available = discover(kind, generation) ?: return
    if (generation != requestGeneration) return
    when (automaticRemoteDecision(available.size)) {
      AutomaticRemoteDecision.CHOOSE_SOURCE -> mutableState.value = InsertionState.ChoosingSource(kind)
      AutomaticRemoteDecision.PULL_SINGLE -> remote(available.single().client.id, kind)
      AutomaticRemoteDecision.CHOOSE_CLIENT -> mutableState.value = InsertionState.ChoosingClient(kind, available)
    }
  }

  suspend fun localImage(uri: Uri) {
    cancel()
    val generation = requestGeneration
    val view = requireNotNull(penView)
    runCatchingCancellable { withContext(Dispatchers.IO) { view.importImage(uri) } }
      .onSuccess {
        if (generation == requestGeneration) {
          mutableState.value = InsertionState.ImageReady(it, null)
        } else {
          view.discardImportedImage(it)
        }
      }
      .onFailure {
        if (generation == requestGeneration) {
          mutableState.value = InsertionState.Error(
            it.message ?: "Image import failed",
          )
        }
      }
  }

  suspend fun remote(kind: ContentKind) {
    cancel()
    val generation = requestGeneration
    mutableState.value = InsertionState.WaitingForPhone(kind)
    val available = discover(kind, generation) ?: return
    if (generation != requestGeneration) return
    when (available.size) {
      0 -> mutableState.value = InsertionState.Error(
        noteText("没有发现包含${contentLabel(kind)}的 NoteLink", "No NoteLink with ${contentLabel(kind)} was found"),
      )

      1 -> remote(available.single().client.id, kind)

      else -> mutableState.value = InsertionState.ChoosingClient(kind, available)
    }
  }

  private suspend fun discover(kind: ContentKind, generation: Long): List<PhoneTransferClient.AvailableNoteLink>? =
    try {
      phone.discoverAvailable(kind)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      if (generation == requestGeneration) {
        mutableState.value = InsertionState.Error(error.message ?: "NoteLink discovery failed")
      }
      null
    }

  suspend fun remote(clientId: String, kind: ContentKind) {
    cancel()
    val generation = requestGeneration
    mutableState.value = InsertionState.WaitingForPhone(kind)
    runCatchingCancellable { phone.requestNext(clientId, kind) }
      .onSuccess { lease ->
        try {
          if (generation != requestGeneration) {
            lease?.payload?.stagedFileOrNull()?.delete()
            lease?.let { finishLease(it, commit = false) }
            return@onSuccess
          }
          if (lease == null) {
            mutableState.value = InsertionState.Error(
              noteText("手机队列中没有${contentLabel(kind)}", "The phone queue has no ${contentLabel(kind)}"),
            )
            return@onSuccess
          }
          when (val payload = lease.payload) {
            is RemotePayload.Image -> {
              val staged = withContext(Dispatchers.IO) {
                requireNotNull(penView).stageRemoteImage(payload.stagedFile, payload.item.mimeType)
                  .also { payload.stagedFile.delete() }
              }
              if (generation == requestGeneration) {
                mutableState.value = InsertionState.ImageReady(staged, lease, clientId)
              } else {
                penView?.discardImportedImage(staged)
                finishLease(lease, commit = false)
              }
            }

            is RemotePayload.Text -> mutableState.value = InsertionState.TextReady(payload.text, lease, clientId)

            is RemotePayload.Pdf -> {
              val imported = suspendCancellableCoroutine<Result<UUID>> { continuation ->
                requireNotNull(penView).importPdfFile(
                  payload.stagedFile,
                  payload.item.displayName ?: "NoteLink.pdf",
                ) { continuation.resume(it) }
              }
              imported.getOrThrow()
              payload.stagedFile.delete()
              finishLease(lease, commit = true)
              mutableState.value = InsertionState.Idle
            }

            is RemotePayload.AppPackage -> error("App packages cannot be inserted into a notebook")
          }
        } catch (error: Throwable) {
          lease?.payload?.stagedFileOrNull()?.delete()
          lease?.let { finishLease(it, commit = false) }
          if (error is CancellationException) throw error
          if (generation == requestGeneration) {
            mutableState.value = InsertionState.Error(error.message ?: "Phone transfer failed")
          }
        }
      }
      .onFailure {
        if (generation == requestGeneration) {
          mutableState.value = InsertionState.Error(
            it.message ?: "Phone transfer failed",
          )
        }
      }
  }

  fun placeImage(x: Float, y: Float): Result<Unit> {
    val ready = mutableState.value as? InsertionState.ImageReady
      ?: return Result.failure(IllegalStateException("No image is ready to insert"))
    val view = requireNotNull(penView)
    val result = if (ready.lease == null) {
      runCatching { view.placeImage(ready.image, x, y) }
    } else {
      val item = ready.lease.offer.item
      view.placeTransferredImage(ready.image, x, y, requireNotNull(ready.sourceDeviceId), item.id)
        .onSuccess { finishLease(ready.lease, commit = true) }
    }
    if (result.isSuccess) mutableState.value = InsertionState.Idle
    return result.map { }
  }

  fun placeText(text: String, x: Float, y: Float): Result<Unit> {
    val ready = mutableState.value as? InsertionState.TextReady
      ?: return Result.failure(IllegalStateException("No text is ready to insert"))
    val view = requireNotNull(penView)
    val result = if (ready.lease == null) {
      runCatching { view.placeText(text, x, y) }
    } else {
      view.placeTransferredText(
        text,
        x,
        y,
        requireNotNull(ready.sourceDeviceId),
        ready.lease.offer.item.id,
      )
        .onSuccess { finishLease(ready.lease, commit = true) }
    }
    if (result.isSuccess) mutableState.value = InsertionState.Idle
    return result.map { }
  }

  fun dismissError() {
    if (mutableState.value is InsertionState.Error) mutableState.value = InsertionState.Idle
  }

  fun cancel() {
    requestGeneration++
    when (val current = mutableState.value) {
      is InsertionState.ImageReady -> {
        penView?.discardImportedImage(current.image)
        current.lease?.let { finishLease(it, commit = false) }
      }

      is InsertionState.TextReady -> current.lease?.let { finishLease(it, commit = false) }

      else -> Unit
    }
    mutableState.value = InsertionState.Idle
  }

  private fun finishLease(lease: ReceivedLease, commit: Boolean) {
    leaseScope.launch {
      runCatchingCancellable {
        if (commit) lease.commitAndAwait() else lease.releaseAndAwait()
      }.onFailure { error ->
        EventLog.log(
          "NoteLinkTransfer",
          "lease ${if (commit) "commit" else "release"} failed " +
            "itemId=${lease.offer.item.id} error=${error.message ?: error.javaClass.simpleName}",
        )
      }
    }
  }

  override fun close() {
    requestGeneration++
    when (val current = mutableState.value) {
      is InsertionState.ImageReady -> penView?.discardImportedImage(current.image)
      else -> Unit
    }
    mutableState.value = InsertionState.Idle
    leaseScope.cancel()
    phone.close()
  }

  private fun RemotePayload.stagedFileOrNull() = when (this) {
    is RemotePayload.Image -> stagedFile
    is RemotePayload.Pdf -> stagedFile
    is RemotePayload.AppPackage -> stagedFile
    is RemotePayload.Text -> null
  }

  private fun contentLabel(kind: ContentKind) = when (kind) {
    ContentKind.IMAGE -> noteText("图片", "image")
    ContentKind.TEXT -> noteText("文字", "text")
    ContentKind.PDF -> "PDF"
    ContentKind.APP_PACKAGE -> noteText("应用安装包", "app package")
  }

  companion object {
    const val PHONE_DEVICE_ID = "betterhv-phone"
  }
}
