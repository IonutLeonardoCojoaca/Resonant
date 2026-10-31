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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.network.PlaymixDetailDTO
import com.example.resonant.data.network.PlaymixSongDTO
import com.example.resonant.data.network.PlaymixTransitionDTO
import com.example.resonant.playback.PlaymixMixMode
import com.example.resonant.ui.bottomsheets.ItemOptions.onLongPressOptions
import com.example.resonant.ui.views.PlaymixMixGlyphView
import com.example.resonant.ui.views.PlaymixTempoFlowView
import com.example.resonant.utils.ImageRequestHelper
import kotlin.math.roundToInt

sealed class PlaymixDetailItem {
    /** Identidad estable para DiffUtil. */
    abstract val key: String

    /** Curva de tempo del set y etiqueta "EL SET": primer elemento de la lista (antes, vistas sobre ella). */
    data class Overview(val tempos: List<Double?>, val rangeText: String?) : PlaymixDetailItem() {
        override val key: String get() = "overview"
    }

    data class SongItem(val song: PlaymixSongDTO, val isFirst: Boolean, val isLast: Boolean) : PlaymixDetailItem() {
        override val key: String get() = "song:${song.playmixSongId}"
    }

    /** [transition] null = la pareja no tiene transición en el servidor y suena con blend automático. */
    data class TransitionItem(
        val transition: PlaymixTransitionDTO?,
        val from: PlaymixSongDTO,
        val to: PlaymixSongDTO
    ) : PlaymixDetailItem() {
        override val key: String get() = "transition:${from.playmixSongId}:${to.playmixSongId}"
    }
}

/**
 * Set de un PlayMix como línea de tiempo: cada canción es un nodo del raíl de marca y entre
 * cada pareja va un conector con su transición (blend automático o transición avanzada).
 *
 * `ListAdapter` + `DiffUtil`: cada actualización del detalle solo toca las filas que cambian, y los
 * flags (solo lectura, transición destacada, compartir) se aplican con un payload sin volver a
 * enlazar las filas enteras. Antes, cada uno de esos cambios era un `notifyDataSetChanged`.
 */
