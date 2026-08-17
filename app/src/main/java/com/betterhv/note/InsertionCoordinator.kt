package com.betterhv.note

import android.net.Uri
import com.betterhv.note.storage.ImportedImage
import com.betterhv.transfer.android.ReceivedLease
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.RemotePayload
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

sealed interface InsertionState {
    data object Idle : InsertionState
    data class ChoosingSource(val kind: ContentKind) : InsertionState
    data class WaitingForPhone(val kind: ContentKind) : InsertionState
    data class ImageReady(val image: ImportedImage, val lease: ReceivedLease?) : InsertionState
    data class TextReady(val initialText: String, val lease: ReceivedLease?) : InsertionState
    data class Error(val message: String) : InsertionState
}

/** Owns insertion resources so Compose recreation cannot leak a staged file or remote lease. */
class InsertionCoordinator(private val phone: PhoneTransferClient) : AutoCloseable {
    private val mutableState = MutableStateFlow<InsertionState>(InsertionState.Idle)
    val state: StateFlow<InsertionState> = mutableState
    private var penView: PenDrawView? = null
    private var requestGeneration = 0L

    fun attach(view: PenDrawView) { penView = view }
    fun choose(kind: ContentKind) { cancel(); mutableState.value = InsertionState.ChoosingSource(kind) }
    fun manualText() { cancel(); mutableState.value = InsertionState.TextReady("", null) }

    /** Skips the source sheet only when the paired phone advertises matching content. */
    suspend fun remoteIfAvailableOrChoose(kind: ContentKind) {
        cancel()
        val generation = requestGeneration
        mutableState.value = InsertionState.WaitingForPhone(kind)
        val available = phone.hasAvailable(kind)
        if (generation != requestGeneration) return
        if (available) remote(kind) else mutableState.value = InsertionState.ChoosingSource(kind)
    }

    suspend fun localImage(uri: Uri) {
        cancel()
        val generation = requestGeneration
        val view = requireNotNull(penView)
        runCatching { withContext(Dispatchers.IO) { view.importImage(uri) } }
            .onSuccess {
                if (generation == requestGeneration) mutableState.value = InsertionState.ImageReady(it, null)
                else view.discardImportedImage(it)
            }
            .onFailure {
                if (generation == requestGeneration) mutableState.value = InsertionState.Error(it.message ?: "图片导入失败")
            }
    }

    suspend fun remote(kind: ContentKind) {
        cancel()
        val generation = requestGeneration
        mutableState.value = InsertionState.WaitingForPhone(kind)
        runCatching { phone.requestNext(kind) }
            .onSuccess { lease ->
                if (generation != requestGeneration) {
                    (lease?.payload as? RemotePayload.Image)?.stagedFile?.delete()
                    lease?.release()
                    return@onSuccess
                }
                if (lease == null) {
                    mutableState.value = InsertionState.Error("手机队列中没有${if (kind == ContentKind.IMAGE) "图片" else "文字"}")
                    return@onSuccess
                }
                when (val payload = lease.payload) {
                    is RemotePayload.Image -> {
                        val staged = withContext(Dispatchers.IO) {
                            requireNotNull(penView).stageRemoteImage(payload.stagedFile, payload.item.mimeType)
                                .also { payload.stagedFile.delete() }
                        }
                        if (generation == requestGeneration) mutableState.value = InsertionState.ImageReady(staged, lease)
                        else {
                            penView?.discardImportedImage(staged)
                            lease.release()
                        }
                    }
                    is RemotePayload.Text -> mutableState.value = InsertionState.TextReady(payload.text, lease)
                }
            }
            .onFailure {
                if (generation == requestGeneration) mutableState.value = InsertionState.Error(it.message ?: "手机传输失败")
            }
    }

    fun placeImage(x: Float, y: Float): Result<Unit> {
        val ready = mutableState.value as? InsertionState.ImageReady
            ?: return Result.failure(IllegalStateException("没有待插入图片"))
        val view = requireNotNull(penView)
        val result = if (ready.lease == null) {
            runCatching { view.placeImage(ready.image, x, y) }
        } else {
            val item = ready.lease.offer.item
            view.placeTransferredImage(ready.image, x, y, PHONE_DEVICE_ID, item.id)
                .onSuccess { ready.lease.commit() }
        }
        if (result.isSuccess) mutableState.value = InsertionState.Idle
        return result.map { }
    }

    fun placeText(text: String, x: Float, y: Float): Result<Unit> {
        val ready = mutableState.value as? InsertionState.TextReady
            ?: return Result.failure(IllegalStateException("没有待插入文字"))
        val view = requireNotNull(penView)
        val result = if (ready.lease == null) {
            runCatching { view.placeText(text, x, y) }
        } else {
            view.placeTransferredText(text, x, y, PHONE_DEVICE_ID, ready.lease.offer.item.id)
                .onSuccess { ready.lease.commit() }
        }
        if (result.isSuccess) mutableState.value = InsertionState.Idle
        return result.map { }
    }

    fun dismissError() { if (mutableState.value is InsertionState.Error) mutableState.value = InsertionState.Idle }

    fun cancel() {
        requestGeneration++
        when (val current = mutableState.value) {
            is InsertionState.ImageReady -> {
                penView?.discardImportedImage(current.image)
                current.lease?.release()
            }
            is InsertionState.TextReady -> current.lease?.release()
            else -> Unit
        }
        mutableState.value = InsertionState.Idle
    }

    override fun close() { cancel(); phone.close() }

    companion object { const val PHONE_DEVICE_ID = "betterhv-phone" }
}
