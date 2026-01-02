package com.example.resonant.ui.adapters

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.managers.HistoryItem // Importamos la clase del manager

class SearchHistoryAdapter(
    private val onItemClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : RecyclerView.Adapter<SearchHistoryAdapter.ViewHolder>() {

    private var historyList: List<HistoryItem> = emptyList()

    fun submitList(list: List<HistoryItem>) {
        historyList = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = historyList[position]

        // 1. Poner Texto
        holder.textView.text = item.query

        // 2. Calcular Tiempo ("Hace 5 min")
        val now = System.currentTimeMillis()
        val timeAgo = DateUtils.getRelativeTimeSpanString(
            item.timestamp,
            now,
            DateUtils.MINUTE_IN_MILLIS
        )
        holder.timeTextView.text = timeAgo

        // Listeners
        holder.itemView.setOnClickListener { onItemClick(item.query) }
        holder.deleteIcon.setOnClickListener { onDeleteClick(item.query) }
    }

    override fun getItemCount() = historyList.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(R.id.historyText)
        val timeTextView: TextView = view.findViewById(R.id.historyTime) // NUEVO
        val deleteIcon: ImageView = view.findViewById(R.id.deleteHistoryIcon)
    }
}