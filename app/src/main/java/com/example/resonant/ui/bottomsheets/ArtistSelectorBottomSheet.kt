package com.example.resonant.ui.bottomsheets

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.resonant.R
import com.example.resonant.data.models.Artist
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.imageview.ShapeableImageView
import com.example.resonant.utils.ImageRequestHelper

class ArtistSelectorBottomSheet(
    private val artists: List<Artist>,
    private val onArtistSelected: (Artist) -> Unit
) : BottomSheetDialogFragment() {

    override fun getTheme(): Int {
        return R.style.AppBottomSheetDialogTheme
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.bottom_sheet_artist_selector, container, false)
        val recyclerView: RecyclerView = view.findViewById(R.id.artistRecyclerView)

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = ArtistSelectorAdapter(artists) { artist ->
            onArtistSelected(artist)
            dismiss()
        }

        return view
    }

    private inner class ArtistSelectorAdapter(
        private val artists: List<Artist>,
        private val onClick: (Artist) -> Unit
    ) : RecyclerView.Adapter<ArtistSelectorAdapter.ViewHolder>() {

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val image: ShapeableImageView = itemView.findViewById(R.id.artistImage)
            val name: TextView = itemView.findViewById(R.id.artistName)

            fun bind(artist: Artist) {
                name.text = artist.name
                
                val placeholderRes = R.drawable.ic_user
                val url = artist.url
                Glide.with(itemView).clear(image)

                if (url.isNullOrBlank()) {
                    image.setImageResource(placeholderRes)
                } else {
                    val model = ImageRequestHelper.buildGlideModel(itemView.context, url)
                    Glide.with(itemView)
                        .load(model)
                        .circleCrop()
                        .placeholder(placeholderRes)
                        .error(placeholderRes)
                        .into(image)
                }

                itemView.setOnClickListener { onClick(artist) }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_artist_selector, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(artists[position])
        }

        override fun getItemCount(): Int = artists.size
    }
}
