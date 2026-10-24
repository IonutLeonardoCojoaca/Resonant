package com.example.resonant.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.data.models.Song
import com.example.resonant.databinding.ItemAiTrackReferenceBinding

/** Resultados de búsqueda de canciones de referencia en Crear con IA; tocar una la añade. */
class AiTrackReferenceAdapter(
    private val onClick: (Song) -> Unit
) : ListAdapter<Song, AiTrackReferenceAdapter.ViewHolder>(Diff) {

    inner class ViewHolder(val binding: ItemAiTrackReferenceBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(getItem(position))
            }
        }

        fun bind(song: Song) {
            binding.referenceTitle.text = song.title
            binding.referenceArtist.text = artistLine(song)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemAiTrackReferenceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    companion object {
        fun artistLine(song: Song): String =
            song.artistName?.takeIf { it.isNotBlank() } ?: song.artists.joinToString(", ") { it.name }

        private val Diff = object : DiffUtil.ItemCallback<Song>() {
            override fun areItemsTheSame(oldItem: Song, newItem: Song) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Song, newItem: Song) =
                oldItem.title == newItem.title && artistLine(oldItem) == artistLine(newItem)
        }
    }
}
