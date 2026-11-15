package com.gabo.ytmp3

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.gabo.ytmp3.databinding.ItemVideoBinding

/** Lista de videos como "grouped inset list" de iOS: tarjeta redondeada con separadores. */
class VideoAdapter(
    private val items: List<VideoItem>,
    private val onToggle: () -> Unit,
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    var locked = false   // durante la descarga no se puede cambiar la selección

    class VH(val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        val ctx = h.itemView.context
        val n = items.size
        val single = n == 1

        h.b.root.setBackgroundResource(when {
            single -> R.drawable.bg_row_single
            position == 0 -> R.drawable.bg_row_top
            position == n - 1 -> R.drawable.bg_row_bottom
            else -> R.drawable.bg_row_mid
        })
        h.b.separator.visibility = if (position == n - 1) View.GONE else View.VISIBLE

        h.b.title.text = if (single) it.title else "${position + 1}. ${it.title}"
        h.b.check.isSelected = it.checked
        h.b.check.alpha = if (locked) 0.45f else 1f
        h.b.check.visibility = if (single) View.GONE else View.VISIBLE
        h.b.textBox.updateMarginStart(if (single) 0 else (14 * ctx.resources.displayMetrics.density).toInt())
        h.b.separator.updateMarginStart(if (single) 0 else (52 * ctx.resources.displayMetrics.density).toInt())

        val dur = Util.fmtDuration(it.duration)
        val (text, colorRes) = when (it.status) {
            Status.PENDING -> dur to R.color.ios_secondary
            Status.DOWNLOADING -> "Descargando… ${it.progress.toInt()} %" to R.color.ios_blue
            Status.DONE -> "✓ ${it.savedAs ?: "Guardado"}" to R.color.ios_green
            Status.ERROR -> "⚠︎ ${it.error ?: "Error"}" to R.color.ios_red
        }
        h.b.subtitle.text = text
        h.b.subtitle.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        h.b.subtitle.setTextColor(ContextCompat.getColor(ctx, colorRes))

        h.itemView.setOnClickListener { _ ->
            if (locked || single) return@setOnClickListener
            it.checked = !it.checked
            h.b.check.isSelected = it.checked
            onToggle()
        }
    }
}

private fun View.updateMarginStart(px: Int) {
    val lp = layoutParams as ViewGroup.MarginLayoutParams
    if (lp.marginStart != px) { lp.marginStart = px; layoutParams = lp }
}

/** Adaptador de un solo elemento para poner la cabecera dentro del RecyclerView. */
class HeaderAdapter(private val view: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        object : RecyclerView.ViewHolder(view) {}
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
    override fun getItemCount() = 1
    override fun getItemViewType(position: Int) = 1001
}
