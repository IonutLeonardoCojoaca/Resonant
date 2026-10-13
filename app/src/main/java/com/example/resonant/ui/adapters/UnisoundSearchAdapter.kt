package com.example.resonant.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R

/** A song, album or playlist found by the room's "Añadir" tab. [type] is the hub source type. */
data class UnisoundSearchRow(
    val type: String,
    val id: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?
)

/** Search results of the room's "Añadir" tab, each with a "+" to add it to the queue. */
class UnisoundSearchAdapter(
    private val onAdd: (UnisoundSearchRow) -> Unit
) : ListAdapter<UnisoundSearchRow, UnisoundSearchAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_unisound_search_result, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ImageView = itemView.findViewById(R.id.unisoundResultCover)
        private val title: TextView = itemView.findViewById(R.id.unisoundResultTitle)
        private val artist: TextView = itemView.findViewById(R.id.unisoundResultArtist)

        init {
            itemView.findViewById<View>(R.id.unisoundResultAdd).setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onAdd(getItem(position))
            }
        }

        fun bind(row: UnisoundSearchRow) {
            title.text = row.title
            artist.text = row.subtitle
            Glide.with(cover)
                .load(row.imageUrl)
                .placeholder(R.drawable.ic_disc)
                .error(R.drawable.ic_disc)
                .into(cover)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<UnisoundSearchRow>() {
            override fun areItemsTheSame(oldItem: UnisoundSearchRow, newItem: UnisoundSearchRow) =
                oldItem.type == newItem.type && oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: UnisoundSearchRow, newItem: UnisoundSearchRow) = oldItem == newItem
        }
    }
}