class PlaymixSongTransitionAdapter(
    private val onTransitionClick: (PlaymixTransitionDTO) -> Unit,
    private val onSongClick: ((PlaymixSongDTO) -> Unit)? = null,
    private val onSongOptionsClick: ((PlaymixSongDTO) -> Unit)? = null,
    private val onPreviewClick: ((PlaymixTransitionDTO) -> Unit)? = null,
    private val onCopyTransitionClick: ((PlaymixTransitionDTO) -> Unit)? = null,
    isReadOnly: Boolean = false,
    /** Compartir el enlace de una transición (playmix-sharing-discovery); null = sin la opción. */
    onShareTransitionClick: ((PlaymixTransitionDTO) -> Unit)? = null
) : ListAdapter<PlaymixDetailItem, RecyclerView.ViewHolder>(DIFF) {

    /** Se puede activar después: las capacidades pueden llegar tras crear la lista (enlace con la app cerrada). */
    var onShareTransitionClick: ((PlaymixTransitionDTO) -> Unit)? = onShareTransitionClick
        set(value) {
            if (field === value) return
            field = value
            notifyFlagsChanged()
        }

    /** Solo lectura (Playmix ajena): se decide con `isOwner` del detalle al cargarlo. */
    var isReadOnly: Boolean = isReadOnly
        set(value) {
            if (field == value) return
            field = value
            notifyFlagsChanged()
        }

    /** Transición destacada al abrir un enlace `/playmix/{id}/transition/{tid}`. */
    var highlightTransitionId: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyFlagsChanged()
        }

    private fun notifyFlagsChanged() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_FLAGS)
    }

    /** Posición de la transición en la lista, o -1. */
    fun positionOfTransition(transitionId: String): Int =
        currentList.indexOfFirst { (it as? PlaymixDetailItem.TransitionItem)?.transition?.id == transitionId }

    fun submitDetail(detail: PlaymixDetailDTO) = submitList(buildItems(detail))

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is PlaymixDetailItem.Overview -> TYPE_OVERVIEW
        is PlaymixDetailItem.SongItem -> TYPE_SONG
        is PlaymixDetailItem.TransitionItem -> TYPE_TRANSITION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_OVERVIEW -> OverviewViewHolder(inflater.inflate(R.layout.item_playmix_set_overview, parent, false))
            TYPE_SONG -> SongViewHolder(inflater.inflate(R.layout.item_playmix_song, parent, false))
            TYPE_TRANSITION -> TransitionViewHolder(inflater.inflate(R.layout.item_playmix_transition, parent, false))
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is PlaymixDetailItem.Overview -> (holder as OverviewViewHolder).bind(item)
            is PlaymixDetailItem.SongItem -> (holder as SongViewHolder).bind(item)
            is PlaymixDetailItem.TransitionItem -> (holder as TransitionViewHolder).bind(item)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty() || payloads.any { it != PAYLOAD_FLAGS }) {
            onBindViewHolder(holder, position)
            return
        }
        when (val item = getItem(position)) {
            is PlaymixDetailItem.SongItem -> (holder as SongViewHolder).bindFlags()
            is PlaymixDetailItem.TransitionItem -> (holder as TransitionViewHolder).bindFlags(item)
            is PlaymixDetailItem.Overview -> Unit
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is SongViewHolder) Glide.with(holder.itemView).clear(holder.cover)
    }

    inner class OverviewViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val section: View = view.findViewById(R.id.tempoFlowSection)
        private val range: TextView = view.findViewById(R.id.tempoFlowRange)
        private val flow: PlaymixTempoFlowView = view.findViewById(R.id.tempoFlowView)
        private var lastTempos: List<Double?>? = null

        init {
            flow.setColors(
                ContextCompat.getColor(view.context, R.color.playmix_accent),
                ContextCompat.getColor(view.context, R.color.playmix_accent_end)
            )
        }

        fun bind(item: PlaymixDetailItem.Overview) {
            section.isVisible = item.rangeText != null
            range.text = item.rangeText
            // La curva se revela animada la primera vez que se pinta un set; los repintados posteriores, no.
            flow.setTempos(item.tempos, animate = lastTempos == null)
            lastTempos = item.tempos
        }
    }

    inner class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val railTop: View = view.findViewById(R.id.songRailTop)
        private val railBottom: View = view.findViewById(R.id.songRailBottom)
        private val positionText: TextView = view.findViewById(R.id.songPosition)
        val cover: ImageView = view.findViewById(R.id.songCover)
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
            (currentList.getOrNull(bindingAdapterPosition) as? PlaymixDetailItem.SongItem)?.song

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

            val url = song.imageUrl ?: song.coverUrl
            Glide.with(cover)
                .load(url?.takeIf { it.isNotBlank() }?.let { ImageRequestHelper.buildGlideModel(cover.context, it) })
                .override(COVER_PX)
                .placeholder(R.drawable.ic_disc)
                .error(R.drawable.ic_disc)
                .centerCrop()
                .into(cover)

            bindFlags()
        }

        fun bindFlags() {
            optionsButton.visibility = if (isReadOnly) View.GONE else View.VISIBLE
        }

        private fun formatDurationSeconds(totalSeconds: Int): String =
            String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
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
        private val verdictDotDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL }

        init {
            node.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(context, R.color.primaryColorTheme))
                setStroke((1.5f * context.resources.displayMetrics.density).toInt(), ColorUtils.setAlphaComponent(accent, 0x99))
            }
            nodeIcon.imageTintList = ColorStateList.valueOf(accent)
            glyph.colorA = accent
            glyph.colorB = accentEnd
            verdictDot.background = verdictDotDrawable

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
            (currentList.getOrNull(bindingAdapterPosition) as? PlaymixDetailItem.TransitionItem)?.transition

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
            verdictDotDrawable.setColor(verdictColor(verdictCode))

            // Escuchar la transición solo tiene sentido con puntos fijos (avanzada o Studio).
            previewBtn.isVisible = transition != null && !smart && onPreviewClick != null
            card.isClickable = transition != null
            bindFlags(item)
        }

        /** Lo que depende de los flags del adapter (solo lectura, compartir, destacada): barato, sin rebind. */
        fun bindFlags(item: PlaymixDetailItem.TransitionItem) {
            val transition = item.transition
            val smart = PlaymixMixMode.isSmart(transition)
            // Ajena: el menú solo copia o comparte (sin editar); propia: igual que antes.
            moreBtn.isVisible = transition != null &&
                (!isReadOnly || (!smart && onCopyTransitionClick != null) || onShareTransitionClick != null)
            val highlighted = transition != null && transition.id == highlightTransitionId
            itemView.setBackgroundColor(if (highlighted) ColorUtils.setAlphaComponent(accent, 0x2E) else Color.TRANSPARENT)
        }

        /** Menú ⋮ con el estilo de la app (mismo panel que el menú de Aria), alineado a la derecha del botón. */
        private fun showMoreMenu(anchor: View, transition: PlaymixTransitionDTO) {
            val smart = PlaymixMixMode.isSmart(transition)
            val menuView = LayoutInflater.from(anchor.context).inflate(R.layout.popup_playmix_transition_menu, null)
            // Inflado sin padre pierde el ancho del XML: se fija aquí (240dp, como el layout).
            val menuWidth = (240 * anchor.resources.displayMetrics.density).toInt()
            val popup = PopupWindow(menuView, menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
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
    }

    companion object {
        private const val TYPE_OVERVIEW = 0
        private const val TYPE_SONG = 1
        private const val TYPE_TRANSITION = 2
        /** Portada de 54dp: decodificada a su tamaño, no a la resolución original. */
        private const val COVER_PX = 180
        private val PAYLOAD_FLAGS = Any()

        private val VERDICT_PERFECT = Color.parseColor("#4CAF50")
        private val VERDICT_GOOD = Color.parseColor("#8BC34A")
        private val VERDICT_MODERATE = Color.parseColor("#FFC107")
        private val VERDICT_POOR = Color.parseColor("#F44336")

        private fun verdictColor(code: String?): Int = when (code) {
            "perfect" -> VERDICT_PERFECT
            "good" -> VERDICT_GOOD
            "moderate" -> VERDICT_MODERATE
            "poor" -> VERDICT_POOR
            else -> Color.GRAY
        }

        /** Set ordenado: resumen (curva de tempo) + canción, transición, canción, ... */
        fun buildItems(detail: PlaymixDetailDTO): List<PlaymixDetailItem> {
            val sortedSongs = detail.songs.sortedBy { it.position }
            if (sortedSongs.isEmpty()) return emptyList()
            val tempos = sortedSongs.map { song -> song.audioAnalysis?.bpm?.takeIf { it > 0 } }
            val known = tempos.filterNotNull()
            val rangeText = if (known.size >= 2) "${known.min().roundToInt()}–${known.max().roundToInt()} BPM" else null
            val items = ArrayList<PlaymixDetailItem>(sortedSongs.size * 2)
            items += PlaymixDetailItem.Overview(tempos, rangeText)
            for (i in sortedSongs.indices) {
                items += PlaymixDetailItem.SongItem(sortedSongs[i], isFirst = i == 0, isLast = i == sortedSongs.lastIndex)
                if (i < sortedSongs.size - 1) {
                    val fromSong = sortedSongs[i]
                    val toSong = sortedSongs[i + 1]
                    val transition = detail.transitions.find {
                        it.fromPlaymixSongId == fromSong.playmixSongId && it.toPlaymixSongId == toSong.playmixSongId
                    }
                    items += PlaymixDetailItem.TransitionItem(transition, fromSong, toSong)
                }
            }
            return items
        }

        private val DIFF = object : DiffUtil.ItemCallback<PlaymixDetailItem>() {
            override fun areItemsTheSame(oldItem: PlaymixDetailItem, newItem: PlaymixDetailItem) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: PlaymixDetailItem, newItem: PlaymixDetailItem) = oldItem == newItem
        }
    }
}
