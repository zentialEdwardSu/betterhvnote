package com.betterhv.note

/** Stable toolbar entries. Their order is the order rendered on screen and in Menu. */
enum class ToolbarItem(val storageId: String, val label: String) {
    PEN_1("pen_1", "笔 1"),
    PEN_2("pen_2", "笔 2"),
    PEN_3("pen_3", "笔 3"),
    TAIL_ERASER("tail_eraser", "笔尾橡皮模式"),
    LASSO("lasso", "套索"),
    INSERT("insert", "插入"),
    UNDO("undo", "撤销"),
    REDO("redo", "重做"),
    DELETE("delete", "删除所选"),
    PAGES("pages", "页面"),
    NOTEBOOKS("notebooks", "笔记本"),
    EXPORT("export", "导出"),
    MENU("menu", "Menu");

    companion object {
        val defaults: Set<ToolbarItem> = entries.filterNot { it == MENU }.toSet()
        fun fromStorageId(value: String): ToolbarItem? = entries.firstOrNull { it.storageId == value }
    }
}

enum class HardwareKeyId(val number: Int) {
    K1(1), K2(2), K3(3), K4(4), K5(5), K6(6), K7(7), K8(8)
}

enum class ShortcutScene(val storageId: String, val label: String) {
    EDITOR("editor", "编辑页"),
    PAGE_MANAGER("page_manager", "页面管理"),
    INSERT("insert", "插入内容")
}

/** Stable action IDs stored in preferences. */
enum class ShortcutAction(
    val storageId: String,
    val label: String,
    val scene: ShortcutScene
) {
    EDITOR_PEN_1("editor.pen_1", "笔 1", ShortcutScene.EDITOR),
    EDITOR_PEN_2("editor.pen_2", "笔 2", ShortcutScene.EDITOR),
    EDITOR_PEN_3("editor.pen_3", "笔 3", ShortcutScene.EDITOR),
    EDITOR_TAIL_ERASER("editor.tail_eraser", "笔尾橡皮模式", ShortcutScene.EDITOR),
    EDITOR_LASSO("editor.lasso", "套索", ShortcutScene.EDITOR),
    EDITOR_INSERT("editor.insert", "插入", ShortcutScene.EDITOR),
    EDITOR_UNDO("editor.undo", "撤销", ShortcutScene.EDITOR),
    EDITOR_REDO("editor.redo", "重做", ShortcutScene.EDITOR),
    EDITOR_DELETE("editor.delete", "删除所选", ShortcutScene.EDITOR),
    EDITOR_PAGES("editor.pages", "页面", ShortcutScene.EDITOR),
    EDITOR_NOTEBOOKS("editor.notebooks", "笔记本", ShortcutScene.EDITOR),
    EDITOR_EXPORT("editor.export", "导出", ShortcutScene.EDITOR),
    EDITOR_MENU("editor.menu", "Menu", ShortcutScene.EDITOR),
    EDITOR_TOGGLE_TOOLBAR("editor.toggle_toolbar", "显示/隐藏工具栏", ShortcutScene.EDITOR),

    PAGE_PREVIOUS("page.previous", "上一页", ShortcutScene.PAGE_MANAGER),
    PAGE_NEXT("page.next", "下一页", ShortcutScene.PAGE_MANAGER),
    PAGE_ADD("page.add", "在当前页后新增", ShortcutScene.PAGE_MANAGER),
    PAGE_DELETE("page.delete", "删除当前页", ShortcutScene.PAGE_MANAGER),
    PAGE_BOOKMARK("page.bookmark", "切换书签", ShortcutScene.PAGE_MANAGER),
    PAGE_CLOSE("page.close", "关闭页面管理", ShortcutScene.PAGE_MANAGER),

    INSERT_IMAGE("insert.image", "图片", ShortcutScene.INSERT),
    INSERT_TEXT("insert.text", "文字", ShortcutScene.INSERT),
    INSERT_LOCAL("insert.local", "本地/手动来源", ShortcutScene.INSERT),
    INSERT_NOTELINK("insert.notelink", "NoteLink 来源", ShortcutScene.INSERT),
    INSERT_CANCEL("insert.cancel", "取消/返回", ShortcutScene.INSERT);

    companion object {
        fun forScene(scene: ShortcutScene): List<ShortcutAction> = entries.filter { it.scene == scene }
        fun fromStorageId(value: String): ShortcutAction? = entries.firstOrNull { it.storageId == value }
    }
}

data class HardwareShortcutBindings(
    val values: Map<ShortcutScene, Map<HardwareKeyId, ShortcutAction>> = emptyMap()
) {
    fun action(scene: ShortcutScene, key: HardwareKeyId): ShortcutAction? = values[scene]?.get(key)

    fun bind(scene: ShortcutScene, key: HardwareKeyId, action: ShortcutAction?): HardwareShortcutBindings {
        require(action == null || action.scene == scene)
        val sceneValues = values[scene].orEmpty().toMutableMap()
        if (action == null) sceneValues.remove(key) else sceneValues[key] = action
        return copy(values = values + (scene to sceneValues))
    }

    fun clear(scene: ShortcutScene): HardwareShortcutBindings = copy(values = values - scene)
}

/** Extension point for hardware-key behavior owned by an attached flyout. */
abstract class FlyoutHardwareShortcutContext(val scene: ShortcutScene) {
    abstract fun perform(action: ShortcutAction): Boolean
}

class PageManagerShortcutContext(
    private val performer: (ShortcutAction) -> Boolean
) : FlyoutHardwareShortcutContext(ShortcutScene.PAGE_MANAGER) {
    override fun perform(action: ShortcutAction): Boolean =
        action.scene == scene && performer(action)
}

class InsertShortcutContext(
    private val performer: (ShortcutAction) -> Boolean
) : FlyoutHardwareShortcutContext(ShortcutScene.INSERT) {
    override fun perform(action: ShortcutAction): Boolean =
        action.scene == scene && performer(action)
}
