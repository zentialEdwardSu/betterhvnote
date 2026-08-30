package com.betterhv.note.storage

import com.betterhv.note.doc.PdfPageSource

data class PdfImportedPage(
    val width: Float,
    val height: Float,
    val source: PdfPageSource
)

data class PdfImportCommit(
    val title: String,
    val assetPath: String,
    val displayName: String,
    val mimeType: String = "application/pdf",
    val byteLength: Long,
    val sha256: String,
    val pages: List<PdfImportedPage>
)
