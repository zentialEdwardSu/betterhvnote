package com.betterhv.note.sender.desktop

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class NoteLinkVersionResourceTest {
  @Test
  fun generatedResourceMatchesInjectedVersion() {
    val expected = requireNotNull(System.getProperty("notelink.expectedVersion"))
    val properties = Properties()
    val resource = requireNotNull(javaClass.classLoader.getResourceAsStream("notelink-version.properties"))

    resource.use(properties::load)

    assertEquals(expected, properties.getProperty("version"))
  }
}
