package com.betterhv.note.sender.shared.db

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.betterhv.note.sender.shared.db.inbox.ExportInboxDatabase
import com.betterhv.note.sender.shared.db.queue.SenderQueueDatabase

/** Opens the original Android database filenames with the shared SQLDelight schemas. */
class AndroidNoteLinkDatabases(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val queueDriver = AndroidSqliteDriver(SenderQueueDatabase.Schema, appContext, "sender_queue.db")
    private val inboxDriver = AndroidSqliteDriver(ExportInboxDatabase.Schema, appContext, "export_inbox.db")
    val queue = SenderQueueDatabase(queueDriver)
    val inbox = ExportInboxDatabase(inboxDriver)

    override fun close() {
        inboxDriver.close()
        queueDriver.close()
    }
}
