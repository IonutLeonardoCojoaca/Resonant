package com.example.resonant.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.resonant.R
import com.example.resonant.data.models.Album
import com.example.resonant.ui.viewmodels.AlbumRank

class TopAlbumAdapter(
    private var albums: List<AlbumRank> = emptyList(),
    private val onAlbumClick: (Album) -> Unit
) : RecyclerView.Adapter<TopAlbumAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rankText: TextView = view.findViewById(R.id.rankText)
        val albumImage: ImageView = view.findViewById(R.id.albumImage)
        val albumName: TextView = view.findViewById(R.id.albumName)
        val albumArtist: TextView = view.findViewById(R.id.albumArtist)
        val albumInfo: TextView = view.findViewById(R.id.albumInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_top_album, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = albums[position]
        val album = item.album

        holder.rankText.text = "#${item.rank}"
        holder.albumName.text = album.title
        holder.albumArtist.text = item.artistName ?: "Artista Desconocido"
        holder.albumInfo.text = "Lanzado en ${item.releaseYear ?: "N/A"}"

        val placeholderRes = R.drawable.ic_playlist_stack
        
        Glide.with(holder.itemView.context)
            .load(album.url)
            .placeholder(placeholderRes)
            .error(placeholderRes)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .into(holder.albumImage)

        holder.itemView.setOnClickListener {
            onAlbumClick(album)
        }
    }

    override fun getItemCount() = albums.size

    fun updateList(newList: List<AlbumRank>) {
        albums = newList
        notifyDataSetChanged()
    }
}
