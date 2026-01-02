package com.example.resonant.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R

class FavoriteAlbumsHeaderAdapter : RecyclerView.Adapter<FavoriteAlbumsHeaderAdapter.HeaderViewHolder>() {

    private var albumCount: Int = 0

    fun updateCount(count: Int) {
        if (albumCount != count) {
            albumCount = count
            notifyItemChanged(0, count)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_favorites_albums_header, parent, false)
        return HeaderViewHolder(view)
    }

    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        holder.bind(albumCount)
    }

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
            val text = if (count == 1) "Tienes 1 álbum." else "Tienes $count álbumes."
            favoriteCountText.text = text
        }
    }
}