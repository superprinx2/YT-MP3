package com.gabo.ytmp3

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/** Hoja de acciones inferior al estilo iOS (UIAlertController .actionSheet). */
object ActionSheet {

    fun show(
        ctx: Context,
        title: String?,
        options: List<String>,
        selected: Int = -1,
        destructive: Set<Int> = emptySet(),
        onPick: (Int) -> Unit,
    ) {
        val d = Dialog(ctx)
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt() }
        val blue = ContextCompat.getColor(ctx, R.color.ios_blue)
        val red = ContextCompat.getColor(ctx, R.color.ios_red)
        val sep = ContextCompat.getColor(ctx, R.color.ios_separator)
        val regular = ResourcesCompat.getFont(ctx, R.font.inter_regular)
        val semibold = ResourcesCompat.getFont(ctx, R.font.inter_semibold)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), 0, dp(8f), dp(8f))
        }
        val group = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            clipToOutline = true
        }

        fun divider() = View(ctx).apply {
            setBackgroundColor(sep)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(0.5f))
        }

        if (title != null) {
            group.addView(TextView(ctx).apply {
                text = title
                typeface = semibold
                setTextColor(ContextCompat.getColor(ctx, R.color.ios_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            })
            group.addView(divider())
        }
        options.forEachIndexed { i, label ->
            if (i > 0) group.addView(divider())
            group.addView(TextView(ctx).apply {
                text = if (i == selected) "$label  ✓" else label
                typeface = if (i == selected) semibold else regular
                setTextColor(if (i in destructive) red else blue)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                gravity = Gravity.CENTER
                minHeight = dp(56f)
                setBackgroundResource(R.drawable.bg_sheet_option)
                setOnClickListener { d.dismiss(); onPick(i) }
            })
        }
        root.addView(group)

        root.addView(TextView(ctx).apply {
            text = "Cancelar"
            typeface = semibold
            setTextColor(blue)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            gravity = Gravity.CENTER
            minHeight = dp(56f)
            setBackgroundResource(R.drawable.bg_row_single)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) }
            setOnClickListener { d.dismiss() }
        })

        d.setContentView(root)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.35f)
        }
        d.show()
    }
}
