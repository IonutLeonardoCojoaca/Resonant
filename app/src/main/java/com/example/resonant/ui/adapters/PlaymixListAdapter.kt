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
import com.example.resonant.data.network.PlaymixDTO

class PlaymixListAdapter(
    private val onClick: (PlaymixDTO) -> Unit,
    private val onDeleteClick: (PlaymixDTO) -> Unit
) : ListAdapter<PlaymixDTO, PlaymixListAdapter.PlaymixViewHolder>(PlaymixDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaymixViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playmix, parent, false)
        return PlaymixViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaymixViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PlaymixViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.playmixCover)
        private val name: TextView = view.findViewById(R.id.playmixName)
        private val trackCount: TextView = view.findViewById(R.id.playmixTrackCount)
        private val duration: TextView = view.findViewById(R.id.playmixDuration)
        private val settings: View = view.findViewById(R.id.settingsPlaymix)

        fun bind(playmix: PlaymixDTO) {
            name.text = playmix.name

            val count = playmix.numberOfTracks
            val songText = if (count == 1) "canción" else "canciones"
            trackCount.text = "$count $songText"

            duration.text = formatDuration(playmix.duration)

            if (!playmix.coverUrl.isNullOrEmpty()) {
                Glide.with(cover.context)
                    .load(playmix.coverUrl)
                    .placeholder(R.drawable.ic_playmix)
                    .error(R.drawable.ic_playmix)
                    .centerCrop()
                    .into(cover)
                cover.clearColorFilter()
            } else {
                cover.setImageResource(R.drawable.ic_playmix)
            }

            itemView.setOnClickListener { onClick(playmix) }
            settings.setOnClickListener { onDeleteClick(playmix) }
        }

        private fun formatDuration(seconds: Int): String {
            val hrs = seconds / 3600
            val mins = (seconds % 3600) / 60
            val secs = seconds % 60
            return if (hrs > 0) {
                String.format("%d:%02d:%02d", hrs, mins, secs)
            } else {
                String.format("%d:%02d", mins, secs)
            }
        }
    }

    override fun onViewRecycled(holder: PlaymixViewHolder) {
        super.onViewRecycled(holder)
        try {
            Glide.with(holder.itemView).clear(holder.itemView.findViewById<ImageView>(R.id.playmixCover))
        } catch (_: Exception) { }
    }

    class PlaymixDiffCallback : DiffUtil.ItemCallback<PlaymixDTO>() {
        override fun areItemsTheSame(oldItem: PlaymixDTO, newItem: PlaymixDTO): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: PlaymixDTO, newItem: PlaymixDTO): Boolean {
            return oldItem == newItem
        }
    }
}
