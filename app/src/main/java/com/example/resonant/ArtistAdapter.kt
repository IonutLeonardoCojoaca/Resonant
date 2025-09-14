package com.example.resonant

import android.graphics.drawable.Drawable
import android.net.Uri
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

class ArtistAdapter(
    private var artists: List<Artist>
) : RecyclerView.Adapter<ArtistAdapter.ArtistViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_artist, parent, false)

        // Mantiene 3 columnas ajustando el ancho del item al ancho del contenedor
        val screenWidth = parent.measuredWidth
        val lp = view.layoutParams
        lp.width = if (screenWidth > 0) screenWidth / 3 else ViewGroup.LayoutParams.MATCH_PARENT
        view.layoutParams = lp

        return ArtistViewHolder(view)
    }

    override fun onBindViewHolder(holder: ArtistViewHolder, position: Int) {
        holder.bind(artists[position])
    }

    override fun getItemCount(): Int = artists.size

    override fun onViewRecycled(holder: ArtistViewHolder) {
        super.onViewRecycled(holder)
        Glide.with(holder.itemView).clear(holder.artistImage)
        holder.loadingAnimation.cancelAnimation()
        holder.loadingAnimation.visibility = View.GONE
    }

    fun submitArtists(newArtists: List<Artist>) {
        this.artists = newArtists
        notifyDataSetChanged()
    }

    inner class ArtistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        val artistImage: ImageView = itemView.findViewById(R.id.artistImage)
        private val artistName: TextView = itemView.findViewById(R.id.artistName)
        val loadingAnimation: LottieAnimationView = itemView.findViewById(R.id.loadingAnimation)

        fun bind(artist: Artist) {
            artistName.text = artist.name
            artistImage.transitionName = "artistImage_${artist.id}"

            // Estado inicial
            loadingAnimation.visibility = View.VISIBLE
            artistImage.visibility = View.INVISIBLE

            // Limpia request anterior
            Glide.with(itemView).clear(artistImage)

            val placeholderRes = R.drawable.user
            val url = artist.fileName
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

            itemView.setOnClickListener {
                val bundle = Bundle().apply {
                    putString("artistId", artist.id)
                    putString("artistName", artist.name)
                    putString("artistImageUrl", artist.fileName)
                    putString("artistImageTransitionName", artistImage.transitionName)
                }
                val extras = FragmentNavigatorExtras(
                    artistImage to artistImage.transitionName
                )
                itemView.findNavController().navigate(
                    R.id.action_homeFragment_to_artistFragment,
                    bundle,
                    null,
                    extras
                )
            }
        }
    }
}