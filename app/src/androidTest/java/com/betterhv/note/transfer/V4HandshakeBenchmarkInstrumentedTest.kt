package com.betterhv.note.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import com.betterhv.transfer.core.v4.bench.HandshakeBenchmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the cold/warm handshake benchmark on the real tablet CPU and writes the JSON artifact.
 * The network path is simulated, so this complements (does not replace) the BLE/LAN device runs.
 */
@RunWith(AndroidJUnit4::class)
class V4HandshakeBenchmarkInstrumentedTest {
  @Test
  fun coldAndWarmHandshakeStayInsideTheBudget() {
    val cold = HandshakeBenchmark.run("device-cold", iterations = 30, rttMillis = 0)
    val warm = HandshakeBenchmark.run("device-warm", iterations = 30, rttMillis = 0)
    assertEquals(1, cold.roundTripsPerHandshake)
    assertEquals(1, warm.roundTripsPerHandshake)
    assertTrue("cold p95 ${cold.p95Millis}", cold.p95Millis <= 1_000)
    assertTrue("warm p95 ${warm.p95Millis}", warm.p95Millis <= 400)

    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val reports = File(context.getExternalFilesDir(null), "reports").apply { mkdirs() }
    File(reports, "v4-handshake-device.json").writeText(cold.toJson() + "\n" + warm.toJson())
    Log.i("V4Bench", cold.toJson())
    Log.i("V4Bench", warm.toJson())
  }
}
