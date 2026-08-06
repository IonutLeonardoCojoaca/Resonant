package com.example.resonant.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.resonant.R
import com.example.resonant.data.network.PlaybackConnectDeviceDTO

class PlaybackDeviceAdapter(
    private val localDeviceId: String,
    private val onDeviceClick: (PlaybackConnectDeviceDTO) -> Unit
) : ListAdapter<PlaybackConnectDeviceDTO, PlaybackDeviceAdapter.DeviceViewHolder>(
    DIFF_CALLBACK
) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playback_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DeviceViewHolder(itemView: View) :
        RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.deviceName)
        private val status: TextView = itemView.findViewById(R.id.deviceStatus)
        private val activeIcon: ImageView =
            itemView.findViewById(R.id.activeDeviceIcon)
        private val deviceIcon: ImageView =
            itemView.findViewById(R.id.deviceIcon)

        fun bind(device: PlaybackConnectDeviceDTO) {
            name.text = if (device.deviceId == localDeviceId) {
                "${device.name} · Este dispositivo"
            } else {
                device.name
            }
            // Prefer the live song title from the device's reported playback
            // state so the user sees what's actually playing on each device,
            // not just a generic status label.
            val liveSongTitle = device.playback
                ?.queueItems
                ?.getOrNull(device.playback.currentIndex)
                ?.title

            status.text = when {
                device.isActive && device.playback?.isPlaying == true ->
                    liveSongTitle?.let { "▶ $it" } ?: "Reproduciendo ahora"
                device.isActive -> "Dispositivo activo"
                device.isOnline -> "Disponible"
                else -> "Sin conexión"
            }
            activeIcon.visibility =
                if (device.isActive) View.VISIBLE else View.GONE
            val enabled = device.isOnline && !device.isActive
            itemView.isEnabled = enabled
            itemView.alpha = if (device.isOnline) 1f else 0.45f
            deviceIcon.alpha = if (device.isActive) 1f else 0.8f
            itemView.setOnClickListener {
                if (enabled) onDeviceClick(device)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<PlaybackConnectDeviceDTO>() {
                override fun areItemsTheSame(
                    oldItem: PlaybackConnectDeviceDTO,
                    newItem: PlaybackConnectDeviceDTO
                ): Boolean = oldItem.deviceId == newItem.deviceId

                override fun areContentsTheSame(
                    oldItem: PlaybackConnectDeviceDTO,
                    newItem: PlaybackConnectDeviceDTO
                ): Boolean = oldItem == newItem
            }
    }
}

