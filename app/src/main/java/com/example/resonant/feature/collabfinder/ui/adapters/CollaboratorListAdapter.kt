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

class CollaboratorListAdapter(
    private val onItemClicked: (CollaboratorNode) -> Unit
) : ListAdapter<CollaboratorNode, CollaboratorListAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_collaborator_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivAvatar)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvCollabCount: TextView = itemView.findViewById(R.id.tvCollabCount)

        fun bind(collaborator: CollaboratorNode) {
            tvName.text = collaborator.name
            tvCollabCount.text = "${collaborator.collaborationCount} canciones en común"

            Glide.with(itemView.context)
                .load(collaborator.imageUrl)
                .placeholder(R.drawable.ic_user)
                .error(R.drawable.ic_user)
                .circleCrop()
                .into(ivAvatar)

            itemView.setOnClickListener {
                onItemClicked(collaborator)
            }
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
