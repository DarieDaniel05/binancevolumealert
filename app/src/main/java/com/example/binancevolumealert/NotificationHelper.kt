package com.example.binancevolumealert

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {

    private const val CHANNEL_ID = "volume_alerts"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Alerte volum Binance",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificare cand volumul unei monede creste brusc"
                enableVibration(true)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // timeframe = "1H" sau "5M" — afecteaza titlul si ID-ul notificarii
    fun showBatchAlert(context: Context, coins: List<FoundCoin>, timeframe: String = "1H") {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val sorted  = coins.sortedByDescending { it.ratio }
        val prefix  = if (timeframe == "5M") "⚡ 5M: " else "⚡ "

        val title = if (sorted.size == 1)
            "${prefix}${sorted[0].symbol} — x%.1f".format(sorted[0].ratio)
        else
            "${prefix}${sorted.size} monede cu volum ridicat"

        val preview = sorted.joinToString(", ") { "${it.symbol} x${"%.1f".format(it.ratio)}" }

        val style = NotificationCompat.InboxStyle()
        sorted.take(5).forEach { c ->
            style.addLine("${c.symbol}   x${"%.1f".format(c.ratio)}   ${"★".repeat(c.stars)}")
        }
        if (sorted.size > 5) style.setSummaryText("+ ${sorted.size - 5} altele")

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()

        // ID-uri separate: 1H = ora curenta, 5M = fereastra de 5min curenta + offset 10000
        val periodMs = if (timeframe == "5M") 300_000L else 3_600_000L
        val offset   = if (timeframe == "5M") 10_000 else 0
        val notifId  = (System.currentTimeMillis() / periodMs).toInt() + offset

        NotificationManagerCompat.from(context).notify(notifId, notification)
    }
}