package com.betterhv.note.storage

import com.betterhv.note.doc.Command
import com.betterhv.note.doc.CommandAction
import com.betterhv.note.doc.Notebook
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.PageObject
import java.util.UUID

data class PageSnapshot(val metadata: Notebook.PageMetadata, val objects: List<PageObject>) {
  companion object {
    fun capture(page: Page) = PageSnapshot(
      metadata = page.toMetadata(),
      objects = page.scene.all().toList(),
    )
  }
}

private fun Page.toMetadata() = Notebook.PageMetadata(
  id, width, height, bookmarked, contentRevision, createdAt, updatedAt,
  kind, parentPdfPageId, pdfSource, templateId,
)

data class ObjectChange(val pageId: UUID, val objectId: UUID, val value: PageObject?)

/** Immutable handoff from the document thread to the storage worker. */
data class DocumentChange(
  val notebookId: UUID,
  val title: String,
  val notebookKind: com.betterhv.note.doc.NotebookKind,
  val createdAt: Long,
  val updatedAt: Long,
  val pageOrder: List<UUID>,
  val pages: List<Notebook.PageMetadata>,
  val objectChanges: List<ObjectChange>,
  val fullPages: List<PageSnapshot>,
  val description: String,
  val pageStructureChanged: Boolean = true,
) {
  companion object {
    fun forCommand(notebook: Notebook, command: Command, action: CommandAction): DocumentChange {
      notebook.markChanged()
      val incremental = command.affectedObjects.flatMap { (pageId, ids) ->
        ids.map { id -> ObjectChange(pageId, id, command.currentObject(pageId, id)) }
      }
      val structureChanged = command.changesPageStructure
      val full = if (structureChanged) {
        command.affectedPages.mapNotNull(notebook::getPage).map(PageSnapshot::capture)
      } else {
        emptyList()
      }
      val affectedPageIds = command.affectedObjects.keys + command.affectedPages
      val pages = if (structureChanged) {
        notebook.allPageMetadata()
      } else {
        affectedPageIds.mapNotNull { id ->
          command.currentPage(id)?.toMetadata() ?: notebook.metadata(id)
        }
      }
      val pageOrder = if (structureChanged) notebook.pageOrder.toList() else emptyList()
      return DocumentChange(
        notebookId = notebook.id,
        title = notebook.title,
        notebookKind = notebook.kind,
        createdAt = notebook.createdAt,
        updatedAt = notebook.updatedAt,
        pageOrder = pageOrder,
        pages = pages,
        objectChanges = incremental,
        fullPages = full,
        description = "${action.name.lowercase()}:${command.javaClass.simpleName}",
        pageStructureChanged = structureChanged,
      )
    }

    fun fullPage(notebook: Notebook, page: Page, description: String): DocumentChange {
      notebook.markChanged()
      return captureBase(notebook, emptyList(), listOf(PageSnapshot.capture(page)), description)
    }

    fun structure(notebook: Notebook, pages: List<Page>, description: String): DocumentChange {
      notebook.markChanged()
      return captureBase(notebook, emptyList(), pages.map(PageSnapshot::capture), description)
    }

    private fun captureBase(
      notebook: Notebook,
      objectChanges: List<ObjectChange>,
      fullPages: List<PageSnapshot>,
      description: String,
    ) = DocumentChange(
      notebookId = notebook.id,
      title = notebook.title,
      notebookKind = notebook.kind,
      createdAt = notebook.createdAt,
      updatedAt = notebook.updatedAt,
      pageOrder = notebook.pageOrder.toList(),
      pages = notebook.allPageMetadata(),
      objectChanges = objectChanges,
      fullPages = fullPages,
      description = description,
      pageStructureChanged = true,
    )
  }
}
