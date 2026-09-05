package com.betterhv.note.sender

import com.betterhv.note.sender.shared.NoteLinkLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NoteLinkSettingsTest {
    @Test fun languageStorageIdsAreStableAndUnknownValuesFollowSystem() {
        assertEquals(NoteLinkLanguage.SYSTEM, NoteLinkLanguage.fromStorageId(null))
        assertEquals(NoteLinkLanguage.SYSTEM, NoteLinkLanguage.fromStorageId("unknown"))
        assertEquals(NoteLinkLanguage.SIMPLIFIED_CHINESE, NoteLinkLanguage.fromStorageId("zh-CN"))
        assertEquals(NoteLinkLanguage.ENGLISH, NoteLinkLanguage.fromStorageId("en"))
    }

    @Test fun displayNameIsTrimmed() {
        assertEquals("My NoteLink", NoteLinkSettings.validateDisplayName("  My NoteLink  "))
    }

    @Test fun emptyAndControlCharacterNamesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            NoteLinkSettings.validateDisplayName("   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NoteLinkSettings.validateDisplayName("Note\nLink")
        }
    }

    @Test fun utf8ByteLimitKeepsAdvertisedNameComplete() {
        assertEquals("六个汉字名称", NoteLinkSettings.validateDisplayName("六个汉字名称"))
        assertThrows(IllegalArgumentException::class.java) {
            NoteLinkSettings.validateDisplayName("七个汉字名称啊")
        }
        assertEquals("123456789012345678", NoteLinkSettings.validateDisplayName("123456789012345678"))
        assertThrows(IllegalArgumentException::class.java) {
            NoteLinkSettings.validateDisplayName("1234567890123456789")
        }
    }
}
