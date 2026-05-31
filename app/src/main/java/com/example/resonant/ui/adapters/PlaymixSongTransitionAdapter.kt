package com.example.resonant.ui.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.network.PlaymixSongDTO
import com.example.resonant.data.network.PlaymixTransitionDTO

sealed class PlaymixDetailItem {
    data class SongItem(val song: PlaymixSongDTO) : PlaymixDetailItem()
    data class TransitionItem(val transition: PlaymixTransitionDTO) : PlaymixDetailItem()
    data class AddTransitionItem(val transition: PlaymixTransitionDTO) : PlaymixDetailItem()
}

class PlaymixSongTransitionAdapter(
    private val onTransitionClick: (PlaymixTransitionDTO) -> Unit,
    private val onAddTransitionClick: ((PlaymixTransitionDTO) -> Unit)? = null,
    private val onSongClick: ((PlaymixSongDTO) -> Unit)? = null,
    private val onSongOptionsClick: ((PlaymixSongDTO) -> Unit)? = null,
    private val onPreviewClick: ((PlaymixTransitionDTO) -> Unit)? = null,
    private val onCopyTransitionClick: ((PlaymixTransitionDTO) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_SONG = 0
        private const val TYPE_TRANSITION = 1
        private const val TYPE_ADD_TRANSITION = 2
    }

    private var items: List<PlaymixDetailItem> = emptyList()

    fun submitItems(songs: List<PlaymixSongDTO>, transitions: List<PlaymixTransitionDTO>) {
        val newItems = mutableListOf<PlaymixDetailItem>()
        val sortedSongs = songs.sortedBy { it.position }

        for (i in 0 until sortedSongs.size) {
            newItems.add(PlaymixDetailItem.SongItem(sortedSongs[i]))

            if (i < sortedSongs.size - 1) {
                val fromSong = sortedSongs[i]
                val toSong = sortedSongs[i + 1]
                val transition = transitions.find {
                    it.fromPlaymixSongId == fromSong.playmixSongId &&
                            it.toPlaymixSongId == toSong.playmixSongId
                }
                if (transition != null) {
                    newItems.add(PlaymixDetailItem.TransitionItem(transition))
                }
            }
        }

        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is PlaymixDetailItem.SongItem -> TYPE_SONG
            is PlaymixDetailItem.TransitionItem -> TYPE_TRANSITION
            is PlaymixDetailItem.AddTransitionItem -> TYPE_ADD_TRANSITION
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_SONG -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_playmix_song, parent, false)
                SongViewHolder(view)
            }
            TYPE_TRANSITION -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_playmix_transition, parent, false)
                TransitionViewHolder(view)
            }
            TYPE_ADD_TRANSITION -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_playmix_add_transition, parent, false)
                AddTransitionViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is PlaymixDetailItem.SongItem -> (holder as SongViewHolder).bind(item.song)
            is PlaymixDetailItem.TransitionItem -> (holder as TransitionViewHolder).bind(item.transition)
            is PlaymixDetailItem.AddTransitionItem -> (holder as AddTransitionViewHolder).bind(item.transition)
        }
    }

    inner class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val positionText: TextView = view.findViewById(R.id.songPosition)
        private val cover: ImageView = view.findViewById(R.id.songCover)
        private val title: TextView = view.findViewById(R.id.songTitle)
        private val artist: TextView = view.findViewById(R.id.songArtist)
        private val duration: TextView = view.findViewById(R.id.songDuration)
        private val bpm: TextView = view.findViewById(R.id.songBpm)
        private val optionsButton: ImageButton = view.findViewById(R.id.songOptionsButton)

        fun bind(song: PlaymixSongDTO) {
            positionText.text = "${song.position + 1}"
            title.text = song.title ?: "Sin título"
            artist.text = song.artist ?: "Artista desconocido"
            duration.text = formatDurationMs(song.duration)
            
            val songBpm = song.audioAnalysis?.bpm
            if (songBpm != null && songBpm > 0) {
                bpm.text = "${songBpm.toInt()} bpm"
                bpm.visibility = View.VISIBLE
            } else {
                bpm.visibility = View.GONE
            }

            Glide.with(itemView.context)
                .load(song.imageUrl ?: song.coverUrl)
                .placeholder(R.drawable.ic_disc)
                .error(R.drawable.ic_disc)
                .centerCrop()
                .into(cover)

            itemView.setOnClickListener {
                onSongClick?.invoke(song)
            }
            optionsButton.setOnClickListener {
                onSongOptionsClick?.invoke(song)
            }
        }

        private fun formatDurationMs(totalSeconds: Int): String {
            val mins = totalSeconds / 60
            val secs = totalSeconds % 60
            return String.format("%d:%02d", mins, secs)
        }
    }

    inner class AddTransitionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: View = view.findViewById(R.id.addTransitionCard)

        fun bind(transition: PlaymixTransitionDTO) {
            card.setOnClickListener {
                onAddTransitionClick?.invoke(transition)
            }
        }
    }

    inner class TransitionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.transitionIcon)
        private val verdict: TextView = view.findViewById(R.id.transitionVerdict)
        private val modeChip: TextView = view.findViewById(R.id.transitionModeChip)
        private val details: TextView = view.findViewById(R.id.transitionDetails)
        private val keyChip: TextView = view.findViewById(R.id.transitionKeyChip)
        private val presetChip: TextView = view.findViewById(R.id.transitionPresetChip)
        private val previewBtn: ImageView = view.findViewById(R.id.waveformPreviewButton)
        private val copyBtn: ImageView = view.findViewById(R.id.copyTransitionButton)
        private val editBtn: ImageView = view.findViewById(R.id.editTransitionButton)

        fun bind(transition: PlaymixTransitionDTO) {
            val compat = transition.compatibility
            val color: Int
            val label: String

            when (compat?.verdict) {
                "perfect" -> {
                    color = Color.parseColor("#4CAF50")
                    label = "PERFECTA"
                }
                "good" -> {
                    color = Color.parseColor("#8BC34A")
                    label = "BUENA"
                }
                "moderate" -> {
                    color = Color.parseColor("#FFC107")
                    label = "MODERADA"
                }
                "poor" -> {
                    color = Color.parseColor("#F44336")
                    label = "DIFÍCIL"
                }
                else -> {
                    color = Color.GRAY
                    label = "—"
                }
            }

            icon.setColorFilter(color)
            verdict.text = label
            verdict.setTextColor(color)
            modeChip.text = buildModeLabel(transition.mixMode)
            details.text = buildTransitionDetails(transition)

            val keyRelation = compat?.keyRelationship
            if (!keyRelation.isNullOrEmpty() && keyRelation != "unknown") {
                keyChip.text = keyRelation.replace("_", " ").replaceFirstChar { it.uppercase() }
                keyChip.visibility = View.VISIBLE
            } else {
                keyChip.visibility = View.GONE
            }

            val preset = transition.presetName ?: transition.presetCode
            if (!preset.isNullOrBlank()) {
                presetChip.text = if (transition.isPresetModified) "$preset editado" else preset
                presetChip.visibility = View.VISIBLE
            } else {
                presetChip.visibility = View.GONE
            }

            itemView.setOnClickListener { onTransitionClick(transition) }
            previewBtn.setOnClickListener { onPreviewClick?.invoke(transition) }
            copyBtn.setOnClickListener { onCopyTransitionClick?.invoke(transition) }
            editBtn.setOnClickListener { onTransitionClick(transition) }
        }

        private fun buildModeLabel(mixMode: String?): String {
            return when (mixMode) {
                "overlap" -> "Overlap"
                "freq_split" -> "Freq split"
                "club_drop" -> "Club drop"
                "hard_edit" -> "Hard edit"
                else -> "Crossfade"
            }
        }

        private fun buildTransitionDetails(transition: PlaymixTransitionDTO): String {
            val duration = formatTransitionMs(transition.crossfadeDurationMs)
            val gap = if (transition.gapMs > 0) " · gap ${transition.gapMs}ms" else ""
            return "$duration$gap"
        }

        private fun formatTransitionMs(ms: Int): String {
            if (ms <= 0) return "corte"
            val seconds = ms / 1000
            val tenths = (ms % 1000) / 100
            return if (tenths == 0) "${seconds}s" else "$seconds.${tenths}s"
        }
    }
}
