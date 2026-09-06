package com.betterhv.note.doc

fun inheritedTemplateId(previous: Notebook.PageMetadata?): String = when (previous?.kind) {
  PageKind.PDF_SOURCE -> DEFAULT_TEMPLATE_ID
  else -> previous?.templateId ?: DEFAULT_TEMPLATE_ID
}
