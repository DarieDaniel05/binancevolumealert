package com.example.binancevolumealert

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

sealed class AlertListItem {
    data class Header(val label: String) : AlertListItem()
    data class Row(val alert: VolumeAlert) : AlertListItem()
}

private const val TYPE_HEADER = 0
private const val TYPE_ROW    = 1

class AlertAdapter(
    private val timeframeFilter: String  // "1H" sau "5M"
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<AlertListItem> = emptyList()

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLabel: TextView = view.findViewById(R.id.tvHeaderLabel)
    }

    class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIcon:    TextView = view.findViewById(R.id.tvIcon)
        val tvSymbol:  TextView = view.findViewById(R.id.tvSymbol)
        val tvStars:   TextView = view.findViewById(R.id.tvStars)
        val tvVolumes: TextView = view.findViewById(R.id.tvVolumes)
        val tvTime:    TextView = view.findViewById(R.id.tvTime)
        val tvRatio:   TextView = view.findViewById(R.id.tvRatio)
    }

    fun submitAlerts(allAlerts: List<VolumeAlert>): Int {
        val filtered = allAlerts
            .filter { (it.timeframe ?: "1H") == timeframeFilter }
            .sortedByDescending { it.timestamp }
        items = buildSections(filtered)
        notifyDataSetChanged()
        return filtered.size
    }

    private fun buildSections(alerts: List<VolumeAlert>): List<AlertListItem> {
        val result   = mutableListOf<AlertListItem>()
        val sdf      = SimpleDateFormat("HH:mm", Locale.getDefault())
        var lastLabel: String? = null

        for (alert in alerts) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = alert.timestamp
                if (timeframeFilter == "5M") {
                    val m = get(Calendar.MINUTE)
                    set(Calendar.MINUTE, (m / 5) * 5)
                } else {
                    set(Calendar.MINUTE, 0)
                }
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val time  = sdf.format(cal.time)
            val label = if (timeframeFilter == "1H") "ORA $time" else time

            if (label != lastLabel) {
                result.add(AlertListItem.Header(label))
                lastLabel = label
            }
            result.add(AlertListItem.Row(alert))
        }
        return result
    }

    override fun getItemViewType(p: Int) = when (items[p]) {
        is AlertListItem.Header -> TYPE_HEADER
        is AlertListItem.Row    -> TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER)
            HeaderViewHolder(inf.inflate(R.layout.item_header, parent, false))
        else
            RowViewHolder(inf.inflate(R.layout.item_alert, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is AlertListItem.Header -> (holder as HeaderViewHolder).tvLabel.text = item.label
            is AlertListItem.Row    -> bindRow(holder as RowViewHolder, item.alert)
        }
    }

    private fun bindRow(h: RowViewHolder, a: VolumeAlert) {
        val density     = h.itemView.context.resources.displayMetrics.density
        val symbolClean = a.symbol.replace("USDT", "")
        val ratioColor  = ratioColor(a.ratio)
        val alpha10     = (ratioColor and 0x00FFFFFF) or (0x1A shl 24)
        val alpha35     = (ratioColor and 0x00FFFFFF) or (0x59 shl 24)

        h.tvSymbol.text  = a.symbol
        h.tvIcon.text    = symbolClean.take(4)
        h.tvVolumes.text = "${formatVolume(a.prevVolume)} → ${formatVolume(a.currVolume)}"
        h.tvTime.text    = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(a.timestamp)
        h.tvRatio.text   = "x%.1f".format(a.ratio)

        h.tvIcon.background  = roundedDrawable(alpha10, alpha35, 8f * density, density)
        h.tvIcon.setTextColor(ratioColor)
        h.tvRatio.background = roundedDrawable(alpha10, alpha35, 20f * density, density)
        h.tvRatio.setTextColor(ratioColor)

        if (a.stars > 0) {
            h.tvStars.text       = "★".repeat(a.stars)
            h.tvStars.setTextColor(starsColor(a.stars))
            h.tvStars.visibility = View.VISIBLE
        } else {
            h.tvStars.visibility = View.GONE
        }
    }

    private fun starsColor(s: Int): Int = Color.parseColor(
        when (s) { 4 -> "#FF4D4D"; 3 -> "#FF8C00"; 2 -> "#FFD700"; else -> "#8B949E" }
    )

    private fun ratioColor(r: Double): Int = Color.parseColor(
        when { r >= 50.0 -> "#FF4D4D"; r >= 25.0 -> "#FF8C00"; r >= 15.0 -> "#FFD700"; else -> "#00E676" }
    )

    private fun roundedDrawable(fill: Int, stroke: Int, radius: Float, density: Float) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = radius
            setColor(fill); setStroke((1f * density).toInt(), stroke)
        }

    override fun getItemCount() = items.size

    private fun formatVolume(v: Double): String = when {
        v >= 1_000_000 -> "%.1fM".format(v / 1_000_000)
        v >= 1_000     -> "%.1fK".format(v / 1_000)
        else           -> v.toLong().toString()
    }
}