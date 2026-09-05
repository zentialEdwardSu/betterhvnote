package com.betterhv.note.sender.shared

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

enum class NoteLinkLanguage(val storageId: String) {
    SYSTEM("system"),
    SIMPLIFIED_CHINESE("zh-CN"),
    ENGLISH("en");

    companion object {
        fun fromStorageId(value: String?): NoteLinkLanguage =
            entries.firstOrNull { it.storageId == value } ?: SYSTEM
    }
}

fun NoteLinkLanguage.isChinese(): Boolean = when (this) {
    NoteLinkLanguage.SYSTEM -> Locale.getDefault().language.startsWith("zh", ignoreCase = true)
    NoteLinkLanguage.SIMPLIFIED_CHINESE -> true
    NoteLinkLanguage.ENGLISH -> false
}

fun NoteLinkLanguage.text(chinese: String, english: String): String =
    if (isChinese()) chinese else english

fun NoteLinkLanguage.displayName(): String = when (this) {
    NoteLinkLanguage.SYSTEM -> text("跟随系统", "System default")
    NoteLinkLanguage.SIMPLIFIED_CHINESE -> "简体中文"
    NoteLinkLanguage.ENGLISH -> "English"
}

object NoteLinkI18n {
    var language: NoteLinkLanguage by mutableStateOf(NoteLinkLanguage.SYSTEM)
}

fun noteLinkText(chinese: String, english: String): String =
    NoteLinkI18n.language.text(chinese, english)
