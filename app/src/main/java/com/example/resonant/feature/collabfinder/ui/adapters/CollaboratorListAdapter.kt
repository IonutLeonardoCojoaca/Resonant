package com.example.resonant.feature.collabfinder.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.feature.collabfinder.domain.model.CollaboratorNode
import com.example.resonant.ui.bottomsheets.ItemOptions.onLongPressOptions

class CollaboratorListAdapter(
    private val onItemClicked: (CollaboratorNode) -> Unit
) : ListAdapter<CollaboratorNode, CollaboratorListAdapter.ViewHolder>(DiffCallback) {

    /** Mantener pulsado: abre las opciones del elemento (ItemOptions). */
    var onItemLongClick: ((CollaboratorNode) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_collaborator_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.itemView.onLongPressOptions {
            val pos = holder.bindingAdapterPosition
            val open = onItemLongClick
            if (pos == RecyclerView.NO_POSITION || open == null) return@onLongPressOptions false
            open(getItem(pos))
            true
        }
        holder.bind(getItem(position), position + 1)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        Glide.with(holder.itemView).clear(holder.ivAvatar)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivAvatar: ImageView = itemView.findViewById(R.id.ivAvatar)
        private val tvRank: TextView = itemView.findViewById(R.id.tvRank)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvCollabCount: TextView = itemView.findViewById(R.id.tvCollabCount)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onItemClicked(getItem(position))
            }
        }

        fun bind(collaborator: CollaboratorNode, rank: Int) {
            tvRank.text = rank.toString()
            tvName.text = collaborator.name

            val count = collaborator.collaborationCount
            val songs = if (count == 1) "1 canción" else "$count canciones"
            val years = when {
                collaborator.firstCollabYear == null -> null
                collaborator.lastCollabYear == null || collaborator.lastCollabYear == collaborator.firstCollabYear ->
                    collaborator.firstCollabYear.toString()
                else -> "${collaborator.firstCollabYear}–${collaborator.lastCollabYear}"
            }
            tvCollabCount.text = listOfNotNull(songs, years).joinToString(" · ")

            Glide.with(itemView)
                .load(collaborator.imageUrl)
                .override(150)
                .placeholder(R.drawable.ic_user)
                .error(R.drawable.ic_user)
                .circleCrop()
                .into(ivAvatar)
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<CollaboratorNode>() {
            override fun areItemsTheSame(oldItem: CollaboratorNode, newItem: CollaboratorNode): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: CollaboratorNode, newItem: CollaboratorNode): Boolean {
                return oldItem == newItem
            }
        }
    }
}
