package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.managers.CapabilitiesManager
import com.example.resonant.managers.RecapManager
import com.example.resonant.managers.RecapResult
import com.example.resonant.ui.adapters.RecapHistoryAdapter
import kotlinx.coroutines.launch

/** "Tus resúmenes": historial de resúmenes mensuales, accesible desde Ajustes con `features.monthlyRecap`. */
class RecapHistoryFragment : Fragment() {

    private lateinit var adapter: RecapHistoryAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_recap_history, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.recapHistoryBack).setOnClickListener { findNavController().navigateUp() }
        adapter = RecapHistoryAdapter { recap ->
            findNavController().navigate(
                R.id.recapStoriesFragment,
                Bundle().apply { putString(RecapStoriesFragment.ARG_PERIOD_KEY, recap.periodKey) }
            )
        }
        view.findViewById<RecyclerView>(R.id.recapHistoryList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@RecapHistoryFragment.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val root = view ?: return
        val loading = root.findViewById<ProgressBar>(R.id.recapHistoryLoading)
        val empty = root.findViewById<TextView>(R.id.recapHistoryEmpty)
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            loading.isVisible = adapter.currentList.isEmpty()
            val result = RecapManager.get(appContext).list()
            loading.isVisible = false
            when (result) {
                is RecapResult.Ok -> {
                    adapter.submitList(result.value)
                    empty.setText(R.string.recap_history_empty)
                    empty.isVisible = result.value.isEmpty()
                }
                RecapResult.Disabled -> {
                    // Función apagada en el servidor: sin mensaje de error, se vuelve atrás.
                    CapabilitiesManager.refresh(appContext, force = true)
                    findNavController().popBackStack()
                }
                else -> {
                    empty.setText(R.string.recap_load_failed)
                    empty.isVisible = adapter.currentList.isEmpty()
                }
            }
        }
    }
}
