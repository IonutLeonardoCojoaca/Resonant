package com.example.resonant.ui.adapters

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.data.models.Genre
import com.example.resonant.ui.bottomsheets.ItemOptions.onLongPressOptions
import java.util.concurrent.ConcurrentHashMap

/**
 * Géneros en tarjeta con degradado. `ListAdapter` + `DiffUtil`: Explorar vuelve a pintar sus secciones en cada
 * actualización y antes cada pintado era un `notifyDataSetChanged` con parseo de colores y un drawable nuevo
 * por fila; ahora solo cambian las filas distintas y los colores se parsean una vez por género.
 */
class GenreAdapter(
    initial: List<Genre>,
    private val viewType: Int = VIEW_TYPE_NORMAL,
    private val onGenreClick: (Genre) -> Unit
) : ListAdapter<Genre, GenreAdapter.GenreViewHolder>(GenreDiffCallback()) {

    /** Mantener pulsado: abre las opciones del elemento (ItemOptions). */
    var onItemLongClick: ((Genre) -> Unit)? = null

    init {
        if (initial.isNotEmpty()) submitList(initial)
    }

    companion object {
        const val VIEW_TYPE_NORMAL = 0
        const val VIEW_TYPE_MINI = 1

        private val FALLBACK_COLORS = intArrayOf(Color.parseColor("#555555"), Color.parseColor("#333333"))
        private val gradientCache = ConcurrentHashMap<String, IntArray>()

        /** Colores del degradado de un género ("#a;#b" o "#a,#b"), parseados una sola vez. */
        private fun gradientColors(raw: String?): IntArray {
            if (raw.isNullOrEmpty()) return FALLBACK_COLORS
            return gradientCache.getOrPut(raw) {
                val separator = if (raw.contains(";")) ";" else ","
                val parsed = raw.split(separator).mapNotNull { part ->
                    val hex = part.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    runCatching { Color.parseColor(if (hex.startsWith("#")) hex else "#$hex") }.getOrNull()
                }
                when (parsed.size) {
                    0 -> FALLBACK_COLORS
                    1 -> intArrayOf(parsed[0], parsed[0])
                    else -> parsed.toIntArray()
                }
            }
        }
    }

    /** Mismo nombre que antes para no tocar a quienes ya lo llaman; delega en `submitList`. */
    fun updateList(newGenres: List<Genre>) = submitList(newGenres)

    override fun getItemViewType(position: Int): Int = viewType

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GenreViewHolder {
        val layoutRes = if (viewType == VIEW_TYPE_MINI) R.layout.item_genre_mini else R.layout.item_genre
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return GenreViewHolder(view)
    }

    override fun onBindViewHolder(holder: GenreViewHolder, position: Int) = holder.bind(getItem(position))

    inner class GenreViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textName: TextView = itemView.findViewById(R.id.textGenreName)
        private val viewGradient: View = itemView.findViewById(R.id.viewGradientBackground)
        private val gradient = GradientDrawable(GradientDrawable.Orientation.BL_TR, FALLBACK_COLORS).apply { cornerRadius = 0f }

        init {
            // Sin borde para mantener la tarjeta limpia.
            itemView.findViewById<com.google.android.material.card.MaterialCardView?>(R.id.cardContainer)?.strokeWidth = 0
            viewGradient.background = gradient
            // Listeners una sola vez; la posición se resuelve al pulsar.
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onGenreClick(getItem(pos))
            }
            itemView.onLongPressOptions {
                val pos = bindingAdapterPosition
                val open = onItemLongClick
                if (pos == RecyclerView.NO_POSITION || open == null) return@onLongPressOptions false
                open(getItem(pos))
                true
            }
        }

        fun bind(genre: Genre) {
            textName.text = genre.name
            gradient.colors = gradientColors(genre.gradientColors)
        }
    }

    class GenreDiffCallback : DiffUtil.ItemCallback<Genre>() {
        override fun areItemsTheSame(oldItem: Genre, newItem: Genre): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Genre, newItem: Genre): Boolean = oldItem == newItem
    }
}
