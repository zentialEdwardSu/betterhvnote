package com.betterhv.note.sender

import com.betterhv.transfer.core.v4.pairing.PairingResponder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the pairing dialog shows while the user's code waits for the Note to connect. */
sealed interface PairingUiState {
  data object Idle : PairingUiState
  data class Waiting(val code: String) : PairingUiState
  data class Paired(val deviceName: String) : PairingUiState
  data class Failed(val message: String) : PairingUiState
}

/**
 * Process-wide pairing state shared by the UI (code entry) and the foreground service (BLE).
 * The service advertises the pairing flag while a responder is active.
 */
object PhonePairingMode {
  private val mutableResponder = MutableStateFlow<PairingResponder?>(null)
  private val mutableUi = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
  val responder: StateFlow<PairingResponder?> = mutableResponder.asStateFlow()
  val ui: StateFlow<PairingUiState> = mutableUi.asStateFlow()

  fun begin(code: String, localDeviceId: String, localDeviceName: String) {
    mutableResponder.value = PairingResponder(code, localDeviceId, localDeviceName)
    mutableUi.value = PairingUiState.Waiting(code)
  }

  /** The active responder, or null when it expired or ran out of attempts. */
  fun active(): PairingResponder? {
    val current = mutableResponder.value ?: return null
    if (current.isActive()) return current
    mutableResponder.compareAndSet(current, null)
    if (mutableUi.value is PairingUiState.Waiting) {
      mutableUi.value = PairingUiState.Failed("The pairing code expired; enter it again")
    }
    return null
  }

  fun paired(deviceName: String) {
    mutableResponder.value = null
    mutableUi.value = PairingUiState.Paired(deviceName)
  }

  fun failedAttempt(attemptsLeft: Int) {
    if (attemptsLeft <= 0) {
      mutableResponder.value = null
      mutableUi.value = PairingUiState.Failed("Too many wrong codes; enter the code shown on Note again")
    } else {
      mutableUi.value = PairingUiState.Failed("The code did not match the Note ($attemptsLeft attempts left)")
    }
  }

  fun cancel() {
    mutableResponder.value = null
    mutableUi.value = PairingUiState.Idle
  }
}
