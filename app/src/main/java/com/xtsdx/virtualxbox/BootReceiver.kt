package com.xtsdx.virtualxbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Config.prefs(context).getBoolean("boot", false)) {
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }
    }
}
