package com.example.resonant.ui.fragments

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.example.resonant.data.network.MixLaneOptionDTO
import com.example.resonant.databinding.ItemMixV2LaneBinding
import com.example.resonant.databinding.ViewMixV2ControlsBinding
import com.example.resonant.playback.MixComposer
import com.example.resonant.ui.viewmodels.MixEditorV2
import com.example.resonant.ui.viewmodels.MixEditorV2State
import com.example.resonant.ui.views.MixEditorView

/**
 * Controles del editor v2 (spotify-style-transition-engine D9) bajo los estilos: el ajuste fino por carriles (una
 * tarjeta por carril en dos columnas, plegable, con el resumen de lo elegido en la cabecera) y el beatmatching.
 * Los estilos, los compases y la ventana los lleva el propio editor.
 */
class MixEditorV2Panel(
    private val binding: ViewMixV2ControlsBinding,
    private val actions: Actions,
    /** Si el ajuste fino empieza desplegado (se recuerda entre sesiones). */
    lanesExpanded: Boolean,
    private val onLanesExpandedChanged: (Boolean) -> Unit,
    /** Desplegable del editor (opciones código → nombre, código actual, elegido). */
    private val popup: (anchor: View, options: List<Pair<String, String>>, current: String, onSelected: (String) -> Unit) -> Unit
) {
    interface Actions {
        fun selectLaneOption(lane: String, code: String)
        fun setBeatSync(enabled: Boolean)
    }

    private val context = binding.root.context
    private val density = context.resources.displayMetrics.density
    private val Int.dp: Int get() = (this * density).toInt()

    private val laneLabels = mutableMapOf<String, TextView>()
    private var bound: MixEditorV2State? = null
    private var expanded = lanesExpanded

    init {
        binding.mixV2BeatSync.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) actions.setBeatSync(checked)
        }
        binding.mixV2LanesHeader.setOnClickListener { setExpanded(!expanded, animate = true) }
        // Dos tarjetas por fila: los cinco carriles caben sin hacer scroll.
        LANES.chunked(2).forEachIndexed { index, pair ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { if (index > 0) topMargin = 8.dp }
            }
            binding.mixV2Lanes.addView(row)
            pair.forEachIndexed { column, lane -> addLane(row, lane, column) }
        }
        setExpanded(expanded, animate = false)
    }

    private fun setExpanded(value: Boolean, animate: Boolean) {
        if (animate) {
            (binding.root.parent?.parent as? ViewGroup ?: binding.root as ViewGroup).let {
                TransitionManager.beginDelayedTransition(it, AutoTransition().setDuration(240))
            }
        }
        expanded = value
        binding.mixV2LanesSection.visibility = if (value) View.VISIBLE else View.GONE
        binding.mixV2LanesChevron.animate().rotation(if (value) 180f else 0f).setDuration(if (animate) 240 else 0).start()
        binding.mixV2LanesHeader.contentDescription = if (value) "Ocultar el ajuste fino" else "Mostrar el ajuste fino"
        if (animate) onLanesExpandedChanged(value)
    }

    private fun addLane(row: LinearLayout, laneSpec: Triple<String, String, Int>, column: Int) {
        val (lane, title, color) = laneSpec
        val cell = ItemMixV2LaneBinding.inflate(LayoutInflater.from(context), row, false)
        (cell.root.layoutParams as LinearLayout.LayoutParams).apply { if (column > 0) marginStart = 8.dp }
        row.addView(cell.root)
        cell.laneName.text = title
        cell.laneDot.backgroundTintList = ColorStateList.valueOf(color)
        cell.laneDropdown.setOnClickListener { anchor ->
            val s = bound ?: return@setOnClickListener
            popup(anchor, optionsFor(s, lane).map { it.code to it.name }, currentFor(s, lane)) {
                actions.selectLaneOption(lane, it)
            }
        }
        laneLabels[lane] = cell.laneSelected
    }

    fun bind(state: MixEditorV2State) {
        bound = state

        // Se puede forzar con tempos a ≤ 6 % aunque el análisis vea poco estable alguno (solo cambia el defecto).
        // playmix-extended-beatmatch: con la capacidad, también entre el 6 % y el 12 % (tempos estables).
        val canSync = MixComposer.canSync(state.songA, state.songB, state.extended)
        binding.mixV2BeatSync.isEnabled = canSync
        binding.mixV2BeatSync.isChecked = canSync && state.document.beatSync == true
        binding.mixV2BeatSyncHint.text = beatSyncHint(state, MixComposer.tempoClash(state.songA, state.songB, state.extended))

        val names = LANES.map { (lane, _, _) ->
            val code = currentFor(state, lane)
            val name = optionsFor(state, lane).firstOrNull { it.code == code }?.name ?: code
            laneLabels[lane]?.text = name
            code to name
        }
        val active = names.filter { it.first != MixComposer.NONE }.map { it.second }
        binding.mixV2LanesSummary.text = if (active.isEmpty()) "Sin efectos: corte directo" else active.joinToString(" · ")
    }

    /** Por qué no hay beatmatching, con la misma regla que [MixComposer.tempoClash]: distancia de tempo o estabilidad. */
    private fun beatSyncHint(state: MixEditorV2State, clash: Boolean): String {
        if (!clash) {
            return when {
                state.document.beatSync != true -> "Desactivado: cada canción a su velocidad"
                state.document.extendedSync == true ->
                    "La segunda canción se ajusta al tempo de la primera durante la mezcla y vuelve al suyo después"
                else -> "La segunda canción se ajusta al tempo de la primera"
            }
        }
        val delta = MixComposer.bpmDeltaPct(state.songA.bpmPrecise ?: state.songA.bpm, state.songB.bpmPrecise ?: state.songB.bpm)
        return when {
            delta == null -> "Falta el tempo de alguna canción: se mezclan sin cambiar su velocidad"
            delta > MixComposer.MAX_SYNC_DELTA_PCT -> "Tempos demasiado distintos: se mezclan sin cambiar su velocidad"
            state.document.beatSync == true -> "Forzado: el análisis no ve estable el tempo de alguna canción"
            else -> "El análisis no ve estable el tempo de alguna canción; puedes activarlo si cuadran"
        }
    }

    private fun optionsFor(state: MixEditorV2State, lane: String): List<MixLaneOptionDTO> = when (lane) {
        MixEditorV2.LANE_VOLUME -> state.laneOptions.volume
        MixEditorV2.LANE_EQ -> state.laneOptions.eq
        MixEditorV2.LANE_FILTER -> state.laneOptions.filter
        MixEditorV2.LANE_FX -> state.laneOptions.fx
        else -> state.laneOptions.loop
    }

    private fun currentFor(state: MixEditorV2State, lane: String): String = MixEditorV2.optionOf(state.selection, lane)

    companion object {
        private val LANES = listOf(
            Triple(MixEditorV2.LANE_VOLUME, "VOLUMEN", MixEditorView.COLOR_VOLUME),
            Triple(MixEditorV2.LANE_EQ, "EQ", MixEditorView.COLOR_EQ),
            Triple(MixEditorV2.LANE_FILTER, "FILTRO", MixEditorView.COLOR_FILTER),
            // El blanco de los efectos sobre la pantalla oscura no se vería en la tarjeta del tema claro.
            Triple(MixEditorV2.LANE_FX, "EFECTOS", android.graphics.Color.parseColor("#A3A3AD")),
            Triple(MixEditorV2.LANE_LOOP, "LOOP", MixEditorView.COLOR_LOOP)
        )
    }
}
