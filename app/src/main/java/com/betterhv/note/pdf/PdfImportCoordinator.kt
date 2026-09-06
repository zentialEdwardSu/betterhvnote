package com.betterhv.note.pdf

import android.content.Context
import android.net.Uri
import com.betterhv.note.storage.NotebookRepository
import com.betterhv.note.storage.PdfImportCommit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class PdfImportCoordinator(
  context: Context,
  private val repository: NotebookRepository,
  private val assets: PdfAssetStore = PdfAssetStore(context.applicationContext),
  private val engine: MuPdfEngine = MuPdfEngine(),
) {
  suspend fun importLocal(uri: Uri): UUID = withContext(Dispatchers.IO) {
    importLocalBlocking(uri)
  }

  fun importLocalBlocking(uri: Uri): UUID {
    val staged = assets.stage(uri)
    return importStaged(staged)
  }

  fun importFileBlocking(file: File, displayName: String): UUID {
    val staged = assets.stageFile(file, displayName)
    return importStaged(staged)
  }

  private fun importStaged(staged: StagedPdf): UUID {
    var durable: StagedPdf? = null
    return try {
      val stagedFile = assets.resolveStaged(staged.relativePath) ?: error("Staged PDF is missing")
      val inspection = engine.inspect(stagedFile)
      durable = assets.commit(staged)
      repository.createPdfNotebook(
        PdfImportCommit(
          // The imported file name is the notebook's visible identity.
          title = durable.displayName.ifBlank { "Imported.pdf" },
          assetPath = durable.relativePath,
          displayName = durable.displayName,
          byteLength = durable.byteLength,
          sha256 = durable.sha256,
          pages = inspection.pages,
        ),
      )
    } catch (t: Throwable) {
      assets.discard(durable ?: staged)
      throw t
    }
  }
}
