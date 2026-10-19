package com.example.resonant.ui.bottomsheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import com.example.resonant.R
import com.example.resonant.data.network.PlaymixSortCriterion
import com.example.resonant.managers.PlaymixSharingManager
import com.example.resonant.ui.viewmodels.PlaymixSharingViewModelFactory
import com.example.resonant.ui.viewmodels.PlaymixSortViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Ordenar una PlayMix propia (playmix-sharing-discovery): al elegir criterio o dirección se pide la vista previa
 * al servidor (no guarda nada); "Aplicar" guarda ese mismo orden (con la semilla en aleatorio). Las parejas que
 * siguen juntas conservan su mezcla.
 */
class PlaymixSortBottomSheet : ResonantBottomSheetDialogFragment() {

    /** Lo fija quien abre la hoja: se llama tras guardar el orden. */
    var onApplied: (() -> Unit)? = null

    private val playmixId: String by lazy { requireArguments().getString(ARG_PLAYMIX_ID).orEmpty() }
    private lateinit var viewModel: PlaymixSortViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.bottom_sheet_playmix_sort, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this, PlaymixSharingViewModelFactory(PlaymixSharingManager(requireContext())))
            .get(PlaymixSortViewModel::class.java)

        val criteria: ChipGroup = view.findViewById(R.id.sortCriterionGroup)
        val directions: ChipGroup = view.findViewById(R.id.sortDirectionGroup)
        val previewText: TextView = view.findViewById(R.id.sortPreviewText)
        val apply: MaterialButton = view.findViewById(R.id.sortApplyButton)

        val criterionByChip = mutableMapOf<Int, String>()
        PlaymixSortCriterion.ALL.forEach { criterion ->
            val chip = chip(criteria, label(criterion))
            criterionByChip[chip.id] = criterion
        }
        val ascChip = chip(directions, "Ascendente")
        val descChip = chip(directions, "Descendente")
        ascChip.isChecked = true

        fun requestPreview() {
            val criterion = criterionByChip[criteria.checkedChipId] ?: return
            val direction = if (directions.checkedChipId == descChip.id) "desc" else "asc"
            directions.visibility = if (criterion == PlaymixSortCriterion.SHUFFLE) View.GONE else View.VISIBLE
            viewModel.preview(playmixId, criterion, direction)
        }
        criteria.setOnCheckedStateChangeListener { _, _ -> requestPreview() }
        directions.setOnCheckedStateChangeListener { _, _ -> requestPreview() }

        apply.setOnClickListener { viewModel.apply(playmixId) }

        viewModel.state.observe(viewLifecycleOwner) { state ->
            apply.isEnabled = !state.isLoading && state.preview.isNotEmpty()
            previewText.text = when {
                state.criterion == null -> "Elige un criterio para ver el nuevo orden."
                state.isLoading -> "Calculando…"
                state.error != null -> state.error
                else -> state.preview.mapIndexed { i, song ->
                    "${i + 1}. ${song.title ?: "Sin título"}${song.artist?.let { " · $it" } ?: ""}"
                }.joinToString("\n")
            }
            if (state.applied) {
                Toast.makeText(requireContext(), "Orden aplicado", Toast.LENGTH_SHORT).show()
                onApplied?.invoke()
                dismiss()
            }
        }

        // Primer criterio marcado: vista previa inmediata.
        criteria.getChildAt(0)?.let { (it as Chip).isChecked = true }
    }

    private fun chip(group: ChipGroup, text: String): Chip {
        val chip = Chip(group.context).apply {
            id = View.generateViewId()
            this.text = text
            isCheckable = true
            isCheckedIconVisible = false
            setTextColor(resources.getColorStateList(R.color.chip_text_color_selector, null))
            chipBackgroundColor = resources.getColorStateList(R.color.chip_background_selector, null)
            chipStrokeWidth = 0f
        }
        group.addView(chip)
        return chip
    }

    companion object {
        private const val ARG_PLAYMIX_ID = "playmixId"

        fun newInstance(playmixId: String) = PlaymixSortBottomSheet().apply {
            arguments = Bundle().apply { putString(ARG_PLAYMIX_ID, playmixId) }
        }

        fun label(criterion: String): String = when (criterion) {
            PlaymixSortCriterion.HARMONIC -> "Recorrido armónico"
            PlaymixSortCriterion.BPM -> "BPM"
            PlaymixSortCriterion.KEY -> "Tonalidad"
            PlaymixSortCriterion.LOUDNESS -> "Sonoridad"
            PlaymixSortCriterion.TITLE -> "Título"
            PlaymixSortCriterion.ARTIST -> "Artista"
            PlaymixSortCriterion.SHUFFLE -> "Aleatorio"
            else -> criterion
        }
    }
}
