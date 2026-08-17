package com.betterhv.note.storage

/** WAL replays committed transactions on open; this validates and checkpoints the recovered DB. */
class RecoveryManager(private val store: SQLiteStore) {
    data class Result(val healthy: Boolean, val checkpointed: Boolean)

    fun recover(): Result {
        val healthy = store.quickCheck()
        if (!healthy) return Result(healthy = false, checkpointed = false)
        store.checkpoint()
        return Result(healthy = true, checkpointed = true)
    }
}
