package com.example.resonant.ui.explore

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.resonant.R
import com.example.resonant.data.models.Artist
import com.example.resonant.utils.ImageRequestHelper

/** Binds `view_explore_collab_card` with the user's top two artists (legacy and dynamic Explore). */
object ExploreCollabCard {

    fun bind(card: View, artists: List<Artist>) {
        val primary = artists.getOrNull(0)
        val secondary = artists.getOrNull(1)

        card.findViewById<TextView>(R.id.collabPrimaryArtistName).text =
            primary?.name?.takeIf { it.isNotBlank() } ?: "Tu artista top"
        card.findViewById<TextView>(R.id.collabSecondaryArtistName).text =
            secondary?.name?.takeIf { it.isNotBlank() } ?: "Segundo artista"
        card.findViewById<TextView>(R.id.collabPairMeta).text = when {
            primary != null && secondary != null -> "Top 1 + Top 2 segun tus escuchas"
            primary != null -> "Desde tu artista mas escuchado"
            else -> "Basado en tus escuchas"
        }

        loadArtistImage(card.findViewById(R.id.collabPrimaryArtistImage), primary?.url)
        loadArtistImage(card.findViewById(R.id.collabSecondaryArtistImage), secondary?.url)
    }

    fun setOnOpenListener(card: View, onOpen: () -> Unit) {
        val listener = View.OnClickListener { onOpen() }
        card.setOnClickListener(listener)
        card.findViewById<View>(R.id.collabFinderButton).setOnClickListener(listener)
    }

    private fun loadArtistImage(imageView: ImageView, url: String?) {
        Glide.with(imageView)
            .load(url?.takeIf { it.isNotBlank() }?.let {
                ImageRequestHelper.buildGlideModel(imageView.context, it)
            })
            .override(240, 240)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .timeout(10_000)
            .placeholder(R.drawable.ic_user)
            .error(R.drawable.ic_user)
            .circleCrop()
            .dontAnimate()
            .into(imageView)
    }
}
