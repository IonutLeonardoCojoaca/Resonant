package com.example.resonant.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.network.UnisoundQueueItemDTO

/** Row of the room queue: who added it and, when allowed, a remove button. */
data class UnisoundQueueRow(
    val item: UnisoundQueueItemDTO,
    val addedByLabel: String,
    val canRemove: Boolean
)

class UnisoundQueueAdapter(
    private val onRemove: (UnisoundQueueItemDTO) -> Unit
) : ListAdapter<UnisoundQueueRow, UnisoundQueueAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_playback_queue, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image: ImageView = itemView.findViewById(R.id.queue_song_image)
        private val title: TextView = itemView.findViewById(R.id.queue_song_title)
        private val subtitle: TextView = itemView.findViewById(R.id.queue_song_artist)
        private val remove: ImageButton = itemView.findViewById(R.id.queue_remove)

        init {
            itemView.findViewById<View>(R.id.queue_drag_handle).visibility = View.GONE
            remove.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onRemove(getItem(position).item)
            }
        }

        fun bind(row: UnisoundQueueRow) {
            val song = row.item.song
            title.text = song?.title ?: "Canción"
            val artists = song?.artistName
                ?: song?.artists?.joinToString(", ") { it.name }?.takeIf(String::isNotBlank)
            subtitle.text = listOfNotNull(artists, row.addedByLabel.takeIf(String::isNotBlank))
                .joinToString(" · ")
            remove.visibility = if (row.canRemove) View.VISIBLE else View.GONE
            Glide.with(image)
                .load(song?.coverUrl ?: song?.imageFileName)
                .placeholder(R.drawable.ic_disc)
                .error(R.drawable.ic_disc)
                .into(image)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<UnisoundQueueRow>() {
            override fun areItemsTheSame(oldItem: UnisoundQueueRow, newItem: UnisoundQueueRow) =
                oldItem.item.itemId == newItem.item.itemId

            override fun areContentsTheSame(oldItem: UnisoundQueueRow, newItem: UnisoundQueueRow) =
                oldItem == newItem
        }
    }
}
