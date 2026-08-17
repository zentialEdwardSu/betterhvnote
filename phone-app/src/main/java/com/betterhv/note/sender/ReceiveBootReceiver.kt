package com.betterhv.note.sender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.betterhv.transfer.android.AndroidPairingController

class ReceiveBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            AndroidPairingController(context).pairedDevice != null &&
            TransferForegroundService.isReceiveEnabled(context)
        ) {
            TransferForegroundService.sync(context, hasItems = false)
        }
    }
}
