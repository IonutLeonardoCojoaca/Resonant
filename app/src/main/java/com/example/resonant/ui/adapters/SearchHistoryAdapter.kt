package com.example.resonant.ui.adapters

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.managers.HistoryItem
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.RelativeCornerSize

class SearchHistoryAdapter(
    private val onItemClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : RecyclerView.Adapter<SearchHistoryAdapter.ViewHolder>() {

    private var historyList: List<HistoryItem> = emptyList()

    fun submitList(list: List<HistoryItem>) {
        historyList = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = historyList[position]

        // 1. Poner Texto
        holder.textView.text = item.query

        // 2. Calcular Tiempo
        val now = System.currentTimeMillis()
        val timeAgo = DateUtils.getRelativeTimeSpanString(
            item.timestamp,
            now,
            DateUtils.MINUTE_IN_MILLIS
        )
        val normalizedType = item.type?.trim()?.lowercase()
        val displayType = when (normalizedType) {
            "canción", "cancion" -> "Canción"
            "álbum", "album" -> "Álbum"
            "artista" -> "Artista"
            else -> "Búsqueda"
        }
        holder.timeTextView.text = "$displayType · $timeAgo"

        // En RecyclerView siempre restablecemos forma para evitar reutilizar estados entre filas.
        applyImageShape(holder.iconRecent, normalizedType == "artista" || normalizedType == "artist")

        // 3. Cargar imagen si existe, sino icono por defecto
        
        if (!item.imageUrl.isNullOrBlank()) {
            Glide.with(holder.itemView.context)
                .load(item.imageUrl)
                .centerCrop()
                .into(holder.iconRecent)
            holder.iconRecent.imageTintList = null
        } else {
            holder.iconRecent.imageTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.white)
            )
        }

        // Listeners
        holder.itemView.setOnClickListener { onItemClick(item.query) }
        holder.deleteIcon.setOnClickListener { onDeleteClick(item.query) }
    }

    override fun getItemCount() = historyList.size

    private fun applyImageShape(imageView: ShapeableImageView, isArtist: Boolean) {
        val updatedShape = if (isArtist) {
            imageView.shapeAppearanceModel
                .toBuilder()
                .setAllCorners(CornerFamily.ROUNDED, 0f)
                .setAllCornerSizes(RelativeCornerSize(0.5f))
                .build()
        } else {
            val cornerPx = 8f * imageView.resources.displayMetrics.density
            imageView.shapeAppearanceModel
                .toBuilder()
                .setAllCorners(CornerFamily.ROUNDED, cornerPx)
                .build()
        }
        imageView.shapeAppearanceModel = updatedShape
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconRecent: ShapeableImageView = view.findViewById(R.id.iconRecent)
        val textView: TextView = view.findViewById(R.id.historyText)
        val timeTextView: TextView = view.findViewById(R.id.historyTime)
        val deleteIcon: ImageView = view.findViewById(R.id.deleteHistoryIcon)
    }
}