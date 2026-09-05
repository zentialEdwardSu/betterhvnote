package com.betterhv.note

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** User-selected UI language. Storage IDs are part of the preferences contract. */
enum class NoteLanguage(val storageId: String) {
    SYSTEM("system"),
    SIMPLIFIED_CHINESE("zh-CN"),
    ENGLISH("en");

    companion object {
        fun fromStorageId(value: String?): NoteLanguage =
            entries.firstOrNull { it.storageId == value } ?: SYSTEM
    }
}

/**
 * Small runtime string selector for the Compose UI and user-facing errors emitted by the editor.
 * Reading [language] from Compose is observable, so changing it refreshes the open screen immediately.
 */
object NoteI18n {
    var language: NoteLanguage by mutableStateOf(NoteLanguage.SYSTEM)

    val isChinese: Boolean
        get() = when (language) {
            NoteLanguage.SYSTEM -> Locale.getDefault().language.startsWith("zh", ignoreCase = true)
            NoteLanguage.SIMPLIFIED_CHINESE -> true
            NoteLanguage.ENGLISH -> false
        }
}

fun noteText(chinese: String, english: String): String =
    if (NoteI18n.isChinese) chinese else english

fun NoteLanguage.displayName(): String = when (this) {
    NoteLanguage.SYSTEM -> noteText("跟随系统", "System default")
    NoteLanguage.SIMPLIFIED_CHINESE -> "简体中文"
    NoteLanguage.ENGLISH -> "English"
}

fun ToolbarItem.localizedLabel(): String = when (this) {
    ToolbarItem.PEN_1 -> noteText("笔 1", "Pen 1")
    ToolbarItem.PEN_2 -> noteText("笔 2", "Pen 2")
    ToolbarItem.PEN_3 -> noteText("笔 3", "Pen 3")
    ToolbarItem.TAIL_ERASER -> noteText("笔尾橡皮模式", "Tail eraser mode")
    ToolbarItem.LASSO -> noteText("套索", "Lasso")
    ToolbarItem.NAVIGATION -> noteText("PDF 导航", "PDF navigation")
    ToolbarItem.INSERT -> noteText("插入", "Insert")
    ToolbarItem.UNDO -> noteText("撤销", "Undo")
    ToolbarItem.REDO -> noteText("重做", "Redo")
    ToolbarItem.DELETE -> noteText("删除所选", "Delete selection")
    ToolbarItem.PAGES -> noteText("页面", "Pages")
    ToolbarItem.NOTEBOOKS -> noteText("笔记本", "Notebooks")
    ToolbarItem.EXPORT -> noteText("导出", "Export")
    ToolbarItem.MENU -> "Menu"
}

fun PenColorChoice.localizedLabel(): String = when (id) {
    "black" -> noteText("黑色", "Black")
    "dark_gray" -> noteText("深灰", "Dark gray")
    "light_gray" -> noteText("浅灰", "Light gray")
    "white" -> noteText("白色", "White")
    "red" -> noteText("红色", "Red")
    "blue" -> noteText("蓝色", "Blue")
    "green" -> noteText("绿色", "Green")
    "yellow" -> noteText("黄色", "Yellow")
    else -> label
}

fun ShortcutScene.localizedLabel(): String = when (this) {
    ShortcutScene.EDITOR -> noteText("编辑页", "Editor")
    ShortcutScene.PAGE_MANAGER -> noteText("页面管理", "Page manager")
    ShortcutScene.INSERT -> noteText("插入内容", "Insert")
}

fun ShortcutAction.localizedLabel(): String = when (this) {
    ShortcutAction.EDITOR_PEN_1 -> noteText("笔 1", "Pen 1")
    ShortcutAction.EDITOR_PEN_2 -> noteText("笔 2", "Pen 2")
    ShortcutAction.EDITOR_PEN_3 -> noteText("笔 3", "Pen 3")
    ShortcutAction.EDITOR_TAIL_ERASER -> noteText("笔尾橡皮模式", "Tail eraser mode")
    ShortcutAction.EDITOR_LASSO -> noteText("套索", "Lasso")
    ShortcutAction.EDITOR_INSERT -> noteText("插入", "Insert")
    ShortcutAction.EDITOR_UNDO -> noteText("撤销", "Undo")
    ShortcutAction.EDITOR_REDO -> noteText("重做", "Redo")
    ShortcutAction.EDITOR_DELETE -> noteText("删除所选", "Delete selection")
    ShortcutAction.EDITOR_PAGES -> noteText("页面", "Pages")
    ShortcutAction.EDITOR_NOTEBOOKS -> noteText("笔记本", "Notebooks")
    ShortcutAction.EDITOR_EXPORT -> noteText("导出", "Export")
    ShortcutAction.EDITOR_MENU -> "Menu"
    ShortcutAction.EDITOR_TOGGLE_TOOLBAR -> noteText("显示/隐藏工具栏", "Show/hide toolbar")
    ShortcutAction.PAGE_PREVIOUS -> noteText("上一页", "Previous page")
    ShortcutAction.PAGE_NEXT -> noteText("下一页", "Next page")
    ShortcutAction.PAGE_ADD -> noteText("在当前页后新增", "Add after current page")
    ShortcutAction.PAGE_DELETE -> noteText("删除当前页", "Delete current page")
    ShortcutAction.PAGE_BOOKMARK -> noteText("切换书签", "Toggle bookmark")
    ShortcutAction.PAGE_CLOSE -> noteText("关闭页面管理", "Close page manager")
    ShortcutAction.INSERT_IMAGE -> noteText("图片", "Image")
    ShortcutAction.INSERT_TEXT -> noteText("文字", "Text")
    ShortcutAction.INSERT_LOCAL -> noteText("本地/手动来源", "Local/manual source")
    ShortcutAction.INSERT_NOTELINK -> noteText("NoteLink 来源", "NoteLink source")
    ShortcutAction.INSERT_CANCEL -> noteText("取消/返回", "Cancel/back")
}
