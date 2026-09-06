package com.betterhv.note.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateManifestCodecTest {
  @Test fun parsesValidManifest() {
    val result = TemplateManifestCodec.parse(
      """{"schemaVersion":1,"templates":[{"id":"custom.grid","name":"Grid","description":"Fine grid","file":"grid.svg","width":1404,"height":1872}]}""",
    )
    assertTrue(result.errors.isEmpty())
    assertEquals("custom.grid", result.entries.single().id)
  }

  @Test fun rejectsDuplicateIdsAndPathTraversal() {
    val result = TemplateManifestCodec.parse(
      """{"schemaVersion":1,"templates":[
              {"id":"same","name":"One","description":"","file":"one.svg","width":3,"height":4},
              {"id":"same","name":"Two","description":"","file":"../two.png","width":3,"height":4}
            ]}""",
    )
    assertEquals(1, result.entries.size)
    assertTrue(result.errors.any { "重复" in it })
  }

  @Test fun rejectsUnsupportedVersionAndExtension() {
    assertTrue(TemplateManifestCodec.parse("""{"schemaVersion":2,"templates":[]}""").errors.isNotEmpty())
    val extension = TemplateManifestCodec.parse(
      """{"schemaVersion":1,"templates":[{"id":"bad","name":"Bad","description":"","file":"bad.jpg","width":3,"height":4}]}""",
    )
    assertTrue(extension.entries.isEmpty())
    assertTrue(extension.errors.isNotEmpty())
  }

  @Test fun aspectRatioToleranceIsStrict() {
    val file = kotlin.io.path.createTempFile(suffix = ".svg").toFile()
    try {
      val definition = TemplateDefinition(
        TemplateManifestEntry("id", "Name", "Description", "file.svg", 3, 4),
        file,
        "hash",
        TemplateAvailability.AVAILABLE,
      )
      assertTrue(definition.isCompatible(1404f, 1872f))
      assertTrue(!definition.isCompatible(1000f, 1400f))
    } finally {
      file.delete()
    }
  }
}
