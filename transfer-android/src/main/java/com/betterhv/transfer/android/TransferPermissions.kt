package com.betterhv.transfer.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object TransferPermissions {
  fun noteRuntimePermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= 31) {
      add(Manifest.permission.BLUETOOTH_SCAN)
      add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
  }.toTypedArray()

  fun phoneRuntimePermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= 31) {
      add(Manifest.permission.BLUETOOTH_ADVERTISE)
      add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
  }.toTypedArray()

  fun missingNotePermissions(context: Context): Array<String> = missing(context, noteRuntimePermissions())

  fun missingPhonePermissions(context: Context): Array<String> = missing(context, phoneRuntimePermissions())

  fun hasAllNotePermissions(context: Context): Boolean = missingNotePermissions(context).isEmpty()

  fun hasAllPhonePermissions(context: Context): Boolean = missingPhonePermissions(context).isEmpty()

  private fun missing(context: Context, permissions: Array<String>): Array<String> = permissions.filter {
    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
  }.toTypedArray()
}
