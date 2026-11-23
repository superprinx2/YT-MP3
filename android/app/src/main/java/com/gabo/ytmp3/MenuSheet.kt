package com.gabo.ytmp3

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog

/** Menú de opciones al estilo Spotify: hoja que sube desde abajo, cada opción con ícono, título y una línea de ayuda. */
object MenuSheet {
    class Item(val icon: Int, val title: String, val subtitle: String? = null, val accent: Boolean = false, val action: () -> Unit)

    fun show(ctx: Context, title: String, subtitle: String? = null, items: List<Item>) {
        val d = BottomSheetDialog(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.dp(10), 0, ctx.dp(24))
            background = GradientDrawable().apply {
                setColor(0xFF242424.toInt())
                cornerRadii = floatArrayOf(ctx.dp(18).toFloat(), ctx.dp(18).toFloat(), ctx.dp(18).toFloat(), ctx.dp(18).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        // asa
        col.addView(View(ctx).apply { background = roundRect(0x66FFFFFF, ctx.dp(3).toFloat()) },
            LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ctx.dp(14) })
        // encabezado
        col.addView(ctx.text(title, 18f, Color.WHITE, 2).apply { setPadding(ctx.dp(22), 0, ctx.dp(22), 0) })
        if (subtitle != null) col.addView(ctx.text(subtitle, 13f, 0xFFB3B3B3.toInt(), 0, 2).apply { setPadding(ctx.dp(22), ctx.dp(2), ctx.dp(22), 0) })
        col.addView(View(ctx).apply { setBackgroundColor(0x1FFFFFFF) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = ctx.dp(14); bottomMargin = ctx.dp(6) })

        for (it in items) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(22), ctx.dp(12), ctx.dp(22), ctx.dp(12))
                isClickable = true; isFocusable = true
                val attrs = intArrayOf(android.R.attr.selectableItemBackground)
                val ta = ctx.obtainStyledAttributes(attrs); foreground = ta.getDrawable(0); ta.recycle()
                setOnClickListener { v -> v.haptic(); d.dismiss(); android.os.Handler(android.os.Looper.getMainLooper()).post { it.action() } }
            }
            val tint = if (it.accent) 0xFF1ED760.toInt() else Color.WHITE
            row.addView(ImageView(ctx).apply {
                setImageResource(it.icon); setColorFilter(tint)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(ctx.dp(26), ctx.dp(26)))
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(18), 0, 0, 0) }
            texts.addView(ctx.text(it.title, 16f, tint, 1))
            if (it.subtitle != null) texts.addView(ctx.text(it.subtitle, 12.5f, 0xFFA7A7A7.toInt(), 0, 2).apply { setPadding(0, ctx.dp(2), 0, 0) })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(row)
        }
        d.setContentView(col)
        d.setOnShowListener {
            d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
        }
        d.show()
    }
}
