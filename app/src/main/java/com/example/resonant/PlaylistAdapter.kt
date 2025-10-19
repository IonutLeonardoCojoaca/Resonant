package com.example.resonant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class PlaylistAdapter(
    private val viewType: Int,
    private val onClick: ((Playlist) -> Unit)? = null,
    private val onPlaylistLongClick: ((Playlist, Bitmap?) -> Unit)? = null
) : ListAdapter<Playlist, RecyclerView.ViewHolder>(PlaylistDiffCallback()) {

    companion object {
        const val VIEW_TYPE_GRID = 0
        const val VIEW_TYPE_LIST = 1
    }

    private val collageCache = ConcurrentHashMap<String, Bitmap>()
    private val hashCache = ConcurrentHashMap<String, String>()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    override fun getItemViewType(position: Int): Int {
        return if (currentList.isNotEmpty()) viewType else super.getItemViewType(position)
    }

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
        val playlist = getItem(position)
        when (holder) {
            is PlaylistGridViewHolder -> holder.bind(playlist)
            is PlaylistListViewHolder -> holder.bind(playlist)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        val playlistId = when(holder) {
            is PlaylistGridViewHolder -> holder.playlistId
            is PlaylistListViewHolder -> holder.playlistId
            else -> null
        }
        playlistId?.let {
            activeJobs[it]?.cancel()
            activeJobs.remove(it)
        }

        when (holder) {
            is PlaylistGridViewHolder -> holder.imgViews.forEach { Glide.with(it).clear(it); it.setImageDrawable(null) }
            is PlaylistListViewHolder -> holder.imgViews.forEach { Glide.with(it).clear(it); it.setImageDrawable(null) }
        }
    }

    inner class PlaylistGridViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val playlistName: TextView = view.findViewById(R.id.playlistName)
        private val collageContainer: View = view.findViewById(R.id.playlistCollageContainer)
        val imgViews: List<ShapeableImageView> = listOf(
            collageContainer.findViewById(R.id.img0),
            collageContainer.findViewById(R.id.img1),
            collageContainer.findViewById(R.id.img2),
            collageContainer.findViewById(R.id.img3)
        )
        var playlistId: String? = null
        private var collageBitmap: Bitmap? = null

        fun bind(playlist: Playlist) {
            this.playlistId = playlist.id
            playlistName.text = playlist.name

            loadAndBindCollage(playlist, itemView.context, imgViews) { generatedBitmap ->
                this.collageBitmap = generatedBitmap
            }

            itemView.setOnClickListener {
                onClick?.invoke(playlist) ?: run {
                    val bundle = Bundle().apply { putString("playlistId", playlist.id) }
                    itemView.findNavController().navigate(R.id.action_savedFragment_to_playlistFragment, bundle)
                }
            }
            itemView.setOnLongClickListener {
                onPlaylistLongClick?.invoke(playlist, collageBitmap)
                true
            }
        }
    }

    inner class PlaylistListViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val playlistName: TextView = view.findViewById(R.id.playlistName)
        private val songsCount: TextView = view.findViewById(R.id.songsCount)
        private val collageContainer: View = view.findViewById(R.id.playlistCollageContainer)
        val imgViews: List<ShapeableImageView> = listOf(
            collageContainer.findViewById(R.id.img0),
            collageContainer.findViewById(R.id.img1),
            collageContainer.findViewById(R.id.img2),
            collageContainer.findViewById(R.id.img3)
        )
        var playlistId: String? = null

        fun bind(playlist: Playlist) {
            this.playlistId = playlist.id
            playlistName.text = playlist.name
            songsCount.text = "${playlist.numberOfTracks ?: 0} canciones"

            loadAndBindCollage(playlist, itemView.context, imgViews)

            itemView.setOnClickListener { onClick?.invoke(playlist) }
        }
    }

    private fun loadAndBindCollage(
        playlist: Playlist,
        context: Context,
        imgViews: List<ShapeableImageView>,
        onBitmapReady: ((Bitmap?) -> Unit)? = null
    ) {
        val placeholderRes = R.drawable.playlist_stack
        imgViews.forEach { it.setImageResource(placeholderRes) }

        val playlistId = playlist.id!!
        activeJobs[playlistId]?.cancel()

        activeJobs[playlistId] = CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentHash = playlist.songsHash ?: playlistId

                val cachedBitmap = collageCache[playlistId]
                if (cachedBitmap != null && hashCache[playlistId] == currentHash) {
                    withContext(Dispatchers.Main) { setCollageToImageViews(cachedBitmap, imgViews) }
                    onBitmapReady?.invoke(cachedBitmap)
                    return@launch
                }

                val collageCacheDir = File(context.cacheDir, "playlist_collages")
                collageCacheDir.mkdirs()
                val collageFile = File(collageCacheDir, "${playlistId}_${currentHash}.png")

                if (collageFile.exists()) {
                    val bitmapFromDisk = BitmapFactory.decodeFile(collageFile.absolutePath)
                    if (bitmapFromDisk != null) {
                        collageCache[playlistId] = bitmapFromDisk
                        hashCache[playlistId] = currentHash
                        withContext(Dispatchers.Main) { setCollageToImageViews(bitmapFromDisk, imgViews) }
                        onBitmapReady?.invoke(bitmapFromDisk)
                        return@launch
                    }
                }

                val service = ApiClient.getService(context)
                val playlistManager = PlaylistManager(service)
                val songs = playlistManager.getSongsByPlaylistId(context, playlistId)
                val firstSongs = songs.take(4)

                val coverUrls = getCoverUrlsForSongs(firstSongs, service)
                val individualBitmaps = getBitmapsWithGlide(context, coverUrls)
                val collage = createCollageBitmap(context, individualBitmaps, placeholderRes, 200)

                FileOutputStream(collageFile).use { out -> collage.compress(Bitmap.CompressFormat.PNG, 90, out) }
                collageCache[playlistId] = collage
                hashCache[playlistId] = currentHash

                // Mostrar en UI
                withContext(Dispatchers.Main) {
                    setCollageToImageViews(collage, imgViews)
                }
                onBitmapReady?.invoke(collage)

            } catch (e: CancellationException) {
            } catch (e: Exception) {
                Log.e("PlaylistAdapter", "Error al crear collage para ${playlist.name}", e)
            } finally {
                // Una vez terminado (o cancelado), se elimina de los trabajos activos.
                activeJobs.remove(playlistId)
            }
        }
    }

    private fun setCollageToImageViews(collage: Bitmap, imgViews: List<ShapeableImageView>) {
        val halfSize = collage.width / 2
        if (halfSize > 0) {
            val splitBitmaps = splitCollageBitmap(collage, halfSize)
            splitBitmaps.forEachIndexed { i, bmp ->
                imgViews.getOrNull(i)?.setImageBitmap(bmp)
            }
        }
    }

    private suspend fun getCoverUrlsForSongs(songs: List<Song>, service: ApiResonantService): List<String> {
        val coversRequest = songs.mapNotNull { song ->
            song.imageFileName?.takeIf { it.isNotBlank() }?.let { fn ->
                song.albumId?.takeIf { it.isNotBlank() }?.let { aid -> fn to aid }
            }
        }
        if (coversRequest.isNotEmpty()) {
            val (fileNames, albumIds) = coversRequest.unzip()
            val coverResponses = service.getMultipleSongCoverUrls(fileNames, albumIds)
            val coverMap = coverResponses.associateBy({ it.imageFileName to it.albumId }, { it.url })
            songs.forEach { song ->
                song.coverUrl = coverMap[song.imageFileName to song.albumId]
            }
        }
        return songs.mapNotNull { it.coverUrl }
    }

    private suspend fun getBitmapsWithGlide(context: Context, urls: List<String>): List<Bitmap?> {
        val bitmaps = MutableList<Bitmap?>(4) { null }
        coroutineScope {
            urls.take(4).forEachIndexed { index, url ->
                launch {
                    try {
                        bitmaps[index] = Glide.with(context)
                            .asBitmap()
                            .load(url)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .submit()
                            .get()
                    } catch (e: Exception) { }
                }
            }
        }
        return bitmaps
    }

    class PlaylistDiffCallback : DiffUtil.ItemCallback<Playlist>() {
        override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem.name == newItem.name &&
                    oldItem.numberOfTracks == newItem.numberOfTracks &&
                    oldItem.songsHash == newItem.songsHash
        }
    }

    private fun splitCollageBitmap(collage: Bitmap, half: Int): List<Bitmap> {
        val result = mutableListOf<Bitmap>()
        for (i in 0 until 4) {
            val left = if (i % 2 == 0) 0 else half
            val top = if (i < 2) 0 else half
            result.add(Bitmap.createBitmap(collage, left, top, half, half))
        }
        return result
    }

    private fun createCollageBitmap(context: Context, bitmaps: List<Bitmap?>, placeholderRes: Int, size: Int): Bitmap {
        val collage = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(collage)
        val half = size / 2
        val drawable = ContextCompat.getDrawable(context, placeholderRes)!!
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

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    fun clearCacheForPlaylist(playlistId: String) {
        collageCache.remove(playlistId)
        hashCache.remove(playlistId)
        Log.d("PlaylistAdapter", "Caché invalidada para la playlist ID: $playlistId")
    }

    fun generatePlaylistHash(songIds: List<String>): String {
        val data = songIds.joinToString(",")
        return data.hashCode().toString()
    }
}