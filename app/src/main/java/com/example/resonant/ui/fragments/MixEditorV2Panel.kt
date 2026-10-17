package com.example.resonant.ui.fragments

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.resonant.R
import com.example.resonant.data.network.MixLaneOptionDTO
import com.example.resonant.databinding.ItemMixV2LaneBinding
import com.example.resonant.databinding.ViewMixV2ControlsBinding
import com.example.resonant.playback.MixComposer
import com.example.resonant.ui.detail.SongInsightsFormatter
import com.example.resonant.ui.viewmodels.MixEditorV2
import com.example.resonant.ui.viewmodels.MixEditorV2State
import com.example.resonant.ui.views.MixEditorView

/**
 * Controles del editor v2 (spotify-style-transition-engine D9) que se añaden al visualizador del editor de
 * transición: los carriles ([lanesSection], una tarjeta por carril en dos columnas, justo debajo de la onda) y,
 * debajo de los tiempos, estado del render, presets (con Personalizada), ventana por compases, tonalidad y
 * beatmatching.
 */
class MixEditorV2Panel(
    private val context: Context,
    private val actions: Actions,
    /** Desplegable del editor (opciones código → nombre, código actual, elegido). */
    private val popup: (anchor: View, options: List<Pair<String, String>>, current: String, onSelected: (String) -> Unit) -> Unit
) {
    interface Actions {
        fun selectPreset(code: String)
        fun selectLaneOption(lane: String, code: String)
        fun moveWindow(deltaBars: Int)
        fun setBeatSync(enabled: Boolean)
    }

    private val binding = ViewMixV2ControlsBinding.inflate(LayoutInflater.from(context))
    val root: View get() = binding.root
    /** Los carriles: el editor los saca de [root] y los pone justo debajo de la onda. */
    val lanesSection: View get() = binding.mixV2LanesSection

    private val density = context.resources.displayMetrics.density
    private val Int.dp: Int get() = (this * density).toInt()

    private val presetChips = linkedMapOf<String, TextView>()
    private val laneLabels = mutableMapOf<String, TextView>()
    private var bound: MixEditorV2State? = null

    init {
        binding.mixV2WindowEarlier.setOnClickListener { actions.moveWindow(-1) }
        binding.mixV2WindowLater.setOnClickListener { actions.moveWindow(1) }
        binding.mixV2BeatSync.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) actions.setBeatSync(checked)
        }
        // Dos tarjetas por fila: los cinco carriles caben bajo la onda sin hacer scroll.
        LANES.chunked(2).forEachIndexed { index, pair ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { if (index > 0) topMargin = 8.dp }
            }
            binding.mixV2Lanes.addView(row)
            pair.forEachIndexed { column, lane -> addLane(row, lane, column) }
        }
    }

    private fun addLane(row: LinearLayout, laneSpec: Triple<String, String, Int>, column: Int) {
        val (lane, title, color) = laneSpec
        val cell = ItemMixV2LaneBinding.inflate(LayoutInflater.from(context), row, false)
        (cell.root.layoutParams as LinearLayout.LayoutParams).apply { if (column > 0) marginStart = 8.dp }
        row.addView(cell.root)
        cell.laneName.text = title
        cell.laneName.setTextColor(color)
        cell.laneDot.backgroundTintList = ColorStateList.valueOf(color)
        cell.laneDropdown.setOnClickListener { anchor ->
            val s = bound ?: return@setOnClickListener
            popup(anchor, optionsFor(s, lane).map { it.code to it.name }, currentFor(s, lane)) {
                actions.selectLaneOption(lane, it)
            }
        }
        laneLabels[lane] = cell.laneSelected
    }

    /** [dirty]: el documento en pantalla no es el guardado. [renderStatus]: estado del clip del servidor. */
    fun bind(state: MixEditorV2State, dirty: Boolean, renderStatus: String?) {
        bound = state
        binding.mixV2Status.text = MixEditorV2.statusLabel(dirty, renderStatus)
        binding.mixV2Status.setTextColor(
            Color.parseColor(if (dirty) "#FFFFB74D" else if (renderStatus == "ready") "#FF69F0AE" else "#CCFFFFFF")
        )

        bindPresets(state)
        binding.mixV2PresetDescription.text = MixEditorV2.describe(state)

        val clash = MixComposer.tempoClash(state.songA, state.songB)
        binding.mixV2BeatSync.isEnabled = !clash
        binding.mixV2BeatSync.isChecked = !clash && state.document.beatSync == true
        binding.mixV2BeatSyncHint.text = beatSyncHint(state, clash)

        bindCamelot(binding.mixV2KeyA, state.songA.keyCamelot)
        bindCamelot(binding.mixV2KeyB, state.songB.keyCamelot)

        LANES.forEach { (lane, _, _) ->
            val code = currentFor(state, lane)
            laneLabels[lane]?.text = optionsFor(state, lane).firstOrNull { it.code == code }?.name ?: code
        }
    }

    /** Por qué no hay beatmatching, con la misma regla que [MixComposer.tempoClash]: distancia de tempo o estabilidad. */
    private fun beatSyncHint(state: MixEditorV2State, clash: Boolean): String {
        if (!clash) return "La segunda canción se ajusta al tempo de la primera"
        val delta = MixComposer.bpmDeltaPct(state.songA.bpmPrecise ?: state.songA.bpm, state.songB.bpmPrecise ?: state.songB.bpm)
        return when {
            delta == null -> "Falta el tempo de alguna canción: se mezclan sin cambiar su velocidad"
            delta > MixComposer.MAX_SYNC_DELTA_PCT -> "Tempos demasiado distintos: se mezclan sin cambiar su velocidad"
            else -> "El análisis no ve estable el tempo de alguna canción: se mezclan sin cambiar su velocidad"
        }
    }

    private fun bindPresets(state: MixEditorV2State) {
        val sorted = state.presets.sortedBy { it.sortOrder }
        val codes = sorted.map { it.code } + MixEditorV2.CUSTOM
        if (presetChips.keys.toList() != codes) {
            binding.mixV2PresetRow.removeAllViews()
            presetChips.clear()
            sorted.forEach { preset -> presetChips[preset.code] = chip(preset.name) { actions.selectPreset(preset.code) } }
            // Personalizada no se elige: aparece al tocar un carril o arrastrar una canción.
            presetChips[MixEditorV2.CUSTOM] = chip("Personalizada", null)
            presetChips.values.forEach { binding.mixV2PresetRow.addView(it) }
        }
        presetChips.forEach { (code, chip) ->
            val selected = code == state.presetCode
            chip.isSelected = selected
            chip.setTextColor(if (selected) Color.WHITE else Color.parseColor("#CCFFFFFF"))
            chip.visibility = if (code == MixEditorV2.CUSTOM && !selected) View.GONE else View.VISIBLE
        }
    }

    /** Chip con el mismo aspecto que los estilos del editor antiguo (`createCompactChip`). */
    private fun chip(text: String, onClick: (() -> Unit)?) = TextView(context).apply {
        this.text = text
        setBackgroundResource(R.drawable.bg_preset_selector_chip)
        typeface = context.resources.getFont(R.font.unageo_medium)
        textSize = 12f
        gravity = Gravity.CENTER
        minWidth = 96.dp
        setPadding(14.dp, 10.dp, 14.dp, 10.dp)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 42.dp).apply { marginEnd = 8.dp }
        if (onClick != null) setOnClickListener { onClick() }
    }

    private fun optionsFor(state: MixEditorV2State, lane: String): List<MixLaneOptionDTO> = when (lane) {
        MixEditorV2.LANE_VOLUME -> state.laneOptions.volume
        MixEditorV2.LANE_EQ -> state.laneOptions.eq
        MixEditorV2.LANE_FILTER -> state.laneOptions.filter
        MixEditorV2.LANE_FX -> state.laneOptions.fx
        else -> state.laneOptions.loop
    }

    private fun currentFor(state: MixEditorV2State, lane: String): String = MixEditorV2.optionOf(state.selection, lane)

    /** Pastilla Camelot con el color de su número en la rueda (más clara en el anillo B, mayor). */
    private fun bindCamelot(view: TextView, code: String?) {
        val position = SongInsightsFormatter.camelotPosition(code)
        view.text = code ?: "—"
        if (position == null) {
            view.backgroundTintList = null
            view.setTextColor(Color.parseColor("#99FFFFFF"))
        } else {
            val hue = ((position.first - 1) * 30f + 150f) % 360f
            view.backgroundTintList = ColorStateList.valueOf(
                Color.HSVToColor(floatArrayOf(hue, if (position.second) 0.55f else 0.35f, 0.95f))
            )
            view.setTextColor(Color.BLACK)
        }
    }

    companion object {
        private val LANES = listOf(
            Triple(MixEditorV2.LANE_VOLUME, "VOLUMEN", MixEditorView.COLOR_VOLUME),
            Triple(MixEditorV2.LANE_EQ, "EQ", MixEditorView.COLOR_EQ),
            Triple(MixEditorV2.LANE_FILTER, "FILTRO", MixEditorView.COLOR_FILTER),
            Triple(MixEditorV2.LANE_FX, "EFECTOS", MixEditorView.COLOR_FX),
            Triple(MixEditorV2.LANE_LOOP, "LOOP", MixEditorView.COLOR_LOOP)
        )
    }
}
