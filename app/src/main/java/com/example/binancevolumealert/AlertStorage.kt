package com.example.binancevolumealert

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar

data class VolumeAlert(
    val symbol: String,
    val prevVolume: Double,
    val currVolume: Double,
    val ratio: Double,
    val timestamp: Long,
    val stars: Int = 0,
    val timeframe: String? = "1H" // null = date vechi salvate, tratate ca 1H
)

object AlertStorage {
    private const val PREFS     = "prefs"
    private const val KEY_ALERTS = "alerts_list"
    private const val MAX_ALERTS = 300

    fun addAlert(context: Context, alert: VolumeAlert) {
        val current = getAlerts(context).toMutableList()
        current.add(0, alert)
        saveAlerts(context, current.take(MAX_ALERTS))
    }

    fun getAlerts(context: Context): List<VolumeAlert> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json  = prefs.getString(KEY_ALERTS, null) ?: return emptyList()
        val type  = object : TypeToken<List<VolumeAlert>>() {}.type
        return try { Gson().fromJson(json, type) } catch (e: Exception) { emptyList() }
    }

    // ── 1H ───────────────────────────────────────────────────────────────────

    // Pastreaza 1H alerts din ultimele 3 ore, 5M alerts neatinse
    fun pruneOldAlerts(context: Context) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val cutoff = cal.timeInMillis - 2 * 3_600_000L
        saveAlerts(context, getAlerts(context).filter {
            it.timeframe == "5M" || it.timestamp >= cutoff
        })
    }

    // Dedup: nu adauga aceeasi moneda de doua ori in ora curenta (1H)
    fun isSymbolInCurrentHour(context: Context, symbol: String): Boolean {
        val cal = Calendar.getInstance().apply {
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val end   = start + 3_600_000L
        return getAlerts(context).any {
            it.symbol == symbol &&
                    (it.timeframe == "1H" || it.timeframe == null) &&
                    it.timestamp in start until end
        }
    }

    // Toate alertele 1H din ora curenta (pentru batch notificare)
    fun getAlertsInCurrentHour(context: Context): List<VolumeAlert> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val end   = start + 3_600_000L
        return getAlerts(context).filter {
            (it.timeframe == "1H" || it.timeframe == null) &&
                    it.timestamp in start until end
        }
    }

    fun countAlertsInLatestHour(context: Context): Int {
        val alerts = getAlerts(context).filter {
            it.timeframe == "1H" || it.timeframe == null
        }
        if (alerts.isEmpty()) return 0
        val latest = (alerts.maxOf { it.timestamp } / 3_600_000L) * 3_600_000L
        return alerts.count { it.timestamp in latest until latest + 3_600_000L }
    }

    // ── 5M ───────────────────────────────────────────────────────────────────

    // Pastreaza 5M alerts din ultima ora, 1H alerts neatinse
    fun pruneOldAlerts5m(context: Context) {
        val cutoff = System.currentTimeMillis() - 3_600_000L
        saveAlerts(context, getAlerts(context).filter {
            it.timeframe != "5M" || it.timestamp >= cutoff
        })
    }

    // Dedup: nu adauga aceeasi moneda de doua ori in aceeasi fereastra de 5m
    fun isSymbolInCurrentPeriod5m(context: Context, symbol: String): Boolean {
        val now   = System.currentTimeMillis()
        val start = (now / 300_000L) * 300_000L
        val end   = start + 300_000L
        return getAlerts(context).any {
            it.symbol == symbol &&
                    it.timeframe == "5M" &&
                    it.timestamp in start until end
        }
    }

    // Toate alertele 5M din fereastra curenta de 5m (pentru batch notificare)
    fun getAlertsInCurrentPeriod5m(context: Context): List<VolumeAlert> {
        val now   = System.currentTimeMillis()
        val start = (now / 300_000L) * 300_000L
        val end   = start + 300_000L
        return getAlerts(context).filter {
            it.timeframe == "5M" && it.timestamp in start until end
        }
    }

    fun countAlertsInLatest5m(context: Context): Int {
        val alerts = getAlerts(context).filter { it.timeframe == "5M" }
        if (alerts.isEmpty()) return 0
        val latest = (alerts.maxOf { it.timestamp } / 300_000L) * 300_000L
        return alerts.count { it.timestamp in latest until latest + 300_000L }
    }

    // ── Multipliers 5M & 1H ───────────────────────────────────────────────────
    private const val KEY_MULT_5M_6H  = "mult_5m_6h"
    private const val KEY_MULT_5M_24H = "mult_5m_24h"
    private const val KEY_MULT_1H_6H  = "mult_1h_6h"
    private const val KEY_MULT_1H_24H = "mult_1h_24h"

    fun getMultiplier5m6h(context: Context): Double {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_MULT_5M_6H, 1.0f).toDouble()
    }

    fun setMultiplier5m6h(context: Context, value: Double) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_MULT_5M_6H, value.toFloat()).apply()
    }

    fun getMultiplier5m24h(context: Context): Double {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_MULT_5M_24H, 1.0f).toDouble()
    }

    fun setMultiplier5m24h(context: Context, value: Double) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_MULT_5M_24H, value.toFloat()).apply()
    }

    fun getMultiplier1h6h(context: Context): Double {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_MULT_1H_6H, 10.0f).toDouble()
    }

    fun setMultiplier1h6h(context: Context, value: Double) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_MULT_1H_6H, value.toFloat()).apply()
    }

    fun getMultiplier1h24h(context: Context): Double {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_MULT_1H_24H, 5.0f).toDouble()
    }

    fun setMultiplier1h24h(context: Context, value: Double) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_MULT_1H_24H, value.toFloat()).apply()
    }

    private fun saveAlerts(context: Context, alerts: List<VolumeAlert>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ALERTS, Gson().toJson(alerts)).apply()
    }
}