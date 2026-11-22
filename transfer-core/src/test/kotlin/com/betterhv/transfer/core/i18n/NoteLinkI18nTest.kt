package com.betterhv.transfer.core.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoteLinkI18nTest {
  @Test
  fun languageAssignmentUpdatesStateFlowAndNoteLinkText() {
    val previous = NoteLinkI18n.language
    try {
      NoteLinkI18n.language = NoteLinkLanguage.ENGLISH
      assertEquals(NoteLinkLanguage.ENGLISH, NoteLinkI18n.languageFlow.value)
      assertFalse(NoteLinkI18n.language.isChinese())
      assertEquals("Open", noteLinkText("打开", "Open"))

      NoteLinkI18n.language = NoteLinkLanguage.SIMPLIFIED_CHINESE
      assertEquals(NoteLinkLanguage.SIMPLIFIED_CHINESE, NoteLinkI18n.languageFlow.value)
      assertTrue(NoteLinkI18n.language.isChinese())
      assertEquals("打开", noteLinkText("打开", "Open"))
    } finally {
      NoteLinkI18n.language = previous
    }
  }

  @Test
  fun storageIdsResolveAndDisplayNamesAreLocalized() {
    assertEquals(NoteLinkLanguage.SYSTEM, NoteLinkLanguage.fromStorageId(null))
    assertEquals(NoteLinkLanguage.SYSTEM, NoteLinkLanguage.fromStorageId("invalid"))
    assertEquals(NoteLinkLanguage.SIMPLIFIED_CHINESE, NoteLinkLanguage.fromStorageId("zh-CN"))
    assertEquals(NoteLinkLanguage.ENGLISH, NoteLinkLanguage.fromStorageId("en"))
    assertEquals("简体中文", NoteLinkLanguage.SIMPLIFIED_CHINESE.displayName())
    assertEquals("English", NoteLinkLanguage.ENGLISH.displayName())
  }
}
