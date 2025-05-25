package com.example.spomusicapp

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso

class AlbumAdapter(private val albums: List<Album>) : RecyclerView.Adapter<AlbumAdapter.AlbumViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_album, parent, false)

        return AlbumViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        val album = albums[position]
        holder.bind(album)
    }

    override fun getItemCount(): Int = albums.size

    inner class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val albumImage: ImageView = itemView.findViewById(R.id.artistImage)
        private val albumName: TextView = itemView.findViewById(R.id.albumName)
        private val artistName: TextView = itemView.findViewById(R.id.artistName)

        fun bind(album: Album) {
            artistName.text = album.artistName ?: "Próximamente"
            albumName.text = album.title ?: "Próximamente"

            if (!album.photoUrl.isNullOrEmpty()) {
                Picasso.get().load(album.photoUrl).into(albumImage)
            } else {
                Picasso.get().load(R.drawable.album_stack).into(albumImage)
            }

            itemView.setOnClickListener {
                val intent = Intent(itemView.context, AlbumActivity::class.java).apply {
                    putExtra(PreferenceKeys.CURRENT_ARTIST_ID, album.id)
                }
                itemView.context.startActivity(intent)
            }







        }
    }
}