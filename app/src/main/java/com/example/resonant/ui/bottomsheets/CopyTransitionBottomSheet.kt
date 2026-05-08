package com.example.resonant.ui.bottomsheets

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.managers.PlaymixManager
import com.example.resonant.ui.adapters.PlaymixSelectorAdapter
import com.example.resonant.ui.viewmodels.PlaymixListViewModel
import com.example.resonant.ui.viewmodels.PlaymixListViewModelFactory
import com.example.resonant.utils.SnackbarUtils.showResonantSnackbar
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CopyTransitionBottomSheet(
    private val transition: PlaymixTransitionDTO,
    private val sourcePlaymixId: String,
    private val songATitle: String,
    private val songBTitle: String
) : BottomSheetDialogFragment() {

    private lateinit var playmixAdapter: PlaymixSelectorAdapter
    private lateinit var playmixRecyclerView: RecyclerView
    private lateinit var noPlaymixTextView: TextView

    private val playmixListViewModel: PlaymixListViewModel by viewModels {
        val playmixManager = PlaymixManager(requireContext())
        PlaymixListViewModelFactory(playmixManager)
    }

    override fun getTheme(): Int = R.style.AppBottomSheetDialogTheme

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.bottom_sheet_copy_transition, container, false)

        val tvSongA: TextView = view.findViewById(R.id.songATitle)
        val tvSongB: TextView = view.findViewById(R.id.songBTitle)
        val tvScore: TextView = view.findViewById(R.id.compatScore)
        val tvVerdict: TextView = view.findViewById(R.id.compatVerdict)
        noPlaymixTextView = view.findViewById(R.id.noPlaymixTextView)
        playmixRecyclerView = view.findViewById(R.id.playmixList)

        tvSongA.text = songATitle
        tvSongB.text = "↳ $songBTitle"

        val compat = transition.compatibility
        if (compat != null && compat.overallScore > 0) {
            val color = when (compat.verdict) {
                "perfect" -> Color.parseColor("#4CAF50")
                "good"    -> Color.parseColor("#8BC34A")
                "moderate" -> Color.parseColor("#FFC107")
                "poor"    -> Color.parseColor("#F44336")
                else      -> Color.GRAY
            }
            val label = when (compat.verdict) {
                "perfect"  -> "PERFECTA"
                "good"     -> "BUENA"
                "moderate" -> "MODERADA"
                "poor"     -> "DIFÍCIL"
                else       -> "—"
            }
            tvScore.text = "${compat.overallScore}/100"
            tvScore.setTextColor(color)
            tvVerdict.text = label
            tvVerdict.setTextColor(color)
        } else {
            tvScore.visibility = View.GONE
            tvVerdict.visibility = View.GONE
        }

        setupRecyclerView()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        playmixListViewModel.playmixes.observe(viewLifecycleOwner) { playmixes ->
            val filtered = (playmixes ?: emptyList()).filter { it.id != sourcePlaymixId }
            playmixAdapter.submitList(filtered)
            updateEmptyState(filtered.isEmpty())
        }

        playmixListViewModel.loadMyPlaymixes()
    }

    private fun setupRecyclerView() {
        val playmixManager = PlaymixManager(requireContext())
        playmixAdapter = PlaymixSelectorAdapter { selectedPlaymix ->
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        playmixManager.copyTransition(sourcePlaymixId, transition.id, selectedPlaymix.id)
                    }
                    showResonantSnackbar(
                        text = "Transición copiada a '${selectedPlaymix.name}'",
                        colorRes = R.color.successColor,
                        iconRes = R.drawable.ic_success
                    )
                    dismiss()
                } catch (e: Exception) {
                    Log.e("CopyTransitionBS", "Error al copiar la transición: ${e.message}")
                    showResonantSnackbar(
                        text = "Error al copiar la transición",
                        colorRes = R.color.errorColor,
                        iconRes = R.drawable.ic_error
                    )
                    dismiss()
                }
            }
        }
        playmixRecyclerView.adapter = playmixAdapter
        playmixRecyclerView.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        noPlaymixTextView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        playmixRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }
}
