package com.gabo.ytmp3

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.gabo.ytmp3.databinding.ItemFooterBinding
import com.gabo.ytmp3.databinding.ItemVideoBigBinding
import com.gabo.ytmp3.databinding.ItemVideoBinding

/**
 * Lista de videos como "grouped inset list" de iOS, con miniaturas.
 * Si hay un solo video se muestra como tarjeta grande.
 */
class VideoAdapter(
    private val items: List<VideoItem>,
    private val onToggle: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var locked = false   // durante la descarga no se puede cambiar la selección

    private class Row(val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root)
    private class Big(val b: ItemVideoBigBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int) = if (items.size == 1) 2 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 2) Big(ItemVideoBigBinding.inflate(inf, parent, false))
               else Row(ItemVideoBinding.inflate(inf, parent, false))
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        val it = items[position]
        when (h) {
            is Big -> bindBig(h.b, it)
            is Row -> bindRow(h, it, position)
        }
    }

    private fun bindStatus(it: VideoItem, sub: TextView, bar: ProgressBar, fallback: String) {
        val ctx = sub.context
        val (text, colorRes) = when (it.status) {
            Status.PENDING -> (if (it.phase.isNotEmpty()) it.phase else fallback) to R.color.ios_secondary
            Status.DOWNLOADING -> {
                val sp = if (it.speedBps > 0) "  ·  ${Util.fmtSpeed(it.speedBps)}" else ""
                val ph = if (it.phase == "Descargando" || it.phase.isEmpty()) "${it.progress.toInt()} %$sp" else it.phase
                ph to R.color.ios_blue
            }
            Status.DONE -> "✓ ${it.savedAs ?: "Guardado"}" to R.color.ios_green
            Status.ERROR -> "⚠︎ ${it.error ?: "Error"}" to R.color.ios_red
        }
        sub.text = text
        sub.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        sub.setTextColor(ContextCompat.getColor(ctx, colorRes))
        bar.visibility = if (it.status == Status.DOWNLOADING) View.VISIBLE else View.GONE
        bar.progress = (it.progress * 10).toInt()
    }

    private fun bindBig(b: ItemVideoBigBinding, it: VideoItem) {
        b.title.text = it.title
        b.duration.text = Util.fmtDuration(it.duration)
        b.duration.visibility = if (b.duration.text.isEmpty()) View.GONE else View.VISIBLE
        loadThumb(b.thumb, it.thumbnailHq, 0f)
        bindStatus(it, b.subtitle, b.miniProgress, "Listo para descargar")
    }

    private fun bindRow(h: Row, it: VideoItem, position: Int) {
        val b = h.b
        val n = items.size
        b.root.setBackgroundResource(when {
            position == 0 -> R.drawable.bg_row_top
            position == n - 1 -> R.drawable.bg_row_bottom
            else -> R.drawable.bg_row_mid
        })
        b.separator.visibility = if (position == n - 1) View.GONE else View.VISIBLE
        b.title.text = "${position + 1}. ${it.title}"
        b.check.isSelected = it.checked
        b.check.alpha = if (locked) 0.45f else 1f
        loadThumb(b.thumb, it.thumbnail, 8f)
        b.thumb.alpha = if (it.checked || it.status != Status.PENDING) 1f else 0.5f
        bindStatus(it, b.subtitle, b.miniProgress, Util.fmtDuration(it.duration))

        b.root.setOnClickListener { v ->
            if (locked) return@setOnClickListener
            v.haptic()
            it.checked = !it.checked
            b.check.isSelected = it.checked
            b.check.animate().cancel()
            b.check.scaleX = 0.6f; b.check.scaleY = 0.6f
            b.check.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(android.view.animation.OvershootInterpolator(4f)).start()
            b.thumb.alpha = if (it.checked) 1f else 0.5f
            onToggle()
        }
    }

    private fun loadThumb(iv: ImageView, url: String?, radiusDp: Float) {
        if (iv.tag == url && url != null) return
        iv.tag = url
        if (url == null) { iv.setImageDrawable(null); return }
        iv.load(url) {
            crossfade(true)
            if (radiusDp > 0) {
                val px = radiusDp * iv.resources.displayMetrics.density
                transformations(RoundedCornersTransformation(px))
            }
        }
    }
}

/** Adaptador de un solo elemento para poner la cabecera dentro del RecyclerView. */
class HeaderAdapter(private val view: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        object : RecyclerView.ViewHolder(view) {}
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
    override fun getItemCount() = 1
    override fun getItemViewType(position: Int) = 1001
}

/** Créditos al final de la lista. */
class FooterAdapter(private val onCredit: (View) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val b = ItemFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        b.credit.setOnClickListener(onCredit)
        return object : RecyclerView.ViewHolder(b.root) {}
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
    override fun getItemCount() = 1
    override fun getItemViewType(position: Int) = 1002
}
