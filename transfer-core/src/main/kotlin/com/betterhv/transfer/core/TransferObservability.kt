package com.betterhv.transfer.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class TransferPhase {
  IDLE,
  DISCOVERING,
  BLE_CONNECTING,
  AUTHENTICATING,
  NEGOTIATING_CAPABILITIES,
  CHECKING_NETWORK,
  PROBING_LAN,
  JOINING_WIFI_DIRECT,
  WAITING_FOR_PEER,
  TRANSFERRING,
  VERIFYING,
  AWAITING_COMMIT,
  COMPLETE,
  FALLING_BACK,
  FAILED,
}

val TransferPhase.isActiveTransferPhase: Boolean
  get() = this != TransferPhase.IDLE && this != TransferPhase.COMPLETE && this != TransferPhase.FAILED

data class TransferSnapshot(
  val operationId: UUID? = null,
  val itemId: UUID? = null,
  val deviceId: String? = null,
  val phase: TransferPhase = TransferPhase.IDLE,
  val mode: TransferMode? = null,
  val localModes: Int = TransferModes.NONE,
  val remoteModes: Int = TransferModes.NONE,
  val ssidMatch: SsidMatch = SsidMatch.UNKNOWN,
  val endpoint: NetworkEndpoint? = null,
  val bytesTransferred: Long = 0,
  val totalBytes: Long = 0,
  val bytesPerSecond: Long = 0,
  val averageBytesPerSecond: Long = 0,
  val etaMillis: Long? = null,
  val attempt: Int = 0,
  val fallbackCount: Int = 0,
  val fallbackReason: TransferFailure? = null,
  val startedAtMillis: Long? = null,
  val phaseStartedAtMillis: Long? = null,
  val lastFailure: TransferFailure? = null,
  val canRetry: Boolean = false,
  val canCancel: Boolean = false,
  val wifiDirectGroupReady: Boolean = false,
)

sealed interface TransferEvent {
  data class PhaseChanged(val operationId: UUID?, val from: TransferPhase, val to: TransferPhase) : TransferEvent
  data class CapabilityNegotiated(val localModes: Int, val remoteModes: Int, val ssidMatch: SsidMatch) : TransferEvent
  data class TransportSelected(val operationId: UUID, val mode: TransferMode, val endpoint: NetworkEndpoint) :
    TransferEvent
  data class Fallback(
    val operationId: UUID,
    val from: TransferMode,
    val to: TransferMode,
    val reason: TransferFailure,
  ) : TransferEvent
  data class Progress(
    val operationId: UUID,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val etaMillis: Long?,
  ) : TransferEvent
  data class Completed(val operationId: UUID, val itemId: UUID) : TransferEvent
  data class Failed(val operationId: UUID?, val failure: TransferFailure) : TransferEvent
}

enum class TransferLogLevel { INFO, WARNING, ERROR }

data class TransferLogEntry(
  val timestampMillis: Long,
  val level: TransferLogLevel,
  val category: String,
  val detail: String,
  val operationId: UUID? = null,
)

/** A bounded replayable view of [TransferObservable.events] for diagnostics and UI timelines. */
class TransferEventLog(private val capacity: Int = 80, private val clock: () -> Long = System::currentTimeMillis) {
  init {
    require(capacity > 0)
  }

  private val mutableEntries = MutableStateFlow<List<TransferLogEntry>>(emptyList())
  val entries: StateFlow<List<TransferLogEntry>> = mutableEntries.asStateFlow()

  @Synchronized
  fun record(event: TransferEvent): TransferLogEntry {
    val entry = event.toLogEntry(clock())
    mutableEntries.value = (mutableEntries.value + entry).takeLast(capacity)
    return entry
  }

  @Synchronized
  fun clear() {
    mutableEntries.value = emptyList()
  }
}

fun TransferEvent.toLogEntry(timestampMillis: Long = System.currentTimeMillis()): TransferLogEntry = when (this) {
  is TransferEvent.PhaseChanged -> TransferLogEntry(
    timestampMillis,
    TransferLogLevel.INFO,
    "phase",
    "from=$from to=$to",
    operationId,
  )

  is TransferEvent.CapabilityNegotiated -> TransferLogEntry(
    timestampMillis,
    TransferLogLevel.INFO,
    "capabilities",
    "localModes=${TransferModes.describe(
      localModes,
    )} remoteModes=${TransferModes.describe(remoteModes)} ssid=$ssidMatch",
  )

  is TransferEvent.TransportSelected -> TransferLogEntry(
    timestampMillis,
    TransferLogLevel.INFO,
    "transport",
    "mode=$mode endpoint=${endpoint.host}:${endpoint.port}",
    operationId,
  )

  is TransferEvent.Fallback -> TransferLogEntry(
    timestampMillis,
    TransferLogLevel.WARNING,
    "fallback",
    "from=$from to=$to code=${reason.code} recoverable=${reason.recoverable}",
    operationId,
  )

  is TransferEvent.Progress -> TransferLogEntry(
    timestampMillis,
    TransferLogLevel.INFO,
    "progress",
    "bytes=$bytesTransferred total=$totalBytes speedBps=$bytesPerSecond etaMs=${etaMillis ?: -1}",
    operationId,
  )

  is TransferEvent.Completed -> TransferLogEntry(
    timestampMillis,
    TransferLogLevel.INFO,
    "completed",
    "itemId=$itemId",
    operationId,
  )

  is TransferEvent.Failed -> TransferLogEntry(
    timestampMillis,
    TransferLogLevel.ERROR,
    "failed",
    "code=${failure.code} recoverable=${failure.recoverable} mode=${failure.causeMode ?: "none"} message=${failure.message}",
    operationId,
  )
}

interface TransferObservable {
  val snapshot: StateFlow<TransferSnapshot>
  val events: SharedFlow<TransferEvent>
  fun cancel()
}

class TransferProgressMeter(
  private val startedAtMillis: Long,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private var previousAt = startedAtMillis
  private var previousBytes = 0L

  fun sample(bytes: Long, total: Long): ProgressSample {
    val now = clock()
    val interval = (now - previousAt).coerceAtLeast(1)
    val elapsed = (now - startedAtMillis).coerceAtLeast(1)
    val current = ((bytes - previousBytes).coerceAtLeast(0) * 1_000L) / interval
    val average = (bytes.coerceAtLeast(0) * 1_000L) / elapsed
    val eta = average.takeIf { it > 0 && total >= bytes }?.let { (total - bytes) * 1_000L / it }
    previousAt = now
    previousBytes = bytes
    return ProgressSample(current, average, eta)
  }
}

data class ProgressSample(val bytesPerSecond: Long, val averageBytesPerSecond: Long, val etaMillis: Long?)
