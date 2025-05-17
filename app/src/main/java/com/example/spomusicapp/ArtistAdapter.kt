package com.example.spomusicapp

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat.startActivity
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso

class ArtistAdapter(private val artists: List<Artist>) : RecyclerView.Adapter<ArtistAdapter.ArtistViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_artist, parent, false)
        return ArtistViewHolder(view)
    }

    override fun onBindViewHolder(holder: ArtistViewHolder, position: Int) {
        val artist = artists[position]
        holder.bind(artist)
    }

    override fun getItemCount(): Int = artists.size

    class ArtistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val artistImage: ImageView = itemView.findViewById(R.id.artistImage)
        private val artistName: TextView = itemView.findViewById(R.id.artistName)

        fun bind(artist: Artist) {
            artistName.text = artist.name

            if (!artist.urlPhoto.isNullOrEmpty()) {
                Picasso.get().load(artist.urlPhoto).into(artistImage)
            } else {
                Picasso.get().load(R.drawable.user).into(artistImage) // Imagen por defecto
            }

        }



    }
}
