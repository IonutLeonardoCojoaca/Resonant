package com.example.resonant.ui.adapters

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.playback.QueueItemSnapshot

class PlaybackQueueAdapter(
    private val onPlay: (QueueItemSnapshot) -> Unit,
    private val onRemove: (QueueItemSnapshot) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<PlaybackQueueAdapter.QueueViewHolder>() {
    private val items = mutableListOf<QueueItemSnapshot>()

    init {
        setHasStableIds(true)
    }

    var canReorderUpcoming: Boolean = false
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_CONTROLS)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playback_queue, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onBindViewHolder(
        holder: QueueViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_CONTROLS)) {
            holder.bindControls(items[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].entryId.hashCode().toLong()

    fun itemAt(position: Int): QueueItemSnapshot? = items.getOrNull(position)

    fun submitQueue(newItems: List<QueueItemSnapshot>) {
        val previous = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = previous.size
            override fun getNewListSize(): Int = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return previous[oldItemPosition].entryId == newItems[newItemPosition].entryId
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return previous[oldItemPosition] == newItems[newItemPosition]
            }
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    fun moveItem(fromPosition: Int, toPosition: Int): Boolean {
        if (
            fromPosition !in items.indices ||
            toPosition !in items.indices ||
            items[fromPosition].isCurrent ||
            items[toPosition].isCurrent
        ) {
            return false
        }
        val moved = items.removeAt(fromPosition)
        items.add(toPosition, moved)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    inner class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image: ImageView = itemView.findViewById(R.id.queue_song_image)
        private val title: TextView = itemView.findViewById(R.id.queue_song_title)
        private val artist: TextView = itemView.findViewById(R.id.queue_song_artist)
        private val remove: ImageButton = itemView.findViewById(R.id.queue_remove)
        private val drag: ImageView = itemView.findViewById(R.id.queue_drag_handle)
        private val nowPlaying: TextView = itemView.findViewById(R.id.queue_now_playing)

        fun bind(item: QueueItemSnapshot) {
            title.text = item.song.title
            artist.text = item.song.artistName
                ?: item.song.artists.joinToString(", ") { it.name }
                    .ifBlank { "Desconocido" }
            title.setTextColor(
                itemView.context.getColor(
                    if (item.isCurrent) R.color.secondaryColorTheme else R.color.white
                )
            )
            Glide.with(image)
                .load(item.song.coverUrl ?: item.song.imageFileName)
                .placeholder(R.drawable.ic_disc)
                .error(R.drawable.ic_disc)
                .into(image)
            itemView.setOnClickListener {
                if (!item.isCurrent) onPlay(item)
            }
            remove.setOnClickListener { onRemove(item) }
            drag.setOnClickListener { }
            drag.setOnTouchListener { view, event ->
                if (
                    event.actionMasked == MotionEvent.ACTION_DOWN &&
                    !item.isCurrent &&
                    canReorderUpcoming
                ) {
                    view.performClick()
                    onStartDrag(this)
                    true
                } else {
                    false
                }
            }
            bindControls(item)
        }

        fun bindControls(item: QueueItemSnapshot) {
            val showActions = !item.isCurrent
            nowPlaying.visibility = if (item.isCurrent) View.VISIBLE else View.GONE
            remove.visibility = if (showActions) View.VISIBLE else View.GONE
            drag.visibility = if (showActions && canReorderUpcoming) View.VISIBLE else View.INVISIBLE
        }
    }

    companion object {
        private const val PAYLOAD_CONTROLS = "controls"
    }
}
