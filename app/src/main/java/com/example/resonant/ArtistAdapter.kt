package com.example.resonant

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
import com.squareup.picasso.Callback
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
        private val loadingAnimation: LottieAnimationView = itemView.findViewById(R.id.loadingAnimation)

        fun bind(artist: Artist) {
            artistName.text = artist.name
            artistImage.transitionName = "artistImage_${artist.id}"

            loadingAnimation.visibility = View.VISIBLE
            artistImage.visibility = View.INVISIBLE

            if (!artist.fileName.isNullOrEmpty()) {
                Picasso.get()
                    .load(artist.fileName)
                    .error(R.drawable.user)
                    .into(artistImage, object : Callback {
                        override fun onSuccess() {
                            Log.d("Picasso", "Imagen cargada correctamente: ${artist.fileName}")
                            loadingAnimation.visibility = View.GONE
                            artistImage.visibility = View.VISIBLE
                        }

                        override fun onError(e: Exception?) {
                            Log.e("Picasso", "Error al cargar la imagen: ${artist.fileName}", e)
                            loadingAnimation.visibility = View.GONE
                            artistImage.visibility = View.VISIBLE
                        }
                    })
            } else {
                artistImage.setImageResource(R.drawable.user)
            }

            itemView.setOnClickListener {
                val bundle = Bundle().apply {
                    putString("artistId", artist.id)
                    putString("artistName", artist.name)
                    putString("artistImageUrl", artist.fileName)
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
