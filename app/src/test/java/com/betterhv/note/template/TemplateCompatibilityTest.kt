package com.betterhv.note.template

import com.betterhv.note.doc.DEFAULT_TEMPLATE_ID
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateCompatibilityTest {
  private val template = TemplateDefinition(
    TemplateManifestEntry("grid", "Grid", "", "grid.png", 1860, 2414),
    File("grid.png"), "test", TemplateAvailability.AVAILABLE,
  )

  @Test fun newCanvasSizeMatchesEvenWhenCurrentPdfHasDifferentRatio() {
    assertFalse(template.isCompatible(595f, 842f))
    assertTrue(template.isCompatible(1860f, 2414f))
    assertTrue(template.isCompatible(930f, 1207f))
    assertFalse(template.isCompatible(2414f, 1860f))
  }

  @Test fun blankRequiresReadySizeButAcceptsAnyAspect() {
    val blank = template.copy(entry = template.entry.copy(id = DEFAULT_TEMPLATE_ID), assetFile = null)
    assertFalse(blank.isCompatible(0f, 0f))
    assertTrue(blank.isCompatible(595f, 842f))
  }
}
