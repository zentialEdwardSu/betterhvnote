package com.betterhv.note.storage

import android.database.sqlite.SQLiteDatabase

/** Explicit schema migrations; every semantic schema change increments VERSION. */
object MigrationManager {
    const val VERSION = 7

    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
            """CREATE TABLE notebooks(
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT 'STANDARD',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE pages(
                id TEXT PRIMARY KEY,
                notebook_id TEXT NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
                width REAL NOT NULL,
                height REAL NOT NULL,
                bookmarked INTEGER NOT NULL DEFAULT 0,
                content_revision INTEGER NOT NULL DEFAULT 0,
                kind TEXT NOT NULL DEFAULT 'BLANK',
                parent_pdf_page_id TEXT REFERENCES pages(id) ON DELETE CASCADE,
                pdf_page_index INTEGER,
                pdf_bound_left REAL,
                pdf_bound_top REAL,
                pdf_bound_right REAL,
                pdf_bound_bottom REAL,
                pdf_a REAL,
                pdf_b REAL,
                pdf_c REAL,
                pdf_d REAL,
                pdf_tx REAL,
                pdf_ty REAL,
                template_id TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE page_order(
                notebook_id TEXT NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
                page_id TEXT NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                PRIMARY KEY(notebook_id, page_id),
                UNIQUE(notebook_id, position)
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE objects(
                id TEXT PRIMARY KEY,
                page_id TEXT NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
                type TEXT NOT NULL,
                a REAL NOT NULL, b REAL NOT NULL, c REAL NOT NULL, d REAL NOT NULL,
                tx REAL NOT NULL, ty REAL NOT NULL,
                left_bound REAL NOT NULL, top_bound REAL NOT NULL,
                right_bound REAL NOT NULL, bottom_bound REAL NOT NULL,
                z_index INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX objects_page_z ON objects(page_id, z_index)")
        db.execSQL(
            """CREATE TABLE strokes(
                object_id TEXT PRIMARY KEY REFERENCES objects(id) ON DELETE CASCADE,
                base_width REAL NOT NULL,
                color INTEGER NOT NULL,
                pressure_a REAL NOT NULL,
                pressure_gamma REAL NOT NULL,
                pen_type TEXT NOT NULL,
                points_blob BLOB NOT NULL
            )""".trimIndent()
        )
        createRichObjectTables(db)
        createPdfTables(db)
        createTransferReceiptTable(db)
        createExportTables(db)
        db.execSQL(
            """CREATE TABLE operation_journal(
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                notebook_id TEXT NOT NULL,
                committed_at INTEGER NOT NULL,
                description TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL("INSERT INTO metadata(key,value) VALUES('schema_version','$VERSION')")
    }

    fun migrate(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(newVersion == VERSION) { "App schema $VERSION cannot migrate to $newVersion" }
        var version = oldVersion
        if (version == 0) {
            create(db)
            version = VERSION
        }
        if (version == 1) {
            db.execSQL("ALTER TABLE pages ADD COLUMN bookmarked INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE pages ADD COLUMN content_revision INTEGER NOT NULL DEFAULT 0")
            version = 2
        }
        if (version == 2) {
            createRichObjectTables(db)
            createTransferReceiptTable(db)
            version = 4
        }
        if (version == 3) {
            db.execSQL("ALTER TABLE images ADD COLUMN exif_orientation INTEGER NOT NULL DEFAULT 1")
            createTransferReceiptTable(db)
            version = 4
        }
        if (version == 4) {
            createExportTables(db)
            version = 5
        }
        if (version == 5) {
            db.execSQL("ALTER TABLE notebooks ADD COLUMN kind TEXT NOT NULL DEFAULT 'STANDARD'")
            db.execSQL("ALTER TABLE pages ADD COLUMN kind TEXT NOT NULL DEFAULT 'BLANK'")
            db.execSQL("ALTER TABLE pages ADD COLUMN parent_pdf_page_id TEXT REFERENCES pages(id) ON DELETE CASCADE")
            db.execSQL("ALTER TABLE pages ADD COLUMN pdf_page_index INTEGER")
            db.execSQL("ALTER TABLE pages ADD COLUMN pdf_bound_left REAL")
            db.execSQL("ALTER TABLE pages ADD COLUMN pdf_bound_top REAL")
            db.execSQL("ALTER TABLE pages ADD COLUMN pdf_bound_right REAL")
            db.execSQL("ALTER TABLE pages ADD COLUMN pdf_bound_bottom REAL")
            db.execSQL("ALTER TABLE pages ADD COLUMN pdf_a REAL")
            db.execSQL("ALTER TABLE pages ADD COLUMN pdf_b REAL")
            db.execSQL("ALTER TABLE pages ADD COLUMN pdf_c REAL")
            db.execSQL("ALTER TABLE pages ADD COLUMN pdf_d REAL")
            db.execSQL("ALTER TABLE pages ADD COLUMN pdf_tx REAL")
            db.execSQL("ALTER TABLE pages ADD COLUMN pdf_ty REAL")
            createPdfTables(db)
            version = 6
        }
        if (version == 6) {
            db.execSQL("ALTER TABLE pages ADD COLUMN template_id TEXT")
            db.execSQL("UPDATE pages SET template_id='builtin.blank' WHERE kind!='PDF_SOURCE'")
            version = 7
        }
        require(version == newVersion) { "Incomplete migration $oldVersion -> $newVersion (at $version)" }
        // Future migrations are appended here, never by mutating v1 semantics.
        db.execSQL(
            "INSERT OR REPLACE INTO metadata(key,value) VALUES('schema_version',?)",
            arrayOf(newVersion.toString())
        )
    }

