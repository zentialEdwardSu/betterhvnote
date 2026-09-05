package com.betterhv.note

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteI18nTest {
    @Test fun languageStorageIdsAreStableAndUnknownValuesFollowSystem() {
        assertEquals(NoteLanguage.SYSTEM, NoteLanguage.fromStorageId(null))
        assertEquals(NoteLanguage.SYSTEM, NoteLanguage.fromStorageId("unknown"))
        assertEquals(NoteLanguage.SIMPLIFIED_CHINESE, NoteLanguage.fromStorageId("zh-CN"))
        assertEquals(NoteLanguage.ENGLISH, NoteLanguage.fromStorageId("en"))
    }

    @Test fun explicitLanguageSelectsExpectedText() {
        val previous = NoteI18n.language
        try {
            NoteI18n.language = NoteLanguage.ENGLISH
            assertEquals("Settings", noteText("设置", "Settings"))
            NoteI18n.language = NoteLanguage.SIMPLIFIED_CHINESE
            assertEquals("设置", noteText("设置", "Settings"))
        } finally {
            NoteI18n.language = previous
        }
    }
}
