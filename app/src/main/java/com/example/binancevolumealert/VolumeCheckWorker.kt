package com.example.binancevolumealert

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import kotlin.math.abs

data class FoundCoin(
    val symbol: String,
    val ratio: Double,
    val stars: Int,
    val notifyWorthy: Boolean
)

class VolumeCheckWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        const val WORK_TAG = "volume_check"
        private const val MAX_CONCURRENT = 10
    }

    private val api: BinanceApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://fapi.binance.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BinanceApi::class.java)
    }

    override suspend fun doWork(): Result {
        return try {
            AlertStorage.pruneOldAlerts(applicationContext)

            val symbols = api.getExchangeInfo().symbols
                .filter { it.status == "TRADING" && it.quoteAsset == "USDT" && it.contractType == "PERPETUAL" }
                .map { it.symbol }

            val semaphore = Semaphore(MAX_CONCURRENT)

            // Proceseaza toate monedele in paralel
            // processSymbol adauga in lista doar monedele noi (dedup per ora)
            coroutineScope {
                symbols.map { symbol ->
                    async {
                        semaphore.withPermit { processSymbol(symbol) }
                    }
                }.awaitAll()
            }

            // Dupa ce s-au terminat TOATE scanarile, notificam cu TOT ce s-a acumulat
            // in ora curenta — inclusiv monede gasite la scanari anterioare (TEST sau alarma)
            // hourId constant in ora = notificarea se ACTUALIZEAZA, nu se dubleaza
            val currentHourAlerts = AlertStorage.getAlertsInCurrentHour(applicationContext)
            if (currentHourAlerts.isNotEmpty()) {
                val coinsToNotify = currentHourAlerts
                    .sortedByDescending { it.ratio }
                    .map { FoundCoin(it.symbol, it.ratio, it.stars, true) }
                NotificationHelper.showBatchAlert(applicationContext, coinsToNotify)
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private suspend fun processSymbol(symbol: String) {
        try {
            val klines = api.getKlines(symbol, "1h", 28)
            if (klines.size < 28) return

            val hourMinus2Volume = (klines[24][5] as String).toDouble()
            val hourMinus1Volume = (klines[25][5] as String).toDouble()
            val currentVolume    = (klines[26][5] as String).toDouble()

            val totalHoursVolume = hourMinus1Volume + hourMinus2Volume / 2.0

            val avg6h = (20..25).sumOf {
                (klines[it][5] as String).toDouble()
            } / 6.0

            val avg24h = (0 until 24).sumOf {
                (klines[it][5] as String).toDouble()
            } / 24.0

            // fix: (3..26) include si ora curenta H in suma zilnica
            val dailyUsdt = (3..26).sumOf { i ->
                (klines[i][7] as String).toDouble()
            }

            val openPrice  = (klines[26][1] as String).toDouble()
            val closePrice = (klines[26][4] as String).toDouble()

            val mult6h = AlertStorage.getMultiplier1h6h(applicationContext)
            val mult24h = AlertStorage.getMultiplier1h24h(applicationContext)

            // ── Filtre obligatorii ────────────────────────────────────────
            if (currentVolume < avg24h * mult24h || currentVolume < avg6h * mult6h) return
            if (dailyUsdt <= 6_666_666) return
            if (currentVolume < totalHoursVolume * 3) return
            if (openPrice > closePrice) return

            // dedup: nu adauga aceeasi moneda de doua ori in aceeasi ora
            // (dar notificarea se construieste din getAlertsInCurrentHour, deci o vede oricum)
            if (AlertStorage.isSymbolInCurrentHour(applicationContext, symbol)) return

            // ── Calcul stele ──────────────────────────────────────────────
            val avg6hQuiet = (20..25).sumOf { i ->
                (klines[i][5] as String).toDouble()
            } / 6.0
            val pricePct = if (openPrice > 0) abs((closePrice - openPrice) / openPrice) else 0.0

            var stars = 0
            if (avg24h > 0 && avg6hQuiet < avg24h * 0.3) stars++
            if (pricePct >= 0.03) stars++
            if (dailyUsdt in 1.0..50_000_000.0) stars++
            if (hourMinus2Volume > 0 &&
                hourMinus2Volume < hourMinus1Volume &&
                hourMinus1Volume < currentVolume) stars++
            // ─────────────────────────────────────────────────────────────

            val ratio = currentVolume / avg6h

            AlertStorage.addAlert(
                applicationContext,
                VolumeAlert(symbol, avg6h, currentVolume, ratio, System.currentTimeMillis(), stars)
            )

        } catch (e: Exception) {
            // simbol esuat -> continuam
        }
    }
}