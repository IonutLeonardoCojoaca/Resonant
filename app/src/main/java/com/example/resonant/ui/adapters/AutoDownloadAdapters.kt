package com.example.resonant.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.data.models.AutoDownloadSubscriptionDTO
import com.example.resonant.ui.viewmodels.AutoDownloadPlanItem

/** Artistas suscritos a descargas automáticas; solo permite cancelar. */
class AutoDownloadSubscriptionAdapter(
    private val onUnsubscribe: (AutoDownloadSubscriptionDTO) -> Unit
) : ListAdapter<AutoDownloadSubscriptionDTO, AutoDownloadSubscriptionAdapter.ViewHolder>(Diff) {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.autoDownloadArtistName)
        private val cancel: View = view.findViewById(R.id.autoDownloadUnsubscribe)

        init {
            cancel.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onUnsubscribe(getItem(position))
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_auto_download_subscription, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.name.text = getItem(position).name.orEmpty()
    }

    private object Diff : DiffUtil.ItemCallback<AutoDownloadSubscriptionDTO>() {
        override fun areItemsTheSame(old: AutoDownloadSubscriptionDTO, new: AutoDownloadSubscriptionDTO) =
            old.artistId == new.artistId

        override fun areContentsTheSame(old: AutoDownloadSubscriptionDTO, new: AutoDownloadSubscriptionDTO) = old == new
    }
}

/** Planes recientes con su estado. */
class AutoDownloadPlanAdapter : ListAdapter<AutoDownloadPlanItem, AutoDownloadPlanAdapter.ViewHolder>(Diff) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.autoDownloadPlanTitle)
        val subtitle: TextView = view.findViewById(R.id.autoDownloadPlanSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_auto_download_plan, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.title.text = item.title
        holder.subtitle.text = listOf(item.artistName, item.statusLabel)
            .filter(String::isNotBlank)
            .joinToString(" · ")
    }

    private object Diff : DiffUtil.ItemCallback<AutoDownloadPlanItem>() {
        override fun areItemsTheSame(old: AutoDownloadPlanItem, new: AutoDownloadPlanItem) = old.planId == new.planId
        override fun areContentsTheSame(old: AutoDownloadPlanItem, new: AutoDownloadPlanItem) = old == new
    }
}
