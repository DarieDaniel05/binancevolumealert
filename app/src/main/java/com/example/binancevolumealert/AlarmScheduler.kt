package com.example.binancevolumealert

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler {

    private const val REQUEST_CODE = 1001

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun nextExactHourMillis(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.add(Calendar.HOUR_OF_DAY, 1) // urmatoarea ora fixa, nu cea curenta
        return calendar.timeInMillis
    }

    fun scheduleNext(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = nextExactHourMillis()

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context)
        )

        context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("monitoring_active", true).apply()
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context))

        context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("monitoring_active", false).apply()
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return alarmManager.canScheduleExactAlarms()
        }
        return true
    }
    fun isActive(context: Context): Boolean {
        return context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getBoolean("monitoring_active", false)
    }
}
