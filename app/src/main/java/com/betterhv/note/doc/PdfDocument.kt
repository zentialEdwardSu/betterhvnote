package com.betterhv.note.doc

import com.betterhv.note.ink.Bounds
import java.util.UUID

enum class NotebookKind { STANDARD, PDF }

enum class PageKind { BLANK, PDF_SOURCE, LINKED_NOTE }

enum class PdfAnchorKind { REGION_IMAGE, REGION_TEXT }

/** MuPDF's authoritative page geometry, retained independently of screen size. */
data class PdfPageSource(
    val sourcePageIndex: Int,
    val bounds: Bounds,
    val pdfToPage: Transform2D
)

data class PdfDocumentRecord(
    val notebookId: UUID,
    val assetPath: String,
    val displayName: String,
    val mimeType: String,
    val byteLength: Long,
    val sha256: String,
    val pageCount: Int,
    val createdAt: Long
)

data class PdfAnchor(
    val id: UUID = UUID.randomUUID(),
    val sourcePageId: UUID,
    val notePageId: UUID,
    val noteObjectId: UUID,
    val kind: PdfAnchorKind,
    val normalizedBounds: Bounds,
    val selectedText: String? = null,
    val ordinal: Int,
    val createdAt: Long = System.currentTimeMillis()
)
