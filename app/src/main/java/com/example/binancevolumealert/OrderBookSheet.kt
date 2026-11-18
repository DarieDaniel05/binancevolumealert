package com.example.binancevolumealert

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class OrderBookSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_SYMBOL = "symbol"
        private const val LEVELS = 16

        fun newInstance(symbol: String) = OrderBookSheet().apply {
            arguments = Bundle().apply { putString(ARG_SYMBOL, symbol) }
        }
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var futuresWs: WebSocket? = null
    private var currentSymbol = ""

    private data class OrderRow(
        val barContainer: LinearLayout,
        val depthBar: View,
        val spacer: View,
        val tvPrice: TextView,
        val tvQty: TextView
    )

    private val askRows = mutableListOf<OrderRow>()
    private val bidRows = mutableListOf<OrderRow>()

    override fun getTheme() = R.style.BottomSheetTheme

    override fun onStart() {
        super.onStart()
        dialog?.let { d ->
            val sheet = d.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return
            sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_order_book_sheet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        currentSymbol = arguments?.getString(ARG_SYMBOL) ?: return

        view.findViewById<TextView>(R.id.tvSheetSymbol).text =
            currentSymbol.replace("USDT", "") + " / USDT PERP"
        view.findViewById<View>(R.id.btnCloseSheet).setOnClickListener { dismiss() }

        buildRows(view)
        fetchMetadata(currentSymbol, view)
        startWebSocket(view)
    }

    // ── Construieste randurile cu weight-based depth bars ─────────────────────
    private fun buildRows(view: View) {
        val asksLayout = view.findViewById<LinearLayout>(R.id.layoutAsks)
        val bidsLayout = view.findViewById<LinearLayout>(R.id.layoutBids)
        repeat(LEVELS) { askRows.add(createRow(asksLayout, isAsk = true)) }
        repeat(LEVELS) { bidRows.add(createRow(bidsLayout, isAsk = false)) }
    }

    private fun createRow(parent: LinearLayout, isAsk: Boolean): OrderRow {
        val dp   = parent.context.resources.displayMetrics.density
        val padH = (16 * dp).toInt()

        // FrameLayout: depth bar (fundal) + text (deasupra)
        val frame = FrameLayout(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (26 * dp).toInt()
            )
        }

        // LinearLayout cu weights — Android calculeaza latimea automat, fara pixeli manuali
        val barContainer = LinearLayout(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.HORIZONTAL
            weightSum   = 1f
        }

        val depthBar = View(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.1f)
            setBackgroundColor(
                if (isAsk) Color.argb(40, 239, 68, 68)
                else       Color.argb(40, 0, 230, 118)
            )
        }

        val spacer = View(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
        }

        // Asks: spacer stanga, bar dreapta (bara creste din dreapta spre stanga)
        // Bids: bar stanga, spacer dreapta (bara creste din stanga spre dreapta)
        if (isAsk) { barContainer.addView(spacer); barContainer.addView(depthBar) }
        else       { barContainer.addView(depthBar); barContainer.addView(spacer) }

        // Textul deasupra
        val textRow = LinearLayout(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(padH, 0, padH, 0)
        }

        val tvPrice = TextView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
            setTextColor(if (isAsk) Color.parseColor("#EF4444") else Color.parseColor("#00E676"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }

        val tvQty = TextView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(Color.parseColor("#64748B"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity  = Gravity.END
        }

        textRow.addView(tvPrice)
        textRow.addView(tvQty)
        frame.addView(barContainer)
        frame.addView(textRow)
        parent.addView(frame)

        return OrderRow(barContainer, depthBar, spacer, tvPrice, tvQty)
    }

    // ── WebSocket Binance Futures ──────────────────────────────────────────────
    private fun startWebSocket(view: View) {
        val sym = currentSymbol.lowercase()

        futuresWs = client.newWebSocket(
            Request.Builder()
                .url("wss://fstream.binance.com/ws/$sym@depth20@100ms")
                .build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, r: Response) =
                    setStatus(view, "● LIVE", Color.parseColor("#00E676"))

                override fun onMessage(ws: WebSocket, text: String) =
                    parseAndRender(text, view)

                override fun onClosing(ws: WebSocket, code: Int, reason: String) =
                    setStatus(view, "● Închis", Color.parseColor("#64748B"))

                override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                    setStatus(view, "● Reconectare...", Color.parseColor("#FF8C00"))
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isAdded) startWebSocket(view)
                    }, 3000)
                }
            }
        )
    }

    private fun parseAndRender(json: String, view: View) {
        try {
            val obj = JsonParser.parseString(json).asJsonObject

            // ── FIX PRINCIPAL ─────────────────────────────────────────────────
            // Binance Futures depth stream foloseste "a" (asks) si "b" (bids)
            // Binance Spot foloseste "asks" si "bids" — complet diferit!
            val asksRaw = obj.getAsJsonArray("a") ?: return
            val bidsRaw = obj.getAsJsonArray("b") ?: return
            // ─────────────────────────────────────────────────────────────────

            // Asks: cel mai MARE pret sus, best ask (cel mai MIC) jos → langa spread
            val asks = asksRaw
                .map { it.asJsonArray.let { a -> a[0].asDouble to a[1].asDouble } }
                .filter { it.second > 0 }
                .sortedByDescending { it.first }
                .take(LEVELS)

            // Bids: best bid (cel mai MARE) sus → langa spread, cel mai mic jos
            val bids = bidsRaw
                .map { it.asJsonArray.let { a -> a[0].asDouble to a[1].asDouble } }
                .filter { it.second > 0 }
                .sortedByDescending { it.first }
                .take(LEVELS)

            if (asks.isEmpty() || bids.isEmpty()) return

            val maxAsk = asks.maxOf { it.second }
            val maxBid = bids.maxOf { it.second }

            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread

                fillRows(askRows, asks, maxAsk, isAsk = true)
                fillRows(bidRows, bids, maxBid, isAsk = false)

                val bestBid = bids.first().first
                val bestAsk = asks.last().first

                view.findViewById<TextView>(R.id.tvLastPrice)?.apply {
                    text = fmtPrice(bestBid)
                    setTextColor(Color.parseColor("#00E676"))
                }

                if (bestBid > 0 && bestAsk > 0) {
                    val spreadPct = (bestAsk - bestBid) / bestBid * 100
                    view.findViewById<TextView>(R.id.tvSpread)?.text =
                        "spread ${"%.4f".format(spreadPct)}%"
                }
            }
        } catch (_: Exception) {}
    }

    private fun fillRows(
        rows: List<OrderRow>,
        levels: List<Pair<Double, Double>>,
        maxQty: Double,
        isAsk: Boolean
    ) {
        rows.forEachIndexed { i, row ->
            if (i < levels.size) {
                val (price, qty) = levels[i]
                row.tvPrice.text = fmtPrice(price)
                row.tvQty.text   = fmtQty(qty)

                val ratio = (qty / maxQty).coerceIn(0.02, 1.0)
                val alpha = (ratio * 55 + 15).toInt().coerceIn(15, 70)

                // Actualizeaza weights — Android recalculeaza latimea automat
                (row.depthBar.layoutParams as LinearLayout.LayoutParams).weight = ratio.toFloat()
                row.depthBar.layoutParams = row.depthBar.layoutParams

                (row.spacer.layoutParams as LinearLayout.LayoutParams).weight = (1.0 - ratio).toFloat()
                row.spacer.layoutParams = row.spacer.layoutParams

                row.depthBar.setBackgroundColor(
                    if (isAsk) Color.argb(alpha, 239, 68, 68)
                    else       Color.argb(alpha, 0, 230, 118)
                )
            } else {
                row.tvPrice.text = ""
                row.tvQty.text   = ""
                (row.depthBar.layoutParams as LinearLayout.LayoutParams).weight = 0f
                row.depthBar.layoutParams = row.depthBar.layoutParams
                (row.spacer.layoutParams as LinearLayout.LayoutParams).weight = 1f
                row.spacer.layoutParams = row.spacer.layoutParams
            }
        }
    }

    // ── Metadata REST ─────────────────────────────────────────────────────────
    private fun fetchMetadata(symbol: String, view: View) {
        Thread {
            try {
                val futJson   = URL("https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=$symbol").readText()
                val futChange = JSONObject(futJson).optString("priceChangePercent", "0").toDoubleOrNull() ?: 0.0

                val premJson  = URL("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=$symbol").readText()
                val premObj   = JSONObject(premJson)
                val funding   = (premObj.optString("lastFundingRate", "0").toDoubleOrNull() ?: 0.0) * 100
                val markPrice = premObj.optString("markPrice",  "0").toDoubleOrNull() ?: 0.0
                val idxPrice  = premObj.optString("indexPrice", "0").toDoubleOrNull() ?: 0.0
                val basis     = if (idxPrice > 0) (markPrice - idxPrice) / idxPrice * 100 else 0.0

                var spotChange: Double? = null
                try {
                    val conn = URL("https://api.binance.com/api/v3/ticker/24hr?symbol=$symbol")
                        .openConnection() as HttpURLConnection
                    if (conn.responseCode == 200) {
                        spotChange = JSONObject(conn.inputStream.bufferedReader().readText())
                            .optString("priceChangePercent", "0").toDoubleOrNull()
                    }
                    conn.disconnect()
                } catch (_: Exception) {}

                val colPos = Color.parseColor("#00E676")
                val colNeg = Color.parseColor("#EF4444")

                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread

                    val layoutCorrelation = view.findViewById<View>(R.id.layoutCorrelation)
                    if (spotChange != null) {
                        layoutCorrelation?.visibility = View.VISIBLE
                        view.findViewById<TextView>(R.id.tvSpotChange)?.apply {
                            text = "${if (spotChange >= 0) "+" else ""}${"%.2f".format(spotChange)}%"
                            setTextColor(if (spotChange >= 0) colPos else colNeg)
                        }
                        view.findViewById<TextView>(R.id.tvFuturesChange)?.apply {
                            text = "${if (futChange >= 0) "+" else ""}${"%.2f".format(futChange)}%"
                            setTextColor(if (futChange >= 0) colPos else colNeg)
                        }
                    } else {
                        layoutCorrelation?.visibility = View.GONE
                    }

                    view.findViewById<TextView>(R.id.tvFundingRate)?.apply {
                        text = "Funding/8h: ${if (funding >= 0) "+" else ""}${"%.4f".format(funding)}%"
                        setTextColor(if (funding >= 0) colPos else colNeg)
                    }
                    view.findViewById<TextView>(R.id.tvBasis)?.apply {
                        text = "Basis: ${if (basis >= 0) "+" else ""}${"%.3f".format(basis)}%"
                        setTextColor(if (basis >= 0) colPos else colNeg)
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    // ── Utils ─────────────────────────────────────────────────────────────────
    private fun setStatus(view: View, msg: String, color: Int) {
        activity?.runOnUiThread {
            if (isAdded) view.findViewById<TextView>(R.id.tvConnectionStatus)?.apply {
                text = msg; setTextColor(color)
            }
        }
    }

    private fun fmtPrice(p: Double): String = when {
        p >= 10_000 -> "%.1f".format(p)
        p >= 100    -> "%.2f".format(p)
        p >= 1      -> "%.4f".format(p)
        p >= 0.001  -> "%.6f".format(p)
        else        -> "%.8f".format(p)
    }

    private fun fmtQty(q: Double): String = when {
        q >= 1_000_000 -> "%.1fM".format(q / 1_000_000)
        q >= 1_000     -> "%.1fK".format(q / 1_000)
        q >= 1         -> "%.2f".format(q)
        else           -> "%.4f".format(q)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        futuresWs?.cancel()
        // Nu inchidem executorService — e shared, il gestioneaza OkHttp singur
    }
}