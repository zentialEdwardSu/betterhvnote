package com.betterhv.note.sender

import android.util.Log
import com.betterhv.transfer.core.TransferEvent
import com.betterhv.transfer.core.TransferEventLog
import com.betterhv.transfer.core.TransferLogEntry
import com.betterhv.transfer.core.TransferLogLevel
import com.betterhv.transfer.core.TransferObservable
import com.betterhv.transfer.core.TransferSnapshot
import com.betterhv.update.NoteAppUpdateTaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Process-wide state source shared by the foreground notification and Compose UI. */
object NoteLinkTransferRuntime : TransferObservable {
  private val mutableSnapshot = MutableStateFlow(TransferSnapshot())
  private val mutableEvents = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 64)
  private val eventLog = TransferEventLog()
  override val snapshot: StateFlow<TransferSnapshot> = mutableSnapshot.asStateFlow()
  override val events: SharedFlow<TransferEvent> = mutableEvents.asSharedFlow()
  val eventHistory: StateFlow<List<TransferLogEntry>> = eventLog.entries
  private val mutableNoteUpdateState = MutableStateFlow<NoteAppUpdateTaskState?>(null)
  val noteUpdateState: StateFlow<NoteAppUpdateTaskState?> = mutableNoteUpdateState.asStateFlow()
  private var source: TransferObservable? = null
  private var snapshotJob: Job? = null
  private var eventJob: Job? = null
  private var noteUpdateJob: Job? = null

  @Synchronized fun attach(scope: CoroutineScope, observable: TransferObservable) {
    snapshotJob?.cancel()
    eventJob?.cancel()
    noteUpdateJob?.cancel()
    source = observable
    snapshotJob = scope.launch { observable.snapshot.collect(mutableSnapshot) }
    eventJob = scope.launch {
      observable.events.collect { event ->
        val entry = eventLog.record(event)
        when (entry.level) {
          TransferLogLevel.INFO -> Log.i(TAG, "${entry.category} ${entry.detail}")
          TransferLogLevel.WARNING -> Log.w(TAG, "${entry.category} ${entry.detail}")
          TransferLogLevel.ERROR -> Log.e(TAG, "${entry.category} ${entry.detail}")
        }
        mutableEvents.emit(event)
      }
    }
    noteUpdateJob = (observable as? PhoneCommandProcessor)?.let { processor ->
      scope.launch { processor.noteUpdateState.collect(mutableNoteUpdateState) }
    }
  }

  @Synchronized fun detach(observable: TransferObservable) {
    if (source !== observable) return
    snapshotJob?.cancel()
    eventJob?.cancel()
    noteUpdateJob?.cancel()
    snapshotJob = null
    eventJob = null
    noteUpdateJob = null
    source = null
    mutableSnapshot.value = TransferSnapshot()
    mutableNoteUpdateState.value = null
  }

  override fun cancel() {
    source?.cancel()
  }

  private const val TAG = "NoteLinkTransfer"
}