    private fun createRichObjectTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE images(
                object_id TEXT PRIMARY KEY REFERENCES objects(id) ON DELETE CASCADE,
                asset_path TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                pixel_width INTEGER NOT NULL,
                pixel_height INTEGER NOT NULL,
                exif_orientation INTEGER NOT NULL DEFAULT 1
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE texts(
                object_id TEXT PRIMARY KEY REFERENCES objects(id) ON DELETE CASCADE,
                content TEXT NOT NULL,
                font_family TEXT NOT NULL,
                font_size REAL NOT NULL
            )""".trimIndent()
        )
    }

    private fun createTransferReceiptTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE transfer_receipts(
                source_device_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                object_id TEXT NOT NULL,
                committed_at INTEGER NOT NULL,
                PRIMARY KEY(source_device_id, item_id)
            )""".trimIndent()
        )
    }

    private fun createPdfTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS pdf_documents(
                notebook_id TEXT PRIMARY KEY REFERENCES notebooks(id) ON DELETE CASCADE,
                asset_path TEXT NOT NULL UNIQUE,
                display_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                byte_length INTEGER NOT NULL,
                sha256 TEXT NOT NULL,
                page_count INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS pdf_anchors(
                id TEXT PRIMARY KEY,
                source_page_id TEXT NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
                note_page_id TEXT NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
                note_object_id TEXT NOT NULL UNIQUE REFERENCES objects(id) ON DELETE CASCADE,
                kind TEXT NOT NULL,
                normalized_left REAL NOT NULL,
                normalized_top REAL NOT NULL,
                normalized_right REAL NOT NULL,
                normalized_bottom REAL NOT NULL,
                selected_text TEXT,
                ordinal INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE(source_page_id, ordinal)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS pdf_anchors_note_page ON pdf_anchors(note_page_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS pages_parent_pdf ON pages(parent_pdf_page_id)")
    }

    private fun createExportTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE export_tasks(
                id TEXT PRIMARY KEY,
                notebook_id TEXT NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
                scope TEXT NOT NULL,
                format TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                last_used_at INTEGER NOT NULL,
                artifact_id TEXT,
                artifact_path TEXT,
                artifact_fingerprint TEXT,
                artifact_size INTEGER,
                artifact_sha256 BLOB,
                artifact_created_at INTEGER,
                last_error TEXT
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE export_task_pages(
                task_id TEXT NOT NULL REFERENCES export_tasks(id) ON DELETE CASCADE,
                page_id TEXT NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY(task_id, page_id),
                UNIQUE(task_id, position)
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE export_page_cache(
                task_id TEXT NOT NULL REFERENCES export_tasks(id) ON DELETE CASCADE,
                page_id TEXT NOT NULL,
                content_revision INTEGER NOT NULL,
                cache_path TEXT NOT NULL,
                PRIMARY KEY(task_id, page_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX export_tasks_recent ON export_tasks(last_used_at DESC)")
    }
}
