package com.betterhv.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorInteractionTest {
    @Test fun toolbarDefaultsContainEveryConfigurableItemButMenu() {
        assertFalse(ToolbarItem.MENU in ToolbarItem.defaults)
        assertEquals(ToolbarItem.entries.size - 1, ToolbarItem.defaults.size)
    }

    @Test fun bindingsAreIndependentBySceneAndDefaultToEmpty() {
        var bindings = HardwareShortcutBindings()
        assertNull(bindings.action(ShortcutScene.EDITOR, HardwareKeyId.K1))
        bindings = bindings
            .bind(ShortcutScene.EDITOR, HardwareKeyId.K1, ShortcutAction.EDITOR_UNDO)
            .bind(ShortcutScene.PAGE_MANAGER, HardwareKeyId.K1, ShortcutAction.PAGE_NEXT)
        assertEquals(ShortcutAction.EDITOR_UNDO, bindings.action(ShortcutScene.EDITOR, HardwareKeyId.K1))
        assertEquals(ShortcutAction.PAGE_NEXT, bindings.action(ShortcutScene.PAGE_MANAGER, HardwareKeyId.K1))
        assertNull(bindings.clear(ShortcutScene.PAGE_MANAGER).action(ShortcutScene.PAGE_MANAGER, HardwareKeyId.K1))
    }

    @Test fun flyoutContextRejectsActionsFromAnotherScene() {
        var called = false
        val context = PageManagerShortcutContext { called = true; true }
        assertFalse(context.perform(ShortcutAction.EDITOR_UNDO))
        assertFalse(called)
        assertTrue(context.perform(ShortcutAction.PAGE_BOOKMARK))
        assertTrue(called)
    }
}
