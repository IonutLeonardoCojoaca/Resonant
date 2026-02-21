package com.example.resonant.ui.adapters

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.data.models.Genre

class GenreAdapter(
    private var genres: List<Genre>,
    private val viewType: Int = VIEW_TYPE_NORMAL,
    private val onGenreClick: (Genre) -> Unit
) : RecyclerView.Adapter<GenreAdapter.GenreViewHolder>() {

    companion object {
        const val VIEW_TYPE_NORMAL = 0
        const val VIEW_TYPE_MINI = 1
    }

    fun updateList(newGenres: List<Genre>) {
        this.genres = newGenres
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return viewType
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GenreViewHolder {
        val layoutRes = if (viewType == VIEW_TYPE_MINI) R.layout.item_genre_mini else R.layout.item_genre
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return GenreViewHolder(view)
    }

    override fun onBindViewHolder(holder: GenreViewHolder, position: Int) {
        holder.bind(genres[position], onGenreClick)
    }

    override fun getItemCount(): Int = genres.size

    class GenreViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textName: TextView = itemView.findViewById(R.id.textGenreName)
        private val viewGradient: View = itemView.findViewById(R.id.viewGradientBackground)

        // Capturamos el contenedor de la tarjeta para cambiar su borde
        private val cardContainer: com.google.android.material.card.MaterialCardView? =
            itemView.findViewById(R.id.cardContainer)

        fun bind(genre: Genre, onClick: (Genre) -> Unit) {
            textName.text = genre.name

            // --- PROCESAMIENTO DE COLORES ---
            val colorList = mutableListOf<Int>()
            val rawColorsString = genre.gradientColors

            if (!rawColorsString.isNullOrEmpty()) {
                val separator = if (rawColorsString.contains(";")) ";" else ","
                val parts = rawColorsString.split(separator)

                for (part in parts) {
                    try {
                        var hex = part.trim()
                        if (hex.isNotEmpty()) {
                            if (!hex.startsWith("#")) hex = "#$hex"
                            colorList.add(Color.parseColor(hex))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            // --- APLICAR GRADIENTE Y BORDES ---
            val finalColors = if (colorList.size >= 2) {
                colorList.toIntArray()
            } else if (colorList.size == 1) {
                intArrayOf(colorList[0], colorList[0])
            } else {
                intArrayOf(Color.parseColor("#555555"), Color.parseColor("#333333"))
            }

            // Aplicamos el color al borde de la tarjeta (si existe en este ViewType)
            if (cardContainer != null) {
                if (colorList.isNotEmpty()) {
                    cardContainer.strokeColor = colorList[0] // Usa el primer color para el borde
                } else {
                    cardContainer.strokeColor = Color.WHITE // Color por defecto si falla
                }
            }

            val gradient = GradientDrawable(GradientDrawable.Orientation.BL_TR, finalColors)
            gradient.cornerRadius = 0f

            viewGradient.background = gradient

            itemView.setOnClickListener { onClick(genre) }
        }
    }
}