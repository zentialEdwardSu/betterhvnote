package com.betterhv.note.template

import com.betterhv.note.doc.DEFAULT_TEMPLATE_ID
import java.io.File

enum class TemplateAvailability { AVAILABLE, CACHED, INVALID }

data class TemplateManifestEntry(
  val id: String,
  val name: String,
  val description: String,
  val file: String,
  val width: Int,
  val height: Int,
)

data class TemplateManifestResult(val entries: List<TemplateManifestEntry>, val errors: List<String>)

data class TemplateDefinition(
  val entry: TemplateManifestEntry,
  val assetFile: File?,
  val fingerprint: String,
  val availability: TemplateAvailability,
  val error: String? = null,
) {
  val id get() = entry.id
  val name get() = entry.name
  val description get() = entry.description

  fun isCompatible(pageWidth: Float, pageHeight: Float): Boolean {
    if (pageWidth <= 0f || pageHeight <= 0f) return false
    // Blank is a virtual white background. It intentionally has no image asset
    // and is valid for every writable page size.
    if (id == DEFAULT_TEMPLATE_ID) return true
    if (assetFile == null) return false
    val templateRatio = entry.width.toDouble() / entry.height
    val pageRatio = pageWidth.toDouble() / pageHeight
    return kotlin.math.abs(templateRatio / pageRatio - 1.0) <= ASPECT_RATIO_TOLERANCE
  }

  companion object {
    const val ASPECT_RATIO_TOLERANCE = 0.001
  }
}

data class TemplateCatalogSnapshot(
  val templates: List<TemplateDefinition>,
  val directoryUri: String?,
  val errors: List<String>,
  val generation: Long,
) {
  fun find(id: String?): TemplateDefinition? = templates.firstOrNull { it.id == id }
    ?: templates.firstOrNull { it.id == DEFAULT_TEMPLATE_ID }
}

interface TemplateResolver {
  fun resolve(templateId: String?): TemplateDefinition?
  fun visualFingerprint(templateId: String?): String
}
