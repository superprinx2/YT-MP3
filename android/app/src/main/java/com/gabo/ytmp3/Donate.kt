package com.gabo.ytmp3

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/** Donativos: "¡Tengo hambre!" con Yape/Plin y cuentas BCP, cada una con botón Copiar. */
object Donate {
    private const val YAPE_PLIN = "924781666"
    private const val BCP_SOLES = "19198376183079"
    private const val BCP_CCI = "00219119837618307959"
    private const val YAPE_PACKAGE = "com.bcp.innovacxion.yapeapp"

    private data class Method(val emoji: String, val label: String, val shown: String, val copy: String, val color: Int)

    fun show(ctx: Context) {
        val d = Dialog(ctx)
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt() }
        val regular = ResourcesCompat.getFont(ctx, R.font.inter_regular)
        val semibold = ResourcesCompat.getFont(ctx, R.font.inter_semibold)
        val bold = ResourcesCompat.getFont(ctx, R.font.inter_bold)
        val label = ContextCompat.getColor(ctx, R.color.ios_label)
        val secondary = ContextCompat.getColor(ctx, R.color.ios_secondary)
        val blue = ContextCompat.getColor(ctx, R.color.ios_blue)
        val sep = ContextCompat.getColor(ctx, R.color.ios_separator)

        val methods = listOf(
            Method("📱", "Yape / Plin", "924 781 666", YAPE_PLIN, 0xFF742284.toInt()),
            Method("🏦", "BCP Soles", "191-98376183-0-79", BCP_SOLES, 0xFF002A8D.toInt()),
            Method("🔁", "Interbancaria (CCI)", "002-191-198376183079-59", BCP_CCI, 0xFFFF7800.toInt()),
        )

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), 0, dp(8f), dp(8f))
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(18f), dp(20f), dp(18f), dp(10f))
            clipToOutline = true
        }

        card.addView(TextView(ctx).apply {
            text = "🍔"; textSize = 46f; gravity = Gravity.CENTER
        })
        card.addView(TextView(ctx).apply {
            text = "¡Tengo hambre!"; typeface = bold; textSize = 24f; gravity = Gravity.CENTER
            setTextColor(label)
        })
        card.addView(TextView(ctx).apply {
            text = "MegaDown es gratis y sin anuncios. Si te ahorró tiempo (y anuncios), invítame un menú " +
                "y ayúdame a seguir mejorándola. ¡Cualquier monto suma! 🙏"
            typeface = regular; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(secondary)
            setPadding(0, dp(6f), 0, dp(14f))
        })

        methods.forEachIndexed { i, m ->
            if (i > 0) card.addView(View(ctx).apply {
                setBackgroundColor(sep)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(0.5f))
            })
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12f), 0, dp(12f))
            }
            row.addView(TextView(ctx).apply {
                text = m.emoji; textSize = 20f; gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((m.color and 0x00FFFFFF) or 0x26000000) }
                layoutParams = LinearLayout.LayoutParams(dp(42f), dp(42f))
            })
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12f) }
            }
            texts.addView(TextView(ctx).apply { text = m.label; typeface = semibold; textSize = 14f; setTextColor(secondary) })
            texts.addView(TextView(ctx).apply {
                text = m.shown; typeface = bold; textSize = 17f; setTextColor(label)
                setTextIsSelectable(true)
            })
            row.addView(texts)
            row.addView(TextView(ctx).apply {
                text = "Copiar"; typeface = semibold; textSize = 14f; setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(m.color) }
                setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                pressable()
                setOnClickListener { v ->
                    v.haptic()
                    ctx.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(m.label, m.copy))
                    Toast.makeText(ctx, "${m.label} copiado ✓", Toast.LENGTH_SHORT).show()
                }
            })
            card.addView(row)
        }
        root.addView(card)

        // Abrir Yape si está instalado
        val yape = ctx.packageManager.getLaunchIntentForPackage(YAPE_PACKAGE)
        if (yape != null) {
            root.addView(TextView(ctx).apply {
                text = "📱  Abrir Yape"; typeface = bold; textSize = 18f; setTextColor(Color.WHITE)
                gravity = Gravity.CENTER; minHeight = dp(54f)
                background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(0xFF742284.toInt()) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) }
                pressable()
                setOnClickListener { v ->
                    v.haptic()
                    ctx.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Yape", YAPE_PLIN))
                    Toast.makeText(ctx, "Número copiado: pégalo en Yape ✓", Toast.LENGTH_LONG).show()
                    ctx.startActivity(yape)
                }
            })
        }

        root.addView(TextView(ctx).apply {
            text = "Ahora no"; typeface = semibold; textSize = 18f; setTextColor(blue)
            gravity = Gravity.CENTER; minHeight = dp(54f)
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
            setDimAmount(0.4f)
        }
        d.show()
    }
}
