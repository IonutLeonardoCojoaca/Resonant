package com.example.resonant.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.resonant.R
import com.example.resonant.data.models.Artist
import com.example.resonant.ui.viewmodels.ArtistRank
import com.example.resonant.utils.ChartUtils

class TopArtistAdapter(
    private var artists: List<ArtistRank> = emptyList(),
    private val onArtistClick: (Artist) -> Unit
) : RecyclerView.Adapter<TopArtistAdapter.ViewHolder>() {

    init {
        setHasStableIds(true)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rankText: TextView = view.findViewById(R.id.rankText)
        val artistImage: ImageView = view.findViewById(R.id.artistImage)
        val artistName: TextView = view.findViewById(R.id.artistName)
        val artistStats: TextView = view.findViewById(R.id.artistStats)
        val tvPositionChange: TextView = view.findViewById(R.id.tvPositionChange)
        val ivTrendIcon: ImageView = view.findViewById(R.id.ivTrendIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_top_artist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = artists[position]
        val artist = item.artist

        holder.rankText.text = "#${item.rank}"
        holder.artistName.text = artist.name
        holder.artistStats.text = "Artista Popular"
        ChartUtils.bindPositionChange(holder.tvPositionChange, holder.ivTrendIcon, artist.positionChange)

        val placeholderRes = R.drawable.ic_user
        
        Glide.with(holder.itemView.context)
            .load(artist.url)
            .placeholder(placeholderRes)
            .error(placeholderRes)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .circleCrop()
            .into(holder.artistImage)

        holder.itemView.setOnClickListener {
            onArtistClick(artist)
        }
    }

    override fun getItemId(position: Int): Long {
        return artists[position].artist.id.hashCode().toLong()
    }

    override fun getItemCount() = artists.size

    fun updateList(newList: List<ArtistRank>) {
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = artists.size

            override fun getNewListSize(): Int = newList.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return artists[oldItemPosition].artist.id == newList[newItemPosition].artist.id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return artists[oldItemPosition] == newList[newItemPosition]
            }
        })

        artists = newList
        diffResult.dispatchUpdatesTo(this)
    }
}
