package com.example.resonant.ui.adapters

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.resonant.R
import com.example.resonant.data.network.NotificationCategories
import com.example.resonant.data.network.NotificationItemDTO

/** Inbox rows. Unread ones show the red dot and a bold title; tapping calls [onClick]. */
class NotificationAdapter(
    private val onClick: (NotificationItemDTO) -> Unit,
    private val now: () -> Long = System::currentTimeMillis
) : ListAdapter<NotificationItemDTO, NotificationAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_notification, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.notificationIcon)
        private val title: TextView = itemView.findViewById(R.id.notificationTitle)
        private val body: TextView = itemView.findViewById(R.id.notificationBody)
        private val time: TextView = itemView.findViewById(R.id.notificationTime)
        private val unreadDot: View = itemView.findViewById(R.id.notificationUnreadDot)
        private val iconPadding = icon.paddingLeft
        private val iconTint = ImageViewCompat.getImageTintList(icon)

        init {
            itemView.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { onClick(getItem(it)) }
            }
        }

        fun bind(item: NotificationItemDTO) {
            val unread = item.readAtUtcMs == null
            title.text = item.title.orEmpty()
            title.alpha = if (unread) 1f else 0.75f
            val text = item.body?.takeIf(String::isNotBlank)
            body.text = text.orEmpty()
            body.visibility = if (text != null) View.VISIBLE else View.GONE
            time.text = DateUtils.getRelativeTimeSpanString(
                item.createdAtUtcMs,
                now(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
            unreadDot.visibility = if (unread) View.VISIBLE else View.INVISIBLE
            renderIcon(item)
        }

        private fun renderIcon(item: NotificationItemDTO) {
            Glide.with(icon).clear(icon)
            val imageUrl = item.imageUrl?.takeIf(String::isNotBlank)
            if (imageUrl != null) {
                ImageViewCompat.setImageTintList(icon, null)
                icon.setPadding(0, 0, 0, 0)
                icon.scaleType = ImageView.ScaleType.CENTER_CROP
                Glide.with(icon).load(imageUrl).error(iconFor(item.category)).into(icon)
            } else {
                ImageViewCompat.setImageTintList(icon, iconTint)
                icon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                icon.setImageDrawable(ContextCompat.getDrawable(icon.context, iconFor(item.category)))
            }
        }
    }

    private fun iconFor(category: String?): Int = when (category) {
        NotificationCategories.ARIA -> R.drawable.ic_unisound_sparkles
        NotificationCategories.UNISOUND -> R.drawable.ic_unisound
        NotificationCategories.LIVE, NotificationCategories.SONG_OF_DAY -> R.drawable.ic_disc
        NotificationCategories.STREAK -> R.drawable.ic_streak_flame
        NotificationCategories.APP_UPDATE -> R.drawable.ic_unisound_arrow_down
        else -> R.drawable.ic_notifications_bell
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<NotificationItemDTO>() {
            override fun areItemsTheSame(oldItem: NotificationItemDTO, newItem: NotificationItemDTO): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: NotificationItemDTO, newItem: NotificationItemDTO): Boolean =
                oldItem == newItem
        }
    }
}
