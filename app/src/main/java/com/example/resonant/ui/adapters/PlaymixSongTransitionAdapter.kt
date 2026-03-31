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
}

class PlaymixSongTransitionAdapter(
    private val onTransitionClick: (PlaymixTransitionDTO) -> Unit,
    private val onSongClick: ((PlaymixSongDTO) -> Unit)? = null,
    private val onSongOptionsClick: ((PlaymixSongDTO) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_SONG = 0
        private const val TYPE_TRANSITION = 1
    }

    private var items: List<PlaymixDetailItem> = emptyList()

    fun submitItems(songs: List<PlaymixSongDTO>, transitions: List<PlaymixTransitionDTO>) {
        val newItems = mutableListOf<PlaymixDetailItem>()
        val sortedSongs = songs.sortedBy { it.position }

        // Pair-based crossfade: transitions between pairs (0-1, 2-3, 4-5, ...)
        // i.e. song 1↔2 form a pair, song 3↔4 form a pair, etc.
        var i = 0
        while (i < sortedSongs.size) {
            newItems.add(PlaymixDetailItem.SongItem(sortedSongs[i]))

            if (i + 1 < sortedSongs.size) {
                // This song has a pair partner → show transition then partner
                val fromSong = sortedSongs[i]
                val toSong = sortedSongs[i + 1]
                val transition = transitions.find {
                    it.fromPlaymixSongId == fromSong.playmixSongId &&
                            it.toPlaymixSongId == toSong.playmixSongId
                }
                if (transition != null) {
                    newItems.add(PlaymixDetailItem.TransitionItem(transition))
                }
                newItems.add(PlaymixDetailItem.SongItem(toSong))
                i += 2
            } else {
                // Odd song without a pair
                i++
            }
        }

        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is PlaymixDetailItem.SongItem -> TYPE_SONG
            is PlaymixDetailItem.TransitionItem -> TYPE_TRANSITION
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
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is PlaymixDetailItem.SongItem -> (holder as SongViewHolder).bind(item.song)
            is PlaymixDetailItem.TransitionItem -> (holder as TransitionViewHolder).bind(item.transition)
        }
    }

    inner class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val positionText: TextView = view.findViewById(R.id.songPosition)
        private val cover: ImageView = view.findViewById(R.id.songCover)
        private val title: TextView = view.findViewById(R.id.songTitle)
        private val artist: TextView = view.findViewById(R.id.songArtist)
        private val duration: TextView = view.findViewById(R.id.songDuration)
        private val optionsButton: ImageButton = view.findViewById(R.id.songOptionsButton)

        fun bind(song: PlaymixSongDTO) {
            positionText.text = "${song.position + 1}"
            title.text = song.title ?: "Sin título"
            artist.text = song.artist ?: "Artista desconocido"
            duration.text = formatDurationMs(song.duration)

            Glide.with(itemView.context)
                .load(song.coverUrl)
                .placeholder(R.drawable.ic_sound)
                .error(R.drawable.ic_sound)
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

    inner class TransitionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.transitionIcon)
        private val score: TextView = view.findViewById(R.id.transitionScore)
        private val verdict: TextView = view.findViewById(R.id.transitionVerdict)

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
            score.text = "${compat?.overallScore ?: 0}/100"
            score.setTextColor(color)
            verdict.text = label
            verdict.setTextColor(color)

            itemView.setOnClickListener { onTransitionClick(transition) }
        }
    }
}
