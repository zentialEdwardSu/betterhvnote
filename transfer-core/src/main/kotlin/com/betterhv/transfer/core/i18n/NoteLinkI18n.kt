package com.betterhv.transfer.core.i18n

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class NoteLinkLanguage(val storageId: String) {
  SYSTEM("system"),
  SIMPLIFIED_CHINESE("zh-CN"),
  ENGLISH("en"),
  ;

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
  private val mutableLanguage = MutableStateFlow(NoteLinkLanguage.SYSTEM)

  val languageFlow: StateFlow<NoteLinkLanguage> = mutableLanguage.asStateFlow()

  @Volatile
  var language: NoteLinkLanguage = NoteLinkLanguage.SYSTEM
    set(value) {
      field = value
      mutableLanguage.value = value
    }
}

fun noteLinkText(chinese: String, english: String): String =
  NoteLinkI18n.language.text(chinese, english)
