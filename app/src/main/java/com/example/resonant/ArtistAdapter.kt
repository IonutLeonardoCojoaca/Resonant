package com.example.resonant

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.navigation.findNavController
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso

class ArtistAdapter(private val artists: List<Artist>) : RecyclerView.Adapter<ArtistAdapter.ArtistViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_artist, parent, false)

        val screenWidth = parent.measuredWidth
        val layoutParams = view.layoutParams
        layoutParams.width = screenWidth / 3
        view.layoutParams = layoutParams

        return ArtistViewHolder(view)
    }

    override fun onBindViewHolder(holder: ArtistViewHolder, position: Int) {
        val artist = artists[position]
        holder.bind(artist)
    }

    override fun getItemCount(): Int = artists.size

    inner class ArtistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val artistImage: ImageView = itemView.findViewById(R.id.artistImage)
        private val artistName: TextView = itemView.findViewById(R.id.artistName)

        fun bind(artist: Artist) {
            artistName.text = artist.name
            artistImage.transitionName = "artistImage_${artist.id}"

            if (!artist.imageUrl.isNullOrEmpty()) {
                Picasso.get().load(artist.imageUrl).into(artistImage)
            } else {
                Picasso.get().load(R.drawable.user).into(artistImage)
            }

            itemView.setOnClickListener {
                val bundle = Bundle().apply {
                    putString("artistId", artist.id)
                    putString("artistName", artist.name)
                    putString("artistImageUrl", artist.imageUrl)
                    putString("artistImageTransitionName", "artistImage_${artist.id}")
                }

                val extras = FragmentNavigatorExtras(
                    artistImage to "artistImage_${artist.id}"
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
