package com.example.resonant.ui.bottomsheets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.ui.adapters.UnisoundQueueAdapter
import com.example.resonant.ui.adapters.UnisoundQueueRow
import com.example.resonant.ui.viewmodels.UnisoundViewModel
import com.example.resonant.unisound.UnisoundQueueMove
import kotlinx.coroutines.launch

/** The room's "A continuación" in the queue sheet, with the same rows and controls as the room. */
class UnisoundQueueBottomSheet : ResonantBottomSheetDialogFragment() {
    private lateinit var viewModel: UnisoundViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.bottom_sheet_unisound_queue, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[UnisoundViewModel::class.java]
        val subtitle = view.findViewById<TextView>(R.id.unisoundQueueSheetSubtitle)
        val empty = view.findViewById<View>(R.id.unisoundQueueSheetEmpty)
        val list = view.findViewById<RecyclerView>(R.id.unisoundQueueSheetList)
        val adapter = UnisoundQueueAdapter(
            onRemove = { item ->
                viewLifecycleOwner.lifecycleScope.launch { viewModel.removeItem(item.itemId) }
            },
            onMove = { item, toIndex ->
                val queue = viewModel.state.value?.queue.orEmpty()
                val from = queue.indexOfFirst { it.itemId == item.itemId }
                val target = UnisoundQueueMove.targetIndex(from, toIndex, queue.size)
                if (from >= 0 && target != null) {
                    viewLifecycleOwner.lifecycleScope.launch { viewModel.moveItem(item.itemId, target) }
                }
            }
        )
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    if (state == null) {
                        dismissAllowingStateLoss()
                        return@collect
                    }
                    val current = state.current?.song?.title
                    subtitle.text = if (current != null) "Sonando: $current" else "Sala de Unisound"
                    adapter.submitList(UnisoundQueueRow.rowsOf(state))
                    empty.isVisible = state.queue.isEmpty()
                    list.isVisible = state.queue.isNotEmpty()
                }
            }
        }
    }

    companion object {
        const val TAG = "UnisoundQueueBottomSheet"
    }
}
