package com.example.resonant.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.live.LiveRowState
import com.example.resonant.live.LiveScheduleRow

/** Programme of the Live block: played rows dimmed, the current one in red. Read-only. */
class LiveScheduleAdapter : ListAdapter<LiveScheduleRow, LiveScheduleAdapter.Holder>(Diff) {

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cover: ImageView = itemView.findViewById(R.id.liveRowCover)
        val title: TextView = itemView.findViewById(R.id.liveRowTitle)
        val artist: TextView = itemView.findViewById(R.id.liveRowArtist)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_live_schedule, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val context = holder.itemView.context
        holder.title.text = row.title ?: context.getString(R.string.live_song_unavailable)
        holder.artist.text = row.artist.orEmpty()
        holder.title.setTextColor(
            ContextCompat.getColor(context, if (row.state == LiveRowState.CURRENT) R.color.live_red else R.color.white)
        )
        holder.itemView.alpha = if (row.state == LiveRowState.PLAYED) PLAYED_ALPHA else 1f
        Glide.with(holder.itemView).clear(holder.cover)
        row.imageUrl?.let { Glide.with(holder.itemView).load(it).centerCrop().into(holder.cover) }
    }

    private object Diff : DiffUtil.ItemCallback<LiveScheduleRow>() {
        override fun areItemsTheSame(old: LiveScheduleRow, new: LiveScheduleRow) = old.key == new.key
        override fun areContentsTheSame(old: LiveScheduleRow, new: LiveScheduleRow) = old == new
    }

    private companion object {
        const val PLAYED_ALPHA = 0.45f
    }
}
