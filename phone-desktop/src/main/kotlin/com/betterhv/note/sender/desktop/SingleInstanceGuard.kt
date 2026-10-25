package com.betterhv.note.sender.desktop

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Keeps one NoteLink process per Windows user.
 *
 * The running instance holds a file lock and listens on a loopback port recorded in `instance.json`.
 * A second launch fails to take the lock, sends [SHOW_COMMAND] to the first instance and exits, so no
 * second receiver service or tray icon is created.
 */
class SingleInstanceGuard(private val root: File) : AutoCloseable {
  private val lockFile = File(root, "notelink.lock")
  private val portFile = File(root, "instance.json")
  private var channel: FileChannel? = null
  private var lock: FileLock? = null
  private var server: ServerSocket? = null
  private var listener: Thread? = null
  private val running = AtomicBoolean(false)

  /** Returns true when this process owns the instance lock. */
  fun tryAcquire(): Boolean {
    val opened = runCatching {
      FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    }.getOrNull() ?: return false
    val acquired = runCatching { opened.tryLock() }.getOrNull()
    if (acquired == null) {
      runCatching { opened.close() }
      return false
    }
    channel = opened
    lock = acquired
    return true
  }

  /** Starts the loopback listener that asks the UI to come to the front. */
  fun startListening(onShow: () -> Unit) {
    if (!running.compareAndSet(false, true)) return
    val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    server = socket
    portFile.parentFile?.mkdirs()
    portFile.writeText("port=${socket.localPort}\n")
    listener = thread(isDaemon = true, name = "notelink-single-instance") {
      while (running.get()) {
        val client = runCatching { socket.accept() }.getOrNull() ?: break
        client.use {
          val command = runCatching { it.getInputStream().bufferedReader().readLine() }.getOrNull()
          if (command == SHOW_COMMAND) onShow()
        }
      }
    }
  }

  /** Asks the already running instance to show its window; false when it cannot be reached. */
  fun signalExisting(): Boolean {
    val port = readPort() ?: return false
    return runCatching {
      Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
        socket.getOutputStream().bufferedWriter().use { writer ->
          writer.write(SHOW_COMMAND)
          writer.newLine()
          writer.flush()
        }
      }
      true
    }.getOrDefault(false)
  }

  fun isOwner(): Boolean = lock?.isValid == true

  override fun close() {
    running.set(false)
    runCatching { server?.close() }
    runCatching { listener?.join(CLOSE_JOIN_MILLIS) }
    runCatching { lock?.release() }
    runCatching { channel?.close() }
    runCatching { portFile.delete() }
    server = null
    listener = null
    lock = null
    channel = null
  }

  private fun readPort(): Int? = runCatching {
    portFile.readLines()
      .firstOrNull { it.startsWith(PORT_PREFIX) }
      ?.removePrefix(PORT_PREFIX)
      ?.trim()
      ?.toIntOrNull()
  }.getOrNull()

  companion object {
    const val SHOW_COMMAND = "SHOW"
    private const val PORT_PREFIX = "port="
    private const val CLOSE_JOIN_MILLIS = 500L
  }
}
