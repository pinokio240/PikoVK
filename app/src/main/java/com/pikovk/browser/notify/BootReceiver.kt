package com.pikovk.browser.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pikovk.browser.util.Prefs

// После перезагрузки устройства возобновляет фоновый опрос, если он включён.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            (Prefs.notifyEnabled || Prefs.imNotifyEnabled)
        ) {
            NotifyService.start(context)
        }
    }
}
