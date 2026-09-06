package com.betterhv.note.template

import com.google.gson.Gson
import com.google.gson.JsonObject

/** Pure manifest parser so validation can run in local JVM tests. */
object TemplateManifestCodec {
  private val gson = Gson()
  private val idPattern = Regex("[A-Za-z0-9._-]{1,80}")

  fun parse(json: String): TemplateManifestResult {
    val root = runCatching { gson.fromJson(json, JsonObject::class.java) }.getOrNull()
      ?: return TemplateManifestResult(emptyList(), listOf("manifest.json is not valid JSON"))
    val schemaVersion = runCatching { root.get("schemaVersion")?.asInt }.getOrNull()
    if (schemaVersion != 1) {
      return TemplateManifestResult(emptyList(), listOf("Only schemaVersion 1 is supported"))
    }
    val array = runCatching { root.getAsJsonArray("templates") }.getOrNull()
      ?: return TemplateManifestResult(emptyList(), listOf("manifest.json is missing the templates array"))
    val entries = ArrayList<TemplateManifestEntry>()
    val errors = ArrayList<String>()
    val seen = HashSet<String>()
    array.forEachIndexed { index, element ->
      val obj = element.takeIf { it.isJsonObject }?.asJsonObject
      val prefix = "templates[$index]"
      if (obj == null) {
        errors += "$prefix must be an object"
        return@forEachIndexed
      }
      val id = obj.string("id")
      val name = obj.string("name")
      val description = obj.string("description")
      val file = obj.string("file")
      val width = obj.int("width")
      val height = obj.int("height")
      val problem = when {
        id == null || !idPattern.matches(id) -> "$prefix.id is invalid"
        !seen.add(id) -> "Duplicate template ID: $id"
        name.isNullOrBlank() -> "$prefix.name cannot be empty"
        description == null -> "$prefix.description is missing"
        file == null || file != FileNamePolicy.baseName(file) ->
          "$prefix.file must be a file name in the root directory"
        !file.endsWith(".svg", true) && !file.endsWith(".png", true) -> "$prefix.file only supports SVG/PNG"
        width == null || width <= 0 || height == null || height <= 0 -> "$prefix.width/height must be positive integers"
        else -> null
      }
      if (problem != null) {
        errors += problem
      } else {
        entries += TemplateManifestEntry(id!!, name!!, description!!, file!!, width!!, height!!)
      }
    }
    return TemplateManifestResult(entries, errors)
  }

  private fun JsonObject.string(name: String): String? = runCatching { get(name)?.asString }.getOrNull()
  private fun JsonObject.int(name: String): Int? = runCatching { get(name)?.asInt }.getOrNull()
}

internal object FileNamePolicy {
  fun baseName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
}
