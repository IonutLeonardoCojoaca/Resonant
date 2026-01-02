package com.example.resonant.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.google.android.material.button.MaterialButton

class FavoriteSongsHeaderAdapter(
    private val onPlayClick: () -> Unit
) : RecyclerView.Adapter<FavoriteSongsHeaderAdapter.HeaderViewHolder>() {

    private var songCount: Int = 0
    private var isPlaying: Boolean = false

    // 1. MODIFICADO: Usamos un payload (el nuevo valor)
    fun updateCount(count: Int) {
        if (songCount != count) {
            songCount = count
            // Pasamos 'count' como payload para decir "solo actualiza el número"
            notifyItemChanged(0, count)
        }
    }

    // 1. MODIFICADO: Usamos un payload (un string o constante)
    fun setPlayingState(playing: Boolean) {
        if (isPlaying != playing) {
            isPlaying = playing
            // Pasamos un string para identificar qué cambió
            notifyItemChanged(0, "PLAY_STATE")
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_favorites_songs_header, parent, false)
        return HeaderViewHolder(view)
    }

    // Bind completo (se ejecuta solo la primera vez que se crea la vista)
    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        holder.bind(songCount, isPlaying)
    }

    // 2. NUEVO: Bind parcial (se ejecuta cuando usamos payloads)
    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            // Si no hay payload, haz el bind completo estándar
            super.onBindViewHolder(holder, position, payloads)
        } else {
            // Si hay payload, actualiza SOLO lo que cambió
            for (payload in payloads) {
                if (payload is Int) {
                    holder.updateCountOnly(payload)
                } else if (payload == "PLAY_STATE") {
                    holder.updatePlayStateOnly(isPlaying)
                }
            }
        }
    }

    override fun getItemCount(): Int = 1

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val favoriteSongsNumber: TextView = itemView.findViewById(R.id.favoriteSongsNumber)
        private val playButton: MaterialButton = itemView.findViewById(R.id.playButton)
        // La imagen de fondo NO se toca aquí, por lo tanto no parpadeará

        init {
            playButton.setOnClickListener { onPlayClick() }
        }

        fun bind(count: Int, isPlaying: Boolean) {
            updateCountOnly(count)
            updatePlayStateOnly(isPlaying)
        }

        // Métodos pequeños para actualizaciones granulares
        fun updateCountOnly(count: Int) {
            favoriteSongsNumber.text = "Tienes $count canciones."
        }

        fun updatePlayStateOnly(playing: Boolean) {
            if (playing) {
                playButton.setIconResource(R.drawable.ic_pause)
            } else {
                playButton.setIconResource(R.drawable.ic_play)
            }
        }
    }
}