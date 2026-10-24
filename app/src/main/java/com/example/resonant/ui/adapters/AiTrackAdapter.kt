package com.example.resonant.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.data.network.AiTrackDTO
import com.example.resonant.databinding.ItemAiTrackBinding
import com.example.resonant.managers.AiTrackMessages

/** Generaciones de "Crear con IA" con su etapa. Tocar una la abre; una fallida muestra el motivo y "Reintentar". */
class AiTrackAdapter(
    private val onOpen: (AiTrackDTO) -> Unit,
    private val onRetry: (AiTrackDTO) -> Unit
) : ListAdapter<AiTrackDTO, AiTrackAdapter.ViewHolder>(Diff) {

    inner class ViewHolder(val binding: ItemAiTrackBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onOpen(getItem(position))
            }
            binding.retryButton.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onRetry(getItem(position))
            }
        }

        fun bind(track: AiTrackDTO) {
            binding.trackTitle.text = track.title
            binding.trackStyle.text = track.style
            binding.trackStatus.text = statusLine(track)
            binding.trackProgress.visibility = if (track.isOpen) View.VISIBLE else View.GONE
            binding.retryButton.visibility = if (track.isFailed) View.VISIBLE else View.GONE
            Glide.with(binding.trackCover).load(track.imageUrl).centerCrop().into(binding.trackCover)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemAiTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: ViewHolder) {
        Glide.with(holder.binding.trackCover).clear(holder.binding.trackCover)
        super.onViewRecycled(holder)
    }

    companion object {
        fun statusLine(track: AiTrackDTO): String {
            val seconds = track.durationMs?.div(1000) ?: track.durationSec
            val duration = "%d:%02d".format(seconds / 60, seconds % 60)
            val engine = when (track.engine) {
                "clip" -> "Lyria 3"
                "song" -> "Lyria 3.5 Pro"
                else -> "RealTime"
            }
            return when {
                track.isFailed -> "${AiTrackMessages.forStatus(track.status)} · ${AiTrackMessages.forGenerationError(track.errorCode)}"
                track.isOpen -> {
                    val stage = AiTrackMessages.forStage(track.stage.ifBlank { track.status })
                    if (track.progress in 1..99) "$stage · ${track.progress}% · $engine" else "$stage · $engine"
                }
                else -> "${AiTrackMessages.forStatus(track.status)} · $duration · $engine"
            }
        }

        private val Diff = object : DiffUtil.ItemCallback<AiTrackDTO>() {
            override fun areItemsTheSame(oldItem: AiTrackDTO, newItem: AiTrackDTO) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: AiTrackDTO, newItem: AiTrackDTO) = oldItem == newItem
        }
    }
}
