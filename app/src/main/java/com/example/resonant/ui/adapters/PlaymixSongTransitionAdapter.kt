package com.example.resonant.ui.adapters

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.network.PlaymixSongDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.playback.PlaymixMixMode
import com.example.resonant.ui.bottomsheets.ItemOptions.onLongPressOptions
import com.example.resonant.ui.views.PlaymixMixGlyphView

sealed class PlaymixDetailItem {
    data class SongItem(val song: PlaymixSongDTO, val isFirst: Boolean, val isLast: Boolean) : PlaymixDetailItem()

    /** [transition] null = la pareja no tiene transición en el servidor y suena con blend automático. */
    data class TransitionItem(
        val transition: PlaymixTransitionDTO?,
        val from: PlaymixSongDTO,
        val to: PlaymixSongDTO
    ) : PlaymixDetailItem()

    data class AddTransitionItem(val transition: PlaymixTransitionDTO) : PlaymixDetailItem()
}

/**
 * Set de un PlayMix como línea de tiempo: cada canción es un nodo del raíl de marca y entre
 * cada pareja va un conector con su transición (blend automático o transición avanzada).
 */
class PlaymixSongTransitionAdapter(
    private val onTransitionClick: (PlaymixTransitionDTO) -> Unit,
    private val onAddTransitionClick: ((PlaymixTransitionDTO) -> Unit)? = null,
    private val onSongClick: ((PlaymixSongDTO) -> Unit)? = null,
    private val onSongOptionsClick: ((PlaymixSongDTO) -> Unit)? = null,
    private val onPreviewClick: ((PlaymixTransitionDTO) -> Unit)? = null,
    private val onCopyTransitionClick: ((PlaymixTransitionDTO) -> Unit)? = null,
    isReadOnly: Boolean = false,
    /** Compartir el enlace de una transición (playmix-sharing-discovery); null = sin la opción. */
    onShareTransitionClick: ((PlaymixTransitionDTO) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Se puede activar después: las capacidades pueden llegar tras crear la lista (enlace con la app cerrada). */
    var onShareTransitionClick: ((PlaymixTransitionDTO) -> Unit)? = onShareTransitionClick
        set(value) {
            if (field === value) return
            field = value
            notifyDataSetChanged()
        }

    /** Solo lectura (Playmix ajena): se decide con `isOwner` del detalle al cargarlo. */
    var isReadOnly: Boolean = isReadOnly
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** Transición destacada al abrir un enlace `/playmix/{id}/transition/{tid}`. */
    var highlightTransitionId: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** Posición de la transición en la lista, o -1. */
    fun positionOfTransition(transitionId: String): Int =
        items.indexOfFirst { (it as? PlaymixDetailItem.TransitionItem)?.transition?.id == transitionId }

    companion object {
        private const val TYPE_SONG = 0
        private const val TYPE_TRANSITION = 1
        private const val TYPE_ADD_TRANSITION = 2
    }

    private var items: List<PlaymixDetailItem> = emptyList()

    fun submitItems(songs: List<PlaymixSongDTO>, transitions: List<PlaymixTransitionDTO>) {
        val newItems = mutableListOf<PlaymixDetailItem>()
        val sortedSongs = songs.sortedBy { it.position }

        for (i in sortedSongs.indices) {
            newItems.add(PlaymixDetailItem.SongItem(sortedSongs[i], isFirst = i == 0, isLast = i == sortedSongs.lastIndex))

            if (i < sortedSongs.size - 1) {
                val fromSong = sortedSongs[i]
                val toSong = sortedSongs[i + 1]
                val transition = transitions.find {
                    it.fromPlaymixSongId == fromSong.playmixSongId &&
                            it.toPlaymixSongId == toSong.playmixSongId
                }
                newItems.add(PlaymixDetailItem.TransitionItem(transition, fromSong, toSong))
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
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SONG -> SongViewHolder(inflater.inflate(R.layout.item_playmix_song, parent, false))
            TYPE_TRANSITION -> TransitionViewHolder(inflater.inflate(R.layout.item_playmix_transition, parent, false))
            TYPE_ADD_TRANSITION -> AddTransitionViewHolder(inflater.inflate(R.layout.item_playmix_add_transition, parent, false))
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is PlaymixDetailItem.SongItem -> (holder as SongViewHolder).bind(item)
            is PlaymixDetailItem.TransitionItem -> (holder as TransitionViewHolder).bind(item)
            is PlaymixDetailItem.AddTransitionItem -> (holder as AddTransitionViewHolder).bind(item.transition)
        }
    }

    inner class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val railTop: View = view.findViewById(R.id.songRailTop)
        private val railBottom: View = view.findViewById(R.id.songRailBottom)
        private val positionText: TextView = view.findViewById(R.id.songPosition)
        private val cover: ImageView = view.findViewById(R.id.songCover)
        private val title: TextView = view.findViewById(R.id.songTitle)
        private val artist: TextView = view.findViewById(R.id.songArtist)
        private val duration: TextView = view.findViewById(R.id.songDuration)
        private val bpm: TextView = view.findViewById(R.id.songBpm)
        private val optionsButton: ImageButton = view.findViewById(R.id.songOptionsButton)

        init {
            itemView.setOnClickListener { currentSong()?.let { onSongClick?.invoke(it) } }
            optionsButton.setOnClickListener { currentSong()?.let { onSongOptionsClick?.invoke(it) } }
            itemView.onLongPressOptions {
                val song = currentSong() ?: return@onLongPressOptions false
                val open = onSongOptionsClick?.takeUnless { isReadOnly } ?: return@onLongPressOptions false
                open(song)
                true
            }
        }

        private fun currentSong(): PlaymixSongDTO? =
            (items.getOrNull(bindingAdapterPosition) as? PlaymixDetailItem.SongItem)?.song

        fun bind(item: PlaymixDetailItem.SongItem) {
            val song = item.song
            railTop.visibility = if (item.isFirst) View.INVISIBLE else View.VISIBLE
            railBottom.visibility = if (item.isLast) View.INVISIBLE else View.VISIBLE
            positionText.text = "${song.position + 1}"
            title.text = song.title ?: "Sin título"
            artist.text = song.artist ?: "Artista desconocido"
            duration.text = formatDurationSeconds(song.duration)

            val songBpm = song.audioAnalysis?.bpm?.takeIf { it > 0 }
            val key = song.audioAnalysis?.musicalKey?.takeIf { it.isNotBlank() }
            bpm.isVisible = songBpm != null
            bpm.text = listOfNotNull(songBpm?.let { "${it.toInt()} BPM" }, key).joinToString(" · ")

            Glide.with(itemView.context)
                .load(song.imageUrl ?: song.coverUrl)
                .placeholder(R.drawable.ic_disc)
                .error(R.drawable.ic_disc)
                .centerCrop()
                .into(cover)

            optionsButton.visibility = if (isReadOnly) View.GONE else View.VISIBLE
        }

        private fun formatDurationSeconds(totalSeconds: Int): String =
            String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
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
        private val context = view.context
        private val accent = ContextCompat.getColor(context, R.color.playmix_accent)
        private val accentEnd = ContextCompat.getColor(context, R.color.playmix_accent_end)
        private val idleText = ContextCompat.getColor(context, R.color.textTheme)

        private val node: View = view.findViewById(R.id.transitionNode)
        private val nodeIcon: ImageView = view.findViewById(R.id.transitionNodeIcon)
        private val card: View = view.findViewById(R.id.transitionCard)
        private val glyph: PlaymixMixGlyphView = view.findViewById(R.id.transitionGlyph)
        private val eyebrow: TextView = view.findViewById(R.id.transitionEyebrow)
        private val verdictDot: View = view.findViewById(R.id.transitionVerdictDot)
        private val verdict: TextView = view.findViewById(R.id.transitionVerdict)
        private val title: TextView = view.findViewById(R.id.transitionTitle)
        private val details: TextView = view.findViewById(R.id.transitionDetails)
        private val previewBtn: ImageButton = view.findViewById(R.id.waveformPreviewButton)
        private val moreBtn: ImageButton = view.findViewById(R.id.transitionMoreButton)

        init {
            node.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(context, R.color.primaryColorTheme))
                setStroke((1.5f * context.resources.displayMetrics.density).toInt(), ColorUtils.setAlphaComponent(accent, 0x99))
            }
            nodeIcon.imageTintList = ColorStateList.valueOf(accent)
            glyph.colorA = accent
            glyph.colorB = accentEnd

            card.setOnClickListener {
                val transition = currentTransition() ?: return@setOnClickListener
                if (!isReadOnly) {
                    onTransitionClick(transition)
                } else if (!PlaymixMixMode.isSmart(transition)) {
                    onPreviewClick?.invoke(transition)
                }
            }
            previewBtn.setOnClickListener { currentTransition()?.let { onPreviewClick?.invoke(it) } }
            moreBtn.setOnClickListener { anchor -> currentTransition()?.let { showMoreMenu(anchor, it) } }
        }

        private fun currentTransition(): PlaymixTransitionDTO? =
            (items.getOrNull(bindingAdapterPosition) as? PlaymixDetailItem.TransitionItem)?.transition

        fun bind(item: PlaymixDetailItem.TransitionItem) {
            val transition = item.transition
            val smart = PlaymixMixMode.isSmart(transition)
            val summary = PlaymixTransitionLabels.summarize(
                transition, item.from.audioAnalysis?.bpm, item.to.audioAnalysis?.bpm
            )
            eyebrow.text = summary.eyebrow
            eyebrow.setTextColor(if (smart) accent else idleText)
            title.text = summary.title
            details.text = summary.details
            glyph.mixMode = transition?.mixMode ?: PlaymixMixMode.SMART

            val verdictCode = transition?.compatibility?.verdict
            val verdictText = PlaymixTransitionLabels.verdict(verdictCode)
            verdict.isVisible = verdictText != null
            verdictDot.isVisible = verdictText != null
            verdict.text = verdictText
            verdictDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(verdictColor(verdictCode))
            }

            // Escuchar la transición solo tiene sentido con puntos fijos (avanzada o Studio).
            previewBtn.isVisible = transition != null && !smart && onPreviewClick != null
            // Ajena: el menú solo copia o comparte (sin editar); propia: igual que antes.
            moreBtn.isVisible = transition != null &&
                (!isReadOnly || (!smart && onCopyTransitionClick != null) || onShareTransitionClick != null)
            card.isClickable = transition != null
            val highlighted = transition != null && transition.id == highlightTransitionId
            itemView.setBackgroundColor(if (highlighted) ColorUtils.setAlphaComponent(accent, 0x2E) else Color.TRANSPARENT)
        }

        /** Menú ⋮ con el estilo de la app (mismo panel que el menú de Aria), alineado a la derecha del botón. */
        private fun showMoreMenu(anchor: View, transition: PlaymixTransitionDTO) {
            val smart = PlaymixMixMode.isSmart(transition)
            val menuView = LayoutInflater.from(anchor.context).inflate(R.layout.popup_playmix_transition_menu, null)
            // Inflado sin padre pierde el ancho del XML: se fija aquí (240dp, como el layout).
            val menuWidth = (240 * anchor.resources.displayMetrics.density).toInt()
            val popup = PopupWindow(
                menuView,
                menuWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                elevation = 16f * anchor.resources.displayMetrics.density
                animationStyle = android.R.style.Animation_Dialog
            }

            menuView.findViewById<TextView>(R.id.menuEditSubtitle).text =
                if (smart) "Blend automático o transición avanzada" else "Puntos, curva y EQ"
            menuView.findViewById<View>(R.id.menuEditTransition).apply {
                isVisible = !isReadOnly
                setOnClickListener {
                    popup.dismiss()
                    onTransitionClick(transition)
                }
            }
            // Copiar solo tiene sentido con puntos fijos; el blend automático se recalcula en cada set.
            val canCopy = !smart && onCopyTransitionClick != null
            menuView.findViewById<View>(R.id.menuCopyDivider).isVisible = canCopy && !isReadOnly
            val canShare = onShareTransitionClick != null
            menuView.findViewById<View>(R.id.menuShareDivider).isVisible = canShare && (canCopy || !isReadOnly)
            menuView.findViewById<View>(R.id.menuShareTransition).apply {
                isVisible = canShare
                setOnClickListener {
                    popup.dismiss()
                    onShareTransitionClick?.invoke(transition)
                }
            }
            menuView.findViewById<View>(R.id.menuCopyTransition).apply {
                isVisible = canCopy
                setOnClickListener {
                    popup.dismiss()
                    onCopyTransitionClick?.invoke(transition)
                }
            }

            popup.showAsDropDown(anchor, anchor.width - menuWidth, 0)
        }

        private fun verdictColor(code: String?): Int = when (code) {
            "perfect" -> Color.parseColor("#4CAF50")
            "good" -> Color.parseColor("#8BC34A")
            "moderate" -> Color.parseColor("#FFC107")
            "poor" -> Color.parseColor("#F44336")
            else -> Color.GRAY
        }
    }
}
