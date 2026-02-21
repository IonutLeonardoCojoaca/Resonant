package com.example.resonant.ui.adapters

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.navigation.findNavController
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.resonant.utils.ImageRequestHelper
import com.example.resonant.R
import com.example.resonant.data.models.Artist

class ArtistAdapter(
    private var artists: List<Artist>,
    private var currentViewType: Int = VIEW_TYPE_GRID
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onArtistClick: ((artist: Artist, sharedImage: ImageView) -> Unit)? = null
    var onSettingsClick: ((artist: Artist) -> Unit)? = null // New callback

    companion object {
        const val VIEW_TYPE_GRID = 0
        const val VIEW_TYPE_LIST = 1
    }

    override fun getItemViewType(position: Int): Int = currentViewType

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_LIST -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_detailed_song_artist, parent, false)
                ListArtistViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_artist, parent, false)
                // Mantiene 3 columnas ajustando el ancho del item al ancho del contenedor solo en grid
                val screenWidth = parent.measuredWidth
                val lp = view.layoutParams
                lp.width = if (screenWidth > 0) screenWidth / 3 else ViewGroup.LayoutParams.MATCH_PARENT
                view.layoutParams = lp
                GridArtistViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val artist = artists[position]
        when (holder) {
            is GridArtistViewHolder -> holder.bind(artist)
            is ListArtistViewHolder -> holder.bind(artist)
        }
    }

    override fun getItemCount(): Int = artists.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is GridArtistViewHolder -> {
                Glide.with(holder.itemView).clear(holder.artistImage)
            }
            is ListArtistViewHolder -> {
                Glide.with(holder.itemView).clear(holder.artistImage)
                holder.loadingAnimation.cancelAnimation()
                holder.loadingAnimation.visibility = View.GONE
            }
        }
    }

    fun submitArtists(newArtists: List<Artist>) {
        this.artists = newArtists
        notifyDataSetChanged()
    }

    fun setViewType(viewType: Int) {
        if (currentViewType != viewType) {
            currentViewType = viewType
            notifyDataSetChanged()
        }
    }

    // ViewHolder para grid
    inner class GridArtistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val artistImage: ImageView = itemView.findViewById(R.id.artistImage)
        private val artistName: TextView = itemView.findViewById(R.id.artistName)
        private val btnSettings: View? = itemView.findViewById(R.id.btnSettings) // Nullable because detailed layout might not have it yet or different layout

        fun bind(artist: Artist) {
            artistName.text = artist.name
            artistImage.transitionName = "artistImage_${artist.id}"

            artistImage.visibility = View.INVISIBLE

            Glide.with(itemView).clear(artistImage)

            val placeholderRes = R.drawable.ic_user
            val url = artist.url
            if (url.isNullOrBlank()) {
                artistImage.setImageResource(placeholderRes)
                artistImage.visibility = View.VISIBLE
            } else {
                val model = ImageRequestHelper.buildGlideModel(itemView.context, url)
                Glide.with(itemView)
                    .load(model)
                    .override(500, 500) // CRUCIAL: Mantiene la RAM baja
                    .encodeQuality(80) // Tu petición de bajar calidad
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .timeout(10_000)
                    .dontAnimate()
                    .circleCrop()
                    .placeholder(placeholderRes)
                    .error(placeholderRes)
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            Log.w("ArtistAdapter", "Artist image load failed: $model -> ${e?.rootCauses?.firstOrNull()?.message}")
                            artistImage.visibility = View.VISIBLE
                            return false
                        }

                        override fun onResourceReady(
                            resource: Drawable,
                            model: Any,
                            target: Target<Drawable>?,
                            dataSource: DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            artistImage.visibility = View.VISIBLE
                            return false
                        }
                    })
                    .into(artistImage)
            }
            
            // Hide settings button in grid view as requested by user
            btnSettings?.visibility = View.GONE

            itemView.setOnClickListener {
                onArtistClick?.invoke(artist, artistImage)
            }
            
            itemView.setOnLongClickListener {
                onSettingsClick?.invoke(artist)
                true
            }
        }
    }

    // ViewHolder para lista
    inner class ListArtistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val artistImage: ImageView = itemView.findViewById(R.id.artistImage)
        private val artistName: TextView = itemView.findViewById(R.id.artistName)
        private val btnSettings: View? = itemView.findViewById(R.id.btnSettings)
        val loadingAnimation: LottieAnimationView = itemView.findViewById(R.id.loadingAnimation)

        fun bind(artist: Artist) {
            artistName.text = artist.name
            artistImage.transitionName = "artistImage_${artist.id}"
            
            // ... (rest of bind logic) ... (Keeping existing logic, just adding long click for list view)

            loadingAnimation.visibility = View.VISIBLE
            artistImage.visibility = View.INVISIBLE

            Glide.with(itemView).clear(artistImage)

            val placeholderRes = R.drawable.ic_user
            val url = artist.url
            if (url.isNullOrBlank()) {
                loadingAnimation.visibility = View.GONE
                artistImage.setImageResource(placeholderRes)
                artistImage.visibility = View.VISIBLE
            } else {
                val model = ImageRequestHelper.buildGlideModel(itemView.context, url)
                Glide.with(itemView)
                    .load(model)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .timeout(10_000)
                    .dontAnimate()
                    .circleCrop()
                    .placeholder(placeholderRes)
                    .error(placeholderRes)
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            Log.w("ArtistAdapter", "Artist image load failed: $model -> ${e?.rootCauses?.firstOrNull()?.message}")
                            loadingAnimation.visibility = View.GONE
                            artistImage.visibility = View.VISIBLE
                            return false
                        }

                        override fun onResourceReady(
                            resource: Drawable,
                            model: Any,
                            target: Target<Drawable>?,
                            dataSource: DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            loadingAnimation.visibility = View.GONE
                            artistImage.visibility = View.VISIBLE
                            return false
                        }
                    })
                    .into(artistImage)
            }

            // Show settings button in list view
            btnSettings?.visibility = View.VISIBLE
            btnSettings?.setOnClickListener {
                onSettingsClick?.invoke(artist)
            }
            // Ensure click passes through if not on button
            
            itemView.setOnClickListener {
                onArtistClick?.invoke(artist, artistImage) 
            }
            
            itemView.setOnLongClickListener {
                 onSettingsClick?.invoke(artist)
                 true
            }
        }
    }
}