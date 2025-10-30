package com.example.resonant

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SuggestionAdapter(
    private val onClick: (SuggestionItem) -> Unit
) : RecyclerView.Adapter<SuggestionAdapter.ViewHolder>() {

    private val items = mutableListOf<SuggestionItem>()

    fun submitList(newItems: List<SuggestionItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_suggestion_search, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val suggestionText: TextView = itemView.findViewById(R.id.suggestionText)
        private val suggestionType: TextView = itemView.findViewById(R.id.suggestionType)

        fun bind(item: SuggestionItem) {
            suggestionText.text = item.text
            suggestionType.text = when(item.type) {
                DataType.SONG -> "Canción"
                DataType.ALBUM -> "Álbum"
                DataType.ARTIST -> "Artista"
            }
            itemView.setOnClickListener { onClick(item) }
        }
    }
}
