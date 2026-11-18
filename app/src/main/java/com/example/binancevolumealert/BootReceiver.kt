package com.example.binancevolumealert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("monitoring_active", false)) {
            AlarmScheduler.scheduleNext(context)
        }
        if (prefs.getBoolean("monitoring_active_5m", false)) {
            AlarmScheduler5m.scheduleNext(context)
        }
    }
}