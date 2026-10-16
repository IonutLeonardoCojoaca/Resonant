package com.example.resonant.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.data.network.RecapSummaryDTO
import com.example.resonant.recap.RecapTexts

/** Lista de resúmenes mensuales ("Septiembre 2026" + "Nuevo" si no se ha visto). */
class RecapHistoryAdapter(
    private val onClick: (RecapSummaryDTO) -> Unit
) : ListAdapter<RecapSummaryDTO, RecapHistoryAdapter.ViewHolder>(Diff) {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.recapHistoryItemTitle)
        private val badge: TextView = view.findViewById(R.id.recapHistoryItemBadge)

        init {
            view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(getItem(position))
            }
        }

        fun bind(item: RecapSummaryDTO) {
            title.text = RecapTexts.periodTitle(item.periodKey)
            badge.isVisible = !item.seen
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_recap_history, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    private object Diff : DiffUtil.ItemCallback<RecapSummaryDTO>() {
        override fun areItemsTheSame(oldItem: RecapSummaryDTO, newItem: RecapSummaryDTO) =
            oldItem.periodKey == newItem.periodKey

        override fun areContentsTheSame(oldItem: RecapSummaryDTO, newItem: RecapSummaryDTO) = oldItem == newItem
    }
}
