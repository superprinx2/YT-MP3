package com.gabo.ytmp3

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** Hoja "Guardar en playlist": marcar una o varias playlists o crear una nueva. */
object PlaylistPicker {

    fun show(
        a: AppCompatActivity, title: String, preselected: Set<Long>, suggestedNew: String?,
        okLabel: String = "Listo", extra: View? = null, onOk: (Set<Long>, String) -> Unit,
    ) {
        a.lifecycleScope.launch {
            val all = Library.dao(a).playlists()
            build(a, title, all, preselected, suggestedNew, okLabel, extra, onOk)
        }
    }

    private class Choice(var id: Long?, val name: String, var checked: Boolean)

    private fun build(
        a: AppCompatActivity, title: String, all: List<Playlist>, pre: Set<Long>, suggested: String?,
        okLabel: String, extra: View?, onOk: (Set<Long>, String) -> Unit,
    ) {
        val d = Dialog(a)
        val label = a.col(R.color.ios_label); val sec = a.col(R.color.ios_secondary)
        val choices = ArrayList<Choice>()
        val sugName = suggested?.trim()?.takeIf { it.isNotEmpty() }
        val existsSug = sugName != null && all.any { it.name.equals(sugName, true) }
        if (sugName != null && !existsSug) choices += Choice(null, sugName, true)
        if (all.isEmpty() && sugName == null) choices += Choice(null, "Mis descargas", true)
        all.forEach { p ->
            val chk = p.id in pre || (existsSug && p.name.equals(sugName, true)) ||
                (pre.isEmpty() && choices.none { it.checked } && p === all.first())
            choices += Choice(p.id, p.name, chk)
        }

        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(8), 0, a.dp(8), a.dp(8)) }
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card); clipToOutline = true
            setPadding(0, a.dp(16), 0, a.dp(6))
        }
        card.addView(a.text(title, 18f, label, 2, 2).apply { gravity = Gravity.CENTER; setPadding(a.dp(16), 0, a.dp(16), a.dp(4)) })
        card.addView(a.text("Puedes marcar varias playlists", 13f, sec).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, a.dp(10)) })
        extra?.let { (it.parent as? ViewGroup)?.removeView(it); card.addView(it, lp(MATCH, WRAP)) }
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(a).apply { addView(list) }
        val ok = a.text(okLabel, 18f, Color.WHITE, 2).apply {
            gravity = Gravity.CENTER; minHeight = a.dp(54)
            background = roundRect(BRAND_RED, a.dp(14).toFloat())
            pressable()
        }
        fun refreshOk() { val any = choices.any { it.checked }; ok.isEnabled = any; ok.alpha = if (any) 1f else 0.4f }

        fun row(c: Choice): View = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(18), a.dp(12), a.dp(18), a.dp(12))
            setBackgroundResource(R.drawable.bg_row_mid)
            val check = ImageView(a).apply { setImageResource(if (c.checked) R.drawable.ic_circle_on else R.drawable.ic_circle_off) }
            addView(a.text(if (c.id == null) "＋ ${c.name}" else c.name, 16f, label, if (c.id == null) 1 else 0), lp(0, WRAP, 1f))
            if (c.id == null) addView(a.text("nueva", 12f, BRAND_RED, 1).apply { setPadding(0, 0, a.dp(10), 0) })
            addView(check, lp(a.dp(24), a.dp(24)))
            setOnClickListener {
                it.haptic(); c.checked = !c.checked
                check.setImageResource(if (c.checked) R.drawable.ic_circle_on else R.drawable.ic_circle_off)
                refreshOk()
            }
        }

        fun rebuild() {
            list.removeAllViews()
            list.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(a.dp(18), a.dp(12), a.dp(18), a.dp(12))
                setBackgroundResource(R.drawable.bg_row_mid)
                addView(a.icon(R.drawable.ic_add, 22, BRAND_RED))
                addView(a.text("Nueva playlist", 16f, BRAND_RED, 1).apply { setPadding(a.dp(10), 0, 0, 0) })
                setOnClickListener { v ->
                    v.haptic()
                    askName(a, "Nueva playlist", "") { name -> choices.add(0, Choice(null, name, true)); rebuild(); refreshOk() }
                }
            })
            choices.forEach { list.addView(row(it)) }
        }
        rebuild()
        card.addView(scroll, lp(MATCH, WRAP))
        root.addView(card)
        ok.layoutParams = lp(MATCH, WRAP).apply { topMargin = a.dp(8) }
        root.addView(ok)
        root.addView(a.text("Cancelar", 18f, a.col(R.color.ios_blue), 1).apply {
            gravity = Gravity.CENTER; minHeight = a.dp(54)
            setBackgroundResource(R.drawable.bg_row_single)
            layoutParams = lp(MATCH, WRAP).apply { topMargin = a.dp(8) }
            setOnClickListener { d.dismiss() }
        })
        refreshOk()
        ok.setOnClickListener {
            it.haptic(true)
            a.lifecycleScope.launch {
                val ids = LinkedHashSet<Long>()
                for (c in choices.filter { it.checked }) {
                    ids += c.id ?: Library.createPlaylist(a, c.name).also { id -> c.id = id }
                }
                d.dismiss()
                onOk(ids, choices.filter { it.checked }.joinToString(", ") { it.name })
            }
        }
        d.setContentView(root)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(0.4f)
        }
        scroll.layoutParams = scroll.layoutParams.apply {
            height = minOf(a.resources.displayMetrics.heightPixels / (if (extra != null) 3 else 2), a.dp(56) * (choices.size + 1))
        }
        d.show()
    }

    /** Diálogo para escribir un nombre (playlist o canción). */
    fun askName(a: AppCompatActivity, title: String, initial: String, max: Int = 60, onOk: (String) -> Unit) {
        val input = EditText(a).apply {
            setText(initial); setSelection(initial.length); isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(max))
        }
        val box = LinearLayout(a).apply { setPadding(a.dp(22), a.dp(8), a.dp(22), 0); addView(input, lp(MATCH, WRAP)) }
        MaterialAlertDialogBuilder(a).setTitle(title).setView(box)
            .setPositiveButton("Guardar") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
            .setNegativeButton("Cancelar", null).show()
        input.requestFocus()
    }
}

@Suppress("unused") private fun TextView.noop() = Unit
