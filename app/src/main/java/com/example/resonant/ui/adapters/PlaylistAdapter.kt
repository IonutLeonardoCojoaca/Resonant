package com.example.resonant.ui.adapters

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.navigation.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.models.Playlist
import com.example.resonant.ui.bottomsheets.ItemOptions.onLongPressOptions
import com.example.resonant.ui.components.DefaultCover

class PlaylistAdapter(
    private val viewType: Int,
    private val onClick: ((Playlist) -> Unit)? = null,
    private val onPlaylistLongClick: ((Playlist, Bitmap?) -> Unit)? = null,
    private val onSettingsClick: (Playlist) -> Unit // <--- AÑADE ESTO
) : ListAdapter<Playlist, RecyclerView.ViewHolder>(PlaylistDiffCallback()) {

    companion object {
        const val VIEW_TYPE_GRID = 0
        const val VIEW_TYPE_LIST = 1
        private const val COVER_SIZE_PX = 240
    }

    override fun getItemViewType(position: Int): Int {
        return if (currentList.isNotEmpty()) viewType else super.getItemViewType(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = if (viewType == VIEW_TYPE_LIST) R.layout.item_playlist_bottom_sheet else R.layout.item_playlist
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)

        return if (viewType == VIEW_TYPE_LIST) PlaylistListViewHolder(view) else PlaylistGridViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val playlist = getItem(position)
        when (holder) {
            is PlaylistGridViewHolder -> holder.bind(playlist)
            is PlaylistListViewHolder -> holder.bind(playlist)
        }
        holder.itemView.findViewById<View>(R.id.settingsPlaylist)?.setOnClickListener {
            onSettingsClick(playlist)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)

        // Limpiar Glide para liberar memoria cuando la vista sale de pantalla
        val coverImage = when (holder) {
            is PlaylistGridViewHolder -> holder.coverImage
            is PlaylistListViewHolder -> holder.coverImage
            else -> null
        }

        coverImage?.let {
            try {
                Glide.with(it).clear(it)
            } catch (e: Exception) {
                // Ignorar si la vista no está adjunta
            }
        }
    }

    // --- VIEW HOLDER PRINCIPAL ---
    inner class PlaylistGridViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val playlistName: TextView = view.findViewById(R.id.playlistName)
        private val playlistInfo: TextView = view.findViewById(R.id.playlistInfo)

        // AHORA: Una sola imagen
        val coverImage: ImageView = view.findViewById(R.id.playlistCoverImage)

        fun bind(playlist: Playlist) {
            playlistName.text = playlist.name

            val count = playlist.numberOfTracks ?: 0
            val songText = if (count == 1) "canción" else "canciones"
            playlistInfo.text = "Lista ● $count $songText"

            // Carga simple y directa de la URL del backend
            DefaultCover.load(coverImage, playlist.imageUrl, DefaultCover.Kind.PLAYLIST, playlist.name, COVER_SIZE_PX)

            itemView.setOnClickListener {
                onClick?.invoke(playlist) ?: run {
                    val bundle = Bundle().apply { putString("playlistId", playlist.id) }
                    itemView.findNavController().navigate(R.id.playlistFragment, bundle)
                }
            }

            itemView.onLongPressOptions { onPlaylistLongClick?.let { it(playlist, null); true } ?: false }
        }
    }

    // --- VIEW HOLDER LISTA (Bottom Sheet) ---
    inner class PlaylistListViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val playlistName: TextView = view.findViewById(R.id.playlistName)
        private val songsCount: TextView = view.findViewById(R.id.songsCount)

        // Asegúrate de que tu item_playlist_bottom_sheet también tenga este ID
        val coverImage: ImageView = view.findViewById(R.id.playlistCoverImage)

        fun bind(playlist: Playlist) {
            playlistName.text = playlist.name
            songsCount.text = "${playlist.numberOfTracks ?: 0} canciones"

            DefaultCover.load(coverImage, playlist.imageUrl, DefaultCover.Kind.PLAYLIST, playlist.name, COVER_SIZE_PX)

            itemView.setOnClickListener { onClick?.invoke(playlist) }
        }
    }

    class PlaylistDiffCallback : DiffUtil.ItemCallback<Playlist>() {
        override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem.name == newItem.name &&
                    oldItem.numberOfTracks == newItem.numberOfTracks &&
                    oldItem.imageUrl == newItem.imageUrl &&
                    oldItem.isPublic == newItem.isPublic &&
                    oldItem.ownerName == newItem.ownerName &&
                    oldItem.isSaved == newItem.isSaved
        }
    }

    fun clearCacheForPlaylist(playlistId: String) {
        // Método mantenido por compatibilidad, pero Glide maneja la caché solo.
    }
}
