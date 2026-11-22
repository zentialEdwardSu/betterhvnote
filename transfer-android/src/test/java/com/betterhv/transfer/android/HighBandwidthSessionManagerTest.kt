package com.betterhv.transfer.android

import com.betterhv.transfer.core.NOTELINK_LAN_PORT
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighBandwidthSessionManagerTest {
  @Test
  fun startWithNewPairingKeyImmediatelyResetsReadyStateToPreparing() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val firstSession = WifiDirectSession(
      groupOwnerAddress = "192.168.49.1",
      localDeviceAddress = "AA:BB:CC:DD:EE:01",
      ownerDeviceName = "Phone",
      isGroupOwner = true,
      networkName = "DIRECT-AA",
      passphrase = "pass-1",
    )
    val secondReady = CompletableDeferred<WifiDirectSession>()
    val driver = FakeHostedGroupDriver(
      onEnsure = { key ->
        if (key?.contentEquals(byteArrayOf(1)) == true) {
          firstSession
        } else {
          secondReady.await()
        }
      },
    )
    val manager = HighBandwidthSessionManager(scope, driver)
    try {
      manager.start(byteArrayOf(1))
      val endpoint1 = manager.awaitReady(2_000L)
      assertEquals("DIRECT-AA", endpoint1?.networkName)
      assertTrue(manager.state.value is HostedGroupState.Ready)

      val closeCallsBeforeSwitch = driver.closeGroupCalls
      manager.start(byteArrayOf(2))

      // State must synchronously reset to Preparing(1) before returning, never exposing stale Ready.
      assertEquals(HostedGroupState.Preparing(1), manager.state.value)
      assertEquals(null, manager.endpoint())

      val deferredEndpoint2 = async { manager.awaitReady(2_000L) }
      val secondSession = WifiDirectSession(
        groupOwnerAddress = "192.168.49.1",
        localDeviceAddress = "AA:BB:CC:DD:EE:01",
        ownerDeviceName = "Phone",
        isGroupOwner = true,
        networkName = "DIRECT-BB",
        passphrase = "pass-2",
      )
      driver.emitSession(secondSession)
      secondReady.complete(secondSession)

      val endpoint2 = deferredEndpoint2.await()
      assertEquals("DIRECT-BB", endpoint2?.networkName)
      assertEquals(NOTELINK_LAN_PORT, endpoint2?.port)
      assertEquals(closeCallsBeforeSwitch + 1, driver.closeGroupCalls)
    } finally {
      manager.close()
      scope.cancel()
    }
  }

  @Test
  fun startWithSamePairingKeyWhileActiveKeepsExistingReadyState() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = WifiDirectSession(
      groupOwnerAddress = "192.168.49.1",
      localDeviceAddress = "AA:BB:CC:DD:EE:01",
      ownerDeviceName = "Phone",
      isGroupOwner = true,
      networkName = "DIRECT-AA",
      passphrase = "pass-1",
    )
    val driver = FakeHostedGroupDriver(onEnsure = { session })
    val manager = HighBandwidthSessionManager(scope, driver)
    try {
      val key = byteArrayOf(7, 8, 9)
      manager.start(key)
      val endpoint = manager.awaitReady(2_000L)
      assertEquals("DIRECT-AA", endpoint?.networkName)

      val closeCallsBeforeRepeat = driver.closeGroupCalls
      manager.start(key.copyOf())

      assertTrue(manager.state.value is HostedGroupState.Ready)
      assertEquals(closeCallsBeforeRepeat, driver.closeGroupCalls)
    } finally {
      manager.close()
      scope.cancel()
    }
  }

  @Test
  fun rebuildImmediatelyResetsReadyStateToPreparing() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val gate = CompletableDeferred<WifiDirectSession>()
    var calls = 0
    val firstSession = WifiDirectSession(
      groupOwnerAddress = "192.168.49.1",
      localDeviceAddress = "AA:BB:CC:DD:EE:01",
      ownerDeviceName = "Phone",
      isGroupOwner = true,
      networkName = "DIRECT-AA",
      passphrase = "pass-1",
    )
    val driver = FakeHostedGroupDriver(
      onEnsure = {
        calls++
        if (calls == 1) firstSession else gate.await()
      },
    )
    val manager = HighBandwidthSessionManager(scope, driver)
    try {
      manager.start(byteArrayOf(1))
      assertEquals("DIRECT-AA", manager.awaitReady(2_000L)?.networkName)

      manager.rebuild()

      assertEquals(HostedGroupState.Preparing(1), manager.state.value)
      gate.cancel()
    } finally {
      manager.close()
      scope.cancel()
    }
  }

  private class FakeHostedGroupDriver(
    private val onEnsure: suspend (ByteArray?) -> WifiDirectSession,
  ) : HostedGroupDriver {
    override var pairingKey: ByteArray? = null
    private val mutableSession = MutableStateFlow<WifiDirectSession?>(null)
    override val session: StateFlow<WifiDirectSession?> = mutableSession.asStateFlow()
    var closeGroupCalls = 0

    fun emitSession(value: WifiDirectSession?) {
      mutableSession.value = value
    }

    override suspend fun ensureHostedGroup(): WifiDirectSession {
      val created = onEnsure(pairingKey)
      mutableSession.value = created
      return created
    }

    override fun requestCloseGroup() {
      closeGroupCalls++
      mutableSession.value = null
    }

    override fun close() {
      mutableSession.value = null
    }
  }
}
