package com.example.resonant.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R

class FavoriteArtistsHeaderAdapter : RecyclerView.Adapter<FavoriteArtistsHeaderAdapter.HeaderViewHolder>() {

    private var artistCount: Int = 0

    // Actualización eficiente mediante Payload
    fun updateCount(count: Int) {
        if (artistCount != count) {
            artistCount = count
            // Pasamos el entero como payload para actualizar SOLO el texto
            notifyItemChanged(0, count)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        // Asegúrate de usar el XML correcto creado arriba
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_favorites_artists_header, parent, false)
        return HeaderViewHolder(view)
    }

    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        holder.bind(artistCount)
    }

    // Bind parcial para eficiencia máxima
    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            for (payload in payloads) {
                if (payload is Int) {
                    holder.updateCountOnly(payload)
                }
            }
        }
    }

    override fun getItemCount(): Int = 1

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val favoriteCountText: TextView = itemView.findViewById(R.id.favoriteCount)

        fun bind(count: Int) {
            updateCountOnly(count)
        }

        fun updateCountOnly(count: Int) {
            val text = if (count == 1) "Tienes 1 artista." else "Tienes $count artistas."
            favoriteCountText.text = text
        }
    }
}