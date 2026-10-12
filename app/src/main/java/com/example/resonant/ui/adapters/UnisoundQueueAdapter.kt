package com.example.resonant.ui.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.network.UnisoundQueueItemDTO

/** Row of the room queue: author label, and the controls this member is allowed to use. */
data class UnisoundQueueRow(
    val item: UnisoundQueueItemDTO,
    val addedByLabel: String,
    val automatic: Boolean = false,
    val canRemove: Boolean = false,
    val canReorder: Boolean = false
)

class UnisoundQueueAdapter(
    private val onRemove: (UnisoundQueueItemDTO) -> Unit = {},
    private val onMove: (UnisoundQueueItemDTO, Int) -> Unit = { _, _ -> },
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit = {}
) : ListAdapter<UnisoundQueueRow, UnisoundQueueAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_unisound_queue, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position), position)

    @SuppressLint("ClickableViewAccessibility")
    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val grip: ImageView = itemView.findViewById(R.id.unisoundItemGrip)
        private val cover: ImageView = itemView.findViewById(R.id.unisoundItemCover)
        private val title: TextView = itemView.findViewById(R.id.unisoundItemTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.unisoundItemSubtitle)
        private val auto: ImageView = itemView.findViewById(R.id.unisoundItemAuto)
        private val up: ImageButton = itemView.findViewById(R.id.unisoundItemUp)
        private val down: ImageButton = itemView.findViewById(R.id.unisoundItemDown)
        private val remove: ImageButton = itemView.findViewById(R.id.unisoundItemRemove)

        init {
            remove.setOnClickListener { rowAt()?.let { onRemove(it.item) } }
            up.setOnClickListener { move(-1) }
            down.setOnClickListener { move(+1) }
            grip.setOnTouchListener { _, event ->
                val canDrag = rowAt()?.canReorder == true
                if (canDrag && event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(this)
                canDrag
            }
        }

        private fun rowAt(): UnisoundQueueRow? =
            bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::getItem)

        private fun move(delta: Int) {
            val position = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return
            onMove(getItem(position).item, position + delta)
        }

        fun bind(row: UnisoundQueueRow, position: Int) {
            val song = row.item.song
            title.text = song?.title ?: "Canción"
            val artists = song?.artistName
                ?: song?.artists?.joinToString(", ") { it.name }?.takeIf(String::isNotBlank)
            subtitle.text = listOfNotNull(artists, row.addedByLabel.takeIf(String::isNotBlank)).joinToString(" · ")
            auto.visibility = if (row.automatic) View.VISIBLE else View.GONE
            grip.visibility = if (row.canReorder) View.VISIBLE else View.INVISIBLE
            up.visibility = if (row.canReorder) View.VISIBLE else View.GONE
            down.visibility = if (row.canReorder) View.VISIBLE else View.GONE
            up.isEnabled = position > 0
            up.alpha = if (position > 0) 1f else DISABLED_ALPHA
            down.isEnabled = position < itemCount - 1
            down.alpha = if (position < itemCount - 1) 1f else DISABLED_ALPHA
            remove.visibility = if (row.canRemove) View.VISIBLE else View.GONE
            Glide.with(cover)
                .load(song?.coverUrl ?: song?.imageFileName)
                .placeholder(R.drawable.ic_disc)
                .error(R.drawable.ic_disc)
                .into(cover)
        }
    }

    private companion object {
        const val DISABLED_ALPHA = 0.3f
        val DIFF = object : DiffUtil.ItemCallback<UnisoundQueueRow>() {
            override fun areItemsTheSame(oldItem: UnisoundQueueRow, newItem: UnisoundQueueRow) =
                oldItem.item.itemId == newItem.item.itemId

            override fun areContentsTheSame(oldItem: UnisoundQueueRow, newItem: UnisoundQueueRow) =
                oldItem == newItem
        }
    }
}
