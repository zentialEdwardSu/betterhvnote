package com.betterhv.note.template

import com.google.gson.Gson
import com.google.gson.JsonObject

/** Pure manifest parser so validation can run in local JVM tests. */
object TemplateManifestCodec {
    private val gson = Gson()
    private val idPattern = Regex("[A-Za-z0-9._-]{1,80}")

    fun parse(json: String): TemplateManifestResult {
        val root = runCatching { gson.fromJson(json, JsonObject::class.java) }.getOrNull()
            ?: return TemplateManifestResult(emptyList(), listOf("manifest.json 不是有效 JSON"))
        val schemaVersion = runCatching { root.get("schemaVersion")?.asInt }.getOrNull()
        if (schemaVersion != 1) {
            return TemplateManifestResult(emptyList(), listOf("仅支持 schemaVersion 1"))
        }
        val array = runCatching { root.getAsJsonArray("templates") }.getOrNull()
            ?: return TemplateManifestResult(emptyList(), listOf("manifest.json 缺少 templates 数组"))
        val entries = ArrayList<TemplateManifestEntry>()
        val errors = ArrayList<String>()
        val seen = HashSet<String>()
        array.forEachIndexed { index, element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject
            val prefix = "templates[$index]"
            if (obj == null) {
                errors += "$prefix 必须是对象"
                return@forEachIndexed
            }
            val id = obj.string("id")
            val name = obj.string("name")
            val description = obj.string("description")
            val file = obj.string("file")
            val width = obj.int("width")
            val height = obj.int("height")
            val problem = when {
                id == null || !idPattern.matches(id) -> "$prefix.id 无效"
                !seen.add(id) -> "模板 ID $id 重复"
                name.isNullOrBlank() -> "$prefix.name 不能为空"
                description == null -> "$prefix.description 缺失"
                file == null || file != FileNamePolicy.baseName(file) -> "$prefix.file 必须是根目录中的文件名"
                !file.endsWith(".svg", true) && !file.endsWith(".png", true) -> "$prefix.file 仅支持 SVG/PNG"
                width == null || width <= 0 || height == null || height <= 0 -> "$prefix.width/height 必须为正整数"
                else -> null
            }
            if (problem != null) errors += problem
            else entries += TemplateManifestEntry(id!!, name!!, description!!, file!!, width!!, height!!)
        }
        return TemplateManifestResult(entries, errors)
    }

    private fun JsonObject.string(name: String): String? = runCatching { get(name)?.asString }.getOrNull()
    private fun JsonObject.int(name: String): Int? = runCatching { get(name)?.asInt }.getOrNull()
}

internal object FileNamePolicy {
    fun baseName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
}
