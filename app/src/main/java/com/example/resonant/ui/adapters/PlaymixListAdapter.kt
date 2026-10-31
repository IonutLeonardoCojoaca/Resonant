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
import com.example.resonant.ui.bottomsheets.ItemOptions.onLongPressOptions
import com.example.resonant.ui.components.DefaultCover

class PlaymixListAdapter(
    private val onClick: (PlaymixDTO) -> Unit,
    private val onOptionsClick: (PlaymixDTO) -> Unit
) : ListAdapter<PlaymixDTO, PlaymixListAdapter.PlaymixViewHolder>(PlaymixDiffCallback()) {

    /** Mantener pulsado: abre las opciones del elemento (ItemOptions). */
    var onItemLongClick: ((PlaymixDTO) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaymixViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playmix, parent, false)
        return PlaymixViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaymixViewHolder, position: Int) = holder.bind(getItem(position))

    inner class PlaymixViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.playmixCover)
        private val name: TextView = view.findViewById(R.id.playmixName)
        private val info: TextView = view.findViewById(R.id.playmixInfo)
        private val settings: View = view.findViewById(R.id.settingsPlaymix)

        init {
            // Listeners una sola vez; la posición se resuelve al pulsar.
            itemView.setOnClickListener { current()?.let(onClick) }
            settings.setOnClickListener { current()?.let(onOptionsClick) }
            itemView.onLongPressOptions {
                val item = current() ?: return@onLongPressOptions false
                (onItemLongClick ?: onOptionsClick)(item) // por defecto, las mismas opciones que ⋯
                true
            }
        }

        private fun current(): PlaymixDTO? =
            bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::getItem)

        fun bind(playmix: PlaymixDTO) {
            name.text = playmix.name

            val count = playmix.numberOfTracks
            val songText = if (count == 1) "canción" else "canciones"
            info.text = "Playmix ● $count $songText · ${formatDuration(playmix.duration)}"

            // Miniatura: se decodifica a su tamaño en pantalla, no a la resolución original.
            DefaultCover.load(cover, playmix.coverUrl, DefaultCover.Kind.PLAYMIX, playmix.name, 240)
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
            Glide.with(holder.itemView).clear(holder.cover)
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
