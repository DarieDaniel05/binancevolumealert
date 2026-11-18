package com.example.binancevolumealert

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class VolumeCheck5mWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        const val WORK_TAG = "volume_check_5m"

        // 2 request-uri per simbol → concurenta mai mica fata de inainte
        private const val MAX_CONCURRENT = 8

        @Volatile private var cachedSymbols: List<String>? = null
        @Volatile private var symbolsCachedAt: Long = 0
        private const val SYMBOLS_TTL = 30 * 60 * 1000L

        private val okHttpClient = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val api: BinanceApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://fapi.binance.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BinanceApi::class.java)
    }

    override suspend fun doWork(): Result {
        return try {
            AlertStorage.pruneOldAlerts5m(applicationContext)

            // Request 1: toti tickerii — un singur request bulk
            val tickerMap = api.getAllTickers().associateBy { it.symbol }

            // Lista simboluri cu cache
            val allSymbols = getSymbolsCached()

            // Pre-filtrare rapida dupa volum zilnic
            // Pastram un interval larg — nu vrem sa excludem monede in timpul pump-ului
            val candidates = allSymbols.filter { symbol ->
                val daily = tickerMap[symbol]?.quoteVolume?.toDoubleOrNull() ?: 0.0
                daily in 1_000_000.0..50_000_000.0  // intre 1M si 50M USDT
            }

            val semaphore = Semaphore(MAX_CONCURRENT)

            coroutineScope {
                candidates.map { symbol ->
                    async {
                        semaphore.withPermit {
                            processSymbol5m(symbol, tickerMap[symbol])
                        }
                    }
                }.awaitAll()
            }

            val current5mAlerts = AlertStorage.getAlertsInCurrentPeriod5m(applicationContext)
            if (current5mAlerts.isNotEmpty()) {
                val coins = current5mAlerts
                    .sortedByDescending { it.ratio }
                    .map { FoundCoin(it.symbol, it.ratio, it.stars, true) }
                NotificationHelper.showBatchAlert(applicationContext, coins, "5M")
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private suspend fun getSymbolsCached(): List<String> {
        val now = System.currentTimeMillis()
        val cached = cachedSymbols
        if (cached != null && (now - symbolsCachedAt) < SYMBOLS_TTL) return cached
        val fresh = api.getExchangeInfo().symbols
            .filter { it.status == "TRADING" && it.quoteAsset == "USDT" && it.contractType == "PERPETUAL" }
            .map { it.symbol }
        cachedSymbols   = fresh
        symbolsCachedAt = now
        return fresh
    }

    private suspend fun processSymbol5m(symbol: String, ticker: TickerData?) {
        try {
            // ── Request A: candela 5m curenta (inchisa) ───────────────────────
            // limit=2: klines5m[0] = ultima inchisa, klines5m[1] = deschisa (ignorata)
            val klines5m = api.getKlines(symbol, "5m", 2)
            if (klines5m.size < 2) return

            val currentVol5m = (klines5m[0][5] as String).toDouble()
            val openPrice    = (klines5m[0][1] as String).toDouble()
            val closePrice   = (klines5m[0][4] as String).toDouble()
            val highPrice    = (klines5m[0][2] as String).toDouble()
            val lowPrice     = (klines5m[0][3] as String).toDouble()

            // Verificare rapida inainte de al doilea request —
            // daca volumul e 0 nu are rost sa facem request-ul de 1h
            if (currentVol5m <= 0) return

            // ── Request B: ultimele 25 candele de 1h ──────────────────────────
            // limit=25: klines1h[0..23] = 24 candele inchise, klines1h[24] = deschisa (ignorata)
            val klines1h = api.getKlines(symbol, "1h", 25)
            if (klines1h.size < 25) return

            // avg6h  = media ultimelor 6 candele de 1h inchise [18..23]
            val avg6h = (18..23).sumOf { i ->
                (klines1h[i][5] as String).toDouble()
            } / 6.0

            // avg24h = media ultimelor 24 candele de 1h inchise [0..23]
            val avg24h = (0..23).sumOf { i ->
                (klines1h[i][5] as String).toDouble()
            } / 24.0

            if (avg6h <= 0 || avg24h <= 0) return

            // ── Conditii principale ───────────────────────────────────────────
            // Candela 5m curenta > 3x media ultimelor 6 ore
            if (currentVol5m < avg6h * 1) return

            // Candela 5m curenta > 6x media ultimelor 24 de ore
            if (currentVol5m < avg24h * 1) return

            // Volum zilnic: eliminam monedele cu lichiditate prea mare
            val dailyUsdt = ticker?.quoteVolume?.toDoubleOrNull() ?: 0.0
            if (dailyUsdt !in 1_000_000.0..50_000_000.0) return
            // apare doar daca candela este verde
            if (closePrice < openPrice) return
            // Dedup: nu adauga aceeasi moneda de doua ori in aceeasi fereastra de 5m
            if (AlertStorage.isSymbolInCurrentPeriod5m(applicationContext, symbol)) return

            // ── Calcul stele ──────────────────────────────────────────────────
            val body  = abs(closePrice - openPrice)
            val range = highPrice - lowPrice

            var stars = 0

            // ★1 — Spike foarte puternic vs 6h: >6x (dublu fata de pragul minim de 3x)
            if (currentVol5m >= avg6h * 6) stars++

            // ★2 — Spike foarte puternic vs 24h: >12x (dublu fata de pragul minim de 6x)
            if (currentVol5m >= avg24h * 12) stars++

            // ★3 — Candela verde (directia e de cumparare)
            if (closePrice > openPrice) stars++

            // ★4 — Corp puternic >= 60% din range (nu e lichidare cu fitiluri)
            if (range > 0 && body / range >= 0.60) stars++
            // ─────────────────────────────────────────────────────────────────

            // Ratio principal: cat de mare e volumul 5m fata de avg6h (mai relevant)
            val ratio = currentVol5m / avg6h

            AlertStorage.addAlert(
                applicationContext,
                VolumeAlert(
                    symbol     = symbol,
                    prevVolume = avg6h,
                    currVolume = currentVol5m,
                    ratio      = ratio,
                    timestamp  = System.currentTimeMillis(),
                    stars      = stars,
                    timeframe  = "5M"
                )
            )

        } catch (e: Exception) {
            // simbol esuat -> continuam
        }
    }
}