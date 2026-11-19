package com.gotify.lite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 开机自启：仅当用户曾主动开启推送时恢复前台服务。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (prefs.isConfigured && prefs.enabled) {
            Log.i("BootReceiver", "boot completed, restarting push service")
            runCatching { PushService.start(context) }
            ReconnectJobService.schedule(context)
        }
    }
}
