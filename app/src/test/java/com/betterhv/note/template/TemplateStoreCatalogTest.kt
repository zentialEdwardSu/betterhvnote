package com.betterhv.note.template

import com.betterhv.note.doc.DEFAULT_TEMPLATE_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateStoreCatalogTest {
  @Test
  fun initialCatalogSeedsOnlyBuiltinBlankTemplate() {
    val blank = blankTemplateDefinition()
    assertEquals(DEFAULT_TEMPLATE_ID, blank.id)
    assertEquals(TemplateAvailability.AVAILABLE, blank.availability)
    assertNull(blank.assetFile)
    assertTrue(blank.isCompatible(1404f, 1872f))
    assertTrue(blank.isCompatible(800f, 600f))

    val initial = initialTemplateCatalog("content://templates/tree")
    assertEquals(0L, initial.generation)
    assertEquals("content://templates/tree", initial.directoryUri)
    assertTrue(initial.errors.isEmpty())
    assertEquals(listOf(DEFAULT_TEMPLATE_ID), initial.templates.map(TemplateDefinition::id))
    assertEquals(blank, initial.find(null))
    assertEquals(blank, initial.find("missing.template"))
  }

  @Test
  fun visualFingerprintResolvesExistingMissingAndNullIds() {
    val custom = TemplateDefinition(
      entry = TemplateManifestEntry("custom.grid", "Grid", "Fine grid", "grid.svg", 1404, 1872),
      assetFile = null,
      fingerprint = "sha256:grid-v1",
      availability = TemplateAvailability.AVAILABLE,
    )
    val snapshot = TemplateCatalogSnapshot(
      templates = listOf(blankTemplateDefinition(), custom),
      directoryUri = null,
      errors = emptyList(),
      generation = 1L,
    )

    assertEquals("none", resolveTemplateFingerprint(snapshot, null))
    assertEquals("sha256:grid-v1", resolveTemplateFingerprint(snapshot, "custom.grid"))
    assertEquals("builtin.blank:white-v1", resolveTemplateFingerprint(snapshot, DEFAULT_TEMPLATE_ID))
    assertEquals(
      "missing:unknown.template:builtin.blank:white-v1",
      resolveTemplateFingerprint(snapshot, "unknown.template"),
    )
  }

  @Test
  fun catalogSignatureTracksTemplatesErrorsAndDirectoryUri() {
    val blank = blankTemplateDefinition()
    val custom = TemplateDefinition(
      entry = TemplateManifestEntry("custom.lines", "Lines", "", "lines.svg", 3, 4),
      assetFile = null,
      fingerprint = "fp-1",
      availability = TemplateAvailability.AVAILABLE,
    )
    val baseline = templateCatalogSignature(listOf(blank, custom), emptyList(), "content://a")
    assertEquals(baseline, templateCatalogSignature(listOf(blank, custom), emptyList(), "content://a"))

    val changedFingerprint = custom.copy(fingerprint = "fp-2")
    assertNotEquals(
      baseline,
      templateCatalogSignature(listOf(blank, changedFingerprint), emptyList(), "content://a"),
    )
    assertNotEquals(
      baseline,
      templateCatalogSignature(listOf(blank, custom), listOf("manifest warning"), "content://a"),
    )
    assertNotEquals(
      baseline,
      templateCatalogSignature(listOf(blank, custom), emptyList(), "content://b"),
    )
  }

  @Test
  fun mergeTemplateDefinitionsPreventsExternalTemplatesFromShadowingBuiltins() {
    val builtinGrid = TemplateDefinition(
      entry = TemplateManifestEntry("builtin.grid", "Builtin Grid", "", "grid.svg", 3, 4),
      assetFile = null,
      fingerprint = "builtin-fp",
      availability = TemplateAvailability.AVAILABLE,
    )
    val externalShadow = builtinGrid.copy(fingerprint = "external-shadow-fp")
    val externalCustom = TemplateDefinition(
      entry = TemplateManifestEntry("user.dots", "Dots", "", "dots.png", 3, 4),
      assetFile = null,
      fingerprint = "user-fp",
      availability = TemplateAvailability.AVAILABLE,
    )

    val merged = mergeTemplateDefinitions(
      builtins = listOf(blankTemplateDefinition(), builtinGrid),
      external = listOf(externalShadow, externalCustom),
    )
    assertEquals(listOf(DEFAULT_TEMPLATE_ID, "builtin.grid", "user.dots"), merged.map(TemplateDefinition::id))
    assertEquals("builtin-fp", merged.first { it.id == "builtin.grid" }.fingerprint)
  }
}
