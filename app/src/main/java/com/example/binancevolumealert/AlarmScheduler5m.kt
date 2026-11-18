package com.example.binancevolumealert

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler5m {

    private const val REQUEST_CODE = 2001

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver5m::class.java)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // Calculeaza urmatorul multiplu de 5 minute exact (ex: daca e 14:23 → 14:25:00)
    private fun nextExact5mMillis(): Long {
        val cal        = Calendar.getInstance()
        val minute     = cal.get(Calendar.MINUTE)
        val nextMinute = (minute / 5 + 1) * 5
        cal.set(Calendar.MINUTE, nextMinute % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (nextMinute >= 60) cal.add(Calendar.HOUR_OF_DAY, 1)
        return cal.timeInMillis
    }

    fun scheduleNext(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextExact5mMillis(), pendingIntent(context))
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("monitoring_active_5m", true).apply()
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("monitoring_active_5m", false).apply()
    }

    fun isActive(context: Context): Boolean =
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getBoolean("monitoring_active_5m", false)

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return am.canScheduleExactAlarms()
        }
        return true
    }
}