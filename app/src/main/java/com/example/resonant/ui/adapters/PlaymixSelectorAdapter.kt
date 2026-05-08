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

class PlaymixSelectorAdapter(
    private val onClick: (PlaymixDTO) -> Unit
) : ListAdapter<PlaymixDTO, PlaymixSelectorAdapter.ViewHolder>(PlaymixSelectorDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playmix_bottom_sheet, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.playmixIcon)
        private val name: TextView = view.findViewById(R.id.playmixName)
        private val trackCount: TextView = view.findViewById(R.id.playmixTrackCount)

        fun bind(playmix: PlaymixDTO) {
            name.text = playmix.name
            val count = playmix.numberOfTracks
            trackCount.text = if (count == 1) "1 canción" else "$count canciones"
            if (!playmix.coverUrl.isNullOrBlank()) {
                Glide.with(icon)
                    .load(playmix.coverUrl)
                    .placeholder(R.drawable.ic_playmix)
                    .error(R.drawable.ic_playmix)
                    .centerCrop()
                    .into(icon)
            } else {
                icon.setImageResource(R.drawable.ic_playmix)
            }
            itemView.setOnClickListener { onClick(playmix) }
        }
    }

    class PlaymixSelectorDiffCallback : DiffUtil.ItemCallback<PlaymixDTO>() {
        override fun areItemsTheSame(oldItem: PlaymixDTO, newItem: PlaymixDTO) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: PlaymixDTO, newItem: PlaymixDTO) = oldItem == newItem
    }
}
