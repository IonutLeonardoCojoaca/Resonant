package com.example.resonant.ui.fragments

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.example.resonant.R
import com.example.resonant.data.network.MixLaneOptionDTO
import com.example.resonant.playback.MixComposer
import com.example.resonant.ui.detail.SongInsightsFormatter
import com.example.resonant.ui.viewmodels.MixEditorV2
import com.example.resonant.ui.viewmodels.MixEditorV2State
import com.example.resonant.ui.views.MixEditorView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Panel del editor v2 (spotify-style-transition-engine D9), construido en código para no tocar el layout del
 * editor antiguo: cabecera con BPM y pastillas Camelot, [MixEditorView], flechas de ventana, compases,
 * beatmatching, fila de presets (con Personalizada) y un desplegable por carril.
 */
class MixEditorV2Panel(
    private val context: Context,
    private val actions: Actions,
    /** Desplegable con el estilo del editor (opciones código → nombre, código actual, elegido). */
    private val popup: (anchor: View, options: List<Pair<String, String>>, current: String, onSelected: (String) -> Unit) -> Unit
) {
    interface Actions {
        fun selectPreset(code: String)
        fun selectLaneOption(lane: String, code: String)
        fun setBars(bars: Int)
        fun moveWindow(deltaBars: Int)
        fun dragA(aStartMs: Int, toBeat: Boolean)
        fun dragB(bStartMs: Int, toBeat: Boolean)
        fun setBeatSync(enabled: Boolean)
        fun preview()
    }

    private val density = context.resources.displayMetrics.density
    private val Int.dp: Int get() = (this * density).toInt()

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 8.dp, 0, 16.dp)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    val editorView = MixEditorView(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        onDragA = { ms, toBeat -> actions.dragA(ms, toBeat) }
        onDragB = { ms, toBeat -> actions.dragB(ms, toBeat) }
    }

    private val titlesLabel = label(13f, "#FFFFFFFF").apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val tempoLabel = label(13f, "#CCFFFFFF")
    private val camelotA = pill()
    private val camelotB = pill()
    private val windowLabel = label(12f, "#99FFFFFF")
    private val barsButton = pill()
    private val beatSwitch = SwitchCompat(context).apply {
        text = "Beatmatching"
        setTextColor(Color.parseColor("#CCFFFFFF"))
        textSize = 13f
    }
    private val presetRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val presetChips = mutableMapOf<String, TextView>()
    private val laneRows = mutableMapOf<String, TextView>()
    private var bound: MixEditorV2State? = null

    init {
        root.addView(titlesLabel)
        root.addView(row(tempoLabel, spacer(), camelotA, label(12f, "#66FFFFFF").apply { text = "→"; setPadding(6.dp, 0, 6.dp, 0) }, camelotB))
        root.addView(editorView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp })

        val left = icon(R.drawable.ic_chevron_left_mini, "Ventana un compás antes") { actions.moveWindow(-1) }
        val right = icon(R.drawable.ic_chevron_right_mini, "Ventana un compás después") { actions.moveWindow(1) }
        val play = icon(R.drawable.ic_play, "Escuchar la mezcla") { actions.preview() }
        barsButton.setOnClickListener { anchor ->
            val current = bound?.window?.bars ?: MixComposer.DEFAULT_BARS
            popup(anchor, MixEditorV2.BAR_OPTIONS.map { it.toString() to barsLabel(it) }, current.toString()) { actions.setBars(it.toInt()) }
        }
        root.addView(row(left, windowLabel, right, spacer(), barsButton, play).apply { setPadding(0, 8.dp, 0, 0) })

        beatSwitch.setOnCheckedChangeListener { button, checked -> if (button.isPressed) actions.setBeatSync(checked) }
        root.addView(beatSwitch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dp })

        root.addView(section("Preset"))
        root.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(presetRow)
        })

        root.addView(section("Carriles"))
        LANES.forEach { (lane, title, color) ->
            val value = label(13f, "#FFFFFFFF").apply { gravity = Gravity.END }
            val dot = View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
                layoutParams = LinearLayout.LayoutParams(10.dp, 10.dp).apply { marginEnd = 10.dp }
            }
            val rowView = row(dot, label(13f, "#CCFFFFFF").apply { text = title }, spacer(), value).apply {
                setPadding(12.dp, 12.dp, 12.dp, 12.dp)
                setBackgroundResource(R.drawable.bg_dropdown_popup)
                setOnClickListener { anchor ->
                    val s = bound ?: return@setOnClickListener
                    val options = optionsFor(s, lane)
                    popup(anchor, options.map { it.code to it.name }, currentFor(s, lane)) { actions.selectLaneOption(lane, it) }
                }
            }
            laneRows[lane] = value
            root.addView(rowView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 6.dp })
        }
    }

    fun setFallbackWaveforms(a: List<Float>?, b: List<Float>?) = editorView.setFallbackWaveforms(a, b)

    fun bind(state: MixEditorV2State, coincidentBeats: List<Double>) {
        bound = state
        editorView.setState(state, coincidentBeats)

        titlesLabel.text = "${state.infoA.title ?: "Canción A"}  →  ${state.infoB.title ?: "Canción B"}"
        val bpmA = state.songA.bpmPrecise ?: state.songA.bpm
        val bpmB = state.songB.bpmPrecise ?: state.songB.bpm
        tempoLabel.text = String.format(Locale("es", "ES"), "%s → %s BPM", bpmText(bpmA), bpmText(bpmB))
        bindCamelot(camelotA, state.songA.keyCamelot)
        bindCamelot(camelotB, state.songB.keyCamelot)

        val doc = state.document
        windowLabel.text = if (doc.durationMs == 0) "Sin transición" else "A ${time(doc.a.startMs)} · B ${time(doc.b.startMs)}"
        barsButton.text = barsLabel(state.window.bars)
        val clash = MixComposer.tempoClash(state.songA, state.songB)
        beatSwitch.isEnabled = !clash
        beatSwitch.isChecked = !clash && doc.beatSync == true
        beatSwitch.text = if (clash) "Beatmatching (tempos incompatibles)" else "Beatmatching"

        bindPresets(state)
        LANES.forEach { (lane, _, _) ->
            val code = currentFor(state, lane)
            laneRows[lane]?.text = optionsFor(state, lane).firstOrNull { it.code == code }?.name ?: code
        }
    }

    private fun bindPresets(state: MixEditorV2State) {
        val sorted = state.presets.sortedBy { it.sortOrder }
        val codes = sorted.map { it.code } + MixEditorV2.CUSTOM
        if (presetChips.keys.toList() != codes) {
            presetRow.removeAllViews()
            presetChips.clear()
            sorted.forEach { preset ->
                presetChips[preset.code] = chip(preset.name) { actions.selectPreset(preset.code) }
            }
            // Personalizada no se elige: aparece al tocar un carril o arrastrar una canción.
            presetChips[MixEditorV2.CUSTOM] = chip("Personalizada", null)
            presetChips.values.forEach { presetRow.addView(it) }
        }
        presetChips.forEach { (code, chip) ->
            val selected = code == state.presetCode
            chip.isSelected = selected
            chip.setTextColor(if (selected) Color.WHITE else Color.parseColor("#CCFFFFFF"))
            chip.visibility = if (code == MixEditorV2.CUSTOM && !selected) View.GONE else View.VISIBLE
        }
    }

    private fun optionsFor(state: MixEditorV2State, lane: String): List<MixLaneOptionDTO> = when (lane) {
        MixEditorV2.LANE_VOLUME -> state.laneOptions.volume
        MixEditorV2.LANE_EQ -> state.laneOptions.eq
        MixEditorV2.LANE_FILTER -> state.laneOptions.filter
        MixEditorV2.LANE_FX -> state.laneOptions.fx
        else -> state.laneOptions.loop
    }

    private fun currentFor(state: MixEditorV2State, lane: String): String = when (lane) {
        MixEditorV2.LANE_VOLUME -> state.selection.volume
        MixEditorV2.LANE_EQ -> state.selection.eq
        MixEditorV2.LANE_FILTER -> state.selection.filter
        MixEditorV2.LANE_FX -> state.selection.fx
        else -> state.selection.loop
    }

    /** Pastilla Camelot con el color de su número en la rueda (más clara en el anillo B, mayor). */
    private fun bindCamelot(view: TextView, code: String?) {
        val position = SongInsightsFormatter.camelotPosition(code)
        view.text = code ?: "—"
        val color = if (position == null) Color.parseColor("#33FFFFFF") else camelotColor(position.first, position.second)
        view.backgroundTintList = ColorStateList.valueOf(color)
        view.setTextColor(if (position == null) Color.parseColor("#99FFFFFF") else Color.BLACK)
    }

    private fun camelotColor(number: Int, minor: Boolean): Int {
        val hue = ((number - 1) * 30f + 150f) % 360f
        return Color.HSVToColor(floatArrayOf(hue, if (minor) 0.55f else 0.35f, 0.95f))
    }

    // ─── Vistas ──────────────────────────────────────────────────────────────────────────────

    private fun label(size: Float, color: String) = TextView(context).apply {
        textSize = size
        setTextColor(Color.parseColor(color))
        typeface = context.resources.getFont(R.font.unageo_medium)
    }

    private fun pill() = label(12f, "#FFFFFFFF").apply {
        setBackgroundResource(R.drawable.bg_preset_selector_chip)
        setPadding(10.dp, 4.dp, 10.dp, 4.dp)
        gravity = Gravity.CENTER
    }

    private fun chip(text: String, onClick: (() -> Unit)?) = pill().apply {
        this.text = text
        textSize = 12f
        minWidth = 96.dp
        setPadding(14.dp, 10.dp, 14.dp, 10.dp)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 40.dp).apply { marginEnd = 8.dp }
        if (onClick != null) setOnClickListener { onClick() }
    }

    private fun icon(res: Int, description: String, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(res)
        contentDescription = description
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        setPadding(6.dp, 6.dp, 6.dp, 6.dp)
        layoutParams = LinearLayout.LayoutParams(36.dp, 36.dp)
        setOnClickListener { onClick() }
    }

    private fun section(title: String) = label(14f, "#FFFFFFFF").apply {
        text = title
        setPadding(0, 16.dp, 0, 8.dp)
    }

    private fun spacer() = View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    private fun row(vararg children: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        children.forEach { addView(it) }
    }

    private fun barsLabel(bars: Int) = if (bars == 1) "1 compás" else "$bars compases"

    private fun bpmText(bpm: Double?) = bpm?.takeIf { it > 0 }?.let { String.format(Locale("es", "ES"), "%.1f", it) } ?: "—"

    private fun time(ms: Int): String {
        val s = (ms / 1000.0).roundToInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    companion object {
        private val LANES = listOf(
            Triple(MixEditorV2.LANE_VOLUME, "Volumen", MixEditorView.COLOR_VOLUME),
            Triple(MixEditorV2.LANE_EQ, "EQ", MixEditorView.COLOR_EQ),
            Triple(MixEditorV2.LANE_FILTER, "Filtro", MixEditorView.COLOR_FILTER),
            Triple(MixEditorV2.LANE_FX, "Efectos", MixEditorView.COLOR_FX),
            Triple(MixEditorV2.LANE_LOOP, "Loop", MixEditorView.COLOR_LOOP)
        )
    }
}
