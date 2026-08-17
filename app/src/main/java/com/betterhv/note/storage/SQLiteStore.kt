package com.betterhv.note.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** Android SQLite boundary. Core document classes never import SQLite APIs. */
class SQLiteStore(context: Context, file: File) :
    SQLiteOpenHelper(context, file.absolutePath, null, MigrationManager.VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA synchronous=NORMAL")
    }

    override fun onCreate(db: SQLiteDatabase) = MigrationManager.create(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        MigrationManager.migrate(db, oldVersion, newVersion)

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Notebook schema downgrade $oldVersion -> $newVersion is not supported")
    }

    fun quickCheck(): Boolean = readableDatabase.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
        cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
    }

    fun checkpoint() {
        writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
    }
}
