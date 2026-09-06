package com.betterhv.note

import android.annotation.SuppressLint

/** Accesses Hanvon's protected hardware-version API without packaging a ROM framework class. */
@SuppressLint("PrivateApi")
object HanvonHardware {
  val isColorDevice: Boolean by lazy {
    try {
      val type = Class.forName("android.hwebook.HanvonEbk")
      val instance = type.getDeclaredConstructor().newInstance()
      val version = type.getMethod("getVersion", Int::class.javaPrimitiveType)
        .invoke(instance, 1) as? String
      version?.startsWith("21.5") == true || version?.startsWith("21.6") == true
    } catch (_: Throwable) {
      false
    }
  }
}
