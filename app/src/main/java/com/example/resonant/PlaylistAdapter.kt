package com.example.resonant

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistAdapter(
    private val viewType: Int,
    private val onClick: ((Playlist) -> Unit)? = null,
    private val onPlaylistLongClick: ((Playlist, Bitmap?) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_GRID = 0
        const val VIEW_TYPE_LIST = 1
    }

    private var playlists: List<Playlist> = emptyList()

    override fun getItemViewType(position: Int): Int = viewType

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_LIST -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_playlist_bottom_sheet, parent, false)
                PlaylistListViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_playlist, parent, false)
                PlaylistGridViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val playlist = playlists[position]
        when (holder) {
            is PlaylistGridViewHolder -> holder.bind(playlist)
            is PlaylistListViewHolder -> holder.bind(playlist)
        }
    }

    override fun getItemCount() = playlists.size

    fun updateData(newPlaylists: List<Playlist>) {
        playlists = newPlaylists
        notifyDataSetChanged()
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is PlaylistGridViewHolder -> {
                // Limpia los 4 ShapeableImageView del collage
                holder.imgViews.forEach { imgView ->
                    Glide.with(holder.itemView).clear(imgView)
                    imgView.setImageDrawable(null) // Opcional: asegura que no queda imagen residual
                }
            }
            is PlaylistListViewHolder -> {
                Glide.with(holder.itemView).clear(holder.playlistImage)
                holder.playlistImage.setImageDrawable(null) // Opcional
            }
        }
    }

    inner class PlaylistGridViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val playlistName: TextView = view.findViewById(R.id.playlistName)
        val collageContainer = view.findViewById<View>(R.id.playlistCollageContainer)
        val imgViews = listOf(
            collageContainer.findViewById<ShapeableImageView>(R.id.img0),
            collageContainer.findViewById<ShapeableImageView>(R.id.img1),
            collageContainer.findViewById<ShapeableImageView>(R.id.img2),
            collageContainer.findViewById<ShapeableImageView>(R.id.img3)
        )

        private var collageBitmap: Bitmap? = null

        fun bind(playlist: Playlist) {
            playlistName.text = playlist.name
            val context = itemView.context
            val service = ApiClient.getService(context)
            val playlistManager = PlaylistManager(service)

            val placeholderRes = R.drawable.playlist_stack
            imgViews.forEach { it.setImageResource(placeholderRes) }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val songs = playlistManager.getSongsByPlaylistId(playlist.id!!)
                    val firstSongs = songs.take(4)

                    // Batch request de portadas
                    val coversRequest = firstSongs.mapNotNull { song ->
                        song.imageFileName?.takeIf { it.isNotBlank() }?.let { fn ->
                            song.albumId.takeIf { it.isNotBlank() }?.let { aid ->
                                fn to aid
                            }
                        }
                    }

                    if (coversRequest.isNotEmpty()) {
                        val (fileNames, albumIds) = coversRequest.unzip()
                        val coverResponses = service.getMultipleSongCoverUrls(fileNames, albumIds)
                        val coverMap = coverResponses.associateBy({ it.imageFileName to it.albumId }, { it.url })
                        firstSongs.forEach { song ->
                            song.coverUrl = coverMap[song.imageFileName to song.albumId]
                        }
                    }

                    withContext(Dispatchers.Main) {
                        val bitmaps = arrayOfNulls<Bitmap>(4)
                        var loadedCount = 0

                        firstSongs.forEachIndexed { i, song ->
                            val url = song.coverUrl
                            val target = object : com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                                override fun onResourceReady(resource: Bitmap, transition: com.bumptech.glide.request.transition.Transition<in Bitmap>?) {
                                    bitmaps[i] = resource
                                    imgViews[i].setImageBitmap(resource)
                                    loadedCount++
                                    if (loadedCount == firstSongs.size) {
                                        // Todas las imágenes cargadas, generar collage
                                        collageBitmap = createCollageBitmap(context, bitmaps.toList(), placeholderRes, imgViews.first().width)
                                    }
                                }

                                override fun onLoadCleared(placeholder: Drawable?) {
                                    imgViews[i].setImageResource(placeholderRes)
                                }

                                override fun onLoadFailed(errorDrawable: Drawable?) {
                                    imgViews[i].setImageResource(placeholderRes)
                                    loadedCount++
                                    if (loadedCount == firstSongs.size) {
                                        collageBitmap = createCollageBitmap(context, bitmaps.toList(), placeholderRes, imgViews.first().width)
                                    }
                                }
                            }

                            if (!url.isNullOrBlank()) {
                                Glide.with(context)
                                    .asBitmap()
                                    .load(url)
                                    .placeholder(placeholderRes)
                                    .error(placeholderRes)
                                    .into(target)
                            } else {
                                imgViews[i].setImageResource(placeholderRes)
                                loadedCount++
                            }
                        }
                    }

                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        imgViews.forEach { it.setImageResource(placeholderRes) }
                    }
                }
            }

            // Click normal
            itemView.setOnClickListener {
                if (onClick != null) onClick.invoke(playlist)
                else {
                    val bundle = Bundle().apply { putString("playlistId", playlist.id) }
                    itemView.findNavController().navigate(R.id.action_savedFragment_to_playlistFragment, bundle)
                }
            }

            // Long click
            itemView.setOnLongClickListener {
                onPlaylistLongClick?.invoke(playlist, collageBitmap)
                onPlaylistLongClick != null
            }
        }


    }

    inner class PlaylistListViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val playlistImage: ImageView = view.findViewById(R.id.songImage)
        val playlistName: TextView = view.findViewById(R.id.playlistName)
        val songsCount: TextView = view.findViewById(R.id.songsCount)

        var albumArtAnimator: ObjectAnimator? = null

        fun bind(playlist: Playlist) {
            playlistName.text = playlist.name
            val placeholderRes = R.drawable.album_cover
            val noImageRes = R.drawable.playlist_stack
            playlistImage.setImageResource(placeholderRes)

            songsCount.text = "${playlist.numberOfTracks ?: 0} canciones"

            val context = playlistImage.context
            val service = ApiClient.getService(context)
            val playlistManager = PlaylistManager(service)

            // Detén cualquier animación previa y resetea la rotación
            albumArtAnimator?.cancel()
            playlistImage.rotation = 0f
            playlistImage.visibility = View.VISIBLE

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val songs = playlistManager.getSongsByPlaylistId(playlist.id!!)
                    val firstSong = songs.firstOrNull()
                    val imageFileName = firstSong?.imageFileName
                    val albumId = firstSong?.albumId

                    // Obtén la URL prefirmada individual de la portada
                    val coverUrl = if (!imageFileName.isNullOrBlank() && !albumId.isNullOrBlank()) {
                        service.getSongCoverUrl(imageFileName, albumId).url
                    } else null

                    withContext(Dispatchers.Main) {
                        if (!coverUrl.isNullOrBlank()) {
                            // Animación de círculo de carga (rotación)
                            albumArtAnimator = ObjectAnimator.ofFloat(playlistImage, "rotation", 0f, 360f).apply {
                                duration = 3000
                                repeatCount = ObjectAnimator.INFINITE
                                interpolator = LinearInterpolator()
                                start()
                            }

                            playlistImage.setImageResource(placeholderRes)

                            // Descarga la portada usando Glide
                            Glide.with(context)
                                .asBitmap()
                                .load(coverUrl)
                                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                                .timeout(10_000)
                                .dontAnimate()
                                .placeholder(placeholderRes)
                                .error(noImageRes)
                                .listener(object : com.bumptech.glide.request.RequestListener<Bitmap> {
                                    override fun onLoadFailed(
                                        e: com.bumptech.glide.load.engine.GlideException?,
                                        model: Any?,
                                        target: com.bumptech.glide.request.target.Target<Bitmap>,
                                        isFirstResource: Boolean
                                    ): Boolean {
                                        albumArtAnimator?.cancel()
                                        playlistImage.rotation = 0f
                                        playlistImage.setImageResource(noImageRes)
                                        return false
                                    }

                                    override fun onResourceReady(
                                        resource: Bitmap,
                                        model: Any,
                                        target: com.bumptech.glide.request.target.Target<Bitmap>?,
                                        dataSource: com.bumptech.glide.load.DataSource,
                                        isFirstResource: Boolean
                                    ): Boolean {
                                        albumArtAnimator?.cancel()
                                        playlistImage.rotation = 0f
                                        playlistImage.setImageBitmap(resource)
                                        return false
                                    }
                                })
                                .into(playlistImage)
                        } else {
                            playlistImage.setImageResource(noImageRes)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        playlistImage.setImageResource(noImageRes)
                    }
                }
            }

            itemView.setOnClickListener {
                onClick?.invoke(playlist)
            }
        }
    }

    fun createCollageBitmap(
        context: Context,
        bitmaps: List<Bitmap?>,
        placeholderRes: Int,
        size: Int
    ): Bitmap {
        val collage = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(collage)
        val half = size / 2
        val drawable = context.getDrawable(placeholderRes)!!
        val placeholderBitmap = drawableToBitmap(drawable, half)

        for (i in 0 until 4) {
            val bmp = bitmaps.getOrNull(i) ?: placeholderBitmap
            val left = if (i % 2 == 0) 0 else half
            val top = if (i < 2) 0 else half
            val rect = Rect(left, top, left + half, top + half)
            canvas.drawBitmap(bmp, null, rect, null)
        }
        return collage
    }

    fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

}